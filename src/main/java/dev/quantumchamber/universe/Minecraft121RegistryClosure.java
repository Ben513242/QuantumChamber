package dev.quantumchamber.universe;

import com.mojang.serialization.Codec;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.*;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.alias.StructurePoolAliasBinding;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;

/**
 * typed registry 傳遞層：noise parameters、受審End值與exact Single pool局部閉包。
 * 未admit的定義／consumer、inline entry與非direct alias仍拒絕，不是完整三role快照。
 * registries/resources 必須由呼叫端的同一次有效來源 lease 提供；本類不取得 live source。
 */
final class Minecraft121RegistryClosure {
    private record Work(Occurrence occurrence, RegistryKey<?> registryKey, TagKey<?> tag, boolean required,
            Identifier template,Minecraft121TemplateSettingsAdapter.Use use) {
        Work(Occurrence occurrence,RegistryKey<?> key,TagKey<?> tag,boolean required){this(occurrence,key,tag,required,null,null);}
    }
    private record ScopedDefinition(Key node,String scope) {}
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;
    private final ResourceManager resources;
    private final ArrayDeque<Work> pending = new ArrayDeque<>();
    private final Set<Key> definitions = new HashSet<>();
    private final Minecraft121NativeValueContracts nativeValues;
    private ServerSettingsContext context;
    private Minecraft121TemplateSettingsAdapter.Scope captureScope;
    private final Set<ScopedDefinition> poolExpanded=new HashSet<>(),templateExpanded=new HashSet<>();

    Minecraft121RegistryClosure(Minecraft121SettingsGraph graph, DynamicRegistryManager registries,
            ResourceManager resources) {
        this.graph=Objects.requireNonNull(graph);
        this.registries=Objects.requireNonNull(registries);
        this.resources=Objects.requireNonNull(resources);
        this.nativeValues=new Minecraft121NativeValueContracts(graph,registries,resources,
                (occurrence,key)->pending.addLast(new Work(occurrence,key,null,true)));
    }

    Minecraft121RegistryClosure(Minecraft121SettingsGraph graph,ServerSettingsContext context) {
        this(graph,(DynamicRegistryManager)Minecraft121SettingsContexts.sources(context).registries(),
                (ResourceManager)Minecraft121SettingsContexts.sources(context).resources());
        this.context=context;
    }
    void template(Key from,String path,Identifier id,Minecraft121TemplateSettingsAdapter.Use use,String aliasScope) {
        checked(()->{
            var target=new Key(Kind.TEMPLATE,"minecraft:structure",id.toString());
            var occurrence=graph.reference(from,path,target,aliasScope,use==null?"unknown":use.name());
            pending.addLast(new Work(occurrence,null,null,true,id,use));
        });
    }

    void reference(Key from, String path, RegistryKey<?> key) {
        checked(() -> enqueue(from,path,key,"ordinary","required"));
    }
    void entry(Key from, String path, RegistryEntry<?> entry) {
        checked(() -> {
            var key=entry.getKey().orElseThrow(() -> failure("UNSUPPORTED_INLINE_ENTRY"));
            require(actual(key)==entry,"REGISTRY_ENTRY_OWNER_MISMATCH");
            enqueue(from,path,key,"ordinary","entry");
        });
    }
    void tag(Key from, String path, TagKey<?> tag, boolean required) {
        checked(() -> {
            var target=new Key(Kind.TAG,tag.registry().getValue().toString(),tag.id().toString());
            var occurrence=graph.reference(from,path,target,"ordinary",required?"required-tag":"optional-tag");
            pending.addLast(new Work(occurrence,null,tag,required));
        });
    }

    /** source 只在固定 direct binding 的這個欄位當 symbolic；普通引用照常解析。 */
    void alias(Key from, String path, StructurePoolAliasBinding binding) {
        checked(() -> {
            require(binding!=null && binding.getClass().getName().equals(
                    "net.minecraft.structure.pool.alias.DirectStructurePoolAliasBinding"),"UNSUPPORTED_ALIAS_BINDING");
            nativeSource(binding.getClass());
            require(binding.getCodec()==Registries.POOL_ALIAS_BINDING.get(Identifier.ofVanilla("direct")),
                    "UNSUPPORTED_ALIAS_CODEC");
            var encoded=roundtrip(StructurePoolAliasBinding.CODEC,binding);
            require(encoded instanceof NbtCompound,"ALIAS_PAYLOAD_INVALID");
            var value=(NbtCompound)encoded;
            require(value.getKeys().equals(Set.of("type","alias","target"))
                    && value.contains("alias",NbtElement.STRING_TYPE) && value.contains("target",NbtElement.STRING_TYPE)
                    && value.getString("type").equals("minecraft:direct"),"ALIAS_PAYLOAD_INVALID");
            var source=Identifier.of(value.getString("alias"));
            var target=RegistryKey.of(RegistryKeys.TEMPLATE_POOL,Identifier.of(value.getString("target")));
            var targets=binding.streamTargets().limit(2).toList();
            require(targets.equals(List.of(target)),"ALIAS_TARGETS_CHANGED");
            String scope=from.kind()+"/"+from.registry()+"/"+from.id()+"/"+path;
            var symbolic=new Key(Kind.RESOLVED,RegistryKeys.TEMPLATE_POOL.getValue().toString(),source.toString());
            var occurrence=graph.reference(from,path+"/alias-source",symbolic,scope,"symbolic-source");
            var marker=new NbtCompound();marker.putString("Resolution","SYMBOLIC_ALIAS_SOURCE");
            graph.payload(symbolic,marker);graph.expanded(occurrence);
            enqueue(from,path+"/alias-target",target,scope,"alias-target");
        });
    }

    void drain() {
        checked(() -> {
            try(var scope=context==null?null:Minecraft121TemplateSettingsAdapter.open(context,graph)) {
                captureScope=scope;
                while(!pending.isEmpty()) {
                    if(scope!=null)scope.checkpoint();
                    Work work=pending.removeFirst();
                    if(work.template()!=null)expandTemplate(work);
                    else if(work.tag()!=null)expandTag(work);
                    else {
                        var entry=actual(work.registryKey());var target=work.occurrence().target();
                        if(Minecraft121NativeValueContracts.supported(work.registryKey()))
                            graph.payload(target,nativeValues.capture(work.registryKey(),entry.value()));
                        else if(work.registryKey().getRegistry().equals(RegistryKeys.TEMPLATE_POOL.getValue()))
                            expandPool(target,entry.value(),work.occurrence().aliasScope());
                        else if(definitions.add(target))expandDefinition(target,work.registryKey(),entry.value());
                    }
                    graph.expanded(work.occurrence());
                }
                if(scope!=null)scope.checkpoint();
            } finally {captureScope=null;}
        });
    }
    private void expandTemplate(Work work) {
        require(captureScope!=null,"TEMPLATE_CONTEXT_REQUIRED");var node=work.occurrence().target();
        graph.resource(node);
        var captured=captureScope.capture(work.template());
        var envelope=Minecraft121TemplateSettingsAdapter.resolveOwned(work.template(),work.use(),captured);
        graph.payload(node,envelope);
        var scoped=new ScopedDefinition(node,work.occurrence().aliasScope());
        if(templateExpanded.add(scoped)) {
            var values=new Minecraft121TemplateValueContracts(graph,registries,
                    (occurrence,key)->pending.addLast(new Work(occurrence,key,null,true)));
            values.captureEnvelope(node,envelope,work.occurrence().aliasScope());
        } else graph.decoded(Minecraft121TemplateValueContracts.measure(envelope.getCompound("NativeTemplate"),0));
        captureScope.checkpoint();
    }

    private void enqueue(Key from,String path,RegistryKey<?> key,String scope,String use) {
        var target=new Key(Kind.REGISTRY,key.getRegistry().toString(),key.getValue().toString());
        // 圖先計費／保留 occurrence，queue 才取得該筆；重複引用也不豁免預算。
        var occurrence=graph.reference(from,path,target,scope,use);
        pending.addLast(new Work(occurrence,key,null,true));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Registry<?> registry(RegistryKey<?> key) {
        Optional<?> result=registries.getOptional((RegistryKey)key);
        return (Registry<?>)result.orElseThrow(() -> failure("REQUIRED_REGISTRY_MISSING"));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private RegistryEntry.Reference<?> actual(RegistryKey<?> key) {
        var registry=(Registry)registry(RegistryKey.ofRegistry(key.getRegistry()));
        Optional<?> result=registry.getEntry((RegistryKey)key);
        return (RegistryEntry.Reference<?>)result.orElseThrow(() -> failure("REQUIRED_REGISTRY_TARGET_MISSING"));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private void expandTag(Work work) {
        var tag=work.tag();var registry=(Registry)registry(tag.registry());
        var source=Identifier.of(tag.id().getNamespace(),RegistryKeys.getTagPath(tag.registry())+"/"+tag.id().getPath()+".json");
        boolean present=!resources.getAllResources(source).isEmpty();
        require(!work.required()||present,"REQUIRED_TAG_MISSING");
        var optional=registry.getEntryList((TagKey)tag);
        require(!present||optional.isPresent(),"TAG_RESOLUTION_UNAVAILABLE");
        var payload=new NbtCompound();payload.putString("Presence",present?"PRESENT":"ABSENT");
        var members=new NbtList();
        if(optional.isPresent()) {
            var list=(RegistryEntryList.Named<?>)optional.get();
            require(list.ownerEquals(registry.getEntryOwner()),"TAG_OWNER_MISMATCH");
            require(present||list.size()==0,"TAG_SOURCE_MISMATCH");
            for(int i=0;i<list.size();i++) {
                var entry=list.get(i);var key=entry.getKey().orElseThrow(() -> failure("TAG_INLINE_MEMBER"));
                require(key.getRegistry().equals(tag.registry().getValue()) && actual(key)==entry,"TAG_MEMBER_OWNER_MISMATCH");
                enqueue(work.occurrence().target(),"members/"+i,key,"ordinary","ordered-tag-member");
                members.add(NbtString.of(key.getValue().toString()));
            }
        }
        payload.put("Members",members);
        graph.payload(work.occurrence().target(),payload);
    }

    private void expandDefinition(Key node, RegistryKey<?> key, Object value) {
        if(key.getRegistry().equals(RegistryKeys.NOISE_PARAMETERS.getValue())) {
            require(value!=null && value.getClass()==NoiseParameters.class,"NATIVE_DEFINITION_OWNER");
            nativeSource(value.getClass());var parameters=(NoiseParameters)value;
            require(parameters.amplitudes().size()<=64,"BUDGET_NATIVE_OCTAVES");
            for(double amplitude:parameters.amplitudes()) require(Double.isFinite(amplitude),"UNSUPPORTED_NONFINITE_NATIVE_VALUE");
            graph.payload(node,roundtrip(NoiseParameters.CODEC,parameters));
            return;
        }
        throw failure("UNSUPPORTED_REGISTRY_DEFINITION");
    }

    private void expandPool(Key node,Object value,String aliasScope) {
        require(value!=null&&value.getClass()==StructurePool.class,"NATIVE_DEFINITION_OWNER");nativeSource(value.getClass());
        var pool=(StructurePool)value;
        require(pool.getElementCount()>=0&&pool.getElementCount()<=131072,"BUDGET_POOL_ELEMENTS");
        // shuffled copy只用於完整actual element admission／多重集合核對；canonical次序沿CODEC原始列表。
        var actual=pool.getElementIndicesInRandomOrder(net.minecraft.util.math.random.Random.create(0L));
        require(actual.size()==pool.getElementCount(),"POOL_ELEMENTS_CHANGED");
        var counts=new HashMap<NbtCompound,Integer>();
        for(var element:actual) {
            if(captureScope!=null)captureScope.checkpoint();
            require(element!=null&&element.getClass()==net.minecraft.structure.pool.SinglePoolElement.class,"UNSUPPORTED_POOL_ELEMENT");
            nativeSource(element.getClass());var single=(net.minecraft.structure.pool.SinglePoolElement)element;
            NbtElement encoded;
            try {encoded=roundtrip(net.minecraft.structure.pool.SinglePoolElement.CODEC.codec(),single);}
            catch(RuntimeException bad){var failure=failure("UNSUPPORTED_SINGLE_LOCATION");failure.initCause(bad);throw failure;}
            require(encoded instanceof NbtCompound,"UNSUPPORTED_SINGLE_LOCATION");var fields=(NbtCompound)encoded;
            require(fields.get("location") instanceof NbtString,"UNSUPPORTED_SINGLE_LOCATION");
            require(single.getType()==net.minecraft.structure.pool.StructurePoolElementType.SINGLE_POOL_ELEMENT,"UNSUPPORTED_POOL_ELEMENT");
            counts.merge(fields,1,Integer::sum);
        }
        var encoded=roundtrip(StructurePool.CODEC,pool);
        require(encoded instanceof NbtCompound&&((NbtCompound)encoded).get("elements") instanceof NbtList,"POOL_PAYLOAD_INVALID");
        var payload=(NbtCompound)encoded;graph.payload(node,payload);
        var elements=payload.getList("elements",NbtElement.COMPOUND_TYPE);
        for(int i=0;i<elements.size();i++) {
            var item=elements.getCompound(i);require(item.get("weight") instanceof NbtInt&&item.get("element") instanceof NbtCompound,"POOL_PAYLOAD_INVALID");
            var fields=item.getCompound("element").copy();require(fields.getString("element_type").equals("minecraft:single_pool_element"),"UNSUPPORTED_POOL_ELEMENT");
            fields.remove("element_type");int weight=item.getInt("weight");Integer count=counts.get(fields);
            require(weight>0&&count!=null&&count>=weight,"POOL_ELEMENTS_CHANGED");counts.put(fields,count-weight);
            var id=Identifier.tryParse(fields.getString("location"));require(id!=null&&id.toString().equals(fields.getString("location")),"UNSUPPORTED_SINGLE_LOCATION");
            template(node,"elements/"+i+"/location",id,Minecraft121TemplateSettingsAdapter.Use.EXACT_SINGLE,aliasScope);
            var processors=fields.get("processors");
            var empty=net.minecraft.structure.processor.StructureProcessorType.REGISTRY_CODEC.encodeStart(registries.getOps(NbtOps.INSTANCE),
                    RegistryEntry.of(new net.minecraft.structure.processor.StructureProcessorList(List.of()))).getOrThrow();
            if(processors instanceof NbtString name)enqueue(node,"elements/"+i+"/processors",RegistryKey.of(RegistryKeys.PROCESSOR_LIST,Identifier.of(name.asString())),aliasScope,"required-processors");
            else require(empty.equals(processors),"UNSUPPORTED_SINGLE_PROCESSORS");
        }
        require(counts.values().stream().allMatch(n->n==0),"POOL_ELEMENTS_CHANGED");
        // 每use都驗Single與template；只對相同node/scope的遞迴fallback停止展開，保留循環邊。
        if(poolExpanded.add(new ScopedDefinition(node,aliasScope))) {
            var fallback=pool.getFallback();var key=fallback.getKey().orElseThrow(()->failure("UNSUPPORTED_INLINE_ENTRY"));
            require(actual(key)==fallback,"REGISTRY_ENTRY_OWNER_MISMATCH");enqueue(node,"fallback",key,aliasScope,"pool-fallback");
        }
    }

    private <T> NbtElement roundtrip(Codec<T> codec,T value) {
        var ops=registries.getOps(NbtOps.INSTANCE);
        var encoded=codec.encodeStart(ops,value).getOrThrow();
        var rebuilt=codec.parse(ops,encoded).getOrThrow();
        require(encoded.equals(codec.encodeStart(ops,rebuilt).getOrThrow()),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
        return encoded;
    }
    private void nativeSource(Class<?> type) {
        require(type.getProtectionDomain().getCodeSource()!=null && SharedConstants.class.getProtectionDomain().getCodeSource()!=null
                && type.getProtectionDomain().getCodeSource().getLocation().equals(
                SharedConstants.class.getProtectionDomain().getCodeSource().getLocation()),"NATIVE_DEFINITION_SOURCE");
    }
    private void checked(Runnable action) {
        try { graph.requireOpen();action.run(); }
        catch(RuntimeException|Error error) { graph.invalidate();pending.clear();throw error; }
    }
    private static Failure failure(String code) { return new Failure(code); }
    private static void require(boolean value,String code) { if(!value) throw failure(code); }
}
