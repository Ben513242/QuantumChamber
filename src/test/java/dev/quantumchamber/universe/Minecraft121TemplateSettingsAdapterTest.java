package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;
import static dev.quantumchamber.universe.Minecraft121TemplateSettingsAdapter.*;
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

    private static OwnedTemplate blankOwned(int paletteCount) {
        var nativeTemplate=new net.minecraft.structure.StructureTemplate();
        var nativeNbt=nativeTemplate.writeNbt(new NbtCompound());
        var shape=new NbtCompound();var palettes=new NbtList();
        for(int i=0;i<paletteCount;i++)palettes.add(new NbtList());
        shape.put("Palettes",palettes);shape.put("Entities",new NbtList());shape.put("Size",ints(0,0,0));shape.putString("Author","?");
        return new OwnedTemplate(shape,nativeNbt);
    }
    private static NbtCompound resolved(CacheKind cache,SourceKind source,OwnedTemplate cached,OwnedTemplate rebuilt) {
        return Minecraft121TemplateSettingsAdapter.resolveOwned(net.minecraft.util.Identifier.of("test:owned"),Use.EXACT_SINGLE,
                new Capture(cache,source,cached,rebuilt));
    }
    @Test void cacheFiveStatesByPresentAbsenceError() {
        var checks=new ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var cache:CacheKind.values())for(var source:SourceKind.values())checks.add(()->{
            var cached=cache==CacheKind.PRESENT_CONSTRUCTOR_BLANK?blankOwned(0):cache==CacheKind.OTHER_PRESENT?blankOwned(1):null;
            var rebuilt=source==SourceKind.PRESENT?blankOwned(1):blankOwned(0);
            boolean accepted=source==SourceKind.PRESENT&&(cache==CacheKind.NO_ENTRY||cache==CacheKind.OTHER_PRESENT)
                    ||source==SourceKind.PROVEN_ABSENT&&(cache==CacheKind.NO_ENTRY||cache==CacheKind.NEGATIVE||cache==CacheKind.PRESENT_CONSTRUCTOR_BLANK);
            if(accepted)assertEquals(source==SourceKind.PRESENT?"PRESENT":"ABSENT_NATIVE_BLANK",resolved(cache,source,cached,rebuilt).getString("Resolution"));
            else assertThrows(Failure.class,()->resolved(cache,source,cached,rebuilt),cache+"/"+source);
        });
        assertAll(checks);
    }
    @Test void sourceErrorOrUnknownCannotFallbackToCachedBlank() {
        rejects("TEMPLATE_SOURCE_ERROR",()->resolved(CacheKind.PRESENT_CONSTRUCTOR_BLANK,SourceKind.ERROR,blankOwned(0),blankOwned(0)));
        rejects("TEMPLATE_SOURCE_UNKNOWN",()->resolved(CacheKind.PRESENT_CONSTRUCTOR_BLANK,SourceKind.UNKNOWN,blankOwned(0),blankOwned(0)));
    }
    @Test void requiredOrUnknownUseRejectsNativeAbsence() {
        for(var use:List.of(Use.REQUIRED,Use.UNKNOWN))rejects("TEMPLATE_ABSENCE_UNSUPPORTED_USE",()->
                Minecraft121TemplateSettingsAdapter.resolveOwned(net.minecraft.util.Identifier.of("test:owned"),use,
                        new Capture(CacheKind.NO_ENTRY,SourceKind.PROVEN_ABSENT,null,blankOwned(0))));
    }
    @Test void constructorBlankZeroPaletteDiffersFromOneEmptyPalette() {
        assertEquals(blankOwned(0).nativeNbt(),blankOwned(1).nativeNbt());
        rejects("TEMPLATE_CONSTRUCTOR_BLANK_MISMATCH",()->resolved(CacheKind.PRESENT_CONSTRUCTOR_BLANK,
                SourceKind.PROVEN_ABSENT,blankOwned(1),blankOwned(0)));
    }
    @Test void presentNativeRebuildMustMatchAllCapturedFields() {
        var original=blankOwned(1);var changed=original.shape();changed.putString("Author","changed");
        rejects("TEMPLATE_CACHE_REBUILD_MISMATCH",()->resolved(CacheKind.OTHER_PRESENT,SourceKind.PRESENT,
                new OwnedTemplate(changed,original.nativeNbt()),original));
    }
    @Test void presentEmptyAndAbsentHaveDifferentBytes() {
        var present=resolved(CacheKind.NO_ENTRY,SourceKind.PRESENT,null,blankOwned(1));
        var absent=resolved(CacheKind.NO_ENTRY,SourceKind.PROVEN_ABSENT,null,blankOwned(0));
        assertEquals(present.getCompound("NativeTemplate"),absent.getCompound("NativeTemplate"));
        var a=new Minecraft121SettingsGraph();a.payload(ROOT,present);var b=new Minecraft121SettingsGraph();b.payload(ROOT,absent);
        assertFalse(Arrays.equals(a.finish(),b.finish()));
    }
    private static NbtCompound envelope() {
        var n=new NbtCompound();n.putString("Contract","quantumchamber:m6-template-resolution-v1");n.putString("TemplateId","test:owned");
        n.putString("Resolution","PRESENT");n.put("NativeTemplate",blankOwned(1).nativeNbt());return n;
    }
    @Test void envelopeRejectsMissingExtraWrongTypeIdAndResolution() {
        for(String key:List.of("Contract","TemplateId","Resolution","NativeTemplate")) {
            var missing=envelope();missing.remove(key);rejects("TEMPLATE_ENVELOPE_INVALID",()->Minecraft121TemplateSettingsAdapter.readEnvelope(missing));
            var wrong=envelope();wrong.putInt(key,1);rejects("TEMPLATE_ENVELOPE_INVALID",()->Minecraft121TemplateSettingsAdapter.readEnvelope(wrong));
        }
        var extra=envelope();extra.putInt("Extra",1);rejects("TEMPLATE_ENVELOPE_INVALID",()->Minecraft121TemplateSettingsAdapter.readEnvelope(extra));
        for(String id:List.of("owned","Bad:owned")){var n=envelope();n.putString("TemplateId",id);rejects("TEMPLATE_ENVELOPE_INVALID",()->Minecraft121TemplateSettingsAdapter.readEnvelope(n));}
        for(String key:List.of("Contract","Resolution")){var n=envelope();n.putString(key,"UNKNOWN");rejects("TEMPLATE_ENVELOPE_INVALID",()->Minecraft121TemplateSettingsAdapter.readEnvelope(n));}
        var legal=envelope();assertEquals(legal,Minecraft121TemplateSettingsAdapter.readEnvelope(legal));
    }
    @Test void ownedDtoDoesNotRetainInputOrOutputNbt() {
        var original=blankOwned(0);var shape=original.shape();var nativeNbt=original.nativeNbt();var dto=new OwnedTemplate(shape,nativeNbt);
        shape.putString("Author","input mutation");nativeNbt.putInt("Extra",1);dto.shape().putString("Author","output mutation");dto.nativeNbt().putInt("Extra",2);
        assertEquals(original.shape(),dto.shape());assertEquals(original.nativeNbt(),dto.nativeNbt());
    }
    @Test void nativeBlockBaseGetterHasStableIdentityAndFilteredBaseIsExact() {
        var base=Registries.BLOCK.getReadOnlyWrapper();assertSame(base,Registries.BLOCK.getReadOnlyWrapper());
        var filtered=base.withFeatureFilter(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES);
        assertTrue(filtered instanceof net.minecraft.registry.RegistryWrapper.Impl.Delegating<?>);
        assertSame(base,((net.minecraft.registry.RegistryWrapper.Impl.Delegating<?>)filtered).getBase());
        assertNotSame(filtered,base.withFeatureFilter(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES));
    }
    @Test void nativeSafeTemplateAccessorRequiresPermitBeforeReadingFields() {
        var value=new net.minecraft.structure.StructureTemplate();assertTrue(value instanceof TemplateAccess);
        assertEquals("TEMPLATE_CAPTURE_PERMIT_REQUIRED",assertThrows(IllegalStateException.class,
                ()->((TemplateAccess)value).quantumchamber$captureSettings(null)).getMessage());
    }
    @Test void recipesUsedOnlyRawKeyAndEmptyPresenceAffectCanonicalBytes() {
        var implicit=new Fixture();implicit.capture(template("minecraft:furnace",history("iron_ingot",NbtInt.of(7))));
        var explicit=new Fixture();explicit.capture(template("minecraft:furnace",history("minecraft:iron_ingot",NbtInt.of(7))));
        assertFalse(Arrays.equals(implicit.finish(1),explicit.finish(1)));
        var noHistory=new Fixture();noHistory.capture(template("minecraft:furnace",owner("minecraft:furnace")));
        var data=owner("minecraft:furnace");data.put("RecipesUsed",new NbtCompound());var emptyHistory=new Fixture();emptyHistory.capture(template("minecraft:furnace",data));
        assertFalse(Arrays.equals(noHistory.finish(1),emptyHistory.finish(1)));
    }
    @Test void effectiveDefaultComponentValueIsInResolvedPayload() {
        var stack=owner("minecraft:stone");stack.putInt("count",1);var items=new NbtList();items.add(stack);
        var n=owner("minecraft:furnace");n.put("Items",items);var f=new Fixture();f.capture(template("minecraft:furnace",n));var actual=f.finish(1);
        byte[] key="minecraft:max_stack_size".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] expected=java.nio.ByteBuffer.allocate(4+key.length+1+4).putInt(key.length).put(key).put((byte)NbtElement.INT_TYPE).putInt(64).array();
        boolean found=false;for(int i=0;i+expected.length<=actual.length;i++)if(Arrays.equals(actual,i,i+expected.length,expected,0,expected.length)){found=true;break;}
        assertTrue(found,"stone有效預設max_stack_size=64必保留在RESOLVED payload，不只是item id引用");
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path nativeRoot;
    private static final class NativeFixture implements AutoCloseable {
        final net.minecraft.resource.ResourceManager resources;
        final net.minecraft.world.level.storage.LevelStorage.Session session;
        final DynamicRegistryManager registries=new DynamicRegistryManager.ImmutableImpl(List.of(Registries.BLOCK));
        final net.minecraft.structure.StructureTemplateManager manager;
        final Minecraft121SettingsContexts contexts=new Minecraft121SettingsContexts(this);
        final ServerSettingsContext context;
        NativeFixture(java.nio.file.Path root) throws Exception {this(root,new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,List.of()));}
        NativeFixture(java.nio.file.Path root,net.minecraft.resource.ResourceManager resources) throws Exception {
            this.resources=resources;
            java.nio.file.Files.createDirectories(root);
            session=net.minecraft.world.level.storage.LevelStorage.create(root).createSession("owned");
            var lookup=Registries.BLOCK.getReadOnlyWrapper().withFeatureFilter(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES);
            manager=new net.minecraft.structure.StructureTemplateManager(resources,session,net.minecraft.datafixer.Schemas.getFixer(),lookup);
            contexts.publishInitial(new Minecraft121SettingsContexts.Sources(registries,resources,manager,session,true));context=contexts.acquire(this);
        }
        ServerSettingsContext withSources(Object rm,Object templateManager,Object sourceSession) {
            var owner=new Minecraft121SettingsContexts(this);owner.publishInitial(new Minecraft121SettingsContexts.Sources(registries,rm,templateManager,sourceSession,true));return owner.acquire(this);
        }
        @Override public void close() throws Exception {try{session.close();}finally{if(resources instanceof AutoCloseable closeable)closeable.close();}}
    }
    @Test void nativeCacheNoEntryNegativeAndConstructorBlankAreObservedWithoutMutation() throws Exception {
        for(int state=0;state<3;state++)try(var f=new NativeFixture(nativeRoot.resolve("state-"+state))) {
            var id=net.minecraft.util.Identifier.of("test:cache");net.minecraft.structure.StructureTemplate original=null;
            if(state==1)assertTrue(f.manager.getTemplate(id).isEmpty());
            if(state==2)original=f.manager.getTemplateOrBlank(id);
            var expected=state==0?CacheKind.NO_ENTRY:state==1?CacheKind.NEGATIVE:CacheKind.PRESENT_CONSTRUCTOR_BLANK;
            assertEquals(expected,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,id).cacheKind());
            assertEquals(expected,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,id).cacheKind());
            if(original!=null)assertSame(original,f.manager.getTemplate(id).orElseThrow());
        }
    }
    @Test void unlockThenLiveMutationCannotChangeCapturedTemplateShape() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var id=net.minecraft.util.Identifier.of("test:cache");var live=f.manager.getTemplateOrBlank(id);live.setAuthor("before");
            var captured=Minecraft121TemplateSettingsAdapter.captureOwned(f.context,id);
            assertEquals(CacheKind.OTHER_PRESENT,captured.cacheKind());assertEquals("before",captured.cacheValue().shape().getString("Author"));
            live.setAuthor("after");assertEquals("before",captured.cacheValue().shape().getString("Author"));
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {assertTrue(executor.submit(()->{try(var lease=Minecraft121TemplateAccessGate.forManager(f.manager).trySnapshot()){lease.requireWithinBudget();return true;}}).get(2,java.util.concurrent.TimeUnit.SECONDS));}
            finally {executor.shutdownNow();}
        }
    }
    @Test void contextResourceAndSessionMustMatchNativeManagerInputs() throws Exception {
        try(var f=new NativeFixture(nativeRoot.resolve("a"));var other=new NativeFixture(nativeRoot.resolve("b"))) {
            var id=net.minecraft.util.Identifier.of("test:cache");
            rejects("TEMPLATE_RESOURCE_OWNER_MISMATCH",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.withSources(other.resources,f.manager,f.session),id));
            rejects("TEMPLATE_SESSION_PATH_MISMATCH",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.withSources(f.resources,f.manager,other.session),id));
        }
    }
    @Test void expiredContextCannotStartNativeCapture() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            f.contexts.reloadStarted();
            assertEquals("CONTEXT_UNAVAILABLE",assertThrows(IllegalStateException.class,
                    ()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,net.minecraft.util.Identifier.of("test:cache"))).getMessage());
        }
    }

    private static CapturePermit arrangedPermit(NativeFixture f,Minecraft121TemplateAccessGate.Lease lease) throws Exception {
        // 僅測試安排真lease的錯誤消費者；production沒有公開permit建構／getter。
        var ctor=CapturePermit.class.getDeclaredConstructor(ServerSettingsContext.class,net.minecraft.structure.StructureTemplateManager.class,
                Minecraft121TemplateAccessGate.class,Minecraft121TemplateAccessGate.Lease.class);
        ctor.setAccessible(true);return ctor.newInstance(f.context,f.manager,Minecraft121TemplateAccessGate.forManager(f.manager),lease);
    }
    @Test void isolatedNativePresentAndConstructorBlankPreserveDifferentInternalShape() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var raw=blankOwned(0).nativeNbt();
            var present=Minecraft121TemplateSettingsAdapter.rebuildOwned(f.context,raw,false);
            var absent=Minecraft121TemplateSettingsAdapter.rebuildOwned(f.context,null,true);
            assertEquals(1,present.shape().getList("Palettes",NbtElement.LIST_TYPE).size());
            assertEquals(0,absent.shape().getList("Palettes",NbtElement.LIST_TYPE).size());
            assertEquals(present.nativeNbt(),absent.nativeNbt());
        }
    }
    @Test void isolatedRebuildCannotFillOrReplaceLiveCache() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var id=net.minecraft.util.Identifier.of("test:cache");assertTrue(f.manager.getTemplate(id).isEmpty());
            assertEquals(CacheKind.NEGATIVE,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,id).cacheKind());
            Minecraft121TemplateSettingsAdapter.rebuildOwned(f.context,blankOwned(0).nativeNbt(),false);
            assertEquals(CacheKind.NEGATIVE,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,id).cacheKind());
        }
    }
    @Test void externalCallerCannotRegisterArbitraryIsolatedTemplate() throws Exception {
        try(var f=new NativeFixture(nativeRoot);var other=new NativeFixture(nativeRoot.resolve("other"))) {
            var template=other.manager.getTemplateOrBlank(net.minecraft.util.Identifier.of("test:cache"));
            try(var lease=Minecraft121TemplateAccessGate.forManager(f.manager).trySnapshot()) {
                var permit=arrangedPermit(f,lease);
                rejects("TEMPLATE_ISOLATED_CALLER_REJECTED",()->permit.captureIsolated(f.manager,other.manager,template,false));
            }
        }
    }
    @Test void crossManagerAndUnassociatedTemplateCannotUseLivePermit() throws Exception {
        try(var f=new NativeFixture(nativeRoot.resolve("a"));var other=new NativeFixture(nativeRoot.resolve("b"))) {
            var foreign=other.manager.getTemplateOrBlank(net.minecraft.util.Identifier.of("test:cache"));
            try(var lease=Minecraft121TemplateAccessGate.forManager(f.manager).trySnapshot()) {
                var permit=arrangedPermit(f,lease);
                rejects("TEMPLATE_TEMPLATE_OWNER_MISMATCH",()->((TemplateAccess)foreign).quantumchamber$captureSettings(permit));
                rejects("TEMPLATE_TEMPLATE_OWNER_MISMATCH",()->((TemplateAccess)new net.minecraft.structure.StructureTemplate()).quantumchamber$captureSettings(permit));
                rejects("TEMPLATE_MANAGER_OWNER_MISMATCH",()->((ManagerAccess)other.manager).quantumchamber$captureSettings(permit,other.session,net.minecraft.util.Identifier.of("test:cache")));
            }
        }
    }
    private static final net.minecraft.util.Identifier SOURCE_ID=net.minecraft.util.Identifier.of("test:source");
    private static java.nio.file.Path saved(NativeFixture f) throws Exception {
        var path=f.session.getDirectory(net.minecraft.util.WorldSavePath.GENERATED).resolve("test/structures/source.nbt");
        java.nio.file.Files.createDirectories(path.getParent());return path;
    }
    private static byte[] compressed(NbtCompound n) throws Exception {
        var out=new java.io.ByteArrayOutputStream();NbtIo.writeCompressed(n,out);return out.toByteArray();
    }
    private static net.minecraft.resource.ResourceManager sourceManager(net.minecraft.resource.InputSupplier<java.io.InputStream> supplier) {
        var pack=(net.minecraft.resource.ResourcePack)java.lang.reflect.Proxy.newProxyInstance(Minecraft121TemplateSettingsAdapterTest.class.getClassLoader(),
            new Class<?>[]{net.minecraft.resource.ResourcePack.class},(p,m,a)->m.getName().equals("getId")?"fixture":null);
        var resource=new net.minecraft.resource.Resource(pack,supplier);
        return (net.minecraft.resource.ResourceManager)java.lang.reflect.Proxy.newProxyInstance(Minecraft121TemplateSettingsAdapterTest.class.getClassLoader(),
            new Class<?>[]{net.minecraft.resource.ResourceManager.class},(p,m,a)->{
                if(m.getName().equals("getResource")){assertEquals("test:structure/source.nbt",a[0].toString());return Optional.of(resource);}
                throw new AssertionError("不應借用其它來源操作："+m.getName());
            });
    }
    @Test void savedPresentUsesNativeRebuildWithoutFillingCache() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var input=template("minecraft:furnace",history("iron_ingot",NbtInt.of(3)));NbtIo.writeCompressed(input,saved(f));
            var captured=Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID);
            assertEquals(SourceKind.PRESENT,captured.sourceKind());assertEquals(CacheKind.NO_ENTRY,captured.cacheKind());
            assertEquals(input.getList("blocks",10),captured.rebuilt().nativeNbt().getList("blocks",10));
            assertEquals("PRESENT",Minecraft121TemplateSettingsAdapter.resolveOwned(SOURCE_ID,Use.EXACT_SINGLE,captured).getString("Resolution"));
            assertEquals(CacheKind.NO_ENTRY,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID).cacheKind());
        }
    }
    @Test void allApplicableSourcesAbsentAllowsOnlyExactSingle() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var c=Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID);
            assertEquals(SourceKind.PROVEN_ABSENT,c.sourceKind());assertTrue(c.rebuilt().constructorBlank());
            assertEquals("ABSENT_NATIVE_BLANK",Minecraft121TemplateSettingsAdapter.resolveOwned(SOURCE_ID,Use.EXACT_SINGLE,c).getString("Resolution"));
            rejects("TEMPLATE_ABSENCE_UNSUPPORTED_USE",()->Minecraft121TemplateSettingsAdapter.resolveOwned(SOURCE_ID,Use.REQUIRED,c));
        }
    }
    @Test void savedErrorCannotFallbackToResource() throws Exception {
        try(var f=new NativeFixture(nativeRoot,sourceManager(()->{throw new AssertionError("高優先錯誤不得fallback");}))) {
            java.nio.file.Files.writeString(saved(f),"invalid compressed nbt");
            rejects("TEMPLATE_SOURCE_ERROR",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }
    @Test void savedParentIsFileCannotBecomeAbsence() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            java.nio.file.Files.writeString(f.session.getDirectory(net.minecraft.util.WorldSavePath.GENERATED),"not a directory");
            rejects("TEMPLATE_SOURCE_ERROR",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }
    @Test void resourcePresentOpenFnfOrIoIsError() throws Exception {
        for(boolean fnf:List.of(false,true))try(var f=new NativeFixture(nativeRoot.resolve("io-"+fnf),sourceManager(()->{
            if(fnf)throw new java.io.FileNotFoundException("present but cannot open");throw new java.io.IOException("broken resource");}))) {
            rejects("TEMPLATE_SOURCE_ERROR",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }
    @Test void resourcePresentAndSavedPriorityAreExact() throws Exception {
        byte[] data=compressed(blankOwned(0).nativeNbt());
        try(var f=new NativeFixture(nativeRoot,sourceManager(()->new java.io.ByteArrayInputStream(data)))) {
            var c=Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID);assertEquals(SourceKind.PRESENT,c.sourceKind());
            assertEquals(1,c.rebuilt().shape().getList("Palettes",9).size());
            var n=template("minecraft:furnace",history("iron_ingot",NbtInt.of(9)));NbtIo.writeCompressed(n,saved(f));
            assertEquals(n.getList("blocks",10),Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID).rebuilt().nativeNbt().getList("blocks",10));
        }
    }
    @Test void developmentSnbtPriorityAndMalformedSourceDoNotFallback() throws Exception {
        boolean previous=net.minecraft.SharedConstants.isDevelopment;String dir=net.minecraft.test.StructureTestUtil.testStructuresDirectoryName;
        net.minecraft.SharedConstants.isDevelopment=true;net.minecraft.test.StructureTestUtil.testStructuresDirectoryName=nativeRoot.resolve("dev").toString();
        try(var f=new NativeFixture(nativeRoot.resolve("world"),sourceManager(()->{throw new AssertionError("dev存在不得讀RM");}))) {
            var dev=nativeRoot.resolve("dev/source.snbt");java.nio.file.Files.createDirectories(dev.getParent());
            java.nio.file.Files.writeString(dev,NbtHelper.toNbtProviderString(blankOwned(0).nativeNbt()));
            assertEquals(SourceKind.PRESENT,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID).sourceKind());
            java.nio.file.Files.writeString(dev,"malformed{");rejects("TEMPLATE_SOURCE_ERROR",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
            NbtIo.writeCompressed(blankOwned(0).nativeNbt(),saved(f));
            assertEquals(SourceKind.PRESENT,Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID).sourceKind());
        }finally{net.minecraft.SharedConstants.isDevelopment=previous;net.minecraft.test.StructureTestUtil.testStructuresDirectoryName=dir;}
    }
    @Test void sourceContentChangeDuringCaptureRejected() throws Exception {
        byte[] first=compressed(blankOwned(0).nativeNbt()),second=compressed(template("minecraft:furnace",history("iron_ingot",NbtInt.of(1))));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var f=new NativeFixture(nativeRoot,sourceManager(()->new java.io.ByteArrayInputStream(calls.getAndIncrement()==0?first:second)))) {
            rejects("TEMPLATE_SOURCE_UNSTABLE",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }
    @Test void reloadDuringSourceReadRejectsAndReleasesLease() throws Exception {
        byte[] data=compressed(blankOwned(0).nativeNbt());var owner=new java.util.concurrent.atomic.AtomicReference<NativeFixture>();
        try(var f=new NativeFixture(nativeRoot,sourceManager(()->{owner.get().contexts.reloadStarted();return new java.io.ByteArrayInputStream(data);}))) {
            owner.set(f);assertEquals("CONTEXT_UNAVAILABLE",assertThrows(IllegalStateException.class,
                ()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID)).getMessage());
            try(var lease=Minecraft121TemplateAccessGate.forManager(f.manager).trySnapshot()){lease.requireWithinBudget();}
        }
    }

    private static void nativeField(Object owner,String name,Object value) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    @Test void foreignDataFixerAndBlockLookupBaseRejected() throws Exception {
        try(var f=new NativeFixture(nativeRoot.resolve("fixer"))) {
            var foreign=(com.mojang.datafixers.DataFixer)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{com.mojang.datafixers.DataFixer.class},(p,m,a)->{throw new AssertionError("不得使用foreign fixer");});
            nativeField(f.manager,"dataFixer",foreign);
            rejects("TEMPLATE_DATAFIXER_OWNER_MISMATCH",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
        try(var f=new NativeFixture(nativeRoot.resolve("lookup"))) {
            var foreign=new SimpleRegistry<>(RegistryKeys.BLOCK,com.mojang.serialization.Lifecycle.stable());
            nativeField(f.manager,"blockLookup",foreign.getReadOnlyWrapper().withFeatureFilter(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES));
            rejects("TEMPLATE_BLOCK_LOOKUP_OWNER_MISMATCH",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }
    @Test void wrongThreadAndClosedPermitCannotConsumeNativeFields() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var gate=Minecraft121TemplateAccessGate.forManager(f.manager);var lease=gate.trySnapshot();var permit=arrangedPermit(f,lease);
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                assertEquals("TEMPLATE_CAPTURE_PERMIT_INVALID",executor.submit(()->assertThrows(IllegalStateException.class,
                    ()->((ManagerAccess)f.manager).quantumchamber$captureSettings(permit,f.session,SOURCE_ID)).getMessage()).get());
            }finally{executor.shutdownNow();lease.close();}
            assertEquals("TEMPLATE_SNAPSHOT_NOT_READY",assertThrows(IllegalStateException.class,
                ()->((ManagerAccess)f.manager).quantumchamber$captureSettings(permit,f.session,SOURCE_ID)).getMessage());
        }
    }
    @Test void cachedTemplateMutationDuringSourceReadRejected() throws Exception {
        byte[] data=compressed(blankOwned(0).nativeNbt());var live=new java.util.concurrent.atomic.AtomicReference<net.minecraft.structure.StructureTemplate>();
        try(var f=new NativeFixture(nativeRoot,sourceManager(()->{live.get().setAuthor("changed");return new java.io.ByteArrayInputStream(data);}))) {
            var template=f.manager.createTemplate(blankOwned(0).nativeNbt());live.set(template);
            var field=f.manager.getClass().getDeclaredField("templates");field.setAccessible(true);
            @SuppressWarnings("unchecked") var cache=(java.util.Map<net.minecraft.util.Identifier,Optional<net.minecraft.structure.StructureTemplate>>)field.get(f.manager);
            cache.put(SOURCE_ID,Optional.of(template));
            rejects("TEMPLATE_CACHE_UNSTABLE",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }
    @Test void unknownResourceObservationCannotBecomeAbsence() throws Exception {
        var unknown=(net.minecraft.resource.ResourceManager)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.minecraft.resource.ResourceManager.class},(p,m,a)->null);
        try(var f=new NativeFixture(nativeRoot,unknown)) {
            rejects("TEMPLATE_SOURCE_UNKNOWN",()->Minecraft121TemplateSettingsAdapter.captureOwned(f.context,SOURCE_ID));
        }
    }

    @Test void isolatedCtorRejectsReconstructedEqualOrDifferentFeatureLookup() throws Exception {
        try(var f=new NativeFixture(nativeRoot)) {
            var lookupField=f.manager.getClass().getDeclaredField("blockLookup");lookupField.setAccessible(true);
            @SuppressWarnings("unchecked") var original=(RegistryEntryLookup<net.minecraft.block.Block>)lookupField.get(f.manager);
            var cacheField=f.manager.getClass().getDeclaredField("templates");cacheField.setAccessible(true);
            var verify=Arrays.stream(f.manager.getClass().getDeclaredMethods()).filter(m->m.getName().equals("quantumchamber$verifyIsolated")).findFirst().orElseThrow();verify.setAccessible(true);
            for(var flags:List.of(net.minecraft.resource.featuretoggle.FeatureFlags.DEFAULT_ENABLED_FEATURES,net.minecraft.resource.featuretoggle.FeatureSet.empty())) {
                var isolated=new net.minecraft.structure.StructureTemplateManager(f.resources,f.session,net.minecraft.datafixer.Schemas.getFixer(),original);
                nativeField(isolated,"blockLookup",Registries.BLOCK.getReadOnlyWrapper().withFeatureFilter(flags));
                var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->verify.invoke(f.manager,isolated,f.resources,
                    net.minecraft.datafixer.Schemas.getFixer(),original,f.session.getDirectory(net.minecraft.util.WorldSavePath.GENERATED).normalize(),cacheField.get(f.manager)));
                assertEquals("TEMPLATE_ISOLATED_SOURCE_MISMATCH",error.getCause().getMessage());
            }
        }
    }

}
