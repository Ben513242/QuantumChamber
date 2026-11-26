package dev.quantumchamber.gametest.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.fabric.impl.gametest.FabricGameTestHelper;
import net.minecraft.test.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod：依 run 選擇 batch，五個 run 各用獨立的新 save。
 * legacy run（legacyOnly）只跑 m4_legacy_runtime；M5 run（m5Only，plan 決策 6）只跑 batch id 前綴 m5_universe 的 test；
 * M6 run（m6Only）只跑 m6_universe；normal run（m4NormalOnly）只跑 m4_normal_；default 排除這四者。
 * M7 run（m7Home）再依 {@code quantumchamber.m7game.segment} 只留 m7-inventory.json {@code gametestSegments} 該段列出的
 * GameTest 類別（使用者 2026-11-25 裁示「拆成兩段」；段名與 inventory 路徑由 build.gradle 的 gameTestM7 傳入）。
 */
@Mixin(value=FabricGameTestHelper.class,remap=false)
abstract class M4LegacyGameTestFilterMixin {
    @Inject(method="getTestFunctions",at=@At("RETURN"),cancellable=true,remap=false)
    private static void quantumchamberTest$isolatedLegacy(CallbackInfoReturnable<Collection<TestFunction>> callback) {
        boolean legacy=Boolean.getBoolean("quantumchamber.gametest.legacyOnly");
        boolean m5=Boolean.getBoolean("quantumchamber.gametest.m5Only");
        boolean m6=Boolean.getBoolean("quantumchamber.gametest.m6Only");
        boolean m7=Boolean.getBoolean("quantumchamber.test.m7Home");
        boolean normal=Boolean.getBoolean("quantumchamber.gametest.m4NormalOnly");
        if((normal ? 1 : 0)+(legacy ? 1 : 0)+(m5 ? 1 : 0)+(m6 ? 1 : 0)+(m7 ? 1 : 0)>1) throw new IllegalStateException("M6_SCOPE_MISMATCH：scope flags 不可混用");
        var selected=callback.getReturnValue().stream().filter(test -> {
            boolean normalBatch=test.batchId().startsWith("m4_normal_");
            boolean legacyBatch=test.batchId().equals("m4_legacy_runtime");
            boolean m5Batch=test.batchId().startsWith("m5_universe");
            boolean m6Batch=test.batchId().startsWith("m6_universe");
            boolean m7Batch=test.batchId().startsWith("m7_home");
            boolean m7Platform=!test.batchId().endsWith("_windows")&&!test.batchId().endsWith("_ubuntu")
                    || test.batchId().endsWith(com.sun.jna.Platform.isWindows()?"_windows":"_ubuntu");
            return m7 ? m7Batch&&m7Platform : normal ? normalBatch : legacy ? legacyBatch : m5 ? m5Batch : m6 ? m6Batch : !normalBatch && !legacyBatch && !m5Batch && !m6Batch && !m7Batch;
        }).toList();
        if(m7) selected=quantumchamberTest$m7Segment(callback.getReturnValue(),selected);
        if(selected.isEmpty() || legacy && selected.size()!=1) throw new IllegalStateException("原生測試隔離範圍不符");
        // 僅 RED 取證用：明確列出的 batch 子集；未設定時完整 suite 不受影響。
        var only=System.getProperty("quantumchamber.gametest.onlyBatches","");
        if(!only.isBlank()) {
            var batches=java.util.Set.of(only.split(","));
            selected=selected.stream().filter(test -> batches.contains(test.batchId())).toList();
            if(!selected.stream().map(TestFunction::batchId).collect(java.util.stream.Collectors.toSet()).equals(batches)) throw new IllegalStateException("指定 batch 子集不符："+batches);
        }
        callback.setReturnValue(selected);
    }

    /**
     * M7 段的選取（同一個 JVM、平台篩選之後）：段名與各段類別讀自 {@code quantumchamber.m7game.inventory}（m7-inventory.json）的
     * {@code gametestSegments}。段名不在清單、清單格式不符、類別在兩段重複、JVM 內 m7_home 的類別集合與各段聯集不符，
     * 或本段在本平台選到 0 案，都 fail closed。類別以 {@link TestFunction#templatePath()} 的前綴辨識（原版
     * TestFunctions：類別 simple name 小寫＋「.」＋方法名稱小寫），不由 batch 名稱推斷。
     */
    @Unique
    private static List<TestFunction> quantumchamberTest$m7Segment(Collection<TestFunction> all,List<TestFunction> platformSelected) {
        String segment=System.getProperty("quantumchamber.m7game.segment","");
        String inventory=System.getProperty("quantumchamber.m7game.inventory","");
        JsonObject segments;
        try {
            segments=JsonParser.parseString(Files.readString(Path.of(inventory))).getAsJsonObject().getAsJsonObject("gametestSegments");
        } catch(Exception error) {
            throw new IllegalStateException("M7_SEGMENT_INVENTORY：無法讀取 gametestSegments："+inventory,error);
        }
        if(segments==null || segments.size()==0) throw new IllegalStateException("M7_SEGMENT_INVENTORY：gametestSegments 缺少或為空："+inventory);
        if(!segments.has(segment)) throw new IllegalStateException("M7_SEGMENT_UNKNOWN："+segment);
        Map<String,String> owner=new HashMap<>();
        for(var entry : segments.entrySet()) {
            JsonElement value=entry.getValue();
            if(!value.isJsonObject() || !value.getAsJsonObject().has("classes") || !value.getAsJsonObject().get("classes").isJsonArray()
                    || value.getAsJsonObject().getAsJsonArray("classes").isEmpty()) throw new IllegalStateException("M7_SEGMENT_FORM："+entry.getKey());
            for(JsonElement name : value.getAsJsonObject().getAsJsonArray("classes")) {
                if(!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) throw new IllegalStateException("M7_SEGMENT_FORM："+entry.getKey());
                if(owner.putIfAbsent(name.getAsString().toLowerCase(Locale.ROOT),entry.getKey())!=null) throw new IllegalStateException("M7_SEGMENT_OVERLAP："+name.getAsString());
            }
        }
        Set<String> m7Classes=all.stream().filter(test -> test.batchId().startsWith("m7_home")).map(test -> quantumchamberTest$testClass(test))
                .collect(Collectors.toCollection(TreeSet::new));
        if(!m7Classes.equals(new TreeSet<>(owner.keySet()))) throw new IllegalStateException("M7_SEGMENT_CLASS_SET：JVM="+m7Classes+" 清單="+new TreeSet<>(owner.keySet()));
        // T5-5 Step 0（拆段審查 M3）：原本這裡另核「本段選到的類別等於清單」（M7_SEGMENT_CLASS_MISMATCH），但選到的類別由同一份 owner 篩出、必然相等，已刪除；
        // 單段的實際防線是上面的 M7_SEGMENT_CLASS_SET 與下面的 M7_SEGMENT_EMPTY，XML 的類別另由 m7-ci-gametest.ps1 核對。
        List<TestFunction> result=platformSelected.stream().filter(test -> segment.equals(owner.get(quantumchamberTest$testClass(test)))).toList();
        if(result.isEmpty()) throw new IllegalStateException("M7_SEGMENT_EMPTY："+segment);
        return result;
    }

    @Unique
    private static String quantumchamberTest$testClass(TestFunction test) {
        int dot=test.templatePath().indexOf('.');
        if(dot<=0) throw new IllegalStateException("M7_SEGMENT_TEST_NAME："+test.templatePath());
        return test.templatePath().substring(0,dot);
    }
}
