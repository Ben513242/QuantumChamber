package dev.quantumchamber.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class ClientLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void usesScriptDirectoryAndFixedClientArgumentsFromForeignDirectory() throws Exception {
        Path javaHome = installedJava("preferred Java");
        Result result = launch(0, javaHome, true);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(result.project().toString(), result.observed("cwd"));
        assertEquals("--no-daemon runClient", result.observed("args"));
        assertEquals(javaHome.toString(), result.observed("java-home"));
    }

    @Test
    void returnsZeroAfterSuccessfulGradleExit() throws Exception {
        Result result = launch(0, installedJava("Java 21"), false);
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void selectsLightingProfileOnlyWhenRequested() throws Exception {
        Path javaHome = installedJava("Java 21");
        Result result = launch(0, javaHome, false, "light");
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("--no-daemon runClientLight", result.observed("args"));
        assertEquals(result.project().toString(), result.observed("cwd"));
        assertEquals(javaHome.toString(), result.observed("java-home"));
        assertEquals(temporaryDirectory.resolve("foreign caller").toString(), result.observed("caller-cwd"));
    }

    @Test
    void preservesLightingProfileGradleFailureCode() throws Exception {
        Result result = launch(23, installedJava("Java 21"), false, "light");
        assertEquals(23, result.exitCode(), result.output());
        assertEquals("--no-daemon runClientLight", result.observed("args"));
        assertTrue(result.output().contains("失敗"), result.output());
    }

    @Test
    void rejectsUnknownArgumentWithoutLaunchingGradle() throws Exception {
        Result result = launch(0, installedJava("Java 21"), false, "unknown");
        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("start-client.bat [light]"), result.output());
        assertTrue(Files.notExists(result.project().resolve("args.txt")));
    }

    @Test
    void rejectsExtraArgumentWithoutLaunchingGradle() throws Exception {
        Result result = launch(0, installedJava("Java 21"), false, "light extra");
        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(Files.notExists(result.project().resolve("args.txt")));
    }

    @Test
    void preservesGradleFailureCodeAndReadableError() throws Exception {
        Result result = launch(23, installedJava("Java 21"), false);
        assertEquals(23, result.exitCode(), result.output());
        assertTrue(result.output().contains("fixture-failure-23"), result.output());
        assertTrue(result.output().contains("Java 21"), result.output());
        assertTrue(result.output().contains("失敗"), result.output());
    }

    @Test
    void findsInstalledAdoptiumJava21WhenJavaHomeIsUnset() throws Exception {
        Result result = launch(0, null, true);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(temporaryDirectory.resolve("Program Files/Eclipse Adoptium/jdk-21.fixture")
                .toString(), result.observed("java-home"));
    }

    @Test
    void leavesPathJavaSelectionToGradleWhenNoAdoptiumInstallationExists() throws Exception {
        Result result = launch(0, null, false);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("", result.observed("java-home"));
    }

    @Test
    void doesNotReplaceInvalidExplicitJavaHomeWithFallback() throws Exception {
        Path invalidJavaHome = temporaryDirectory.resolve("missing explicit Java");
        Result result = launch(0, invalidJavaHome, true);
        assertEquals(1, result.exitCode(), result.output());
        assertEquals(invalidJavaHome.toString(), result.observed("java-home"));
        assertTrue(result.output().contains("fixture-invalid-JAVA_HOME"), result.output());
    }

    private Path installedJava(String directory) throws Exception {
        Path javaHome = temporaryDirectory.resolve(directory);
        Files.createDirectories(javaHome.resolve("bin"));
        Files.createFile(javaHome.resolve("bin/java.exe"));
        return javaHome;
    }

    private Result launch(int gradleExitCode, Path javaHome, boolean fallback) throws Exception {
        return launch(gradleExitCode, javaHome, fallback, "");
    }

    private Result launch(int gradleExitCode, Path javaHome, boolean fallback, String clientArgument)
            throws Exception {
        Path source = Path.of("../..", "start-client.bat").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "缺少專案根目錄 start-client.bat");
        Path project = Files.createDirectories(temporaryDirectory.resolve("isolated project with spaces"));
        Path foreignDirectory = Files.createDirectories(temporaryDirectory.resolve("foreign caller"));
        Path programFiles = Files.createDirectories(temporaryDirectory.resolve("Program Files"));
        if (fallback) {
            installedJava("Program Files/Eclipse Adoptium/jdk-21.fixture");
        }
        Files.copy(source, project.resolve("start-client.bat"));
        // 替身只取代 Gradle 邊界；仍由真實 cmd.exe 執行正式啟動腳本。
        String fixture = """
                @echo off
                > cwd.txt echo %cd%
                > args.txt echo %*
                > java-home.txt echo(%JAVA_HOME%
                if not defined JAVA_HOME goto ready
                if exist "%JAVA_HOME%\\bin\\java.exe" goto ready
                echo fixture-invalid-JAVA_HOME 1>&2
                exit /b 1
                :ready
                echo fixture-failure-EXIT_CODE 1>&2
                exit /b EXIT_CODE
                """.replace("EXIT_CODE", Integer.toString(gradleExitCode));
        Files.writeString(project.resolve("gradlew.bat"), fixture.replace("\n", "\r\n"),
                StandardCharsets.UTF_8);
        // cmd.exe 啟動會重建 ProgramFiles；必須在隔離 caller 內設定才真正隔離宿主 JDK。
        Path caller = foreignDirectory.resolve("fixture-caller.bat");
        Files.writeString(caller, "@echo off\r\nset \"ProgramFiles=" + programFiles
                + "\"\r\ncall \"" + project.resolve("start-client.bat")
                + "\" " + clientArgument + "\r\nset \"CALLER_EXIT_CODE=%errorlevel%\"\r\n> \""
                + project.resolve("caller-cwd.txt") + "\" echo %cd%\r\nexit /b %CALLER_EXIT_CODE%\r\n",
                StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", "call \"" + caller + "\"");
        builder.directory(foreignDirectory.toFile()).redirectErrorStream(true);
        // Windows 環境名稱不分大小寫；移除所有大小寫變體，避免繼承宿主 JAVA_HOME。
        builder.environment().keySet().removeIf(name -> name.equalsIgnoreCase("JAVA_HOME"));
        if (javaHome != null) {
            builder.environment().put("JAVA_HOME", javaHome.toString());
        }
        Process process = builder.start();
        try {
            // 供失敗後的 pause 消耗輸入，測試不會留住任何客戶端或命令程序。
            process.getOutputStream().write("\r\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "隔離啟動腳本未在期限內結束");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output, project);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record Result(int exitCode, String output, Path project) {
        String observed(String name) throws Exception {
            return Files.readString(project.resolve(name + ".txt"), StandardCharsets.UTF_8).strip();
        }
    }
}
