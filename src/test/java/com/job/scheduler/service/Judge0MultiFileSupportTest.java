package com.job.scheduler.service;

import com.job.scheduler.service.Judge0MultiFileSupport.MultiFileSubmission;
import com.job.scheduler.workflow.task.TaskResourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Judge0MultiFileSupportTest {
    @Mock
    private Judge0Client judge0Client;

    private Judge0MultiFileSupport support() {
        return new Judge0MultiFileSupport(judge0Client);
    }

    @Test
    void interpretedLanguageRunsInNativeBoxWithEntryAsSourceCode() {
        when(judge0Client.languageName(71)).thenReturn("Python (3.8.1)");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", "from helper import bump\nprint(bump(1))\n");
        files.put("helper.py", "def bump(x):\n    return x + 1\n");

        MultiFileSubmission mf = support().plan(71, zip(files), null, null);

        assertThat(mf.languageId()).isEqualTo(71); // native box, not 89
        assertThat(mf.sourceCode()).contains("from helper import bump");
        // the entry is pulled out; only siblings remain in additional_files
        Map<String, String> extras = unzip(mf.additionalFilesBase64());
        assertThat(extras).containsOnlyKeys("helper.py");
    }

    @Test
    void rubyUsesNativeBox() {
        when(judge0Client.languageName(72)).thenReturn("Ruby (2.7.0)");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.rb", "require_relative 'helper'\nputs val\n");
        files.put("helper.rb", "def val; 42; end\n");

        MultiFileSubmission mf = support().plan(72, zip(files), null, null);
        assertThat(mf.languageId()).isEqualTo(72);
        assertThat(mf.sourceCode()).contains("require_relative");
        assertThat(unzip(mf.additionalFilesBase64())).containsOnlyKeys("helper.rb");
    }

    @Test
    void interpretedLanguageKeepsOptionsAndArgs() {
        when(judge0Client.languageName(63)).thenReturn("JavaScript (Node.js 12.14.0)");
        MultiFileSubmission mf = support().plan(63, zip(Map.of("main.js", "console.log(1)")), "--x", "--y");
        assertThat(mf.languageId()).isEqualTo(63);
        assertThat(mf.compilerOptions()).isEqualTo("--x");
        assertThat(mf.commandLineArguments()).isEqualTo("--y");
        assertThat(mf.additionalFilesBase64()).isNull(); // only the entry existed
    }

    @Test
    void compiledMultiSourceLanguageUsesMultiFileBoxWithScripts() {
        when(judge0Client.languageName(54)).thenReturn("C++ (GCC 9.2.0)");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.cpp", "int main(){return 0;}");
        files.put("util.cpp", "");

        MultiFileSubmission mf = support().plan(54, zip(files), "-O2", "--fast");

        assertThat(mf.languageId()).isEqualTo(Judge0MultiFileSupport.MULTI_FILE_LANGUAGE_ID); // 89
        assertThat(mf.sourceCode()).isNull();
        assertThat(mf.compilerOptions()).isNull(); // baked into the compile script
        assertThat(mf.commandLineArguments()).isNull();
        Map<String, String> out = unzip(mf.additionalFilesBase64());
        assertThat(out).containsKeys("main.cpp", "util.cpp", "compile", "run");
        assertThat(out.get("compile")).contains("g++").contains("-O2").contains("*.cpp -o a.out");
        assertThat(out.get("run")).contains("./a.out").contains("--fast");
    }

    @Test
    void javaUsesMultiFileBoxWithMainClassEntry() {
        when(judge0Client.languageName(62)).thenReturn("Java (OpenJDK 13.0.1)");
        MultiFileSubmission mf = support().plan(62, zip(Map.of("Main.java", "class Main{}")), null, null);
        Map<String, String> out = unzip(mf.additionalFilesBase64());
        assertThat(mf.languageId()).isEqualTo(89);
        assertThat(out.get("compile")).contains("javac *.java");
        assertThat(out.get("run")).contains("java Main");
    }

    @Test
    void unsupportedLanguageIsRejectedGracefully() {
        when(judge0Client.languageName(99)).thenReturn("Prolog (GNU Prolog 1.4.5)");
        assertThat(support().isSupported(99)).isFalse();
        assertThatThrownBy(() -> support().plan(99, zip(Map.of("a.pl", "x.")), null, null))
                .isInstanceOf(TaskResourceException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void languagesThatCannotBuildSiblingsAreDeclined() {
        // C#, Go, Kotlin, Swift: Judge0 compiles only the entry file for these and
        // their toolchain is absent from the multi-file box, so multi-file must be
        // declined cleanly rather than fail with a compile error.
        when(judge0Client.languageName(51)).thenReturn("C# (Mono 6.6.0.161)");
        when(judge0Client.languageName(60)).thenReturn("Go (1.13.5)");
        when(judge0Client.languageName(78)).thenReturn("Kotlin (1.3.70)");
        when(judge0Client.languageName(83)).thenReturn("Swift (5.2.3)");
        for (int id : new int[]{51, 60, 78, 83}) {
            assertThat(support().isSupported(id)).isFalse();
        }
    }

    @Test
    void missingEntryFileIsRejected() {
        when(judge0Client.languageName(71)).thenReturn("Python (3.8.1)");
        // no main.py / main.* and more than one file -> no resolvable entry
        Map<String, String> files = new LinkedHashMap<>();
        files.put("alpha.py", "x = 1");
        files.put("beta.py", "y = 2");
        assertThatThrownBy(() -> support().plan(71, zip(files), null, null))
                .isInstanceOf(TaskResourceException.class)
                .hasMessageContaining("entry file");
    }

    // --- helpers ---
    private static String zip(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static Map<String, String> unzip(String base64) {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        return files;
    }
}
