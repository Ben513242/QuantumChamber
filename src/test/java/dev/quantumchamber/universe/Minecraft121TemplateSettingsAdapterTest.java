package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Minecraft121TemplateSettingsAdapterTest {
    private static final Key ROOT=new Key(Kind.TEMPLATE,"minecraft:structure","test:owned");
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();
    }
    private record Reference(Occurrence occurrence,RegistryKey<?> key) {}
    private static final class Fixture {
        final Minecraft121SettingsGraph graph;
        final List<Reference> refs=new ArrayList<>();
        final Minecraft121TemplateValueContracts values;
        Fixture(){this(new Minecraft121SettingsGraph());}
        Fixture(Minecraft121SettingsGraph graph){
            this.graph=graph;values=new Minecraft121TemplateValueContracts(graph,
                new DynamicRegistryManager.ImmutableImpl(List.of()),(o,k)->refs.add(new Reference(o,k)));
        }
        NbtCompound capture(NbtCompound n) { return values.capture(ROOT,n); }
        byte[] finish(int targetValue) {
            for(var ref:refs) { graph.payload(ref.occurrence().target(),NbtInt.of(targetValue));graph.expanded(ref.occurrence()); }
            return graph.finish();
        }
    }
    private static NbtList ints(int... values) {var n=new NbtList();for(int v:values)n.add(NbtInt.of(v));return n;}
    private static NbtCompound template(String block,NbtCompound data) {
        var n=new NbtCompound();n.put("size",ints(1,1,1));n.putInt("DataVersion",3953);
        var palette=new NbtList();var state=new NbtCompound();state.putString("Name",block);palette.add(state);n.put("palette",palette);
        var b=new NbtCompound();b.putInt("state",0);b.put("pos",ints(0,0,0));b.put("nbt",data);
        var blocks=new NbtList();blocks.add(b);n.put("blocks",blocks);n.put("entities",new NbtList());return n;
    }
    private static NbtCompound owner(String id) {var n=new NbtCompound();n.putString("id",id);return n;}
    private static NbtCompound history(String key,NbtElement count) {
        var n=owner("minecraft:furnace");var h=new NbtCompound();h.put(key,count);n.put("RecipesUsed",h);return n;
    }
    private static void rejects(String code,org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(Failure.class,action).getMessage());
    }
    @Test void recipesUsedLiteralIntPreservesRawKeyAndType() {
        var input=template("minecraft:furnace",history("iron_ingot",NbtInt.of(7)));
        var f=new Fixture();var owned=f.capture(input);
        assertEquals(input,owned);assertTrue(owned.getList("blocks",10).getCompound(0).getCompound("nbt")
                .getCompound("RecipesUsed").get("iron_ingot") instanceof NbtInt);
        assertTrue(f.refs.stream().noneMatch(r->r.key().getRegistry().toString().contains("recipe")));
        assertTrue(f.finish(1).length>0);
    }
    @Test void recipesUsedMissingRecipeAndEmptyAccepted() {
        for(String block:List.of("minecraft:furnace","minecraft:smoker","minecraft:blast_furnace")) {
            for(boolean empty:List.of(false,true)) {
                var data=owner(block);var h=new NbtCompound();if(!empty)h.putInt("absent:recipe",-3);data.put("RecipesUsed",h);
                var f=new Fixture();assertEquals(template(block,data),f.capture(template(block,data)));f.finish(1);
            }
        }
    }
    @Test void recipesUsedWrongOwnerContainerKeyOrValueRejected() {
        var chest=history("iron_ingot",NbtInt.of(1));chest.putString("id","minecraft:chest");
        rejects("UNSUPPORTED_RECIPES_USED_OWNER",()->new Fixture().capture(template("minecraft:chest",chest)));
        var wrong=owner("minecraft:furnace");wrong.putString("RecipesUsed","bad");
        rejects("UNSUPPORTED_RECIPES_USED_CONTAINER",()->new Fixture().capture(template("minecraft:furnace",wrong)));
        rejects("UNSUPPORTED_RECIPES_USED_ID",()->new Fixture().capture(template("minecraft:furnace",history("bad key",NbtInt.of(1)))));
        rejects("UNSUPPORTED_RECIPES_USED_VALUE",()->new Fixture().capture(template("minecraft:furnace",history("iron_ingot",NbtByte.of((byte)1)))));
    }
    @Test void ownedNbtAndCanonicalBytesIgnoreLaterInputAndOutputMutation() {
        var input=template("minecraft:furnace",history("iron_ingot",NbtInt.of(7)));var saved=input.copy();
        var f=new Fixture();var result=f.capture(input);input.getList("blocks",10).clear();result.getList("blocks",10).clear();
        var expected=new Fixture();expected.capture(saved);assertArrayEquals(expected.finish(1),f.finish(1));
    }
    @Test void literalHistoryCountAndTypeRemainCanonicalInputs() {
        var f=new Fixture();f.capture(template("minecraft:furnace",history("iron_ingot",NbtInt.of(7))));
        var h=new Fixture();h.capture(template("minecraft:furnace",history("iron_ingot",NbtInt.of(8))));
        assertFalse(Arrays.equals(f.finish(1),h.finish(1)));
    }
    @Test void jigsawCreatesRequiredPoolOccurrence() {
        var n=owner("minecraft:jigsaw");n.putString("pool","test:next");
        var f=new Fixture();f.capture(template("minecraft:jigsaw",n));
        assertTrue(f.refs.stream().anyMatch(r->r.key().getRegistry().equals(RegistryKeys.TEMPLATE_POOL.getValue())
                &&r.key().getValue().toString().equals("test:next")));
        rejects("GRAPH_UNCLOSED",f.graph::finish);
    }
    @Test void lootAndComponentTargetChangedOrMissingRejected() {
        for(boolean component:List.of(false,true)) {
            var n=owner("minecraft:chest");
            if(component){var c=new NbtCompound();var loot=new NbtCompound();loot.putString("loot_table","test:loot");c.put("minecraft:container_loot",loot);n.put("components",c);}
            else n.putString("LootTable","test:loot");
            var input=template("minecraft:chest",n);var f=new Fixture();f.capture(input);
            assertTrue(f.refs.stream().anyMatch(r->r.key().getRegistry().equals(RegistryKeys.LOOT_TABLE.getValue())
                    &&r.key().getValue().toString().equals("test:loot")));
            rejects("GRAPH_UNCLOSED",f.graph::finish);
            var a=new Fixture();a.capture(input);var b=new Fixture();b.capture(input);
            assertFalse(Arrays.equals(a.finish(1),b.finish(2)),"真正target內容改變必改canonical圖");
        }
    }
    @Test void unknownOwnerEntityAndComponentFailClosed() {
        rejects("UNSUPPORTED_TEMPLATE_BLOCK_ENTITY",()->new Fixture().capture(template("minecraft:vault",owner("minecraft:vault"))));
        var n=owner("minecraft:chest");var c=new NbtCompound();c.putString("minecraft:custom_name","未知文字consumer");n.put("components",c);
        rejects("UNSUPPORTED_TEMPLATE_COMPONENT",()->new Fixture().capture(template("minecraft:chest",n)));
        var t=template("minecraft:furnace",owner("minecraft:furnace"));var es=new NbtList();var e=new NbtCompound();e.put("nbt",owner("minecraft:pig"));es.add(e);t.put("entities",es);
        rejects("UNSUPPORTED_TEMPLATE_ENTITY",()->new Fixture().capture(t));
    }
    @Test void blockEntityCannotClaimDifferentOwner() {
        rejects("TEMPLATE_BLOCK_ENTITY_OWNER_MISMATCH",()->new Fixture().capture(template("minecraft:chest",owner("minecraft:furnace"))));
    }
    @Test void malformedPaletteIndexAndReferenceTypeReject() {
        var t=template("minecraft:furnace",owner("minecraft:furnace"));t.getList("blocks",10).getCompound(0).putInt("state",9);
        rejects("TEMPLATE_PALETTE_INDEX_INVALID",()->new Fixture().capture(t));
        var n=owner("minecraft:chest");n.putInt("LootTable",1);
        rejects("TEMPLATE_REFERENCE_TYPE",()->new Fixture().capture(template("minecraft:chest",n)));
    }
    @Test void everyRepeatedTemplateUseKeepsRequiredOccurrences() {
        var n=owner("minecraft:jigsaw");n.putString("pool","test:next");var t=template("minecraft:jigsaw",n);
        var f=new Fixture();f.capture(t);int first=f.refs.size();f.capture(t);assertEquals(first*2,f.refs.size());
        for(var ref:f.refs.subList(0,first)){f.graph.payload(ref.occurrence().target(),NbtInt.of(1));f.graph.expanded(ref.occurrence());}
        rejects("GRAPH_UNCLOSED",f.graph::finish);
    }

    @Test void nativeItemStackKeepsDefaultComponentsAndRequiredItemEdge() {
        var stack=new NbtCompound();stack.putString("id","minecraft:stone");stack.putInt("count",2);stack.putByte("Slot",(byte)0);
        var items=new NbtList();items.add(stack);var n=owner("minecraft:furnace");n.put("Items",items);
        var input=template("minecraft:furnace",n);var f=new Fixture();assertEquals(input,f.capture(input));
        assertTrue(f.refs.stream().anyMatch(r->r.key().getRegistry().equals(RegistryKeys.ITEM.getValue())
                &&r.key().getValue().toString().equals("minecraft:stone")));f.finish(1);
    }
    @Test void unreviewedItemComponentCannotHideBehindNativeCodec() {
        var stack=new NbtCompound();stack.putString("id","minecraft:stone");stack.putInt("count",1);
        var components=new NbtCompound();components.putString("minecraft:custom_name","\"name\"");stack.put("components",components);
        var items=new NbtList();items.add(stack);var n=owner("minecraft:furnace");n.put("Items",items);
        rejects("UNSUPPORTED_TEMPLATE_COMPONENT",()->new Fixture().capture(template("minecraft:furnace",n)));
    }
    @Test void lowRawAndOccurrenceBudgetsFailBeforeSuccessfulReturn() {
        var raw=new Minecraft121SettingsGraph(new Limits(100,100,100,10,32,10000,10000,128));
        rejects("BUDGET_RAW",()->new Fixture(raw).capture(template("minecraft:furnace",owner("minecraft:furnace"))));
        rejects("GRAPH_FAILED",raw::finish);
        var g=new Minecraft121SettingsGraph(new Limits(100,1,100,10,10000,10000,10000,128));
        var n=owner("minecraft:jigsaw");n.putString("pool","test:next");
        rejects("BUDGET_EDGES",()->new Fixture(g).capture(template("minecraft:jigsaw",n)));
        rejects("GRAPH_FAILED",g::finish);
    }
    @Test void unknownNestedBlockFieldFailsClosedAndPoisonsGraph() {
        var n=owner("minecraft:furnace");n.put("UnknownConfiguration",new NbtCompound());var f=new Fixture();
        rejects("UNSUPPORTED_TEMPLATE_BLOCK_ENTITY_FIELD",()->f.capture(template("minecraft:furnace",n)));
        rejects("GRAPH_FAILED",f.graph::finish);
    }
    @Test void failedRequiredTargetResolverPoisonsWholeGraph() {
        var g=new Minecraft121SettingsGraph();var values=new Minecraft121TemplateValueContracts(g,
                new DynamicRegistryManager.ImmutableImpl(List.of()),(o,k)->{throw new Failure("REQUIRED_TARGET_MISSING");});
        rejects("REQUIRED_TARGET_MISSING",()->values.capture(ROOT,template("minecraft:furnace",owner("minecraft:furnace"))));
        rejects("GRAPH_FAILED",g::finish);
    }

    @Test void missingBlockEntityNbtCannotSkipDefaultConsumerAdmission() {
        for(String block:List.of("minecraft:vault","minecraft:jigsaw","minecraft:chest")) {
            var input=template(block,owner(block));input.getList("blocks",10).getCompound(0).remove("nbt");
            rejects("TEMPLATE_BLOCK_ENTITY_NBT_REQUIRED",()->new Fixture().capture(input));
        }
    }
    @Test void ordinaryBlockWithoutBlockEntityNbtRemainsSupported() {
        var input=template("minecraft:stone",new NbtCompound());input.getList("blocks",10).getCompound(0).remove("nbt");
        var f=new Fixture();assertEquals(input,f.capture(input));f.finish(1);
    }

    @Test void itemUnknownRootCannotBorrowFurnaceHistory() {
        for(String extra:List.of("RecipesUsed","UnknownConfiguration")) {
            var stack=owner("minecraft:stone");stack.putInt("count",1);stack.put(extra,new NbtCompound());
            var items=new NbtList();items.add(stack);var n=owner("minecraft:furnace");n.put("Items",items);
            rejects("UNSUPPORTED_TEMPLATE_ITEM_FIELD",()->new Fixture().capture(template("minecraft:furnace",n)));
        }
    }
    @Test void containerLootCannotBorrowFurnaceHistory() {
        for(String extra:List.of("RecipesUsed","UnknownConfiguration")) {
            var loot=new NbtCompound();loot.putString("loot_table","test:loot");loot.put(extra,new NbtCompound());
            var components=new NbtCompound();components.put("minecraft:container_loot",loot);
            var n=owner("minecraft:chest");n.put("components",components);
            rejects("TEMPLATE_COMPONENT_SHAPE_INVALID",()->new Fixture().capture(template("minecraft:chest",n)));
        }
    }
}
