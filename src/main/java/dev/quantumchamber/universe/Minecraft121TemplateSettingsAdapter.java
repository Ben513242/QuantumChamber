package dev.quantumchamber.universe;

import java.util.Set;
import net.minecraft.nbt.*;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelStorage;

/** 固定1.21 template 擷取邊界；未完成的來源／lease路徑一律拒絕。 */
public final class Minecraft121TemplateSettingsAdapter {
    private Minecraft121TemplateSettingsAdapter() {}
    public enum CacheKind { UNOBSERVABLE, NO_ENTRY, NEGATIVE, PRESENT_CONSTRUCTOR_BLANK, OTHER_PRESENT }
    public enum SourceKind { PRESENT, PROVEN_ABSENT, ERROR, UNKNOWN }
    enum Use { EXACT_SINGLE, REQUIRED, UNKNOWN }
    public static final class OwnedTemplate {
        private final NbtCompound shape,nativeNbt;
        public OwnedTemplate(NbtCompound shape,NbtCompound nativeNbt) {
            this.shape=shape.copy();this.nativeNbt=nativeNbt.copy();
        }
        public NbtCompound shape() {return shape.copy();}
        public NbtCompound nativeNbt() {return nativeNbt.copy();}
        public boolean sameValue(OwnedTemplate other) {return other!=null&&shape.equals(other.shape)&&nativeNbt.equals(other.nativeNbt);}
        public boolean constructorBlank() {try{blank(this);return true;}catch(Minecraft121SettingsGraph.Failure rejected){return false;}}
    }
    public record Capture(CacheKind cacheKind,SourceKind sourceKind,OwnedTemplate cacheValue,OwnedTemplate rebuilt) {}
    public interface ManagerAccess {
        Capture quantumchamber$captureSettings(CapturePermit permit,LevelStorage.Session session,Identifier id);
        OwnedTemplate quantumchamber$rebuildSettings(CapturePermit permit,LevelStorage.Session session,NbtCompound raw,boolean absent);
    }
    public interface TemplateAccess {
        OwnedTemplate quantumchamber$captureSettings(CapturePermit permit);
    }
    /** 建構權限只屬Adapter；消費端沒有可取得raw欄位的getter。 */
    public static final class CapturePermit {
        private ServerSettingsContext context;
        private Minecraft121SettingsContexts.Sources sources;
        private StructureTemplateManager manager;
        private Minecraft121TemplateAccessGate gate;
        private Minecraft121TemplateAccessGate.Lease lease;
        private final Thread thread=Thread.currentThread();
        private volatile boolean closed;
        private long ownedBytes;
        private Minecraft121SettingsGraph graph;
        private StructureTemplate isolatedTemplate;
        private StructureTemplateManager isolatedManager;
        private boolean isolatedBlank;
        private CapturePermit(ServerSettingsContext context,StructureTemplateManager manager,
                Minecraft121TemplateAccessGate gate,Minecraft121TemplateAccessGate.Lease lease) {
            this.context=context;this.sources=Minecraft121SettingsContexts.sources(context);
            this.manager=manager;this.gate=gate;this.lease=lease;
            require(this.sources.templates()==manager,"TEMPLATE_MANAGER_OWNER_MISMATCH");
        }
        public void checkpoint() {
            require(!closed&&Thread.currentThread()==thread,"TEMPLATE_CAPTURE_PERMIT_INVALID");
            lease.requireWithinBudget();context.requireValid();
        }
        public void requireManager(StructureTemplateManager value) {
            checkpoint();require(value==manager&&gate==Minecraft121TemplateAccessGate.forManager(value),"TEMPLATE_MANAGER_OWNER_MISMATCH");
            nativeType(value,StructureTemplateManager.class);
        }
        public void requireTemplate(StructureTemplate value) {
            requireManager(manager);nativeType(value,StructureTemplate.class);
            if(value==isolatedTemplate) {
                require(!gate.owns(value),"TEMPLATE_ISOLATION_SHARED");
                if(!isolatedBlank)require(Minecraft121TemplateAccessGate.forManager(isolatedManager).owns(value),"TEMPLATE_ISOLATED_OWNER_MISMATCH");
                return;
            }
            require(gate.owns(value),"TEMPLATE_TEMPLATE_OWNER_MISMATCH");
        }
        public void requireSession(LevelStorage.Session session) {checkpoint();require(session==sources.session(),"TEMPLATE_SESSION_OWNER_MISMATCH");}
        /** 只比identity，不回傳context的借用來源。 */
        public void requireResources(Object value) {checkpoint();require(value==sources.resources(),"TEMPLATE_RESOURCE_OWNER_MISMATCH");}
        public void requireBlockBase(Object value) {
            checkpoint();require(sources.registries() instanceof net.minecraft.registry.DynamicRegistryManager,"TEMPLATE_REGISTRY_CONTEXT_UNSUPPORTED");
            var registries=(net.minecraft.registry.DynamicRegistryManager)sources.registries();
            require(value==registries.get(net.minecraft.registry.RegistryKeys.BLOCK).getReadOnlyWrapper(),"TEMPLATE_BLOCK_LOOKUP_OWNER_MISMATCH");
        }
        public void chargeSource(long bytes) {checkpoint();if(graph!=null)graph.decoded(bytes);}
        public void chargeOwned(long bytes) {checkpoint();require(bytes>=0&&bytes<=134217728-ownedBytes,"BUDGET_OWNED_BYTES");ownedBytes+=bytes;}
        /** Mixin合併到原生package後的同步bridge；只允許已審manager消費者直接呼叫，沒有對外登記入口。 */
        public OwnedTemplate captureIsolated(StructureTemplateManager consumer,StructureTemplateManager isolated,StructureTemplate template,boolean blank) {
            requireManager(consumer);
            require(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass()==StructureTemplateManager.class,
                    "TEMPLATE_ISOLATED_CALLER_REJECTED");
            nativeType(isolated,StructureTemplateManager.class);nativeType(template,StructureTemplate.class);
            require(isolated!=manager&&!gate.owns(template),"TEMPLATE_ISOLATION_SHARED");
            require(isolatedTemplate==null,"TEMPLATE_ISOLATED_SCOPE_BUSY");
            if(!blank)require(Minecraft121TemplateAccessGate.forManager(isolated).owns(template),"TEMPLATE_ISOLATED_OWNER_MISMATCH");
            isolatedManager=isolated;isolatedTemplate=template;isolatedBlank=blank;
            try {return ((TemplateAccess)template).quantumchamber$captureSettings(this);}
            finally {isolatedManager=null;isolatedTemplate=null;isolatedBlank=false;}
        }
        private void invalidate() {closed=true;isolatedManager=null;isolatedTemplate=null;context=null;sources=null;manager=null;gate=null;lease=null;}
    }
    /** 整個typed traversal共用一次live lease；此scope不外傳permit或借用來源。 */
    static final class Scope implements AutoCloseable {
        private final Minecraft121TemplateAccessGate.Lease lease;
        private final CapturePermit permit;
        private Scope(ServerSettingsContext context,Minecraft121SettingsGraph graph) {
            var sources=Minecraft121SettingsContexts.sources(context);
            nativeType(sources.templates(),StructureTemplateManager.class);nativeType(sources.session(),LevelStorage.Session.class);
            var manager=(StructureTemplateManager)sources.templates();var gate=Minecraft121TemplateAccessGate.forManager(manager);
            lease=gate.trySnapshot();
            try {permit=new CapturePermit(context,manager,gate,lease);permit.graph=graph;}
            catch(RuntimeException|Error error){lease.close();throw error;}
        }
        Capture capture(Identifier id) {
            permit.checkpoint();return ((ManagerAccess)permit.manager).quantumchamber$captureSettings(permit,
                    (LevelStorage.Session)permit.sources.session(),id);
        }
        void checkpoint() {permit.checkpoint();}
        @Override public void close(){permit.invalidate();lease.close();}
    }
    static Scope open(ServerSettingsContext context,Minecraft121SettingsGraph graph){return new Scope(context,graph);}
    static Capture captureOwned(ServerSettingsContext context,Identifier id) {
        var sources=Minecraft121SettingsContexts.sources(context);
        nativeType(sources.templates(),StructureTemplateManager.class);
        nativeType(sources.session(),LevelStorage.Session.class);
        var manager=(StructureTemplateManager)sources.templates();var gate=Minecraft121TemplateAccessGate.forManager(manager);
        try(var lease=gate.trySnapshot()) {
            var permit=new CapturePermit(context,manager,gate,lease);
            try {return ((ManagerAccess)manager).quantumchamber$captureSettings(permit,(LevelStorage.Session)sources.session(),id);}
            finally {permit.invalidate();}
        }
    }
    public static void nativeType(Object value,Class<?> expected) {
        require(value!=null&&value.getClass()==expected,"TEMPLATE_NATIVE_OWNER");nativeSource(expected);
    }
    public static void nativeSource(Class<?> type) {
        var source=type.getProtectionDomain().getCodeSource();var nativeSource=net.minecraft.SharedConstants.class.getProtectionDomain().getCodeSource();
        require(source!=null&&nativeSource!=null&&source.getLocation().equals(nativeSource.getLocation()),"TEMPLATE_NATIVE_SOURCE");
    }
    public static IllegalStateException rejected(String code) {return failure(code);}
    static OwnedTemplate rebuildOwned(ServerSettingsContext context,NbtCompound raw,boolean absent) {
        var sources=Minecraft121SettingsContexts.sources(context);nativeType(sources.templates(),StructureTemplateManager.class);
        nativeType(sources.session(),LevelStorage.Session.class);
        var manager=(StructureTemplateManager)sources.templates();var gate=Minecraft121TemplateAccessGate.forManager(manager);
        try(var lease=gate.trySnapshot()) {
            var permit=new CapturePermit(context,manager,gate,lease);
            try {return ((ManagerAccess)manager).quantumchamber$rebuildSettings(permit,(LevelStorage.Session)sources.session(),raw,absent);}
            finally {permit.invalidate();}
        }
    }
    /** 已owned的NBT值先界限檢查，才交隔離原生reader複製。 */
    public static void requireOwnedNbt(NbtCompound value) {require(value!=null,"TEMPLATE_REBUILD_INPUT_MISSING");Minecraft121TemplateValueContracts.measure(value,0);}
    static NbtCompound resolveOwned(Identifier id,Use use,Capture capture) {
        require(id!=null&&capture!=null&&capture.sourceKind()!=null&&capture.cacheKind()!=null,"TEMPLATE_CAPTURE_INVALID");
        if(capture.sourceKind()==SourceKind.ERROR)throw failure("TEMPLATE_SOURCE_ERROR");
        if(capture.sourceKind()==SourceKind.UNKNOWN)throw failure("TEMPLATE_SOURCE_UNKNOWN");
        require(capture.cacheKind()!=CacheKind.UNOBSERVABLE,"TEMPLATE_CACHE_UNOBSERVABLE");
        require(capture.rebuilt()!=null,"TEMPLATE_REBUILD_MISSING");
        shape(capture.rebuilt());
        if(capture.sourceKind()==SourceKind.PROVEN_ABSENT) {
            require(use==Use.EXACT_SINGLE,"TEMPLATE_ABSENCE_UNSUPPORTED_USE");
            blank(capture.rebuilt());
            require(capture.cacheKind()!=CacheKind.OTHER_PRESENT,"TEMPLATE_CACHE_SOURCE_MISMATCH");
            if(capture.cacheKind()==CacheKind.PRESENT_CONSTRUCTOR_BLANK)blank(capture.cacheValue());
            else require(capture.cacheValue()==null,"TEMPLATE_CACHE_SOURCE_MISMATCH");
        } else {
            require(use!=null&&use!=Use.UNKNOWN,"TEMPLATE_USE_UNSUPPORTED");
            require(capture.cacheKind()==CacheKind.NO_ENTRY||capture.cacheKind()==CacheKind.OTHER_PRESENT,"TEMPLATE_CACHE_SOURCE_MISMATCH");
            if(capture.cacheKind()==CacheKind.OTHER_PRESENT) {
                require(capture.cacheValue()!=null,"TEMPLATE_CACHE_REBUILD_MISMATCH");shape(capture.cacheValue());
                require(capture.cacheValue().shape.equals(capture.rebuilt().shape)
                        &&capture.cacheValue().nativeNbt.equals(capture.rebuilt().nativeNbt),"TEMPLATE_CACHE_REBUILD_MISMATCH");
            } else require(capture.cacheValue()==null,"TEMPLATE_CACHE_SOURCE_MISMATCH");
        }
        var envelope=new NbtCompound();envelope.putString("Contract","quantumchamber:m6-template-resolution-v1");
        envelope.putString("TemplateId",id.toString());
        envelope.putString("Resolution",capture.sourceKind()==SourceKind.PRESENT?"PRESENT":"ABSENT_NATIVE_BLANK");
        envelope.put("NativeTemplate",capture.rebuilt().nativeNbt());return readEnvelope(envelope);
    }
    static NbtCompound readEnvelope(NbtCompound envelope) {
        require(envelope!=null&&envelope.getKeys().equals(Set.of("Contract","TemplateId","Resolution","NativeTemplate")),"TEMPLATE_ENVELOPE_INVALID");
        require(envelope.get("Contract") instanceof NbtString&&envelope.get("TemplateId") instanceof NbtString
                &&envelope.get("Resolution") instanceof NbtString&&envelope.get("NativeTemplate") instanceof NbtCompound,"TEMPLATE_ENVELOPE_INVALID");
        require(envelope.getString("Contract").equals("quantumchamber:m6-template-resolution-v1")
                &&Set.of("PRESENT","ABSENT_NATIVE_BLANK").contains(envelope.getString("Resolution")),"TEMPLATE_ENVELOPE_INVALID");
        var id=Identifier.tryParse(envelope.getString("TemplateId"));
        require(id!=null&&id.toString().equals(envelope.getString("TemplateId")),"TEMPLATE_ENVELOPE_INVALID");
        return envelope.copy();
    }
    private static void shape(OwnedTemplate value) {
        var shape=value.shape;
        require(shape.getKeys().equals(Set.of("Palettes","Entities","Size","Author"))
                &&shape.get("Palettes") instanceof NbtList&&shape.get("Entities") instanceof NbtList
                &&shape.get("Size") instanceof NbtList&&shape.get("Author") instanceof NbtString,"TEMPLATE_SHAPE_INVALID");
        var size=(NbtList)shape.get("Size");require(size.size()==3&&size.getHeldType()==NbtElement.INT_TYPE,"TEMPLATE_SHAPE_INVALID");
    }
    private static void blank(OwnedTemplate value) {
        require(value!=null,"TEMPLATE_CONSTRUCTOR_BLANK_MISMATCH");shape(value);var s=value.shape;
        var size=(NbtList)s.get("Size");
        require(((NbtList)s.get("Palettes")).isEmpty()&&((NbtList)s.get("Entities")).isEmpty()
                &&size.getInt(0)==0&&size.getInt(1)==0&&size.getInt(2)==0&&s.getString("Author").equals("?")
                &&value.nativeNbt.equals(new StructureTemplate().writeNbt(new NbtCompound())),"TEMPLATE_CONSTRUCTOR_BLANK_MISMATCH");
    }
    private static void require(boolean value,String code) {if(!value)throw failure(code);}
    private static Minecraft121SettingsGraph.Failure failure(String code) {return new Minecraft121SettingsGraph.Failure(code);}
    private static Minecraft121SettingsGraph.Failure unavailable() {
        return new Minecraft121SettingsGraph.Failure("TEMPLATE_ADAPTER_UNAVAILABLE");
    }
}
