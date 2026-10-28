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
}
