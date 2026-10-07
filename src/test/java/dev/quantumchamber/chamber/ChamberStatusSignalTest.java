package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChamberStatusSignalTest {
    @Test
    void mapsEachM1StateToItsLockedComparatorSignal() {
        assertEquals(0, ChamberStatusSignal.forState(ChamberState.INVALID));
        assertEquals(3, ChamberStatusSignal.forState(ChamberState.IDLE));
        assertEquals(7, ChamberStatusSignal.forState(ChamberState.READY));
        assertEquals(11, ChamberStatusSignal.forState(ChamberState.ARMED));
    }

    /** M5 spec §10.7（F5-2）：量子通道開啟中是 15；M1 的四個狀態不變，enum 恰好多了這一個。 */
    @Test
    void passageIsFifteenAndTheOnlyStateAddedByM5() {
        assertEquals(15, ChamberStatusSignal.forState(ChamberState.PASSAGE));
        assertEquals(List.of(ChamberState.INVALID, ChamberState.IDLE, ChamberState.READY, ChamberState.ARMED, ChamberState.PASSAGE), List.of(ChamberState.values()));
    }

    /**
     * M5 spec §10.7（F5-2）：Controller 開關門訊息的 PASSAGE（{@code ChamberControllerBlock} 的 switch）是 translatable，key 在 {@code message.quantumchamber.m5.*}，
     * zh_tw 與 en_us 都有（plan Global Constraints；字句由 F5-2 依既有的寫法決定）。key 以字面值斷言，不信任 production 的字串。
     */
    @Test
    void passageControllerMessageExistsInBothLanguages() throws Exception {
        var key = "message.quantumchamber.m5.controller_passage";
        var zh = lang("zh_tw");
        var en = lang("en_us");
        assertTrue(zh.has(key) && en.has(key), "兩個語言檔都要有 " + key);
        assertEquals("艙體的量子通道已開啟，原艙維持封鎖。", zh.get(key).getAsString());
        assertFalse(en.get(key).getAsString().isBlank(), "en_us 有翻譯");
        assertEquals(zh.keySet(), en.keySet(), "兩個語言檔的 key 集合相同");
    }

    private static JsonObject lang(String name) throws Exception {
        try (var stream = ChamberStatusSignal.class.getResourceAsStream("/assets/quantumchamber/lang/" + name + ".json")) {
            assertNotNull(stream, "語言檔不在 classpath：" + name);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
