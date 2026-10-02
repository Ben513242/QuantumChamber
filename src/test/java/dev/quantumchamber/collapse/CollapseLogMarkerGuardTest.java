package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * F4a-0 fix round 1（quality Q3）：塌縮流程的每一則 log 都必須帶 {@link CollapseService#LOG_MARKER} 或 {@link CollapseService#TICK_LOG_MARKER}。
 * GameTest 的 {@code collapseFlowNeverWarned} 依 marker 篩選，沒帶 marker 的 WARN 不會被看到、witness 會默默通過，所以由這裡守門：掃描
 * {@code src/main/java/dev/quantumchamber/collapse/} 下所有 .java（含子 package）的原始碼。
 * <ul>
 * <li>每個 {@code LOGGER.trace/debug/info/warn/error(} 的第一個參數必須是 {@code LOG_MARKER} 或 {@code TICK_LOG_MARKER}（可以加
 *     {@code CollapseService.} 前綴）。比對前先把註解換成空白、字串與字元常值（含 text block）的內容遮掉，所以跨行的呼叫照樣抓得到，註解或字串裡的
 *     字樣不算。</li>
 * <li>logger 只能是 {@code Logger LOGGER = LoggerFactory.getLogger("quantumchamber")}：{@code LogCapture} 依 logger 名稱擷取，用 class 或其他名稱的
 *     logger 時 capture 看不到。其他名稱的 {@code Logger} 變數（別名）、宣告以外的 {@code getLogger(}、{@code LOGGER} 當成值使用（方法參考、當成參數
 *     傳出）、{@code LOGGER} 的其他方法（例如 fluent API 的 {@code atWarn()}；{@code is*Enabled()} 除外）、直接用 log4j 的 {@code LogManager}、
 *     {@code System.out/err} 或 {@code printStackTrace}，都算違規。</li>
 * <li>只有 {@code CollapseService} 可以宣告這兩個 marker；別的類別宣告同名常數，第一項的比對就失去意義。</li>
 * <li>例外：F2b 的 {@code CollapseAuthorities} 記 M5 權威的初始化與收斂，不歸屬任何 session，也不是塌縮流程的推進；它既有的 7 則 log 逐則列在
 *     {@link #LEGACY}（檔名、等級、第一個字串常值的開頭），每一則必須恰好對到一個呼叫。多一則沒有 marker 的 log 就失敗：新的 log 要帶 marker，
 *     或經 review 加進清單。</li>
 * </ul>
 * 不處理（寫明理由）：以反射或在別的 package 的 helper 裡代為記錄的 log——規則只約束 collapse package 的原始碼，別的 package 的 log 不是
 * 塌縮流程的 log；把 marker 存進別名常數再傳入（例如 {@code Marker m = LOG_MARKER}）會被當成沒有 marker 而失敗，請直接寫常數。
 * Gradle 的 test 工作目錄是 {@code run/testworlds}，所以原始碼位置從 {@code CollapseService} 的 class 所在位置（{@code build/classes/java/main}）
 * 往上找，找不到再從工作目錄往上找；都找不到就失敗。
 */
class CollapseLogMarkerGuardTest {
    private static final String PACKAGE_DIR = "src/main/java/dev/quantumchamber/collapse";

    /** 不帶 marker 的既有 log：{@code CollapseAuthorities}（F2b，M5 權威的初始化與收斂）。 */
    private static final List<Legacy> LEGACY = List.of(
            new Legacy("CollapseAuthorities.java", "info", "M5 authority 初始化完成"),
            new Legacy("CollapseAuthorities.java", "error", "M5 authority 初始化失敗"),
            new Legacy("CollapseAuthorities.java", "error", "M5 {} 的檔案在執行期間消失"),
            new Legacy("CollapseAuthorities.java", "warn", "M5 CATALOG 的 checked 寫出未確認"),
            new Legacy("CollapseAuthorities.java", "info", "M5 CATALOG 收斂完成"),
            new Legacy("CollapseAuthorities.java", "info", "M5 {} 收斂完成"),
            new Legacy("CollapseAuthorities.java", "warn", "M5 {} 的 checked 寫入未確認"));

    private static final Pattern LOGGER_TOKEN = Pattern.compile("\\bLOGGER\\b");
    private static final Pattern DECLARATION = Pattern.compile(
            "\\bLogger\\s+LOGGER\\s*=\\s*(?:org\\s*\\.\\s*slf4j\\s*\\.\\s*)?LoggerFactory\\s*\\.\\s*getLogger\\s*\\(\\s*\"");
    private static final Pattern OTHER_LOGGER = Pattern.compile("\\bLogger\\s+(\\w+)");
    private static final Pattern GET_LOGGER = Pattern.compile("\\bgetLogger\\s*\\(");
    private static final Pattern MEMBER_CALL = Pattern.compile("\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern MARKER_ARGUMENT = Pattern.compile("(?:CollapseService\\s*\\.\\s*)?(?:TICK_)?LOG_MARKER");
    private static final Pattern MARKER_DECLARATION = Pattern.compile("\\b(?:TICK_)?LOG_MARKER\\s*=(?!=)");
    private static final Pattern FORBIDDEN = Pattern.compile("\\bLogManager\\b|\\bSystem\\s*\\.\\s*(?:out|err)\\b|\\.\\s*printStackTrace\\s*\\(");
    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");
    private static final Set<String> QUERIES = Set.of("isTraceEnabled", "isDebugEnabled", "isInfoEnabled", "isWarnEnabled", "isErrorEnabled");

    record Legacy(String file, String level, String prefix) { }
    /** 第一個參數不是塌縮 marker 的 logger 呼叫：檔名、行號、等級、第一個參數（字串常值時為它的內容）。 */
    record Unmarked(String file, int line, String level, String first) { }
    /** 一個檔案的掃描結果：帶 marker 的呼叫數、沒帶 marker 的呼叫、其他違規。 */
    record Scan(int markedCalls, List<Unmarked> unmarked, List<String> violations) { }

    @Test void everyCollapseLogCallCarriesTheCollapseMarker() throws Exception {
        var dir = collapseSources();
        List<Path> files;
        try (var walk = Files.walk(dir)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        var violations = new ArrayList<String>();
        var unmarked = new ArrayList<Unmarked>();
        int serviceMarked = -1;
        for (var file : files) {
            var name = dir.relativize(file).toString().replace('\\', '/');
            var scan = scan(name, Files.readString(file, StandardCharsets.UTF_8));
            violations.addAll(scan.violations());
            unmarked.addAll(scan.unmarked());
            if (name.equals("CollapseService.java")) serviceMarked = scan.markedCalls();
        }
        var remaining = new ArrayList<>(unmarked);
        for (var legacy : LEGACY) {
            var hits = remaining.stream().filter(call -> call.file().equals(legacy.file()) && call.level().equals(legacy.level())
                    && call.first().startsWith(legacy.prefix())).toList();
            if (hits.size() != 1) violations.add("例外清單的 " + legacy + " 對到 " + hits.size() + " 個呼叫（應恰好 1 個）：" + hits);
            remaining.removeAll(hits);
        }
        remaining.forEach(call -> violations.add(call.file() + ":" + call.line() + " LOGGER." + call.level()
                + " 的第一個參數不是 LOG_MARKER／TICK_LOG_MARKER：" + call.first()));
        int marked = serviceMarked;
        assertAll(
                () -> assertEquals("QUANTUMCHAMBER_COLLAPSE", CollapseService.LOG_MARKER.getName()),
                () -> assertTrue(CollapseService.TICK_LOG_MARKER.contains(CollapseService.LOG_MARKER), "TICK_LOG_MARKER 的父 marker 是 LOG_MARKER"),
                () -> assertTrue(marked > 0, () -> "掃描範圍要包含 CollapseService.java，而且看得到它帶 marker 的 log 呼叫：" + marked + " " + dir),
                () -> assertEquals(List.of(), violations, "塌縮流程（collapse package）的每一則 log 都必須帶 LOG_MARKER 或 TICK_LOG_MARKER"));
    }

    /** 掃描器本身：跨行呼叫、限定名稱、註解與字串裡的字樣、各種別名與繞過。 */
    @Test void scannerSeesMultilineCallsAndRejectsAliasesButIgnoresCommentsAndStrings() {
        var declaration = "    private static final Logger LOGGER = LoggerFactory.getLogger(\"quantumchamber\");\n";
        var good = scan("Good.java", "class Good {\n" + declaration
                + "    void f(String text) {\n"
                + "        // LOGGER.warn(\"註解裡的字樣不算\");\n"
                + "        /* LOGGER.error(\"區塊註解也不算\"); */\n"
                + "        var quoted = \"LOGGER.warn(\\\"字串裡的字樣不算\\\")\" + '\"';\n"
                + "        var block = \"\"\"\n            LOGGER.warn(\"text block 裡的字樣不算\");\n            \"\"\";\n"
                + "        LOGGER.warn(\n                LOG_MARKER,\n                \"跨行的呼叫：{}\", text);\n"
                + "        LOGGER.info(CollapseService.TICK_LOG_MARKER, \"限定名稱\");\n"
                + "        if (LOGGER.isDebugEnabled()) LOGGER.debug(LOG_MARKER, \"查詢等級不算呼叫\");\n"
                + "    }\n}\n");
        var multiline = scan("Multi.java", "class Multi {\n" + declaration + "    void f(int x) {\n        LOGGER\n            .warn(\n"
                + "                \"沒有 marker：{}\", x);\n    }\n}\n");
        assertAll(
                () -> assertEquals(new Scan(3, List.of(), List.of()), good, "合規的寫法"),
                () -> assertEquals(List.of(new Unmarked("Multi.java", 4, "warn", "沒有 marker：{}")), multiline.unmarked(), "跨行而沒有 marker 的呼叫"),
                () -> assertViolation("別名 logger", "class C {\n" + declaration
                        + "    private static final Logger LOG = LoggerFactory.getLogger(\"quantumchamber\");\n    void f() { LOG.warn(\"x\"); }\n}\n"),
                () -> assertViolation("logger 名稱", "class C {\n    private static final Logger LOGGER = LoggerFactory.getLogger(\"C\");\n}\n"),
                () -> assertViolation("logger 名稱", "class C {\n    private static final Logger LOGGER = LoggerFactory.getLogger(C.class);\n}\n"),
                () -> assertViolation("LOGGER 只能", "class C {\n" + declaration + "    java.util.function.Consumer<String> log = LOGGER::warn;\n}\n"),
                () -> assertViolation("LOGGER 只能", "class C {\n" + declaration + "    void f() { g(LOGGER); }\n}\n"),
                () -> assertViolation("LOGGER.atWarn", "class C {\n" + declaration + "    void f() { LOGGER.atWarn().log(\"x\"); }\n}\n"),
                () -> assertViolation("不得繞過", "class C {\n    void f() { org.apache.logging.log4j.LogManager.getLogger(\"quantumchamber\").warn(\"x\"); }\n}\n"),
                () -> assertViolation("不得繞過", "class C {\n    void f() { System.err.println(\"x\"); }\n}\n"),
                () -> assertViolation("不得繞過", "class C {\n    void f(Exception e) { e.printStackTrace(); }\n}\n"),
                () -> assertViolation("只有 CollapseService", "class C {\n    static final org.slf4j.Marker LOG_MARKER = org.slf4j.MarkerFactory.getMarker(\"X\");\n}\n"),
                () -> assertViolation("沒有宣告", "class C {\n    void f() { LOGGER.warn(LOG_MARKER, \"x\"); }\n}\n"));
    }

    private static void assertViolation(String expected, String source) {
        var violations = scan("C.java", source).violations();
        assertTrue(violations.stream().anyMatch(violation -> violation.contains(expected)), () -> "預期違規「" + expected + "」：" + violations + "\n" + source);
    }

    /** 掃描一個檔案（{@code file} 是相對於 collapse package 的路徑，只用於訊息與 marker 宣告的判定）。 */
    static Scan scan(String file, String source) {
        var code = mask(source);
        var violations = new ArrayList<String>();
        var unmarked = new ArrayList<Unmarked>();
        var declaredTokens = new HashSet<Integer>();
        var declaredGetLogger = new HashSet<Integer>();
        var declarations = DECLARATION.matcher(code);
        while (declarations.find()) {
            declaredTokens.add(code.indexOf("LOGGER", declarations.start()));
            declaredGetLogger.add(code.lastIndexOf("getLogger", declarations.end()));
            int open = declarations.end() - 1, close = code.indexOf('"', open + 1);
            var name = close < 0 ? "" : source.substring(open + 1, close);
            if (!name.equals("quantumchamber") || !code.substring(close + 1).stripLeading().startsWith(")"))
                violations.add(where(file, source, declarations.start()) + " logger 名稱必須是 \"quantumchamber\"（LogCapture 依 logger 名稱擷取）：" + name);
        }
        int marked = 0;
        boolean usesLogger = false;
        var tokens = LOGGER_TOKEN.matcher(code);
        while (tokens.find()) {
            int at = tokens.start();
            if (declaredTokens.contains(at)) continue;
            usesLogger = true;
            var member = MEMBER_CALL.matcher(code).region(tokens.end(), code.length());
            if (!member.lookingAt()) {
                violations.add(where(file, source, at) + " LOGGER 只能以 LOGGER.<等級>(marker, …) 使用（別名、方法參考、當成參數傳出，或不是 "
                        + "LoggerFactory.getLogger(\"quantumchamber\") 的宣告都不允許；logger 名稱必須是 quantumchamber）");
                continue;
            }
            var method = member.group(1);
            if (QUERIES.contains(method)) continue;
            if (!LEVELS.contains(method)) {
                violations.add(where(file, source, at) + " LOGGER." + method + "(…) 不允許：只用 trace/debug/info/warn/error，並以 marker 為第一個參數");
                continue;
            }
            int start = member.end(), end = argumentEnd(code, start);
            var first = code.substring(start, end).strip();
            if (MARKER_ARGUMENT.matcher(first).matches()) { marked++; continue; }
            int quote = code.indexOf('"', start);
            var shown = first.startsWith("\"") && quote >= 0 && quote < end ? source.substring(quote + 1, Math.max(quote + 1, code.indexOf('"', quote + 1)))
                    : first;
            unmarked.add(new Unmarked(file, line(source, at), method, shown));
        }
        var others = OTHER_LOGGER.matcher(code);
        while (others.find()) if (!others.group(1).equals("LOGGER")) violations.add(where(file, source, others.start()) + " 別名 logger：" + others.group(1));
        var getters = GET_LOGGER.matcher(code);
        while (getters.find()) if (!declaredGetLogger.contains(getters.start()))
            violations.add(where(file, source, getters.start()) + " getLogger 只能用在 LOGGER 的宣告（logger 名稱必須是 quantumchamber）");
        var forbidden = FORBIDDEN.matcher(code);
        while (forbidden.find()) violations.add(where(file, source, forbidden.start()) + " 不得繞過 LOGGER：" + forbidden.group());
        if (!file.equals("CollapseService.java")) {
            var markers = MARKER_DECLARATION.matcher(code);
            while (markers.find()) violations.add(where(file, source, markers.start()) + " 只有 CollapseService 可以宣告塌縮流程的 marker");
        }
        if (usesLogger && declaredTokens.isEmpty()) violations.add(file + " 使用 LOGGER，卻沒有宣告 LoggerFactory.getLogger(\"quantumchamber\")");
        return new Scan(marked, List.copyOf(unmarked), List.copyOf(violations));
    }

    /** 第一個參數的結尾：深度 0 的 {@code ,} 或 {@code )}（字串已遮掉，括號只會是程式碼的括號）。 */
    private static int argumentEnd(String code, int start) {
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && depth > 0) depth--;
            else if ((c == ',' || c == ')') && depth == 0) return i;
        }
        return code.length();
    }

    /**
     * 註解換成空白（保留換行，行號不變）；字串、字元常值與 text block 的內容換成 {@code _}（保留引號與長度）。其餘原樣，所以索引與原始碼一一對應。
     */
    static String mask(String source) {
        var out = source.toCharArray();
        int n = source.length(), i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) if (out[i] != '\n' && out[i] != '\r') out[i] = ' ';
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end;
                for (int j = i + 3; j < end; j++) if (out[j] != '\n' && out[j] != '\r') out[j] = '_';
                i = Math.min(n, end + 3);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') j += source.charAt(j) == '\\' ? 2 : 1;
                for (int k = i + 1; k < Math.min(j, n); k++) out[k] = '_';
                i = Math.min(n, j + 1);
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String where(String file, String source, int at) {
        return file + ":" + line(source, at);
    }

    private static int line(String source, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    /** collapse package 的原始碼目錄：從 CollapseService 的 class 所在位置往上找，再從工作目錄往上找。 */
    private static Path collapseSources() throws Exception {
        var starts = new ArrayList<Path>();
        var codeSource = CollapseService.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) starts.add(Path.of(codeSource.getLocation().toURI()));
        starts.add(Path.of(System.getProperty("user.dir")));
        for (var start : starts) {
            for (var dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
                var candidate = dir.resolve(PACKAGE_DIR);
                if (Files.isRegularFile(candidate.resolve("CollapseService.java"))) return candidate;
            }
        }
        return fail("找不到 " + PACKAGE_DIR + "（從 " + starts + " 往上找）");
    }
}
