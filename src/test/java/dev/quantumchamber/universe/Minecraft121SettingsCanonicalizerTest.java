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
                var loaded=java.util.List.copyOf(loader.load(rm).get(key.id()));assertEquals(members,loaded);
                r.populateTags(java.util.Map.of(key,loaded));var g=graph();var c=new Minecraft121RegistryClosure(g,new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(r)),rm);
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

    @Test void endNativeBridgesHitRealOwnersAndOwnSealBytes() throws Exception {
        var end=net.minecraft.world.gen.densityfunction.DensityFunctionTypes.endIslands(0L);
        var access=assertInstanceOf(Minecraft121NativeValueContracts.EndStateAccess.class,end);
        var state=access.quantumchamber$captureEndState();assertEquals(2076,state.bytes().length);
        var sampler=nativeField(end,"sampler").get(end);var expected=java.nio.ByteBuffer.allocate(2076);
        for(String name:java.util.List.of("originX","originY","originZ"))expected.putLong(Double.doubleToRawLongBits(nativeField(sampler,name).getDouble(sampler)));
        var permutation=(int[])nativeField(sampler,"permutation").get(sampler);expected.putInt(512);for(int element:permutation)expected.putInt(element);
        assertArrayEquals(expected.array(),state.bytes(),"三origin與全部512個int須逐byte一致");
        assertTrue(state.sameIdentity(access.quantumchamber$captureEndState()));
        var copy=state.bytes();copy[0]^=1;assertArrayEquals(access.quantumchamber$captureEndState().bytes(),state.bytes());
        var rebuilt=(Minecraft121NativeValueContracts.EndStateAccess)net.minecraft.world.gen.densityfunction.DensityFunctionTypes.endIslands(0L);
        var other=rebuilt.quantumchamber$captureEndState();assertTrue(state.independentIdentity(other));assertArrayEquals(state.bytes(),other.bytes());
        var binary=net.minecraft.world.gen.densityfunction.DensityFunctionTypes.add(end,net.minecraft.world.gen.densityfunction.DensityFunctionTypes.cache2d(end));
        var binaryAccess=assertInstanceOf(dev.quantumchamber.mixin.DensityBinarySettingsAccess.class,binary);
        assertSame(end,binaryAccess.quantumchamber$argument1());
    }
    @Test void nativeSealCopiesBothDirectionsAndUsesIdentityNotEquals() {
        var first=new String("same");var equal=new String("same");byte[] input={1,2,3};
        var seal=Minecraft121NativeValueContracts.SimplexStateSeal.capture(first,input);input[0]=99;
        var output=seal.bytes();output[1]=99;assertArrayEquals(new byte[]{1,2,3},seal.bytes());
        assertTrue(seal.sameIdentity(Minecraft121NativeValueContracts.SimplexStateSeal.capture(first,input)));
        assertTrue(seal.independentIdentity(Minecraft121NativeValueContracts.SimplexStateSeal.capture(equal,input)));
    }

    private static final net.minecraft.registry.RegistryKey<net.minecraft.world.gen.chunk.ChunkGeneratorSettings> END_SETTINGS=
            net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.CHUNK_GENERATOR_SETTINGS,net.minecraft.util.Identifier.ofVanilla("end"));
    private static final net.minecraft.registry.RegistryKey<net.minecraft.world.gen.densityfunction.DensityFunction> END_SLOPED=
            net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION,net.minecraft.util.Identifier.ofVanilla("end/sloped_cheese"));
    private record EndFixture(net.minecraft.resource.LifecycledResourceManagerImpl resources,net.minecraft.registry.DynamicRegistryManager.Immutable registries) implements AutoCloseable {
        static EndFixture load() {
            var rm=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,
                    java.util.List.of(net.minecraft.resource.VanillaDataPackProvider.createDefaultPack()));
            try {
                var combined=net.minecraft.registry.ServerDynamicRegistryType.createCombinedDynamicRegistries();
                var worldgen=net.minecraft.registry.RegistryLoader.loadFromResource(rm,
                        combined.getPrecedingRegistryManagers(net.minecraft.registry.ServerDynamicRegistryType.WORLDGEN),
                        net.minecraft.registry.RegistryLoader.DYNAMIC_REGISTRIES);
                return new EndFixture(rm,worldgen);
            } catch(RuntimeException|Error failure) { rm.close();throw failure; }
        }
        Object settings() { return registries.get(net.minecraft.registry.RegistryKeys.CHUNK_GENERATOR_SETTINGS).get(END_SETTINGS); }
        Object sloped() { return registries.get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION).get(END_SLOPED); }
        public void close() { resources.close(); }
    }
    @Test void fixedNativeEndTwoRoutesCaptureOwnedSimplexState() {
        try(var f=EndFixture.load()) {
            var g=graph();var nativeValues=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());
            var captured=(NbtCompound)nativeValues.capture(END_SETTINGS,f.settings());
            var states=captured.getList("EndStates",NbtElement.BYTE_ARRAY_TYPE);assertEquals(2,states.size());
            for(var state:states)assertEquals(2076,((NbtByteArray)state).getByteArray().length);
            assertArrayEquals(((NbtByteArray)states.get(0)).getByteArray(),((NbtByteArray)states.get(1)).getByteArray());
            var dependent=(NbtCompound)nativeValues.capture(END_SLOPED,f.sloped());
            assertEquals(2076,dependent.getByteArray("EndState").length);
        }
    }

    private static net.minecraft.world.gen.densityfunction.DensityFunction endEndpoint(EndFixture f) {
        var settings=(net.minecraft.world.gen.chunk.ChunkGeneratorSettings)f.settings();
        var wrapping=(net.minecraft.world.gen.densityfunction.DensityFunctionTypes.Wrapper)
                ((net.minecraft.world.gen.densityfunction.DensityFunctionTypes.RegistryEntryHolder)settings.noiseRouter().erosion()).function().value();
        return ((net.minecraft.world.gen.densityfunction.DensityFunctionTypes.RegistryEntryHolder)wrapping.wrapped()).function().value();
    }
    private static java.lang.reflect.Field nativeField(Object owner,String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static Minecraft121SettingsGraph.Key nativeNode(net.minecraft.registry.RegistryKey<?> key) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,key.getRegistry().toString(),key.getValue().toString());
    }
    @Test void endLocalCaptureRetainsFourTypedRequiredEdgesButCannotFinishWholeGraph() {
        try(var f=EndFixture.load()) {
            var g=graph();var edges=new java.util.ArrayList<String>();
            var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources(),(occurrence,key)->edges.add(key.getValue().toString()));
            g.payload(nativeNode(END_SETTINGS),values.capture(END_SETTINGS,f.settings()));
            g.payload(nativeNode(END_SLOPED),values.capture(END_SLOPED,f.sloped()));
            assertEquals(java.util.List.of("minecraft:end/sloped_cheese","minecraft:end/sloped_cheese","minecraft:end/base_3d_noise","minecraft:end/base_3d_noise"),edges);
            rejects("GRAPH_UNCLOSED",g::finish);
            var h=graph();var closure=new Minecraft121RegistryClosure(h,f.registries(),f.resources());
            closure.reference(ROOT,"end",END_SETTINGS);rejects("UNSUPPORTED_REGISTRY_DEFINITION",closure::drain);
            rejects("GRAPH_FAILED",h::finish);
        }
    }
    @Test void endRejectsForeignOwnerAndUnscopedNativeValues() {
        try(var f=EndFixture.load();var foreign=EndFixture.load()) {
            var g=graph();var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());
            rejects("NATIVE_DEFINITION_OWNER",()->values.capture(END_SETTINGS,foreign.settings()));rejects("GRAPH_FAILED",g::finish);
            for(Object value:java.util.List.of(net.minecraft.world.gen.densityfunction.DensityFunctionTypes.endIslands(0L),
                    new net.minecraft.util.math.noise.SimplexNoiseSampler(new net.minecraft.util.math.random.CheckedRandom(0L)),new Object())) {
                var h=graph();var isolated=new Minecraft121NativeValueContracts(h,f.registries(),f.resources());
                rejects("NATIVE_DEFINITION_OWNER",()->isolated.capture(END_SETTINGS,value));rejects("GRAPH_FAILED",h::finish);
            }
            var unknown=net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION,net.minecraft.util.Identifier.ofVanilla("end/base_3d_noise"));
            rejects("UNSUPPORTED_NATIVE_VALUE",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(unknown,f.sloped()));
        }
    }
    @Test void endStateDriftAtLastPermutationIndexIsRejectedAndOriginalArrayIsNotPublished() throws Exception {
        try(var f=EndFixture.load()) {
            var endpoint=endEndpoint(f);var sampler=nativeField(endpoint,"sampler").get(endpoint);
            var array=(int[])nativeField(sampler,"permutation").get(sampler);int old=array[511];
            try {
                array[511]^=1;var g=graph();var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());
                rejects("NATIVE_END_SIMPLEX_STATE_MISMATCH",()->values.capture(END_SETTINGS,f.settings()));rejects("GRAPH_FAILED",g::finish);
            } finally { array[511]=old; }
        }
    }
    @Test void endOriginRawBitsAndPermutationShapeAreValidated() throws Exception {
        try(var f=EndFixture.load()) {
            var endpoint=endEndpoint(f);var sampler=nativeField(endpoint,"sampler").get(endpoint);var origin=nativeField(sampler,"originX");double old=origin.getDouble(sampler);
            try {
                origin.setDouble(sampler,Double.longBitsToDouble(Double.doubleToRawLongBits(old)^1));
                rejects("NATIVE_END_SIMPLEX_STATE_MISMATCH",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_SETTINGS,f.settings()));
            } finally { origin.setDouble(sampler,old); }
            var permutation=nativeField(sampler,"permutation");Object original=permutation.get(sampler);
            try { permutation.set(sampler,new int[511]);rejects("NATIVE_END_SIMPLEX_SHAPE",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_SETTINGS,f.settings())); }
            finally { permutation.set(sampler,original); }
        }
    }
    @Test void slopedCaptureRechecksOriginalSamplerAndArrayIdentity() throws Exception {
        try(var f=EndFixture.load()) {
            var endpoint=endEndpoint(f);var samplerField=nativeField(endpoint,"sampler");var sampler=samplerField.get(endpoint);
            var arrayField=nativeField(sampler,"permutation");int[] original=(int[])arrayField.get(sampler);
            var values=new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources());values.capture(END_SETTINGS,f.settings());
            try { arrayField.set(sampler,original.clone());rejects("NATIVE_END_ORIGINAL_IDENTITY_CHANGED",()->values.capture(END_SLOPED,f.sloped())); }
            finally { arrayField.set(sampler,original); }
            var second=new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources());second.capture(END_SETTINGS,f.settings());
            var rebuilt=net.minecraft.world.gen.densityfunction.DensityFunctionTypes.endIslands(0L);
            try { samplerField.set(endpoint,nativeField(rebuilt,"sampler").get(rebuilt));rejects("NATIVE_END_ORIGINAL_IDENTITY_CHANGED",()->second.capture(END_SLOPED,f.sloped())); }
            finally { samplerField.set(endpoint,sampler); }
        }
    }
    @Test void fixedEndCodecIdentityDriftIsRejected() throws Exception {
        try(var f=EndFixture.load()) {
            var entry=net.minecraft.registry.Registries.DENSITY_FUNCTION_TYPE.getEntry(net.minecraft.util.Identifier.ofVanilla("end_islands")).orElseThrow();
            var value=nativeField(entry,"value");Object before=value.get(entry);
            try {
                value.set(entry,net.minecraft.registry.Registries.DENSITY_FUNCTION_TYPE.get(net.minecraft.util.Identifier.ofVanilla("add")));
                rejects("NATIVE_END_CODEC_OWNER",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_SETTINGS,f.settings()));
            } finally { value.set(entry,before); }
        }
    }
    @Test void repeatedEndCaptureChargesStateAndSourceForEveryOccurrence() {
        try(var f=EndFixture.load()) {
            var g=limited(100,100,100,100,8388608,20000,268435456,128);g.payload(ROOT,NbtInt.of(1));
            var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());values.capture(END_SETTINGS,f.settings());
            rejects("BUDGET_TOTAL_RAW",()->values.capture(END_SETTINGS,f.settings()));rejects("GRAPH_FAILED",g::finish);
        }
    }
    @Test void endSourceWinnerCannotBeReplacedByAnIdenticalNamedOverlay(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        try(var f=EndFixture.load()) {
            var id=net.minecraft.util.Identifier.ofVanilla("worldgen/noise_settings/end.json");byte[] raw;
            try(var in=f.resources().getResource(id).orElseThrow().getInputStream()) { raw=in.readAllBytes(); }
            var path=temp.resolve("data/minecraft/worldgen/noise_settings/end.json");java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.write(path,raw);
            var info=new net.minecraft.resource.ResourcePackInfo("vanilla",net.minecraft.text.Text.literal("vanilla"),net.minecraft.resource.ResourcePackSource.NONE,java.util.Optional.empty());
            try(var overlay=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of(new net.minecraft.resource.DirectoryResourcePack(info,temp)))) {
                rejects("UNSUPPORTED_END_SOURCE_WINNER",()->new Minecraft121NativeValueContracts(graph(),f.registries(),overlay).capture(END_SETTINGS,f.settings()));
            }
        }
    }

    @Test void slopedDirectAndReferenceShapesCannotBeSwapped() throws Exception {
        try(var f=EndFixture.load()) {
            var registry=f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION);
            var entry=registry.getEntry(END_SLOPED).orElseThrow();Object original=entry.value();var field=nativeField(entry,"value");
            var binary=(dev.quantumchamber.mixin.DensityBinarySettingsAccess)original;
            for(int mode=0;mode<2;mode++) {
                var values=new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources());values.capture(END_SETTINGS,f.settings());
                var bad=mode==0?net.minecraft.world.gen.densityfunction.DensityFunctionTypes.add(binary.quantumchamber$argument2(),binary.quantumchamber$argument2()):
                        net.minecraft.world.gen.densityfunction.DensityFunctionTypes.add(binary.quantumchamber$argument1(),binary.quantumchamber$argument1());
                try { field.set(entry,bad);String expected=mode==0?"END_OWNER_PATH_UNRESOLVED":"END_DEPENDENCY_ARGUMENT2_SHAPE";
                    rejects(expected,()->values.capture(END_SLOPED,bad)); }
                finally { field.set(entry,original); }
            }
        }
    }
    @Test @SuppressWarnings("unchecked") void slopedOrdinaryBaseReferenceRemainsRequired() throws Exception {
        try(var f=EndFixture.load()) {
            var registry=f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION);
            var base=net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION,net.minecraft.util.Identifier.ofVanilla("end/base_3d_noise"));
            var entries=(java.util.Map<Object,Object>)nativeField(registry,"keyToEntry").get(registry);
            var g=graph();var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());values.capture(END_SETTINGS,f.settings());
            Object removed=entries.remove(base);assertNotNull(removed);
            try { rejects("REQUIRED_REGISTRY_TARGET_MISSING",()->values.capture(END_SLOPED,f.sloped()));rejects("GRAPH_FAILED",g::finish); }
            finally { entries.put(base,removed); }
        }
    }
    @Test void terminalSourceReadCannotHideAnArrayIdentityChange() throws Exception {
        try(var f=EndFixture.load()) {
            var endpoint=endEndpoint(f);var sampler=nativeField(endpoint,"sampler").get(endpoint);
            var field=nativeField(sampler,"permutation");int[] original=(int[])field.get(sampler);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var resources=(net.minecraft.resource.ResourceManager)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{net.minecraft.resource.ResourceManager.class},(proxy,method,args)->{
                        if(method.getName().equals("getResource") && calls.incrementAndGet()==2)field.set(sampler,original.clone());
                        return method.invoke(f.resources(),args);
                    });
            var g=graph();var values=new Minecraft121NativeValueContracts(g,f.registries(),resources);
            try { rejects("NATIVE_END_ORIGINAL_IDENTITY_CHANGED",()->values.capture(END_SETTINGS,f.settings()));rejects("GRAPH_FAILED",g::finish); }
            finally { field.set(sampler,original); }
        }
    }

    @Test void exactEndSourceBytesAreCheckedEvenWithNativePackIdentity() throws Exception {
        try(var f=EndFixture.load()) {
            var resources=(net.minecraft.resource.ResourceManager)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{net.minecraft.resource.ResourceManager.class},(proxy,method,args)->{
                        if(method.getName().equals("getResource")) {
                            var actual=f.resources().getResource((net.minecraft.util.Identifier)args[0]).orElseThrow();
                            byte[] bytes;try(var in=actual.getInputStream()) { bytes=in.readAllBytes(); }bytes[0]^=1;
                            return java.util.Optional.of(new net.minecraft.resource.Resource(actual.getPack(),()->new java.io.ByteArrayInputStream(bytes)));
                        }
                        return method.invoke(f.resources(),args);
                    });
            rejects("UNSUPPORTED_END_SOURCE",()->new Minecraft121NativeValueContracts(graph(),f.registries(),resources).capture(END_SETTINGS,f.settings()));
        }
    }

    @Test void slopedTerminalReadMustRecheckTheRegistryRootIdentity() throws Exception {
        try(var f=EndFixture.load()) {
            var entry=f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION).getEntry(END_SLOPED).orElseThrow();
            Object original=entry.value();var field=nativeField(entry,"value");var args=(dev.quantumchamber.mixin.DensityBinarySettingsAccess)original;
            var replacement=net.minecraft.world.gen.densityfunction.DensityFunctionTypes.add(args.quantumchamber$argument1(),args.quantumchamber$argument2());
            assertNotSame(original,replacement);var calls=new java.util.concurrent.atomic.AtomicInteger();
            var resources=(net.minecraft.resource.ResourceManager)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{net.minecraft.resource.ResourceManager.class},(proxy,method,arguments)->{
                        if(method.getName().equals("getResource") && calls.incrementAndGet()==4)field.set(entry,replacement);
                        return method.invoke(f.resources(),arguments);
                    });
            var g=graph();var values=new Minecraft121NativeValueContracts(g,f.registries(),resources);values.capture(END_SETTINGS,f.settings());
            try { rejects("NATIVE_END_ORIGINAL_IDENTITY_CHANGED",()->values.capture(END_SLOPED,original));rejects("GRAPH_FAILED",g::finish); }
            finally { field.set(entry,original); }
        }
    }

    @Test void repeatedEndNativeStateAlsoConsumesCanonicalBudget() {
        try(var f=EndFixture.load()) {
            var g=limited(100,100,100,100,8388608,134217728,8000,128);g.payload(ROOT,NbtInt.of(1));
            var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());
            values.capture(END_SETTINGS,f.settings());
            rejects("BUDGET_CANONICAL",()->values.capture(END_SETTINGS,f.settings()));rejects("GRAPH_FAILED",g::finish);
        }
    }

    @Test void nativeStateBudgetIsSeparateAndLeavesCanonicalOutputUnchanged() {
        byte[] original=graph().finish();long cap=original.length+2076L;
        var g=limited(10,10,10,10,8388608,134217728,cap,128);g.payload(ROOT,NbtInt.of(1));g.nativeState(2076);
        assertArrayEquals(original,g.finish());
        var h=limited(10,10,10,10,8388608,134217728,cap,128);h.payload(ROOT,NbtInt.of(1));h.nativeState(2076);
        rejects("BUDGET_CANONICAL",()->h.nativeState(1));rejects("GRAPH_FAILED",h::finish);
    }
    @Test void laterPayloadAndEdgesRespectAlreadyChargedNativeState() {
        long cap=graph().finish().length+2076L;
        for(boolean payload:java.util.List.of(true,false)) {
            var g=limited(10,10,10,10,8388608,134217728,cap,128);g.payload(ROOT,NbtInt.of(1));g.nativeState(2076);
            rejects("BUDGET_CANONICAL",()->{if(payload)g.payload(key(Minecraft121SettingsGraph.Kind.REGISTRY,"later"),NbtInt.of(2));
                else g.reference(ROOT,"later",ROOT,"ordinary","required");});rejects("GRAPH_FAILED",g::finish);
        }
    }
    @Test void nativeStateRejectsNegativeAndOverflowBeforeReuse() {
        for(long bytes:new long[]{-1,Long.MAX_VALUE}) {
            var g=graph();rejects(bytes<0?"BUDGET_NATIVE_STATE":"BUDGET_CANONICAL",()->g.nativeState(bytes));rejects("GRAPH_FAILED",g::finish);
        }
    }
}
