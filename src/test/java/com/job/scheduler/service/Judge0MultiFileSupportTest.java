package com.job.scheduler.service;

import com.job.scheduler.workflow.task.TaskResourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
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
    void interpretedLanguageInjectsRunScriptAndPreservesFiles() {
        when(judge0Client.languageName(71)).thenReturn("Python (3.8.1)");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", "print('hi')\n");
        files.put("helper.py", "def bump(x):\n    return x + 1\n");

        String archive = support().buildArchive(71, zip(files), null, null);
        Map<String, String> out = unzip(archive);

        assertThat(out).containsKeys("main.py", "helper.py", "run");
        assertThat(out).doesNotContainKey("compile");
        assertThat(out.get("run")).contains("python3 main.py");
        assertThat(out.get("main.py")).isEqualTo("print('hi')\n");
    }

    @Test
    void compiledLanguageInjectsCompileAndRunWithOptionsAndArgs() {
        when(judge0Client.languageName(54)).thenReturn("C++ (GCC 9.2.0)");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.cpp", "int main(){return 0;}");
        files.put("util.cpp", "");

        String archive = support().buildArchive(54, zip(files), "-O2", "--fast");
        Map<String, String> out = unzip(archive);

        assertThat(out).containsKeys("main.cpp", "util.cpp", "compile", "run");
        assertThat(out.get("compile")).contains("g++").contains("-O2").contains("*.cpp -o a.out");
        assertThat(out.get("run")).contains("./a.out").contains("--fast");
    }

    @Test
    void javaUsesMainClassEntry() {
        when(judge0Client.languageName(62)).thenReturn("Java (OpenJDK 13.0.1)");
        String archive = support().buildArchive(62, zip(Map.of("Main.java", "class Main{}")), null, null);
        Map<String, String> out = unzip(archive);
        assertThat(out.get("compile")).contains("javac *.java");
        assertThat(out.get("run")).contains("java Main");
    }

    @Test
    void unsupportedLanguageIsRejectedGracefully() {
        when(judge0Client.languageName(99)).thenReturn("Prolog (GNU Prolog 1.4.5)");
        assertThat(support().isSupported(99)).isFalse();
        assertThatThrownBy(() -> support().buildArchive(99, zip(Map.of("a.pl", "x.")), null, null))
                .isInstanceOf(TaskResourceException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void existingUserRunScriptIsPreserved() {
        when(judge0Client.languageName(71)).thenReturn("Python (3.8.1)");
        Map<String, String> files = new HashMap<>();
        files.put("main.py", "print('hi')");
        files.put("run", "#!/bin/bash\npython3 custom.py\n");
        Map<String, String> out = unzip(support().buildArchive(71, zip(files), null, null));
        assertThat(out.get("run")).contains("custom.py");
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
