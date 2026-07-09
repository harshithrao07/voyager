package com.job.scheduler.service;

import com.job.scheduler.workflow.task.TaskResourceErrors;
import com.job.scheduler.workflow.task.TaskResourceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns a stored multi-file bundle into a Judge0 submission. Judge0 has no single
 * mechanism that works for every language, so two strategies are used:
 *
 * <ul>
 *   <li><b>Native box</b> (interpreted languages, and compilers that pick up
 *       sibling modules automatically): the bundle's entry file is sent as
 *       {@code source_code} under the language's own id and the remaining files
 *       ride along as {@code additional_files}. This runs in the language's real
 *       sandbox, so its interpreter/compiler is on PATH (Judge0's generic
 *       multi-file box only ships python3/gcc/g++/bash).</li>
 *   <li><b>Multi-file box</b> (language id {@value #MULTI_FILE_LANGUAGE_ID}, for
 *       genuinely multi-source compiled languages): generated {@code compile}
 *       and {@code run} scripts are injected so the whole directory is built at
 *       once (e.g. {@code g++ *.cpp}). Only usable for compilers present in that
 *       box (gcc/g++/javac/go here).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class Judge0MultiFileSupport {
    public static final int MULTI_FILE_LANGUAGE_ID = 89;

    private static final String RUN_FILE = "run";
    private static final String COMPILE_FILE = "compile";

    private final Judge0Client judge0Client;

    /**
     * The concrete Judge0 submission fields to use for a multi-file version.
     * For native-box languages {@code languageId} stays the real id and
     * {@code sourceCode} holds the entry file; for the multi-file box the id is
     * {@value #MULTI_FILE_LANGUAGE_ID} and {@code sourceCode} is {@code null}.
     */
    public record MultiFileSubmission(
            int languageId,
            String sourceCode,
            String additionalFilesBase64,
            String compilerOptions,
            String commandLineArguments
    ) {
    }

    /** Whether multi-file execution is supported for the given real language id. */
    public boolean isSupported(int languageId) {
        return supportsMultiFile(judge0Client.languageName(languageId));
    }

    /**
     * Whether multi-file execution is supported for a language by name. Static so
     * it can be used when building the language list without a Judge0 lookup.
     */
    public static boolean supportsMultiFile(String languageName) {
        return recipeForName(languageName) != null;
    }

    public MultiFileSubmission plan(
            int languageId,
            String base64Zip,
            String compilerOptions,
            String commandLineArguments
    ) {
        Recipe recipe = recipeForName(judge0Client.languageName(languageId));
        if (recipe == null) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Multi-file execution is not supported for " + safeLanguageName(languageId)
                            + ". Use single-file mode for this language."
            );
        }
        if (base64Zip == null || base64Zip.isBlank()) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Multi-file function is missing its file bundle."
            );
        }
        Map<String, byte[]> entries = readArchive(base64Zip);
        String opts = compilerOptions == null ? "" : compilerOptions.trim();
        String args = commandLineArguments == null ? "" : commandLineArguments.trim();

        if (recipe.entryName() != null) {
            // Native-box strategy: pull the entry out as source_code and let the
            // language's own sandbox run it with the siblings alongside.
            String entryKey = findEntry(entries, recipe.entryName());
            if (entryKey == null) {
                throw new TaskResourceException(
                        TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                        "Multi-file bundle has no entry file (expected " + recipe.entryName() + ")."
                );
            }
            String sourceCode = new String(entries.remove(entryKey), StandardCharsets.UTF_8);
            String rest = entries.isEmpty() ? null : writeArchive(entries);
            return new MultiFileSubmission(
                    languageId,
                    sourceCode,
                    rest,
                    compilerOptions == null || compilerOptions.isBlank() ? null : compilerOptions,
                    commandLineArguments == null || commandLineArguments.isBlank() ? null : commandLineArguments
            );
        }

        // Multi-file-box strategy: inject compile/run scripts; options and args
        // are baked into the scripts, so they are not sent as submission fields.
        String compileScript = recipe.compile(opts);
        if (compileScript != null) {
            entries.putIfAbsent(COMPILE_FILE, compileScript.getBytes(StandardCharsets.UTF_8));
        }
        entries.putIfAbsent(RUN_FILE, recipe.run(args).getBytes(StandardCharsets.UTF_8));
        return new MultiFileSubmission(
                MULTI_FILE_LANGUAGE_ID,
                null,
                writeArchive(entries),
                null,
                null
        );
    }

    private String findEntry(Map<String, byte[]> entries, String entryName) {
        if (entries.containsKey(entryName)) {
            return entryName;
        }
        for (String key : entries.keySet()) {
            if (basename(key).equalsIgnoreCase(entryName)) {
                return key;
            }
        }
        for (String key : entries.keySet()) {
            String base = basename(key);
            if (base.matches("(?i)^(main)\\.[a-z0-9]+$")) {
                return key;
            }
        }
        return entries.size() == 1 ? entries.keySet().iterator().next() : null;
    }

    private String basename(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private Map<String, byte[]> readArchive(String base64Zip) {
        byte[] zipBytes;
        try {
            zipBytes = Base64.getDecoder().decode(base64Zip.trim());
        } catch (IllegalArgumentException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Multi-file bundle is not valid base64.",
                    exception
            );
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(normalize(entry.getName()), in.readAllBytes());
            }
        } catch (Exception exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Could not read the multi-file bundle.",
                    exception
            );
        }
        if (entries.isEmpty()) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Multi-file bundle is empty."
            );
        }
        return entries;
    }

    private String writeArchive(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (Exception exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Could not assemble the multi-file bundle.",
                    exception
            );
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private String normalize(String name) {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    private String safeLanguageName(int languageId) {
        String name = judge0Client.languageName(languageId);
        return name == null ? "language id " + languageId : name;
    }

    /**
     * Maps the version's real language to a multi-file recipe. Interpreted (and
     * self-contained-compile) languages get an {@code entryName} for the
     * native-box strategy; multi-source compiled languages get compile/run
     * builders for the multi-file box.
     */
    private static Recipe recipeForName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        // Order matters: match "node"/"typescript" before "java", "c++" before "c".
        //
        // Only languages whose sibling files are actually built are listed. C#,
        // Kotlin and Swift are intentionally omitted: Judge0's per-language box
        // compiles only the entry file (so a helper class never links), and the
        // multi-file box (lang 89) doesn't ship mcs/kotlinc/swiftc. Go is omitted
        // for the same reason (its box compiles only the entry, and lang 89 has no
        // `go` binary). Leaving them out makes multi-file decline them cleanly
        // ("not supported") instead of throwing a cryptic compile error.
        if (n.contains("python")) {
            return Recipe.native_("main.py");
        }
        if (n.contains("typescript")) {
            return Recipe.native_("main.ts");
        }
        if (n.contains("javascript") || n.contains("node")) {
            return Recipe.native_("main.js");
        }
        if (n.contains("ruby")) {
            return Recipe.native_("main.rb");
        }
        if (n.contains("php")) {
            return Recipe.native_("main.php");
        }
        if (n.contains("bash") || n.contains("shell")) {
            return Recipe.native_("main.sh");
        }
        if (n.contains("rust")) {
            // rustc compiles modules declared from the entry, so the native box works.
            return Recipe.native_("main.rs");
        }
        if (n.contains("c++") || n.contains("cpp")) {
            return Recipe.box(opts -> "g++ " + opts + " *.cpp -o a.out", "./a.out");
        }
        if (n.contains("java")) {
            return Recipe.box(opts -> "javac *.java", "java Main");
        }
        if (n.startsWith("c ") || n.equals("c")) {
            return Recipe.box(opts -> "gcc " + opts + " *.c -o a.out", "./a.out");
        }
        return null;
    }

    /**
     * A multi-file recipe. Either {@code entryName} is set (native-box strategy)
     * or the compile/run builders are set (multi-file-box strategy).
     */
    private record Recipe(
            String entryName,
            Function<String, String> compileBuilder,
            String runCommand
    ) {
        static Recipe native_(String entryName) {
            return new Recipe(entryName, null, null);
        }

        static Recipe box(Function<String, String> compileBuilder, String runCommand) {
            return new Recipe(null, compileBuilder, runCommand);
        }

        String compile(String options) {
            if (compileBuilder == null) {
                return null;
            }
            return "#!/bin/bash\nset -e\n" + compileBuilder.apply(options).trim() + "\n";
        }

        String run(String arguments) {
            String suffix = arguments == null || arguments.isBlank() ? "" : " " + arguments.trim();
            return "#!/bin/bash\n" + runCommand + suffix + "\n";
        }
    }
}
