package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.chamber.ChamberProtectionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * F4b-3 Step 0（F4b-1 quality Q-m1）：{@code chamber} package 不得依賴 {@code collapse} package。跨 package 一律走反轉：{@code collapse} 實作並註冊
 * {@code chamber} 定義的介面（{@code ChamberCollapsePort}；比照 {@code ChamberSessions.gateway()} 與 {@code ChamberProtectionService.registerAdditionalGuard}）。
 * 掃描 {@code src/main/java/dev/quantumchamber/chamber/} 下所有 .java（含子 package）的原始碼：比對前以 {@link CollapseLogMarkerGuardTest#mask} 把註解換成空白、
 * 字串與字元常值的內容遮掉，之後不得出現 {@code dev.quantumchamber.collapse}（import、static import、完整限定名稱都算；點的前後可以有空白）。
 * <p>不處理（寫明理由）：以字串經反射載入 collapse 的類別（字串已遮掉）——chamber 沒有這種寫法，要禁止它需要另一種守門；只約束 main 的原始碼，test 與
 * testmod 不在範圍內。原始碼位置從 {@code ChamberProtectionService} 的 class 所在位置往上找，找不到再從工作目錄往上找（與 {@code CollapseLogMarkerGuardTest} 相同）。
 */
class ChamberCollapseDependencyGuardTest {
    private static final String CHAMBER_DIR = "src/main/java/dev/quantumchamber/chamber";
    private static final Pattern COLLAPSE_REFERENCE = Pattern.compile("\\bdev\\s*\\.\\s*quantumchamber\\s*\\.\\s*collapse\\b");

    @Test void chamberSourcesNeverReferenceTheCollapsePackage() throws Exception {
        var dir = chamberSources();
        List<Path> files;
        try (var walk = Files.walk(dir)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        var names = files.stream().map(file -> dir.relativize(file).toString().replace('\\', '/')).toList();
        var violations = new ArrayList<String>();
        for (var file : files) {
            var name = dir.relativize(file).toString().replace('\\', '/');
            violations.addAll(references(name, Files.readString(file, StandardCharsets.UTF_8)));
        }
        assertAll(
                () -> assertTrue(names.containsAll(Set.of("ChamberProtectionService.java", "ChamberPowerCoordinator.java", "WorldChamberDoorAdapter.java")),
                        () -> "掃描範圍要包含改走反轉介面的檔案：" + names + " " + dir),
                () -> assertEquals(List.of(), violations, "chamber package 不得依賴 collapse package（改走 ChamberCollapsePort）"));
    }

    /** 掃描器本身：import、static import、完整限定名稱與點旁的空白都抓到；註解、Javadoc、字串與名稱相近的識別字不算。 */
    @Test void scannerSeesImportsAndQualifiedNamesButIgnoresCommentsAndStrings() {
        var allowed = references("Allowed.java", "package dev.quantumchamber.chamber;\n"
                + "// dev.quantumchamber.collapse 的投影保護索引（註解裡的字樣不算）\n"
                + "/** {@code dev.quantumchamber.collapse.CollapseService}（Javadoc 也不算） */\n"
                + "class Allowed {\n"
                + "    String text = \"dev.quantumchamber.collapse.ProjectionProtectionIndex\";\n"
                + "    Object near = dev.quantumchamber.collapsed.Other.VALUE;\n"
                + "}\n");
        assertAll(
                () -> assertEquals(List.of(), allowed, "註解、字串與名稱相近的識別字"),
                () -> assertEquals(List.of("Import.java:2"), references("Import.java",
                        "package dev.quantumchamber.chamber;\nimport dev.quantumchamber.collapse.ProjectionProtectionIndex;\n")),
                () -> assertEquals(List.of("Static.java:2"), references("Static.java",
                        "package dev.quantumchamber.chamber;\nimport static dev.quantumchamber.collapse.CollapseService.LOG_MARKER;\n")),
                () -> assertEquals(List.of("Qualified.java:3"), references("Qualified.java",
                        "class Qualified {\n    boolean f() {\n        return dev.quantumchamber.collapse.ProjectionProtectionIndex.protectedCell(null, null, null);\n    }\n}\n")),
                () -> assertEquals(List.of("Spaced.java:1"), references("Spaced.java", "import dev . quantumchamber\n    . collapse . CollapseService;\n")));
    }

    /** {@code file} 中每一處 collapse package 的參考，以「檔名:行號」表示（行號是參考開頭所在的行）。 */
    static List<String> references(String file, String source) {
        var code = CollapseLogMarkerGuardTest.mask(source);
        var found = new ArrayList<String>();
        var matcher = COLLAPSE_REFERENCE.matcher(code);
        while (matcher.find()) found.add(file + ":" + line(source, matcher.start()));
        return List.copyOf(found);
    }

    private static int line(String source, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    /** chamber package 的原始碼目錄：從 ChamberProtectionService 的 class 所在位置往上找，再從工作目錄往上找。 */
    private static Path chamberSources() throws Exception {
        var starts = new ArrayList<Path>();
        var codeSource = ChamberProtectionService.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) starts.add(Path.of(codeSource.getLocation().toURI()));
        starts.add(Path.of(System.getProperty("user.dir")));
        for (var start : starts) {
            for (var dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
                var candidate = dir.resolve(CHAMBER_DIR);
                if (Files.isRegularFile(candidate.resolve("ChamberProtectionService.java"))) return candidate;
            }
        }
        return fail("找不到 " + CHAMBER_DIR + "（從 " + starts + " 往上找）");
    }
}
