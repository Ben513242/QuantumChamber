package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class Minecraft121SettingsCanonicalizerTest {
    private static final Minecraft121SettingsGraph.Key ROOT = key(Minecraft121SettingsGraph.Kind.OPTIONS, "root");
    private static Minecraft121SettingsGraph.Key key(Minecraft121SettingsGraph.Kind kind, String id) {
        return new Minecraft121SettingsGraph.Key(kind, "minecraft:settings", "minecraft:" + id);
    }
    private static Minecraft121SettingsGraph graph() {
        var graph = new Minecraft121SettingsGraph();
        graph.payload(ROOT, NbtInt.of(1));
        return graph;
    }
    private static void rejects(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(Minecraft121SettingsGraph.Failure.class, action).getMessage());
    }
    private static Minecraft121SettingsGraph limited(long nodes, long edges, long states,
            long resources, long raw, long totalRaw, long canonical, int depth) {
        return new Minecraft121SettingsGraph(new Minecraft121SettingsGraph.Limits(
                nodes, edges, states, resources, raw, totalRaw, canonical, depth));
    }
    @Test void requiredTargetMustHavePayloadAndCompletedOccurrence() {
        var g = graph();
        g.reference(ROOT, "required", key(Minecraft121SettingsGraph.Kind.REGISTRY,"target"), "root", "required");
        rejects("GRAPH_UNCLOSED", g::finish);
    }
    @Test void aliasOccurrenceCannotBeSkippedAfterSharedPayload() {
        var g = graph(); var target=key(Minecraft121SettingsGraph.Kind.REGISTRY,"target");
        var first=g.reference(ROOT,"first",target,"alias-a","required");
        g.payload(target,NbtInt.of(2)); g.expanded(first);
        g.reference(ROOT,"second",target,"alias-b","required");
        rejects("GRAPH_UNCLOSED",g::finish);
    }
    @Test void sameIdDifferentKindsAndUseRemainDistinct() {
        var g=graph(); var registry=key(Minecraft121SettingsGraph.Kind.REGISTRY,"same");
        var template=key(Minecraft121SettingsGraph.Kind.TEMPLATE,"same");
        var a=g.reference(ROOT,"a",registry,"root","required");
        var b=g.reference(ROOT,"b",template,"root","single");
        g.payload(registry,NbtInt.of(2)); g.expanded(a);
        rejects("GRAPH_UNCLOSED",g::finish);
    }
    @Test void completedCycleKeepsEdgesWithoutDuplicatingNodes() {
        var g=graph(); var child=key(Minecraft121SettingsGraph.Kind.REGISTRY,"child");
        var a=g.reference(ROOT,"child",child,"root","required"); g.payload(child,NbtInt.of(2));
        var b=g.reference(child,"back",ROOT,"root","required"); g.expanded(a); g.expanded(b);
        assertTrue(g.finish().length>0);
    }
    @Test void listOrderAndNumericTypesRemainSignificant() {
        var a=new NbtList();a.add(NbtString.of("a"));a.add(NbtString.of("b"));
        var b=new NbtList();b.add(NbtString.of("b"));b.add(NbtString.of("a"));
        assertFalse(Arrays.equals(bytes(a),bytes(b)));
        assertFalse(Arrays.equals(bytes(NbtInt.of(1)),bytes(NbtByte.of((byte)1))));
    }
    @Test void compoundOrderIsStableAndOwnedBytesResistMutation() {
        var a=new NbtCompound();a.putInt("b",2);a.putInt("a",1);
        var b=new NbtCompound();b.putInt("a",1);b.putInt("b",2);
        var g=new Minecraft121SettingsGraph();g.payload(ROOT,a);a.putInt("a",99);
        var expected=bytes(b);var actual=g.finish();assertArrayEquals(expected,actual);
        actual[0]^=1;assertArrayEquals(expected,g.finish());
    }
    @Test void changedPayloadPoisonsGraph() {
        var g=graph();rejects("DATA_NODE_CHANGED",()->g.payload(ROOT,NbtInt.of(2)));
        rejects("GRAPH_FAILED",g::finish);
    }
    @Test void nodeBudgetRejectsBeforeAddingNewNode() {
        var g=limited(1,10,10,10,100,1000,1000,10);g.payload(ROOT,NbtInt.of(1));
        rejects("BUDGET_NODES",()->g.payload(key(Minecraft121SettingsGraph.Kind.REGISTRY,"two"),NbtInt.of(2)));
    }
    @Test void edgeBudgetCountsRepeatedOccurrences() {
        var g=limited(10,1,10,10,100,1000,1000,10);g.payload(ROOT,NbtInt.of(1));
        g.reference(ROOT,"same",ROOT,"root","required");
        rejects("BUDGET_EDGES",()->g.reference(ROOT,"same",ROOT,"root","required"));
    }
    @Test void stateBudgetIncludesAliasAndUse() {
        var g=limited(10,10,1,10,100,1000,1000,10);g.payload(ROOT,NbtInt.of(1));
        g.reference(ROOT,"one",ROOT,"root","single");
        rejects("BUDGET_STATES",()->g.reference(ROOT,"two",ROOT,"root","unknown"));
    }
    @Test void resourceAndDecodeBudgetsRejectBoundaryPlusOne() {
        var g=limited(10,10,10,1,4,5,1000,10);
        g.resource(ROOT);g.resource(ROOT);
        rejects("BUDGET_RESOURCES",()->g.resource(key(Minecraft121SettingsGraph.Kind.TEMPLATE,"two")));
        var h=limited(10,10,10,10,4,5,1000,10);h.decoded(4);h.decoded(1);
        rejects("BUDGET_TOTAL_RAW",()->h.decoded(1));
        rejects("BUDGET_RAW",()->limited(10,10,10,10,4,5,1000,10).decoded(5));
    }
    @Test void canonicalAndDepthBudgetsRejectBeforeUnboundedCopy() {
        var g=limited(10,10,10,10,100,1000,4,10);
        rejects("BUDGET_CANONICAL",()->g.payload(ROOT,NbtInt.of(1)));
        var n=new NbtCompound();var inner=new NbtCompound();inner.putInt("value",1);n.put("inner",inner);
        rejects("BUDGET_DEPTH",()->limited(10,10,10,10,100,1000,1000,1).payload(ROOT,n));
    }
    @Test void unknownNbtAndInvalidIdentifiersFailClosed() {
        rejects("UNSUPPORTED_NBT_TYPE",()->graph().payload(ROOT,NbtEnd.INSTANCE));
        assertThrows(IllegalArgumentException.class,()->new Minecraft121SettingsGraph.Key(
                Minecraft121SettingsGraph.Kind.REGISTRY,"minecraft:settings","implicit"));
    }
    @Test void everyEdgeOccurrenceMustCompleteEvenForSameTargetScopeAndUse() {
        for (String secondPath : new String[]{"first", "second"}) {
            var g=graph(); var target=key(Minecraft121SettingsGraph.Kind.REGISTRY,"shared");
            var first=g.reference(ROOT,"first",target,"root","required");
            g.reference(ROOT,secondPath,target,"root","required");
            g.payload(target,NbtInt.of(2));g.expanded(first);
            rejects("GRAPH_UNCLOSED",g::finish);
        }
    }
    @Test void nativeIdentifierMustBeCanonicalAndExplicit() {
        for (String id : new String[]{"stone", ":stone", "Minecraft:stone", "minecraft:a b"}) {
            assertThrows(IllegalArgumentException.class,()->new Minecraft121SettingsGraph.Key(
                    Minecraft121SettingsGraph.Kind.REGISTRY,"minecraft:settings",id), id);
        }
        for (String id : new String[]{"minecraft:plains", "minecraft:"}) {
            var accepted = new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,
                    "minecraft:worldgen/biome", id);
            assertEquals(net.minecraft.util.Identifier.of(id).toString(),accepted.id());
        }
    }
    @Test void oversizedEdgeLabelsAreRejectedBeforeRetentionOrSorting() {
        String huge="x".repeat(4096);
        for (int field=0;field<3;field++) {
            var g=limited(10,10,10,10,100,1000,128,10);g.payload(ROOT,NbtInt.of(1));
            String path=field==0?huge:"path", scope=field==1?huge:"scope", use=field==2?huge:"use";
            rejects("BUDGET_CANONICAL",()->g.reference(ROOT,path,ROOT,scope,use));
            rejects("GRAPH_FAILED",g::finish);
        }
        var nbt=new NbtCompound();nbt.putInt(huge,1);nbt.putInt("a",2);
        rejects("BUDGET_CANONICAL",()->limited(10,10,10,10,100,1000,128,10).payload(ROOT,nbt));
    }
    @Test void rejectedInputCannotLeaveAnOlderGraphPublishable() {
        String invalid=String.valueOf((char)0xd800);
        var payloadGraph=graph();
        assertThrows(IllegalArgumentException.class,()->payloadGraph.payload(ROOT,NbtString.of(invalid)));
        rejects("GRAPH_FAILED",payloadGraph::finish);
        for (int field=0;field<3;field++) {
            var g=graph();String path=field==0?invalid:"path",scope=field==1?invalid:"scope",use=field==2?invalid:"use";
            assertThrows(IllegalArgumentException.class,()->g.reference(ROOT,path,ROOT,scope,use));
            rejects("GRAPH_FAILED",g::finish);
        }
        var g=graph();assertThrows(NullPointerException.class,()->g.payload(ROOT,null));
        rejects("GRAPH_FAILED",g::finish);
    }
    @Test void numericAndArrayEncodingHasFixedBigEndianBytes() {
        var hex=java.util.HexFormat.of();
        assertTrue(hex.formatHex(bytes(NbtInt.of(0x01020304))).endsWith("030102030400000000"));
        assertTrue(hex.formatHex(bytes(new NbtIntArray(new int[]{0x01020304,-1})))
                .endsWith("0b0000000201020304ffffffff00000000"));
        assertTrue(hex.formatHex(bytes(new NbtLongArray(new long[]{0x0102030405060708L})))
                .endsWith("0c00000001010203040506070800000000"));
        assertTrue(hex.formatHex(bytes(new NbtByteArray(new byte[]{1,-1})))
                .endsWith("070000000201ff00000000"));
        assertTrue(hex.formatHex(bytes(NbtFloat.of(Float.intBitsToFloat(0x7fc00001))))
                .endsWith("057fc0000100000000"));
        assertTrue(hex.formatHex(bytes(NbtDouble.of(Double.longBitsToDouble(0x7ff8000000000001L))))
                .endsWith("067ff800000000000100000000"));
    }
    @Test void nonAsciiCompoundKeysUseUtf8OrderAndEdgeInsertionOrderIsIrrelevant() {
        var nbt=new NbtCompound();nbt.putInt("😀",2);nbt.putInt("\ue000",1);
        assertTrue(java.util.HexFormat.of().formatHex(bytes(nbt)).endsWith(
                "0a0000000200000003ee8080030000000100000004f09f9880030000000200000000"));
        var first=graph();var a=first.reference(ROOT,"a",ROOT,"root","required");
        var b=first.reference(ROOT,"b",ROOT,"root","required");first.expanded(a);first.expanded(b);
        var second=graph();var d=second.reference(ROOT,"b",ROOT,"root","required");
        var c=second.reference(ROOT,"a",ROOT,"root","required");second.expanded(c);second.expanded(d);
        assertArrayEquals(first.finish(),second.finish());
    }
    private static byte[] bytes(NbtElement value) {
        var g=new Minecraft121SettingsGraph();g.payload(ROOT,value);return g.finish();
    }

    @org.junit.jupiter.api.BeforeAll static void bootstrapRegistryFixture() {
        net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize();
    }
    private static net.minecraft.registry.RegistryKey<net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters> noiseKey(String id) {
        return net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.NOISE_PARAMETERS, net.minecraft.util.Identifier.of("minecraft",id));
    }
    private static net.minecraft.registry.SimpleRegistry<net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters> noiseRegistry() {
        var r=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.NOISE_PARAMETERS,com.mojang.serialization.Lifecycle.stable());
        r.add(noiseKey("a"),new net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters(-3,1.0),net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);
        r.add(noiseKey("b"),new net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters(-2,0.5),net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);
        r.freeze(); return r;
    }
    private static Minecraft121RegistryClosure closure(Minecraft121SettingsGraph g, net.minecraft.registry.Registry<?> r) {
        return new Minecraft121RegistryClosure(g,new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(r)),new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of()));
    }
    @Test void typedRegistryEntryCapturesNativeValueAndEveryOccurrence() {
        var r=noiseRegistry(); var g=graph();var c=closure(g,r);
        c.entry(ROOT,"entry",r.getEntry(noiseKey("a")).orElseThrow());c.reference(ROOT,"ordinary",noiseKey("a"));c.drain();
        var actual=g.finish();
        var one=graph();var d=closure(one,r);d.reference(ROOT,"ordinary",noiseKey("a"));d.drain();
        assertFalse(Arrays.equals(actual,one.finish()),"兩個真引用不可被去重");
        var changed=graph();var e=closure(changed,r);e.reference(ROOT,"ordinary",noiseKey("b"));e.drain();
        assertFalse(Arrays.equals(one.finish(),changed.finish()),"原生定義值必須進圖");
    }
    @Test void missingRegistryTargetAndForeignEntryPoisonGraph() {
        var r=noiseRegistry();var g=graph();var c=closure(g,r);c.reference(ROOT,"missing",noiseKey("missing"));
        rejects("REQUIRED_REGISTRY_TARGET_MISSING",c::drain);rejects("GRAPH_FAILED",g::finish);
        var h=graph();var d=closure(h,r);var foreign=noiseRegistry().getEntry(noiseKey("a")).orElseThrow();
        rejects("REGISTRY_ENTRY_OWNER_MISMATCH",()->d.entry(ROOT,"foreign",foreign));rejects("GRAPH_FAILED",h::finish);
    }
    @Test void requiredAbsentTagRejectsWhileOptionalAbsenceIsExplicit() {
        var r=noiseRegistry();var key=net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.NOISE_PARAMETERS,net.minecraft.util.Identifier.of("minecraft","none"));
        var g=graph();var c=closure(g,r);c.tag(ROOT,"required",key,true);
        rejects("REQUIRED_TAG_MISSING",c::drain);rejects("GRAPH_FAILED",g::finish);
        var h=graph();var d=closure(h,r);d.tag(ROOT,"optional",key,false);d.drain();assertFalse(Arrays.equals(graph().finish(),h.finish()));
    }
    @Test void unknownNativeRegistryDefinitionFailsClosed() {
        var r=new net.minecraft.registry.SimpleRegistry<net.minecraft.nbt.NbtCompound>(net.minecraft.registry.RegistryKey.ofRegistry(net.minecraft.util.Identifier.of("test","opaque")),com.mojang.serialization.Lifecycle.stable());
        var k=net.minecraft.registry.RegistryKey.of(r.getKey(),net.minecraft.util.Identifier.of("minecraft","a"));r.add(k,new NbtCompound(),net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);r.freeze();
        var g=graph();var c=closure(g,r);c.reference(ROOT,"unknown",k);rejects("UNSUPPORTED_REGISTRY_DEFINITION",c::drain);rejects("GRAPH_FAILED",g::finish);
    }

    private static net.minecraft.registry.SimpleRegistry<net.minecraft.structure.pool.StructurePool> poolRegistry() {
        var r=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL,com.mojang.serialization.Lifecycle.stable());
        var a=net.minecraft.registry.RegistryKey.of(r.getKey(),net.minecraft.util.Identifier.of("minecraft","a"));
        var b=net.minecraft.registry.RegistryKey.of(r.getKey(),net.minecraft.util.Identifier.of("minecraft","b"));
        var lookup=r.createMutableEntryLookup();var ar=lookup.getOrThrow(a);var br=lookup.getOrThrow(b);
        r.add(a,new net.minecraft.structure.pool.StructurePool(br,java.util.List.of()),net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);
        r.add(b,new net.minecraft.structure.pool.StructurePool(ar,java.util.List.of()),net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);r.freeze();return r;
    }
    @Test void nativeEmptyPoolFallbackCycleIsClosedAndTypedIdsStayDistinct() {
        var r=poolRegistry();var n=noiseRegistry();var g=graph();
        var c=new Minecraft121RegistryClosure(g,new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(r,n)),new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of()));
        c.reference(ROOT,"pool",net.minecraft.registry.RegistryKey.of(r.getKey(),net.minecraft.util.Identifier.of("minecraft","a")));
        c.reference(ROOT,"noise",noiseKey("a"));c.drain();
        var expected=graph();
        var pa=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,"minecraft:worldgen/template_pool","minecraft:a");
        var pb=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,"minecraft:worldgen/template_pool","minecraft:b");
        var na=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,"minecraft:worldgen/noise","minecraft:a");
        var manager=new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(r,n));
        var ops=manager.getOps(NbtOps.INSTANCE);
        expected.payload(pa,net.minecraft.structure.pool.StructurePool.CODEC.encodeStart(ops,r.get(net.minecraft.util.Identifier.ofVanilla("a"))).getOrThrow());
        expected.payload(pb,net.minecraft.structure.pool.StructurePool.CODEC.encodeStart(ops,r.get(net.minecraft.util.Identifier.ofVanilla("b"))).getOrThrow());
        expected.payload(na,net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters.CODEC.encodeStart(ops,n.get(noiseKey("a"))).getOrThrow());
        expected.expanded(expected.reference(ROOT,"pool",pa,"ordinary","required"));
        expected.expanded(expected.reference(ROOT,"noise",na,"ordinary","required"));
        expected.expanded(expected.reference(pa,"fallback",pb,"ordinary","pool-fallback"));
        expected.expanded(expected.reference(pb,"fallback",pa,"ordinary","pool-fallback"));
        assertArrayEquals(expected.finish(),g.finish(),"兩個typed registry與兩條循環邊皆須完整");
    }
    private static net.minecraft.structure.pool.alias.StructurePoolAliasBinding directAlias(String source,String target) {
        return net.minecraft.structure.pool.alias.StructurePoolAliasBinding.direct(
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL,net.minecraft.util.Identifier.of("minecraft",source)),
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL,net.minecraft.util.Identifier.of("minecraft",target)));
    }
    @Test void aliasSourceIsSymbolicOnlyForItsOwnOccurrence() {
        var r=poolRegistry();var g=graph();var c=closure(g,r);
        net.minecraft.structure.pool.alias.StructurePoolAliasBinding binding=directAlias("missing","a");
        c.alias(ROOT,"mapping",binding);c.drain();assertTrue(g.finish().length>graph().finish().length);
        var h=graph();var d=closure(h,r);d.alias(ROOT,"mapping",binding);
        d.reference(ROOT,"ordinary",net.minecraft.registry.RegistryKey.of(r.getKey(),net.minecraft.util.Identifier.of("minecraft","missing")));
        rejects("REQUIRED_REGISTRY_TARGET_MISSING",d::drain);rejects("GRAPH_FAILED",h::finish);
    }
    @Test void aliasTargetsRemainRequiredIncludingSourceIdUsedAsTarget() {
        var g=graph();var c=closure(g,poolRegistry());
        c.alias(ROOT,"mapping",directAlias("missing","missing"));
        rejects("REQUIRED_REGISTRY_TARGET_MISSING",c::drain);
    }
    @Test void tagOrderAndPresentEmptyDifferFromAbsent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var key=net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.NOISE_PARAMETERS,net.minecraft.util.Identifier.of("minecraft","a"));
        var tagFile=temp.resolve("data/minecraft/"+net.minecraft.registry.RegistryKeys.getTagPath(key.registry())+"/a.json");
        java.nio.file.Files.createDirectories(tagFile.getParent());java.nio.file.Files.writeString(tagFile,"{\"values\":[]}");
        var info=new net.minecraft.resource.ResourcePackInfo("fixture",net.minecraft.text.Text.literal("fixture"),net.minecraft.resource.ResourcePackSource.NONE,java.util.Optional.empty());
        try(var rm=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of(new net.minecraft.resource.DirectoryResourcePack(info,temp)))) {
            byte[][] values=new byte[3][];
            for(int mode=0;mode<3;mode++) {
                var r=noiseRegistry();var a=r.getEntry(noiseKey("a")).orElseThrow();var b=r.getEntry(noiseKey("b")).orElseThrow();
                java.util.List<net.minecraft.registry.entry.RegistryEntry<net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters>> members=mode==0?java.util.List.of(a,b):mode==1?java.util.List.of(b,a):java.util.List.of();
                java.nio.file.Files.writeString(tagFile,mode==0?"{\"values\":[\"minecraft:a\",\"minecraft:b\"]}":mode==1?"{\"values\":[\"minecraft:b\",\"minecraft:a\"]}":"{\"values\":[]}");
                var loader=new net.minecraft.registry.tag.TagGroupLoader<net.minecraft.registry.entry.RegistryEntry<net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters>>(
                        id->r.getEntry(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.NOISE_PARAMETERS,id)).map(x->x),net.minecraft.registry.RegistryKeys.getTagPath(key.registry()));
                var loaded=loader.load(rm);assertEquals(members,loaded.get(key.id()));
                r.populateTags(java.util.Map.of(key,loaded.get(key.id())));var g=graph();var c=new Minecraft121RegistryClosure(g,new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(r)),rm);
                c.tag(ROOT,"tag",key,false);c.reference(ROOT,"ordinary",noiseKey("a"));c.drain();values[mode]=g.finish();
            }
            assertFalse(Arrays.equals(values[0],values[1]),"原生成員次序不得排序");
            var h=graph();var d=closure(h,noiseRegistry());d.tag(ROOT,"tag",key,false);d.reference(ROOT,"ordinary",noiseKey("a"));d.drain();
            assertFalse(Arrays.equals(values[2],h.finish()),"存在空tag與來源缺失必須可區分");
        }
    }
    @Test void closureQueueUsesGraphBudgetAndCannotRecoverAfterFailure() {
        var g=limited(10,1,10,10,100,1000,2000,10);g.payload(ROOT,NbtInt.of(1));var c=closure(g,noiseRegistry());
        c.reference(ROOT,"one",noiseKey("a"));rejects("BUDGET_EDGES",()->c.reference(ROOT,"two",noiseKey("a")));
        rejects("GRAPH_FAILED",c::drain);rejects("GRAPH_FAILED",g::finish);
    }

    @Test void sameTypedIdChangedNativeValueChangesOwnedBytes() {
        var r=noiseRegistry();var g=graph();var c=closure(g,r);c.reference(ROOT,"value",noiseKey("a"));c.drain();var before=g.finish();
        r.get(noiseKey("a")).amplitudes().set(0,0.25);
        assertArrayEquals(before,g.finish(),"完成圖不得持有可變原生值");
        var h=graph();var d=closure(h,r);d.reference(ROOT,"value",noiseKey("a"));d.drain();
        assertFalse(Arrays.equals(before,h.finish()),"同id的新原生內容不得沿用舊payload");
    }
}
