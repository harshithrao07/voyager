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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns a stored multi-file bundle into a Judge0 "Multi-file program"
 * (language id {@value #MULTI_FILE_LANGUAGE_ID}) submission.
 *
 * <p>Judge0 executes a {@code run} script (and an optional {@code compile}
 * script) from the root of the uploaded archive. The bundle the user authors
 * only contains their source files, so this component injects the correct
 * {@code compile}/{@code run} scripts for the version's real language before the
 * archive is submitted. Without them Judge0 fails with
 * {@code /bin/bash: run: No such file or directory}.
 */
@Component
@RequiredArgsConstructor
public class Judge0MultiFileSupport {
    public static final int MULTI_FILE_LANGUAGE_ID = 89;

    private static final String RUN_FILE = "run";
    private static final String COMPILE_FILE = "compile";

    private final Judge0Client judge0Client;

    /** Whether multi-file execution is supported for the given real language id. */
    public boolean isSupported(int languageId) {
        return scriptsFor(languageId) != null;
    }

    /**
     * Rebuilds the base64 archive with {@code compile}/{@code run} scripts for the
     * language so it can be submitted as language {@value #MULTI_FILE_LANGUAGE_ID}.
     *
     * @throws TaskResourceException if the language has no multi-file recipe or the
     *                               archive cannot be read.
     */
    public String buildArchive(
            int languageId,
            String base64Zip,
            String compilerOptions,
            String commandLineArguments
    ) {
        Scripts scripts = scriptsFor(languageId);
        if (scripts == null) {
            String name = safeLanguageName(languageId);
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Multi-file execution is not supported for " + name
                            + ". Use single-file mode for this language."
            );
        }
        if (base64Zip == null || base64Zip.isBlank()) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Multi-file function is missing its file bundle."
            );
        }
        String opts = compilerOptions == null ? "" : compilerOptions.trim();
        String args = commandLineArguments == null ? "" : commandLineArguments.trim();
        return augmentArchive(
                base64Zip,
                scripts.compile(opts),
                scripts.run(args)
        );
    }

    /**
     * Reads every entry from the source archive and writes a new archive that also
     * contains the generated {@code compile}/{@code run} scripts. User-supplied
     * scripts of the same name are preserved.
     */
    private String augmentArchive(String base64Zip, String compileScript, String runScript) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
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

        if (compileScript != null) {
            entries.putIfAbsent(COMPILE_FILE, compileScript.getBytes(StandardCharsets.UTF_8));
        }
        entries.putIfAbsent(RUN_FILE, runScript.getBytes(StandardCharsets.UTF_8));

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
     * Maps the version's real language to compile/run recipes, matching the entry
     * filenames the editor generates (main.py, Main.java, main.cpp, ...).
     */
    private Scripts scriptsFor(int languageId) {
        String name = judge0Client.languageName(languageId);
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        // Order matters: match "node"/"typescript" before "java", "c++"/"c#" before "c".
        if (n.contains("python")) {
            return interpreted("python3 main.py");
        }
        if (n.contains("typescript")) {
            return new Scripts(
                    opts -> "#!/bin/bash\nset -e\ntsc *.ts\n",
                    args -> runLine("node main.js", args)
            );
        }
        if (n.contains("javascript") || n.contains("node")) {
            return interpreted("node main.js");
        }
        if (n.contains("ruby")) {
            return interpreted("ruby main.rb");
        }
        if (n.contains("php")) {
            return interpreted("php main.php");
        }
        if (n.contains("bash") || n.contains("shell")) {
            return interpreted("bash main.sh");
        }
        if (n.contains("c++") || n.contains("cpp")) {
            return compiled(opts -> "g++ " + opts + " *.cpp -o a.out", "./a.out");
        }
        if (n.contains("c#") || n.contains("csharp")) {
            return new Scripts(
                    opts -> "#!/bin/bash\nset -e\nmcs " + opts + " -out:main.exe *.cs\n",
                    args -> runLine("mono main.exe", args)
            );
        }
        if (n.contains("kotlin")) {
            return new Scripts(
                    opts -> "#!/bin/bash\nset -e\nkotlinc *.kt -include-runtime -d main.jar\n",
                    args -> runLine("java -jar main.jar", args)
            );
        }
        if (n.contains("java")) {
            return new Scripts(
                    opts -> "#!/bin/bash\nset -e\njavac *.java\n",
                    args -> runLine("java Main", args)
            );
        }
        if (n.startsWith("go ") || n.equals("go") || n.contains("(go ")) {
            // go run compiles the whole package flat directory in one shot.
            return new Scripts(null, args -> runLine("go run *.go", args));
        }
        if (n.contains("rust")) {
            return compiled(opts -> "rustc " + opts + " main.rs -o a.out", "./a.out");
        }
        if (n.contains("swift")) {
            return compiled(opts -> "swiftc " + opts + " *.swift -o a.out", "./a.out");
        }
        if (n.startsWith("c ") || n.equals("c")) {
            return compiled(opts -> "gcc " + opts + " *.c -o a.out", "./a.out");
        }
        return null;
    }

    private Scripts interpreted(String runCommand) {
        return new Scripts(null, args -> runLine(runCommand, args));
    }

    private Scripts compiled(java.util.function.Function<String, String> compileCommand, String runCommand) {
        return new Scripts(
                opts -> "#!/bin/bash\nset -e\n" + compileCommand.apply(opts).trim() + "\n",
                args -> runLine(runCommand, args)
        );
    }

    private String runLine(String command, String args) {
        String suffix = args == null || args.isBlank() ? "" : " " + args.trim();
        return "#!/bin/bash\n" + command + suffix + "\n";
    }

    /**
     * A pair of script builders. {@code compile} may be {@code null} for
     * interpreted languages; both take the version's options/arguments.
     */
    private record Scripts(
            java.util.function.Function<String, String> compileBuilder,
            java.util.function.Function<String, String> runBuilder
    ) {
        String compile(String options) {
            return compileBuilder == null ? null : compileBuilder.apply(options == null ? "" : options);
        }

        String run(String arguments) {
            return runBuilder.apply(arguments == null ? "" : arguments);
        }
    }
}
