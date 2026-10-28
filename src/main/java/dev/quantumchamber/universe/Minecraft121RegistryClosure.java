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
 * C1b 的 typed registry 傳遞層。接納原生 noise parameters、空 pool 與 End 局部值；
 * End 的 base_3d_noise 仍未支援；其他定義、inline entry 與非 direct alias 具名拒絕，不能作完整設定快照。
 * registries/resources 必須由呼叫端的同一次有效來源 lease 提供；本類不取得 live source。
 */
final class Minecraft121RegistryClosure {
    private record Work(Occurrence occurrence, RegistryKey<?> registryKey, TagKey<?> tag, boolean required) {}
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;
    private final ResourceManager resources;
    private final ArrayDeque<Work> pending = new ArrayDeque<>();
    private final Set<Key> definitions = new HashSet<>();
    private final Minecraft121NativeValueContracts nativeValues;

    Minecraft121RegistryClosure(Minecraft121SettingsGraph graph, DynamicRegistryManager registries,
            ResourceManager resources) {
        this.graph=Objects.requireNonNull(graph);
        this.registries=Objects.requireNonNull(registries);
        this.resources=Objects.requireNonNull(resources);
        this.nativeValues=new Minecraft121NativeValueContracts(graph,registries,resources,
                (occurrence,key)->pending.addLast(new Work(occurrence,key,null,true)));
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
            while(!pending.isEmpty()) {
                Work work=pending.removeFirst();
                if(work.tag()!=null) expandTag(work);
                else {
                    var entry=actual(work.registryKey());
                    var target=work.occurrence().target();
                    if(Minecraft121NativeValueContracts.supported(work.registryKey()))
                        graph.payload(target,nativeValues.capture(work.registryKey(),entry.value()));
                    else if(definitions.add(target)) expandDefinition(target,work.registryKey(),entry.value());
                }
                graph.expanded(work.occurrence());
            }
        });
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
        if(key.getRegistry().equals(RegistryKeys.TEMPLATE_POOL.getValue())) {
            require(value!=null && value.getClass()==StructurePool.class,"NATIVE_DEFINITION_OWNER");
            nativeSource(value.getClass());var pool=(StructurePool)value;
            require(pool.getElementCount()==0,"UNSUPPORTED_POOL_ELEMENTS");
            var encoded=roundtrip(StructurePool.CODEC,pool);
            require(encoded instanceof NbtCompound && ((NbtCompound)encoded).contains("elements",NbtElement.LIST_TYPE)
                    && ((NbtCompound)encoded).getList("elements",NbtElement.COMPOUND_TYPE).isEmpty(),"UNSUPPORTED_POOL_ELEMENTS");
            graph.payload(node,encoded);
            var fallback=pool.getFallback();
            var fallbackKey=fallback.getKey().orElseThrow(() -> failure("UNSUPPORTED_INLINE_ENTRY"));
            require(actual(fallbackKey)==fallback,"REGISTRY_ENTRY_OWNER_MISMATCH");
            enqueue(node,"fallback",fallbackKey,"ordinary","pool-fallback");
            return;
        }
        throw failure("UNSUPPORTED_REGISTRY_DEFINITION");
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
