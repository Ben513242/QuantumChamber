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
            closure.reference(ROOT,"end",END_SETTINGS);closure.drain();
            assertTrue(h.finish().length>1000,"只閉合此局部End子圖，不代表三role或snapshot");
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
            rejects("NATIVE_DEFINITION_OWNER",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(unknown,f.sloped()));
            var unsupported=net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION,net.minecraft.util.Identifier.ofVanilla("end/unknown"));
            rejects("UNSUPPORTED_NATIVE_VALUE",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(unsupported,f.sloped()));
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
    private static final net.minecraft.registry.RegistryKey<net.minecraft.world.gen.densityfunction.DensityFunction> END_BASE=
            net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION,net.minecraft.util.Identifier.ofVanilla("end/base_3d_noise"));
    private static net.minecraft.util.math.noise.InterpolatedNoiseSampler baseSampler(EndFixture f) {
        return (net.minecraft.util.math.noise.InterpolatedNoiseSampler)f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION).get(END_BASE);
    }
    private static Minecraft121NativeValueContracts.SamplerStateSeal samplerSeal(Object sampler) {
        return ((Minecraft121NativeValueContracts.InterpolatedStateAccess)sampler).quantumchamber$captureInterpolatedState();
    }
    private static net.minecraft.util.math.noise.PerlinNoiseSampler[] lowerSlots(Object sampler) throws Exception {
        return (net.minecraft.util.math.noise.PerlinNoiseSampler[])nativeField(nativeField(sampler,"lowerInterpolatedNoise").get(sampler),"octaveSamplers")
                .get(nativeField(sampler,"lowerInterpolatedNoise").get(sampler));
    }
    @Test void samplerBridgesHitAllThreeNativeClassesBeforeBehaviorTests() throws Exception {
        try(var f=EndFixture.load()) {
            var sampler=baseSampler(f);assertInstanceOf(Minecraft121NativeValueContracts.InterpolatedStateAccess.class,sampler);
            for(String name:java.util.List.of("lowerInterpolatedNoise","upperInterpolatedNoise","interpolationNoise")) {
                var octave=nativeField(sampler,name).get(sampler);assertInstanceOf(Minecraft121NativeValueContracts.OctaveStateAccess.class,octave);
                for(var perlin:(Object[])nativeField(octave,"octaveSamplers").get(octave))
                    if(perlin!=null)assertInstanceOf(Minecraft121NativeValueContracts.PerlinStateAccess.class,perlin);
            }
            var state=samplerSeal(sampler);assertTrue(state.bytes().length>1000);
            assertTrue(state.sameIdentity(samplerSeal(sampler)));
            var codec=net.minecraft.util.math.noise.InterpolatedNoiseSampler.CODEC.codec().codec();
            var encoded=codec.encodeStart(NbtOps.INSTANCE,sampler).getOrThrow();
            var rebuilt=codec.parse(NbtOps.INSTANCE,encoded).getOrThrow();
            assertTrue(state.independentIdentity(samplerSeal(rebuilt)));
            assertArrayEquals(state.bytes(),samplerSeal(rebuilt).bytes());
        }
    }
    @Test void baseSamplerCapturesRawStateAndOnlyClosesItsRequiredNode() {
        try(var f=EndFixture.load()) {
            var g=graph();var values=new Minecraft121NativeValueContracts(g,f.registries(),f.resources());
            var captured=(NbtCompound)values.capture(END_BASE,baseSampler(f));
            assertArrayEquals(samplerSeal(baseSampler(f)).bytes(),captured.getByteArray("SamplerState"));
            assertTrue(captured.contains("NativeCodec"));
            var occurrence=g.reference(ROOT,"base",nativeNode(END_BASE),"ordinary","required");
            g.payload(nativeNode(END_BASE),captured);g.expanded(occurrence);assertTrue(g.finish().length>1000);
        }
    }
    @FunctionalInterface private interface SamplerMutation { void run(Object sampler) throws Exception; }
    private void samplerRejects(SamplerMutation mutation,String expected) throws Exception {
        try(var f=EndFixture.load()) {
            var sampler=baseSampler(f);mutation.run(sampler);var g=graph();
            rejects(expected,()->new Minecraft121NativeValueContracts(g,f.registries(),f.resources()).capture(END_BASE,sampler));
            rejects("GRAPH_FAILED",g::finish);
        }
    }
    @Test void samplerCase10RandomizedTreeRejectsDespiteSameCodec() throws Exception {
        try(var f=EndFixture.load()) {
            var sampler=baseSampler(f);var random=sampler.copyWithRandom(new net.minecraft.util.math.random.CheckedRandom(7));
            var entry=f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION).getEntry(END_BASE).orElseThrow();
            var field=nativeField(entry,"value");field.set(entry,random);
            try { rejects("NATIVE_SAMPLER_REBUILD_MISMATCH",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_BASE,random)); }
            finally { field.set(entry,sampler); }
        }
    }
    @Test void samplerCase11PermutationMutationRejects() throws Exception {
        samplerRejects(s->{var p=lowerSlots(s)[0];((byte[])nativeField(p,"permutation").get(p))[0]^=1;},"NATIVE_SAMPLER_REBUILD_MISMATCH");
    }
    @Test void samplerCase12OriginMutationRejects() throws Exception {
        samplerRejects(s->{var p=lowerSlots(s)[0];var field=nativeField(p,"originX");field.setDouble(p,field.getDouble(p)+1);},"NATIVE_SAMPLER_REBUILD_MISMATCH");
    }
    @Test void samplerCase13NullSlotMutationRejects() throws Exception {
        samplerRejects(s->lowerSlots(s)[0]=null,"NATIVE_SAMPLER_REBUILD_MISMATCH");
    }
    @Test void samplerCase39SixtyFiveSlotsRejectBeforeCapture() throws Exception {
        samplerRejects(s->{var o=nativeField(s,"lowerInterpolatedNoise").get(s);nativeField(o,"octaveSamplers").set(o,java.util.Arrays.copyOf(lowerSlots(s),65));},"BUDGET_NATIVE_OCTAVES");
    }
    @Test void samplerCase40Permutation257RejectBeforeCapture() throws Exception {
        samplerRejects(s->{var p=lowerSlots(s)[0];nativeField(p,"permutation").set(p,new byte[257]);},"NATIVE_PERMUTATION_SHAPE");
    }

    @Test void samplerSealClonesNestedIdentityVectorsAndBytesBothDirections() {
        Object owner=new Object(), array=new Object(), childOwner=new Object(), childArray=new Object();
        byte[] childBytes={1,2}, bytes={3,4};
        var child=Minecraft121NativeValueContracts.SamplerStateSeal.capture(childOwner,childArray,new Object[0],new Minecraft121NativeValueContracts.SamplerStateSeal[0],childBytes);
        Object[] identities={childOwner,null};var children=new Minecraft121NativeValueContracts.SamplerStateSeal[]{child,null};
        var seal=Minecraft121NativeValueContracts.SamplerStateSeal.capture(owner,array,identities,children,bytes);
        identities[0]=new Object();children[0]=null;childBytes[0]=9;bytes[0]=9;
        var original=Minecraft121NativeValueContracts.SamplerStateSeal.capture(owner,array,new Object[]{childOwner,null},
                new Minecraft121NativeValueContracts.SamplerStateSeal[]{child,null},new byte[]{3,4});
        assertTrue(seal.sameIdentity(original));assertArrayEquals(new byte[]{1,2},child.bytes());
        byte[] out=seal.bytes();out[0]=8;assertArrayEquals(new byte[]{3,4},seal.bytes());
    }
    private static byte[] rawSamplerOracle(Object sampler,int level) throws Exception {
        var buffer=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(buffer);
        if(level==0) {
            for(String field:java.util.List.of("xzScale","yScale","xzFactor","yFactor","smearScaleMultiplier","scaledXzScale","scaledYScale","maxValue"))
                out.writeLong(Double.doubleToRawLongBits(nativeField(sampler,field).getDouble(sampler)));
            for(String field:java.util.List.of("lowerInterpolatedNoise","upperInterpolatedNoise","interpolationNoise")) {
                byte[] child=rawSamplerOracle(nativeField(sampler,field).get(sampler),1);out.writeInt(child.length);out.write(child);
            }
        } else if(level==1) {
            out.writeInt(nativeField(sampler,"firstOctave").getInt(sampler));
            var amplitudes=(it.unimi.dsi.fastutil.doubles.DoubleList)nativeField(sampler,"amplitudes").get(sampler);
            out.writeInt(amplitudes.size());for(double value:amplitudes)out.writeLong(Double.doubleToRawLongBits(value));
            for(String field:java.util.List.of("persistence","lacunarity","maxValue"))out.writeLong(Double.doubleToRawLongBits(nativeField(sampler,field).getDouble(sampler)));
            var slots=(Object[])nativeField(sampler,"octaveSamplers").get(sampler);out.writeInt(slots.length);
            for(Object slot:slots){out.writeByte(slot==null?0:1);if(slot!=null)out.write(rawSamplerOracle(slot,2));}
        } else {
            for(String field:java.util.List.of("originX","originY","originZ"))out.writeLong(Double.doubleToRawLongBits(nativeField(sampler,field).getDouble(sampler)));
            byte[] permutation=(byte[])nativeField(sampler,"permutation").get(sampler);out.writeInt(permutation.length);out.write(permutation);
        }
        return buffer.toByteArray();
    }
    @Test void samplerSealMatchesAllRawFieldsAndPreservesNullSlotOrder() throws Exception {
        try(var f=EndFixture.load()) {
            var sampler=baseSampler(f);assertArrayEquals(rawSamplerOracle(sampler,0),samplerSeal(sampler).bytes());
            var slots=lowerSlots(sampler);slots[1]=null;
            assertArrayEquals(rawSamplerOracle(sampler,0),samplerSeal(sampler).bytes());
            slots[2]=null;assertArrayEquals(rawSamplerOracle(sampler,0),samplerSeal(sampler).bytes());
        }
    }
    @Test void samplerAllEightScalarsAndDerivedOctaveValuesRetainRawBits() throws Exception {
        try(var f=EndFixture.load()) {
            var sampler=baseSampler(f);
            for(String name:java.util.List.of("xzScale","yScale","xzFactor","yFactor","smearScaleMultiplier","scaledXzScale","scaledYScale","maxValue")) {
                var field=nativeField(sampler,name);double original=field.getDouble(sampler);byte[] before=samplerSeal(sampler).bytes();
                try { field.setDouble(sampler,-0.0d);assertFalse(java.util.Arrays.equals(before,samplerSeal(sampler).bytes()));
                    assertArrayEquals(rawSamplerOracle(sampler,0),samplerSeal(sampler).bytes()); }
                finally { field.setDouble(sampler,original); }
            }
            var octave=nativeField(sampler,"upperInterpolatedNoise").get(sampler);
            for(String name:java.util.List.of("persistence","lacunarity","maxValue")) {
                var field=nativeField(octave,name);double original=field.getDouble(octave);
                try { field.setDouble(octave,-0.0d);assertArrayEquals(rawSamplerOracle(sampler,0),samplerSeal(sampler).bytes()); }
                finally { field.setDouble(octave,original); }
            }
        }
    }
    @Test void samplerRejectsNonfiniteDerivedScalarAndOctaveAmplitudeCap() throws Exception {
        samplerRejects(s->nativeField(s,"scaledXzScale").setDouble(s,Double.NaN),"NATIVE_SAMPLER_NONFINITE");
        samplerRejects(s->{var o=nativeField(s,"lowerInterpolatedNoise").get(s);nativeField(o,"amplitudes").set(o,new it.unimi.dsi.fastutil.doubles.DoubleArrayList(new double[65]));},"BUDGET_NATIVE_OCTAVES");
    }
    @Test void sampler64SlotsAreCapturedButDifferingShapeCannotReplay() throws Exception {
        try(var f=EndFixture.load()) {
            var s=baseSampler(f);var o=nativeField(s,"lowerInterpolatedNoise").get(s);
            nativeField(o,"octaveSamplers").set(o,java.util.Arrays.copyOf(lowerSlots(s),64));
            assertArrayEquals(rawSamplerOracle(s,0),samplerSeal(s).bytes());
            rejects("NATIVE_SAMPLER_REBUILD_MISMATCH",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_BASE,s));
        }
    }
    @Test void samplerRejectsThreeChildAliasAndOrderSwap() throws Exception {
        samplerRejects(s->nativeField(s,"upperInterpolatedNoise").set(s,nativeField(s,"lowerInterpolatedNoise").get(s)),"NATIVE_SAMPLER_ALIASED");
        samplerRejects(s->{var lower=nativeField(s,"lowerInterpolatedNoise");var upper=nativeField(s,"upperInterpolatedNoise");Object old=lower.get(s);lower.set(s,upper.get(s));upper.set(s,old);},"NATIVE_SAMPLER_REBUILD_MISMATCH");
    }
    private static net.minecraft.resource.ResourceManager onSecondBaseRead(EndFixture f,org.junit.jupiter.api.function.Executable mutation) {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        return (net.minecraft.resource.ResourceManager)java.lang.reflect.Proxy.newProxyInstance(Minecraft121SettingsCanonicalizerTest.class.getClassLoader(),
                new Class<?>[]{net.minecraft.resource.ResourceManager.class},(proxy,method,args)->{
                    if(method.getName().equals("getResource") && calls.incrementAndGet()==2)mutation.execute();
                    return method.invoke(f.resources(),args);
                });
    }
    @Test void samplerNonFirstSlotSameRawNewIdentityIsRejectedAtTerminalRead() throws Exception {
        try(var f=EndFixture.load()) {
            var sampler=baseSampler(f);var slots=lowerSlots(sampler);var codec=net.minecraft.util.math.noise.InterpolatedNoiseSampler.CODEC.codec().codec();
            var rebuilt=codec.parse(NbtOps.INSTANCE,codec.encodeStart(NbtOps.INSTANCE,sampler).getOrThrow()).getOrThrow();
            Object original=slots[1];var replacement=lowerSlots(rebuilt)[1];assertNotSame(original,replacement);
            var before=samplerSeal(sampler);
            var resources=onSecondBaseRead(f,()->{slots[1]=replacement;assertArrayEquals(before.bytes(),samplerSeal(sampler).bytes());assertFalse(before.sameIdentity(samplerSeal(sampler)));});
            var g=graph();rejects("NATIVE_SAMPLER_ORIGINAL_IDENTITY_CHANGED",()->new Minecraft121NativeValueContracts(g,f.registries(),resources).capture(END_BASE,sampler));rejects("GRAPH_FAILED",g::finish);
        }
    }
    @Test void samplerPermutationArraySameBytesIdentityDriftIsRejected() throws Exception {
        try(var f=EndFixture.load()) {
            var s=baseSampler(f);var p=lowerSlots(s)[2];var field=nativeField(p,"permutation");byte[] original=(byte[])field.get(p);
            var resources=onSecondBaseRead(f,()->field.set(p,original.clone()));
            rejects("NATIVE_SAMPLER_ORIGINAL_IDENTITY_CHANGED",()->new Minecraft121NativeValueContracts(graph(),f.registries(),resources).capture(END_BASE,s));
        }
    }
    @Test void samplerTerminalStateMutationCannotHideBehindMatchingCodec() throws Exception {
        try(var f=EndFixture.load()) {
            var s=baseSampler(f);var p=lowerSlots(s)[2];byte[] permutation=(byte[])nativeField(p,"permutation").get(p);
            var resources=onSecondBaseRead(f,()->permutation[255]^=1);
            rejects("NATIVE_SAMPLER_ORIGINAL_STATE_CHANGED",()->new Minecraft121NativeValueContracts(graph(),f.registries(),resources).capture(END_BASE,s));
        }
    }
    @Test @SuppressWarnings("unchecked") void samplerTerminalRegistryEntryIdentityMustRemainExact() throws Exception {
        try(var f=EndFixture.load();var foreign=EndFixture.load()) {
            var registry=f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION);var sampler=baseSampler(f);
            var original=registry.getEntry(END_BASE).orElseThrow();
            var replacement=foreign.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION).getEntry(END_BASE).orElseThrow();
            nativeField(replacement,"value").set(replacement,sampler);
            var entries=(java.util.Map<Object,Object>)nativeField(registry,"keyToEntry").get(registry);
            var resources=onSecondBaseRead(f,()->entries.put(END_BASE,replacement));
            try { rejects("NATIVE_SAMPLER_ORIGINAL_IDENTITY_CHANGED",()->new Minecraft121NativeValueContracts(graph(),f.registries(),resources).capture(END_BASE,sampler)); }
            finally { entries.put(END_BASE,original); }
        }
    }
    @Test void samplerForeignSubclassAndNonBaseOwnerReject() throws Exception {
        try(var f=EndFixture.load();var foreign=EndFixture.load()) {
            rejects("NATIVE_DEFINITION_OWNER",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_BASE,baseSampler(foreign)));
            var subclass=new net.minecraft.util.math.noise.InterpolatedNoiseSampler(new net.minecraft.util.math.random.CheckedRandom(0),0.25,0.25,80,160,4){};
            var entry=f.registries().get(net.minecraft.registry.RegistryKeys.DENSITY_FUNCTION).getEntry(END_BASE).orElseThrow();var field=nativeField(entry,"value");var original=entry.value();
            try { field.set(entry,subclass);rejects("NATIVE_DEFINITION_OWNER",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_BASE,subclass)); }
            finally { field.set(entry,original); }
            rejects("NATIVE_DEFINITION_OWNER",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_SETTINGS,original));
        }
    }
    @Test void samplerRepeatedCaptureChargesRawAndCanonicalBudget() {
        try(var f=EndFixture.load()) {
            var s=baseSampler(f);int stateSize=samplerSeal(s).bytes().length;
            var raw=limited(100,100,100,100,8388608,4L*stateSize+314,268435456,128);
            var values=new Minecraft121NativeValueContracts(raw,f.registries(),f.resources());values.capture(END_BASE,s);
            rejects("BUDGET_TOTAL_RAW",()->values.capture(END_BASE,s));rejects("GRAPH_FAILED",raw::finish);
            var canonical=limited(100,100,100,100,8388608,134217728,4L*stateSize+1000,128);
            var second=new Minecraft121NativeValueContracts(canonical,f.registries(),f.resources());second.capture(END_BASE,s);
            rejects("BUDGET_CANONICAL",()->second.capture(END_BASE,s));rejects("GRAPH_FAILED",canonical::finish);
        }
    }
    @Test void samplerCancellationRejectsWithoutClearingInterrupt() {
        try(var f=EndFixture.load()) {
            Thread.currentThread().interrupt();
            try { rejects("NATIVE_SAMPLER_CANCELLED",()->new Minecraft121NativeValueContracts(graph(),f.registries(),f.resources()).capture(END_BASE,baseSampler(f)));assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
        }
    }

    private static net.minecraft.registry.RegistryKey<net.minecraft.structure.pool.StructurePool> poolKey(String id) {
        return net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL,net.minecraft.util.Identifier.of(id));
    }
    private static net.minecraft.registry.RegistryKey<net.minecraft.structure.processor.StructureProcessorList> processorKey(String id) {
        return net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.PROCESSOR_LIST,net.minecraft.util.Identifier.of(id));
    }
    private static net.minecraft.registry.DynamicRegistryManager processorRegistries(String id,
            net.minecraft.structure.processor.StructureProcessorList value) {
        var processors=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.PROCESSOR_LIST,
                com.mojang.serialization.Lifecycle.stable());
        processors.add(processorKey(id),value,net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);
        processors.freeze();
        return new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(processors));
    }
    @Test void namedProcessorListRequiresAdmittedNativeDefinition() {
        var value=new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(
                net.minecraft.structure.processor.NopStructureProcessor.INSTANCE));
        var registries=processorRegistries("test:nop",value);
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var closure=new Minecraft121RegistryClosure(g,registries,resources);
            closure.reference(ROOT,"processor",processorKey("test:nop"));closure.drain();
            assertTrue(g.finish().length>0);
        } finally {resources.close();}
    }
    private static byte[] namedProcessorBytes(String id,net.minecraft.structure.processor.StructureProcessorList value) {
        var registries=processorRegistries(id,value);
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var closure=new Minecraft121RegistryClosure(g,registries,resources);
            closure.reference(ROOT,"processor",processorKey(id));closure.drain();return g.finish();
        } finally {resources.close();}
    }
    @Test void namedProcessorCanonicalBytesPreserveEmptyTypeAndOrder() {
        var nop=net.minecraft.structure.processor.NopStructureProcessor.INSTANCE;
        var lava=net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE;
        var empty=namedProcessorBytes("test:list",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of()));
        var one=namedProcessorBytes("test:list",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(nop)));
        var other=namedProcessorBytes("test:list",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(lava)));
        var forward=namedProcessorBytes("test:list",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(nop,lava)));
        var reverse=namedProcessorBytes("test:list",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(lava,nop)));
        assertFalse(Arrays.equals(empty,one));assertFalse(Arrays.equals(one,other));assertFalse(Arrays.equals(forward,reverse));
        String text=new String(forward,java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("minecraft:nop"));assertTrue(text.contains("minecraft:lava_submerged_block"));
    }
    @Test void namedProcessorCaptureOwnsEarlierGraphAfterSourceMutation() {
        var mutable=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>();
        mutable.add(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE);
        var registries=processorRegistries("test:mutable",new net.minecraft.structure.processor.StructureProcessorList(mutable));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            // finish結果會快取：先drain、再改來源，最後才第一次finish，舊圖若仍讀可變來源才會被抓到。
            var old=graph();var a=new Minecraft121RegistryClosure(old,registries,resources);
            a.reference(ROOT,"processor",processorKey("test:mutable"));a.drain();
            mutable.set(0,net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE);
            assertArrayEquals(namedProcessorBytes("test:mutable",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(
                    net.minecraft.structure.processor.NopStructureProcessor.INSTANCE))),old.finish(),"drain時的[NOP]須已由舊圖擁有");
            var fresh=graph();var b=new Minecraft121RegistryClosure(fresh,registries,resources);
            b.reference(ROOT,"processor",processorKey("test:mutable"));b.drain();
            assertArrayEquals(namedProcessorBytes("test:mutable",new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(
                    net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE))),fresh.finish(),"新capture須反映新值");
        } finally {resources.close();}
    }
    @Test void namedProcessorRejectsUnsupportedNativeAndForeignTypes() {
        var unsupported=new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(
                new net.minecraft.structure.processor.BlockIgnoreStructureProcessor(java.util.List.of())));
        var subclass=new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of()) {};
        var lavaSubclass=new net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor() {};
        var cases=java.util.List.of(unsupported,subclass,
                new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(lavaSubclass)));
        var codes=java.util.List.of("UNSUPPORTED_PROCESSOR_TYPE","NATIVE_DEFINITION_OWNER","NATIVE_DEFINITION_SOURCE");
        for(int i=0;i<cases.size();i++) {
            var registries=processorRegistries("test:bad",cases.get(i));
            var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                    net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
            try {
                var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
                c.reference(ROOT,"processor",processorKey("test:bad"));
                rejects(codes.get(i),c::drain);rejects("GRAPH_FAILED",g::finish);
            } finally {resources.close();}
        }
    }
    @Test void namedProcessorChecksEveryElementNotOnlyTheFirst() {
        var trailingNull=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>();
        trailingNull.add(net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE);trailingNull.add(null);
        var cases=java.util.List.<java.util.List<net.minecraft.structure.processor.StructureProcessor>>of(
                java.util.List.of(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE,
                        new net.minecraft.structure.processor.BlockIgnoreStructureProcessor(java.util.List.of())),trailingNull);
        var codes=java.util.List.of("UNSUPPORTED_PROCESSOR_TYPE","PROCESSOR_LIST_CHANGED");
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(int index=0;index<cases.size();index++) {
            final int i=index;
            checks.add(()->assertAll("subcase="+codes.get(i),()->{
                var registries=processorRegistries("test:bad",new net.minecraft.structure.processor.StructureProcessorList(cases.get(i)));
                var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                        net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
                try {
                    var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
                    c.reference(ROOT,"processor",processorKey("test:bad"));
                    rejects(codes.get(i),c::drain);rejects("GRAPH_FAILED",g::finish);
                } finally {resources.close();}
            }));
        }
        assertAll(checks);
    }
    @Test void namedProcessorPayloadIsBoundedByGraphCanonicalBudgetDuringDrain() {
        var nop=(net.minecraft.structure.processor.StructureProcessor)net.minecraft.structure.processor.NopStructureProcessor.INSTANCE;
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            // 同一組limits：ROOT、reference與1項payload都放得下，4096項payload放不下；reference須成功，拒絕只能發生在drain內的processor payload。
            for(int count:new int[]{1,4096}) {
                var g=limited(10,10,10,10,100,134217728,2048,128);g.payload(ROOT,NbtInt.of(1));
                var c=new Minecraft121RegistryClosure(g,processorRegistries("test:payload",
                        new net.minecraft.structure.processor.StructureProcessorList(java.util.Collections.nCopies(count,nop))),resources);
                c.reference(ROOT,"processor",processorKey("test:payload"));
                if(count==1) {c.drain();assertTrue(g.finish().length>0,"小payload在同一組limits下須可完成");}
                else {rejects("BUDGET_CANONICAL",c::drain);rejects("GRAPH_FAILED",g::finish);}
            }
        } finally {resources.close();}
    }
    @Test void namedProcessorCountCapIsIndependentOfGraphBudget() {
        var nop=net.minecraft.structure.processor.NopStructureProcessor.INSTANCE;
        var atCap=java.util.Collections.nCopies(4096,(net.minecraft.structure.processor.StructureProcessor)nop);
        assertTrue(namedProcessorBytes("test:large",new net.minecraft.structure.processor.StructureProcessorList(atCap)).length>0);
        var tooLarge=processorRegistries("test:large",new net.minecraft.structure.processor.StructureProcessorList(
                java.util.Collections.nCopies(4097,(net.minecraft.structure.processor.StructureProcessor)nop)));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var c=new Minecraft121RegistryClosure(g,tooLarge,resources);
            c.reference(ROOT,"processor",processorKey("test:large"));
            rejects("BUDGET_PROCESSORS",c::drain);rejects("GRAPH_FAILED",g::finish);
            var small=limited(10,10,10,10,100,1000,8,10);
            var d=new Minecraft121RegistryClosure(small,processorRegistries("test:small",
                    new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of(nop))),resources);
            rejects("BUDGET_CANONICAL",()->{d.reference(ROOT,"processor",processorKey("test:small"));d.drain();});
            rejects("GRAPH_FAILED",small::finish);
        } finally {resources.close();}
    }
    @Test void namedProcessorMissingTargetWrongRegistryAndInlineFailClosed() {
        var processors=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.PROCESSOR_LIST,
                com.mojang.serialization.Lifecycle.stable());processors.freeze();
        var noise=noiseRegistry();
        var registries=new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(processors,noise));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            for(int i=0;i<3;i++) {
                var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);final int variant=i;
                rejects(i==0?"REQUIRED_REGISTRY_TARGET_MISSING":i==1?"REQUIRED_REGISTRY_MISSING":"UNSUPPORTED_INLINE_ENTRY",()->{
                    if(variant==0)c.reference(ROOT,"missing",processorKey("test:missing"));
                    else if(variant==1)c.reference(ROOT,"wrong",poolKey("test:missing"));
                    else c.entry(ROOT,"inline",net.minecraft.registry.entry.RegistryEntry.of(
                            new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of())));
                    c.drain();
                });rejects("GRAPH_FAILED",g::finish);
            }
        } finally {resources.close();}
    }
    @Test void namedProcessorNullAndChangingListFailClosed() {
        var nullItem=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>();nullItem.add(null);
        var drifting=new java.util.AbstractList<net.minecraft.structure.processor.StructureProcessor>() {
            int reads;
            @Override public int size(){return ++reads==1?1:2;}
            @Override public net.minecraft.structure.processor.StructureProcessor get(int index){return net.minecraft.structure.processor.NopStructureProcessor.INSTANCE;}
        };
        var cases=java.util.List.of(nullItem,drifting);
        for(int i=0;i<cases.size();i++) {
            var registries=processorRegistries("test:bad",new net.minecraft.structure.processor.StructureProcessorList(cases.get(i)));
            var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                    net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
            try {
                var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
                c.reference(ROOT,"processor",processorKey("test:bad"));
                rejects("PROCESSOR_LIST_CHANGED",c::drain);rejects("GRAPH_FAILED",g::finish);
            } finally {resources.close();}
        }
    }
    /** 期望drain以PROCESSOR_LIST_CHANGED拒絕並毒化Graph；回傳Failure供呼叫端再驗原因例外。 */
    private static Minecraft121SettingsGraph.Failure processorListChanged(String label,
            java.util.List<net.minecraft.structure.processor.StructureProcessor> list) {
        var registries=processorRegistries("test:bad",new net.minecraft.structure.processor.StructureProcessorList(list));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
            c.reference(ROOT,"processor",processorKey("test:bad"));
            var failure=assertThrows(Minecraft121SettingsGraph.Failure.class,c::drain,label);
            assertEquals("PROCESSOR_LIST_CHANGED",failure.getMessage(),label);
            assertEquals("GRAPH_FAILED",assertThrows(Minecraft121SettingsGraph.Failure.class,g::finish,label).getMessage(),label);
            return failure;
        } finally {resources.close();}
    }
    @Test void namedProcessorListUnreadableOrDriftingSourceFailsAsChanged() {
        var nop=(net.minecraft.structure.processor.StructureProcessor)net.minecraft.structure.processor.NopStructureProcessor.INSTANCE;
        var sizeFails=new java.util.AbstractList<net.minecraft.structure.processor.StructureProcessor>() {
            @Override public int size(){throw new UnsupportedOperationException("fixture size");}
            @Override public net.minecraft.structure.processor.StructureProcessor get(int index){return nop;}
        };
        var shorter=new java.util.AbstractList<net.minecraft.structure.processor.StructureProcessor>() {
            int reads;
            @Override public int size(){return ++reads==1?2:1;}
            @Override public net.minecraft.structure.processor.StructureProcessor get(int index){return nop;}
        };
        var structural=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>() {
            boolean added;
            @Override public java.util.Iterator<net.minecraft.structure.processor.StructureProcessor> iterator() {
                var delegate=super.iterator();
                return new java.util.Iterator<>() {
                    @Override public boolean hasNext(){return delegate.hasNext();}
                    @Override public net.minecraft.structure.processor.StructureProcessor next(){
                        var item=delegate.next();if(!added){added=true;add(nop);}return item;
                    }
                };
            }
        };
        structural.add(nop);structural.add(nop);
        // production依序讀size：配置前、第一遍後、第二遍後；ArrayList自身iterator不呼叫size()。
        var grownAfterCopy=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>() {
            int reads;
            @Override public int size(){return ++reads==2?3:super.size();}
        };
        grownAfterCopy.add(nop);grownAfterCopy.add(nop);
        var grownAfterVerify=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>() {
            int reads;
            @Override public int size(){return ++reads==3?3:super.size();}
        };
        grownAfterVerify.add(nop);grownAfterVerify.add(nop);
        var verifyShorter=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>() {
            int iterators;
            @Override public java.util.Iterator<net.minecraft.structure.processor.StructureProcessor> iterator() {
                return ++iterators==1?super.iterator():java.util.List.of(nop).iterator();
            }
        };
        verifyShorter.add(nop);verifyShorter.add(nop);
        var verifyLonger=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>() {
            int iterators;
            @Override public java.util.Iterator<net.minecraft.structure.processor.StructureProcessor> iterator() {
                return ++iterators==1?super.iterator():java.util.List.of(nop,nop,nop).iterator();
            }
        };
        verifyLonger.add(nop);verifyLonger.add(nop);
        assertAll(
                ()->processorListChanged("getList()==null",null),
                ()->assertInstanceOf(UnsupportedOperationException.class,
                        processorListChanged("size()丟例外",sizeFails).getCause(),"size()丟例外：須保留原因例外"),
                ()->processorListChanged("清單比size短",shorter),
                ()->assertInstanceOf(java.util.ConcurrentModificationException.class,
                        processorListChanged("迭代中結構性add（CME）",structural).getCause(),"CME：須保留原因例外"),
                ()->processorListChanged("第一遍後size改變",grownAfterCopy),
                ()->processorListChanged("第二遍較短",verifyShorter),
                ()->processorListChanged("第二遍較長",verifyLonger),
                ()->processorListChanged("第二遍後size改變",grownAfterVerify));
    }
    @Test void namedProcessorListNegativeSizeAndExhaustedIteratorFailAsChanged() {
        var nop=(net.minecraft.structure.processor.StructureProcessor)net.minecraft.structure.processor.NopStructureProcessor.INSTANCE;
        var negative=new java.util.AbstractList<net.minecraft.structure.processor.StructureProcessor>() {
            @Override public int size(){return -1;}
            @Override public net.minecraft.structure.processor.StructureProcessor get(int index){return nop;}
        };
        // AbstractList的iterator在get丟IndexOutOfBounds且modCount未變時改丟NoSuchElementException（清單在hasNext後縮短）。
        var exhausted=new java.util.AbstractList<net.minecraft.structure.processor.StructureProcessor>() {
            @Override public int size(){return 1;}
            @Override public net.minecraft.structure.processor.StructureProcessor get(int index){throw new IndexOutOfBoundsException(index);}
        };
        assertAll(
                ()->processorListChanged("size()為負",negative),
                ()->assertInstanceOf(java.util.NoSuchElementException.class,
                        processorListChanged("iterator提早耗盡（NoSuchElementException）",exhausted).getCause(),
                        "NoSuchElementException：須保留原因例外"));
    }
    @Test void namedProcessorSameSizeReplacementDuringCopyFailsClosed() {
        var changing=new java.util.ArrayList<net.minecraft.structure.processor.StructureProcessor>() {
            boolean changed;
            @Override public java.util.Iterator<net.minecraft.structure.processor.StructureProcessor> iterator() {
                var delegate=super.iterator();
                return new java.util.Iterator<>() {
                    @Override public boolean hasNext(){return delegate.hasNext();}
                    @Override public net.minecraft.structure.processor.StructureProcessor next(){
                        var item=delegate.next();
                        if(!changed){changed=true;set(0,net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE);
                            set(1,net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE);}
                        return item;
                    }
                };
            }
        };
        changing.add(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE);
        changing.add(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE);
        var registries=processorRegistries("test:changing",new net.minecraft.structure.processor.StructureProcessorList(changing));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
            c.reference(ROOT,"processor",processorKey("test:changing"));
            rejects("PROCESSOR_LIST_CHANGED",c::drain);rejects("GRAPH_FAILED",g::finish);
        } finally {resources.close();}
    }
    @Test void namedProcessorDoesNotUseSameIdFromWrongRegistry() {
        var processors=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.PROCESSOR_LIST,
                com.mojang.serialization.Lifecycle.stable());processors.freeze();
        var noise=noiseRegistry();assertTrue(noise.getEntry(noiseKey("a")).isPresent());
        var registries=new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(processors,noise));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
            rejects("REQUIRED_REGISTRY_TARGET_MISSING",()->{c.reference(ROOT,"wrong",processorKey("minecraft:a"));c.drain();});
            rejects("GRAPH_FAILED",g::finish);
        } finally {resources.close();}
    }
    private static String encodedGraphFields(String... fields) throws java.io.IOException {
        var bytes=new java.io.ByteArrayOutputStream();var output=new java.io.DataOutputStream(bytes);
        for(var field:fields){byte[] value=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(value.length);output.write(value);}
        return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    @Test void namedProcessorKeepsBothAliasScopedUses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try(var f=new PoolFixture(root,"test:processors",java.util.List.of(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE),"test:shared")) {
            var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);
            var binding=net.minecraft.structure.pool.alias.StructurePoolAliasBinding.direct(poolKey("test:symbol"),poolKey("test:pool"));
            c.alias(ROOT,"alias-one",binding);c.alias(ROOT,"alias-two",binding);c.drain();
            String encoded=new String(g.finish(),java.nio.charset.StandardCharsets.ISO_8859_1);
            for(var path:java.util.List.of("alias-one","alias-two")) {
                String scope=ROOT.kind()+"/"+ROOT.registry()+"/"+ROOT.id()+"/"+path;
                assertTrue(encoded.contains(encodedGraphFields("REGISTRY",net.minecraft.registry.RegistryKeys.PROCESSOR_LIST.getValue().toString(),
                        "test:processors",scope,"required-processors")),path);
            }
        }
    }
    @Test void namedProcessorRepeatedUsesKeepBothEdgesAndBudget() {
        var registries=processorRegistries("test:same",new net.minecraft.structure.processor.StructureProcessorList(
                java.util.List.of(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE)));
        var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(
                net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        try {
            var g=graph();var c=new Minecraft121RegistryClosure(g,registries,resources);
            c.reference(ROOT,"first",processorKey("test:same"));c.reference(ROOT,"second",processorKey("test:same"));c.drain();
            String text=new String(g.finish(),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("first"));assertTrue(text.contains("second"));
            var limited=limited(10,1,10,10,8388608,134217728,268435456,128);
            limited.payload(ROOT,NbtInt.of(1));var d=new Minecraft121RegistryClosure(limited,registries,resources);
            d.reference(ROOT,"first",processorKey("test:same"));
            rejects("BUDGET_EDGES",()->d.reference(ROOT,"second",processorKey("test:same")));
            rejects("GRAPH_FAILED",limited::finish);
        } finally {resources.close();}
    }
    private static net.minecraft.structure.pool.SinglePoolElement single(String id) {
        return net.minecraft.structure.pool.StructurePoolElement.ofSingle(id).apply(net.minecraft.structure.pool.StructurePool.Projection.RIGID);
    }
    private static final class PoolFixture implements AutoCloseable {
        final net.minecraft.resource.LifecycledResourceManagerImpl rm=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of());
        final net.minecraft.world.level.storage.LevelStorage.Session session;
        final net.minecraft.registry.DynamicRegistryManager registries;
        final ServerSettingsContext context;
        PoolFixture(java.nio.file.Path root,net.minecraft.structure.pool.StructurePoolElement... elements) throws Exception {
            this(root,null,null,null,elements);
        }
        PoolFixture(java.nio.file.Path root,String processorId,java.util.List<net.minecraft.structure.processor.StructureProcessor> values,
                String template,net.minecraft.structure.pool.StructurePoolElement... elements) throws Exception {
            java.nio.file.Files.createDirectories(root);session=net.minecraft.world.level.storage.LevelStorage.create(root).createSession("pool");
            var processors=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.PROCESSOR_LIST,com.mojang.serialization.Lifecycle.stable());
            if(processorId!=null)processors.add(processorKey(processorId),new net.minecraft.structure.processor.StructureProcessorList(values),
                    net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);
            processors.freeze();
            var pools=new net.minecraft.registry.SimpleRegistry<>(net.minecraft.registry.RegistryKeys.TEMPLATE_POOL,com.mojang.serialization.Lifecycle.stable());
            var fallback=pools.createMutableEntryLookup().getOrThrow(poolKey("test:pool"));
            var weighted=new java.util.ArrayList<com.mojang.datafixers.util.Pair<net.minecraft.structure.pool.StructurePoolElement,Integer>>();
            for(var e:elements)weighted.add(com.mojang.datafixers.util.Pair.of(e,1));
            if(template!=null)weighted.add(com.mojang.datafixers.util.Pair.of(net.minecraft.structure.pool.StructurePoolElement.ofProcessedSingle(
                    template,processors.getEntry(processorKey(processorId)).orElseThrow()).apply(
                    net.minecraft.structure.pool.StructurePool.Projection.RIGID),1));
            pools.add(poolKey("test:pool"),new net.minecraft.structure.pool.StructurePool(fallback,weighted),net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);pools.freeze();
            registries=new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(pools,processors,net.minecraft.registry.Registries.BLOCK));
            var lookup=net.minecraft.registry.Registries.BLOCK.getReadOnlyWrapper().withFeatureFilter(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES);
            var manager=new net.minecraft.structure.StructureTemplateManager(rm,session,net.minecraft.datafixer.Schemas.getFixer(),lookup);
            var contexts=new Minecraft121SettingsContexts(this);contexts.publishInitial(new Minecraft121SettingsContexts.Sources(registries,rm,manager,session,true));context=contexts.acquire(this);
        }
        void present(String id) throws Exception {
            var key=net.minecraft.util.Identifier.of(id);var path=session.getDirectory(net.minecraft.util.WorldSavePath.GENERATED)
                .resolve(key.getNamespace()+"/structures/"+key.getPath()+".nbt");java.nio.file.Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(new net.minecraft.structure.StructureTemplate().writeNbt(new NbtCompound()),path);
        }
        @Override public void close() throws Exception {try{session.close();}finally{rm.close();}}
    }
    @Test void processedSingleKeepsNamedProcessorEdgeDistinctFromInlineEmpty(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        for(var values:java.util.List.of(java.util.List.<net.minecraft.structure.processor.StructureProcessor>of(),
                java.util.List.<net.minecraft.structure.processor.StructureProcessor>of(net.minecraft.structure.processor.NopStructureProcessor.INSTANCE),
                java.util.List.<net.minecraft.structure.processor.StructureProcessor>of(net.minecraft.structure.processor.LavaSubmergedBlockStructureProcessor.INSTANCE))) {
            try(var f=new PoolFixture(root.resolve("case-"+values.size()+"-"+(values.isEmpty()?"empty":values.get(0).getClass().getSimpleName())),
                    "test:processors",values,"test:shared")) {
                var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);c.reference(ROOT,"pool",poolKey("test:pool"));c.drain();
                String text=new String(g.finish(),java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(text.contains("test:processors"));assertTrue(text.contains("required-processors"));
                assertTrue(text.contains("test:shared"));
            }
        }
        try(var f=new PoolFixture(root.resolve("inline"),single("test:shared"))) {
            var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);c.reference(ROOT,"pool",poolKey("test:pool"));c.drain();
            assertFalse(new String(g.finish(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("test:processors"));
        }
    }
    @Test void nonEmptyPoolSingleCodecLocationCreatesTemplateEdge(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try(var f=new PoolFixture(root,single("test:shared"))) {
            var codec=net.minecraft.structure.pool.SinglePoolElement.CODEC.codec();var ops=f.registries.getOps(NbtOps.INSTANCE);
            var encoded=codec.encodeStart(ops,single("test:shared")).getOrThrow();
            assertEquals(encoded,codec.encodeStart(ops,codec.parse(ops,encoded).getOrThrow()).getOrThrow(),"fixture原生Single left必能roundtrip");
            var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);c.reference(ROOT,"pool",poolKey("test:pool"));c.drain();
            String absent=new String(g.finish(),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(absent.contains("ABSENT_NATIVE_BLANK"));assertTrue(absent.contains("test:shared"));assertTrue(absent.contains("fallback"));
            f.present("test:shared");var h=graph();var d=new Minecraft121RegistryClosure(h,f.context);d.reference(ROOT,"pool",poolKey("test:pool"));d.drain();
            assertTrue(new String(h.finish(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("PRESENT"));assertFalse(Arrays.equals(g.finish(),h.finish()));
        }
    }
    @Test void singleCodecRejectsLegacySubclassAndInlineRight(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var ctor=net.minecraft.structure.pool.SinglePoolElement.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var inline=(net.minecraft.structure.pool.SinglePoolElement)ctor.newInstance(com.mojang.datafixers.util.Either.right(new net.minecraft.structure.StructureTemplate()),
            net.minecraft.registry.entry.RegistryEntry.of(new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of())),
            net.minecraft.structure.pool.StructurePool.Projection.RIGID,java.util.Optional.empty());
        var legacy=net.minecraft.structure.pool.StructurePoolElement.ofLegacySingle("test:shared").apply(net.minecraft.structure.pool.StructurePool.Projection.RIGID);
        var subclass=new net.minecraft.structure.pool.SinglePoolElement(com.mojang.datafixers.util.Either.left(net.minecraft.util.Identifier.of("test:shared")),
            net.minecraft.registry.entry.RegistryEntry.of(new net.minecraft.structure.processor.StructureProcessorList(java.util.List.of())),
            net.minecraft.structure.pool.StructurePool.Projection.RIGID,java.util.Optional.empty()) {};
        var cases=java.util.List.of(legacy,subclass,inline);
        for(int i=0;i<cases.size();i++)try(var f=new PoolFixture(root.resolve("case-"+i),cases.get(i))) {
            var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);c.reference(ROOT,"pool",poolKey("test:pool"));
            rejects(i==2?"UNSUPPORTED_SINGLE_LOCATION":"UNSUPPORTED_POOL_ELEMENT",c::drain);rejects("GRAPH_FAILED",g::finish);
        }
    }
    @Test void sameTemplateDifferentUsesAndAliasOccurrenceExpand(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try(var f=new PoolFixture(root,single("test:shared"))) {
            var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);
            c.template(ROOT,"one",net.minecraft.util.Identifier.of("test:shared"),Minecraft121TemplateSettingsAdapter.Use.EXACT_SINGLE,"scope-a");
            c.template(ROOT,"two",net.minecraft.util.Identifier.of("test:shared"),Minecraft121TemplateSettingsAdapter.Use.EXACT_SINGLE,"scope-b");c.drain();
            String bytes=new String(g.finish(),java.nio.charset.StandardCharsets.ISO_8859_1);assertTrue(bytes.contains("scope-a"));assertTrue(bytes.contains("scope-b"));
            var h=graph();var d=new Minecraft121RegistryClosure(h,f.context);d.reference(ROOT,"pool",poolKey("test:pool"));
            d.alias(ROOT,"alias",net.minecraft.structure.pool.alias.StructurePoolAliasBinding.direct(poolKey("test:symbol"),poolKey("test:pool")));d.drain();
            assertTrue(new String(h.finish(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("alias-target"));
        }
    }
    @Test void singleThenUnknownAndUnknownThenSingleBothReject(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        for(boolean reverse:java.util.List.of(false,true))try(var f=new PoolFixture(root.resolve("order-"+reverse))) {
            var g=graph();var c=new Minecraft121RegistryClosure(g,f.context);var id=net.minecraft.util.Identifier.of("test:shared");
            rejects("TEMPLATE_ABSENCE_UNSUPPORTED_USE",()->{
                c.template(ROOT,"one",id,reverse?Minecraft121TemplateSettingsAdapter.Use.UNKNOWN:Minecraft121TemplateSettingsAdapter.Use.EXACT_SINGLE,"scope");
                c.template(ROOT,"two",id,reverse?Minecraft121TemplateSettingsAdapter.Use.EXACT_SINGLE:Minecraft121TemplateSettingsAdapter.Use.UNKNOWN,"scope");c.drain();
            });rejects("GRAPH_FAILED",g::finish);
        }
    }
    @Test void templateStateEdgeResourceRawCanonicalBudgetOverflowRejected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String[] codes={"BUDGET_EDGES","BUDGET_STATES","BUDGET_RESOURCES","BUDGET_RAW","BUDGET_CANONICAL"};
        // 任何lease之前先算blank charge，本方法只拿它暖機：BUDGET_RESOURCES是本方法第一個在lease內capture的子案，先讓JVM首次的原生blank template載入落在lease外。
        // 100ms量的是hold，不排除冷類別載入；這只降低風險，lease內仍有其他冷成本（例如BUDGET_EDGES的pool codec），production冷啟動仍屬live hold I-1。
        Minecraft121TemplateValueContracts.measure(new net.minecraft.structure.StructureTemplate().writeNbt(new NbtCompound()),0);
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(int index=0;index<codes.length;index++){final int i=index;checks.add(()->assertAll("subcase="+codes[i],()->{try(var f=new PoolFixture(root.resolve("budget-"+i),single("test:a"),single("test:b"))) {
            var g=limited(100,i==0?1:100,i==1?1:100,i==2?1:10,i==3?1:10000,100000,i==4?128:100000,128);
            var c=new Minecraft121RegistryClosure(g,f.context);final String code=codes[i];
            rejects(code,()->{g.payload(ROOT,NbtInt.of(1));c.reference(ROOT,"pool",poolKey("test:pool"));c.drain();});rejects("GRAPH_FAILED",g::finish);
        }}));}
        assertAll(checks);
    }
    private static Minecraft121RegistryClosure repeatedTemplate(Minecraft121SettingsGraph g,PoolFixture f,int occurrences) {
        g.payload(ROOT,NbtInt.of(1));var c=new Minecraft121RegistryClosure(g,f.context);
        for(int j=0;j<occurrences;j++)c.template(ROOT,"repeat-"+j,net.minecraft.util.Identifier.of("test:repeat"),
                Minecraft121TemplateSettingsAdapter.Use.EXACT_SINGLE,"budget");
        return c;
    }
    @Test void templateTotalRawAllowsOneChargeAndRejectsAccumulation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        // 任何lease之前先算blank charge：它是本測試的BUDGET_TOTAL_RAW邊界，也讓JVM首次的原生blank template載入落在lease外（暖機）。
        long charge=Minecraft121TemplateValueContracts.measure(new net.minecraft.structure.StructureTemplate().writeNbt(new NbtCompound()),0);
        try(var f=new PoolFixture(root,single("test:a"),single("test:b"))) {
            var below=limited(100,100,100,10,10000,charge-1,100000,128);
            var once=limited(100,100,100,10,10000,charge,100000,128);
            var twice=limited(100,100,100,10,10000,charge,100000,128);
            assertAll(
                    ()->assertAll("subcase=totalRaw=charge-1,occurrences=1",()->{
                        var c=repeatedTemplate(below,f,1);rejects("BUDGET_TOTAL_RAW",c::drain);rejects("GRAPH_FAILED",below::finish);}),
                    ()->assertAll("subcase=totalRaw=charge,occurrences=1",()->{
                        repeatedTemplate(once,f,1).drain();assertTrue(once.finish().length>0,"一份charge足夠一次occurrence");}),
                    ()->assertAll("subcase=totalRaw=charge,occurrences=2",()->{
                        var c=repeatedTemplate(twice,f,2);rejects("BUDGET_TOTAL_RAW",c::drain);rejects("GRAPH_FAILED",twice::finish);}));
        }
    }

    @Test void nativeSingleCodecMissingProcessorRegistryDiagnostic() {
        var ops=new net.minecraft.registry.DynamicRegistryManager.ImmutableImpl(java.util.List.of(poolRegistry(),net.minecraft.registry.Registries.BLOCK)).getOps(NbtOps.INSTANCE);
        var codec=net.minecraft.structure.pool.SinglePoolElement.CODEC.codec();var encoded=codec.encodeStart(ops,single("test:shared"));
        String message=encoded.error().map(com.mojang.serialization.DataResult.Error::message).orElseGet(()->
            codec.parse(ops,encoded.getOrThrow()).error().orElseThrow().message());
        System.out.println("MISSING_PROCESSOR_REGISTRY_NATIVE_CAUSE="+message);
        assertTrue(message.contains("processor_list"),message);
    }

}
