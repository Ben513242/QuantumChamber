package dev.quantumchamber.universe;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.*;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.DefaultResourcePack;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.densityfunction.*;
import dev.quantumchamber.mixin.DensityBinarySettingsAccess;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.resource.ResourceManager;

/** 固定1.21 End局部值契約；不宣稱完整End資料閉包或snapshot。 */
public final class Minecraft121NativeValueContracts {
    public interface SimplexStateAccess { SimplexStateSeal quantumchamber$captureSimplexState(); }
    public interface EndStateAccess { EndStateSeal quantumchamber$captureEndState(); }
    /** 身分只可比較；原陣列不提供getter，bytes入出皆複製。 */
    public static final class SimplexStateSeal {
        private final Object array;
        private final byte[] bytes;
        private SimplexStateSeal(Object array, byte[] bytes) {
            this.array=Objects.requireNonNull(array); this.bytes=bytes.clone();
        }
        public static SimplexStateSeal capture(Object array, byte[] bytes) { return new SimplexStateSeal(array,bytes); }
        public byte[] bytes() { return bytes.clone(); }
        public boolean sameIdentity(SimplexStateSeal other) { return array==other.array; }
        public boolean independentIdentity(SimplexStateSeal other) { return array!=other.array; }
    }
    public static final class EndStateSeal {
        private final Object owner, sampler;
        private final SimplexStateSeal state;
        private EndStateSeal(Object owner,Object sampler,SimplexStateSeal state) {
            this.owner=Objects.requireNonNull(owner);this.sampler=Objects.requireNonNull(sampler);this.state=Objects.requireNonNull(state);
        }
        public static EndStateSeal capture(Object owner,Object sampler,SimplexStateSeal state) { return new EndStateSeal(owner,sampler,state); }
        public byte[] bytes() { return state.bytes(); }
        public boolean sameIdentity(EndStateSeal other) { return owner==other.owner && sampler==other.sampler && state.sameIdentity(other.state); }
        public boolean independentIdentity(EndStateSeal other) { return owner!=other.owner && sampler!=other.sampler && state.independentIdentity(other.state); }
    }
    private static final RegistryKey<ChunkGeneratorSettings> END_SETTINGS=RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS,Identifier.ofVanilla("end"));
    private static final RegistryKey<DensityFunction> SLOPED=RegistryKey.of(RegistryKeys.DENSITY_FUNCTION,Identifier.ofVanilla("end/sloped_cheese"));
    private static final RegistryKey<DensityFunction> BASE=RegistryKey.of(RegistryKeys.DENSITY_FUNCTION,Identifier.ofVanilla("end/base_3d_noise"));
    /** 延遲到bootstrap之後，以固定公開factory取得精確Class；不依runtime mapping的類名。 */
    private static final class NativeTypes {
        static final Class<?> END=DensityFunctionTypes.endIslands(0L).getClass();
        static final Class<?> WRAPPING=DensityFunctionTypes.cache2d(DensityFunctionTypes.zero()).getClass();
        static final Class<?> BINARY=DensityFunctionTypes.add(DensityFunctionTypes.endIslands(0L),
                DensityFunctionTypes.cache2d(DensityFunctionTypes.endIslands(0L))).getClass();
    }
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;
    private final ResourceManager resources;
    private final java.util.function.BiConsumer<Occurrence,RegistryKey<?>> references;
    private ChunkGeneratorSettings originalSettings;
    private DensityFunction[] endpoints;
    private EndStateSeal[] states;

    Minecraft121NativeValueContracts(Minecraft121SettingsGraph graph,DynamicRegistryManager registries,ResourceManager resources) {
        this(graph,registries,resources,(occurrence,key)->{});
    }
    Minecraft121NativeValueContracts(Minecraft121SettingsGraph graph,DynamicRegistryManager registries,ResourceManager resources,
            java.util.function.BiConsumer<Occurrence,RegistryKey<?>> references) {
        this.graph=Objects.requireNonNull(graph);this.registries=Objects.requireNonNull(registries);
        this.resources=Objects.requireNonNull(resources);this.references=Objects.requireNonNull(references);
    }
    static boolean supported(RegistryKey<?> key) { return key.equals(END_SETTINGS)||key.equals(SLOPED); }
    NbtElement capture(RegistryKey<?> key,Object value) {
        try {
            graph.requireOpen();require(supported(key),"UNSUPPORTED_NATIVE_VALUE");
            require(actual(key).value()==value,"NATIVE_DEFINITION_OWNER");
            var captured=key.equals(END_SETTINGS)?settings(value):sloped(value);
            require(actual(key).value()==value,"NATIVE_END_ORIGINAL_IDENTITY_CHANGED");return captured;
        } catch(RuntimeException|Error error) { graph.invalidate();throw error; }
    }
    private NbtElement settings(Object value) {
        nativeType(value,ChunkGeneratorSettings.class);var settings=(ChunkGeneratorSettings)value;
        byte[] before=source(END_SETTINGS,3923,"8f779257fd0a4237e38cf09c7e8057152b73d1ae0c392cefbd21b1b10763daaf");
        DensityFunction[] found=paths(settings);var captured=new EndStateSeal[2];var list=new NbtList();
        for(int i=0;i<2;i++) { captured[i]=verifyEnd(found[i]);list.add(new NbtByteArray(captured[i].bytes())); }
        var encoded=codec(ChunkGeneratorSettings.CODEC,settings,END_SETTINGS,SLOPED);
        sourceMatches(ChunkGeneratorSettings.CODEC,encoded,before);
        // 這是固定1.21 final_density 的typed欄位，不掃描任意id字串。
        NbtElement cursor=((NbtCompound)encoded).getCompound("noise_router").get("final_density");
        for(String field:List.of("argument","argument2","argument","argument","argument2","argument2","argument2","argument2","argument2","argument2")) {
            require(cursor instanceof NbtCompound,"END_DEPENDENCY_TYPED_PATH");cursor=((NbtCompound)cursor).get(field);
        }
        require(cursor instanceof NbtString && cursor.asString().equals(SLOPED.getValue().toString()),"END_DEPENDENCY_TYPED_PATH");
        edge(END_SETTINGS,"typed-entry-data",SLOPED);
        verifyStable(settings,found,captured);
        require(Arrays.equals(before,source(END_SETTINGS,3923,"8f779257fd0a4237e38cf09c7e8057152b73d1ae0c392cefbd21b1b10763daaf")),"UNSUPPORTED_END_SOURCE");
        verifyStable(settings,found,captured);
        originalSettings=settings;endpoints=found;states=captured;
        var result=new NbtCompound();result.put("NativeCodec",encoded);result.put("EndStates",list);return result;
    }
    private NbtElement sloped(Object value) {
        require(originalSettings!=null,"UNSUPPORTED_END_DEPENDENCY_ORDER");
        verifyStable(originalSettings,endpoints,states);
        byte[] before=source(SLOPED,133,"175954c7e03469b790e047f16358e0c0a6a7804030c34cf160a9361219d67c25");
        var binary=binary((DensityFunction)value,"add");
        var first=direct(binary.quantumchamber$argument1(),NativeTypes.END);
        int index=first==endpoints[0]?0:first==endpoints[1]?1:-1;
        require(index>=0,"UNSUPPORTED_END_DEPENDENCY_IDENTITY");
        var second=binary.quantumchamber$argument2();nativeType(second,DensityFunctionTypes.RegistryEntryHolder.class);
        var entry=((DensityFunctionTypes.RegistryEntryHolder)second).function();
        require(entry.getClass()==RegistryEntry.Reference.class && entry.getKey().filter(BASE::equals).isPresent(),"END_DEPENDENCY_ARGUMENT2_SHAPE");
        require(actual(BASE)==entry,"REGISTRY_ENTRY_OWNER_MISMATCH");
        var state=verifyEnd(first);require(state.sameIdentity(states[index]),"NATIVE_END_ORIGINAL_IDENTITY_CHANGED");
        var encoded=codec(DensityFunctionTypes.CODEC,(DensityFunction)value,SLOPED,BASE);
        sourceMatches(DensityFunctionTypes.CODEC,encoded,before);edge(SLOPED,"typed-entry-data",BASE);
        verifyStable(originalSettings,endpoints,states);
        require(Arrays.equals(before,source(SLOPED,133,"175954c7e03469b790e047f16358e0c0a6a7804030c34cf160a9361219d67c25")),"UNSUPPORTED_END_SOURCE");
        verifyStable(originalSettings,endpoints,states);
        var result=new NbtCompound();result.put("NativeCodec",encoded);result.putByteArray("EndState",state.bytes());return result;
    }
    private DensityFunction[] paths(ChunkGeneratorSettings settings) {
        nativeType(settings.noiseRouter(),net.minecraft.world.gen.noise.NoiseRouter.class);
        var erosion=cache(direct(settings.noiseRouter().erosion(),NativeTypes.WRAPPING));
        DensityFunction current=direct(settings.noiseRouter().initialDensityWithoutJaggedness(),NativeTypes.BINARY);
        String[] operators={"add","mul","add","add","mul","add","add"};
        for(int i=0;i<operators.length;i++) current=direct(binary(current,operators[i]).quantumchamber$argument2(),
                i==operators.length-1?NativeTypes.WRAPPING:NativeTypes.BINARY);
        return new DensityFunction[]{erosion,cache(current)};
    }
    private DensityFunction cache(DensityFunction value) {
        nativeType(value,NativeTypes.WRAPPING);require(value instanceof DensityFunctionTypes.Wrapper,"END_OWNER_PATH_UNRESOLVED");
        var wrapping=(DensityFunctionTypes.Wrapper)value;
        Object type=wrapping.type();Object expected=((DensityFunctionTypes.Wrapper)DensityFunctionTypes.cache2d(DensityFunctionTypes.zero())).type();
        require(type==expected,"END_OWNER_PATH_UNRESOLVED");
        nativeType(type,expected.getClass());
        require(type instanceof Enum<?> named && named.name().equals("Cache2D"),"END_OWNER_PATH_UNRESOLVED");
        codecIdentity(value,"cache_2d");return direct(wrapping.wrapped(),NativeTypes.END);
    }
    private DensityBinarySettingsAccess binary(DensityFunction value,String operator) {
        nativeType(value,NativeTypes.BINARY);codecIdentity(value,operator);
        require(value instanceof DensityBinarySettingsAccess,"NATIVE_END_BRIDGE_MISSING");return (DensityBinarySettingsAccess)value;
    }
    private DensityFunction direct(DensityFunction holder,Class<?> expected) {
        nativeType(holder,DensityFunctionTypes.RegistryEntryHolder.class);var entry=((DensityFunctionTypes.RegistryEntryHolder)holder).function();
        require(entry.getClass()==RegistryEntry.Direct.class && entry.getKey().isEmpty(),"END_OWNER_PATH_UNRESOLVED");
        nativeType(entry,RegistryEntry.Direct.class);var value=entry.value();nativeType(value,expected);return value;
    }
    private EndStateSeal verifyEnd(DensityFunction value) {
        nativeType(value,NativeTypes.END);codecIdentity(value,"end_islands");
        var before=state(value);graph.nativeState(2076);
        var rebuilt=state(DensityFunctionTypes.endIslands(0L));
        require(before.independentIdentity(rebuilt),"NATIVE_END_REFERENCE_NOT_INDEPENDENT");
        equalState(before,rebuilt);
        var codec=DensityFunctionTypes.CODEC;var encoded=codec.encodeStart(NbtOps.INSTANCE,value).getOrThrow();
        var decoded=codec.parse(NbtOps.INSTANCE,encoded).getOrThrow();
        require(encoded.equals(codec.encodeStart(NbtOps.INSTANCE,decoded).getOrThrow()),"NATIVE_END_CODEC_REPLAY_MISMATCH");
        var after=state(value);require(before.sameIdentity(after),"NATIVE_END_ORIGINAL_IDENTITY_CHANGED");equalState(before,after);return before;
    }
    private EndStateSeal state(DensityFunction value) {
        require(value instanceof EndStateAccess,"NATIVE_END_BRIDGE_MISSING");
        try { var result=((EndStateAccess)value).quantumchamber$captureEndState();require(result.bytes().length==2076,"NATIVE_END_SIMPLEX_SHAPE");return result; }
        catch(IllegalStateException error) { throw new Failure(error.getMessage()); }
    }
    private void equalState(EndStateSeal first,EndStateSeal second) { require(Arrays.equals(first.bytes(),second.bytes()),"NATIVE_END_SIMPLEX_STATE_MISMATCH"); }
    private void verifyStable(ChunkGeneratorSettings settings,DensityFunction[] found,EndStateSeal[] captured) {
        require(actual(END_SETTINGS).value()==settings,"NATIVE_END_ORIGINAL_IDENTITY_CHANGED");var current=paths(settings);
        for(int i=0;i<2;i++) { require(current[i]==found[i],"NATIVE_END_ORIGINAL_IDENTITY_CHANGED");var now=state(current[i]);
            require(captured[i].sameIdentity(now),"NATIVE_END_ORIGINAL_IDENTITY_CHANGED");equalState(captured[i],now); }
    }
    private void codecIdentity(DensityFunction value,String id) {
        require(value.getCodecHolder().codec()==Registries.DENSITY_FUNCTION_TYPE.get(Identifier.ofVanilla(id)),"NATIVE_END_CODEC_OWNER");
    }
    private <T> NbtElement codec(Codec<T> codec,T value,RegistryKey<?> from,RegistryKey<?> expected) {
        var plain=registries.getOps(NbtOps.INSTANCE);var encoded=codec.encodeStart(plain,value).getOrThrow();
        int[] count={0};var traced=RegistryOps.of(NbtOps.INSTANCE,new RegistryOps.RegistryInfoGetter() {
            @Override public <E> Optional<RegistryOps.RegistryInfo<E>> getRegistryInfo(RegistryKey<? extends Registry<? extends E>> key) {
                return registries.getOptionalWrapper(key).map(wrapper->new RegistryOps.RegistryInfo<>(wrapper,new RegistryEntryLookup<E>() {
                    @Override public Optional<RegistryEntry.Reference<E>> getOptional(RegistryKey<E> target) {
                        require(target.equals(expected),"UNSUPPORTED_END_CODEC_REFERENCE");count[0]++;edge(from,"native-entry-reference",target);return wrapper.getOptional(target);
                    }
                    @Override public Optional<RegistryEntryList.Named<E>> getOptional(TagKey<E> tag) { throw new Failure("UNSUPPORTED_END_CODEC_TAG"); }
                },wrapper.getLifecycle()));
            }
        });
        var decoded=codec.parse(traced,encoded).getOrThrow();require(count[0]==1,"END_DEPENDENCY_NATIVE_EDGE_MISSING");
        require(encoded.equals(codec.encodeStart(plain,decoded).getOrThrow()),"NATIVE_CODEC_ROUNDTRIP_CHANGED");return encoded;
    }
    private <T> void sourceMatches(Codec<T> codec,NbtElement encoded,byte[] source) {
        var decoded=codec.parse(registries.getOps(JsonOps.INSTANCE),JsonParser.parseString(new String(source,StandardCharsets.UTF_8))).getOrThrow();
        require(encoded.equals(codec.encodeStart(registries.getOps(NbtOps.INSTANCE),decoded).getOrThrow()),"UNSUPPORTED_END_SOURCE_VALUE");
    }
    private byte[] source(RegistryKey<?> key,int length,String sha) {
        var id=Identifier.ofVanilla(key.getRegistry().getPath()+"/"+key.getValue().getPath()+".json");
        var resource=resources.getResource(id).orElseThrow(()->new Failure("UNSUPPORTED_END_SOURCE"));
        require(resource.getPack().getClass()==DefaultResourcePack.class,"UNSUPPORTED_END_SOURCE_WINNER");nativeType(resource.getPack(),DefaultResourcePack.class);
        try(var input=resource.getInputStream()) {
            byte[] bytes=input.readNBytes(length+1);graph.resource(new Key(Kind.RESOLVED,"minecraft:resource",id.toString()));graph.decoded(bytes.length);
            require(bytes.length==length && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha),"UNSUPPORTED_END_SOURCE");return bytes;
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException error) { throw new Failure("UNSUPPORTED_END_SOURCE"); }
    }
    private void edge(RegistryKey<?> from,String path,RegistryKey<?> target) {
        actual(target);var occurrence=graph.reference(node(from),path,node(target),"ordinary","required-native-value");references.accept(occurrence,target);
    }
    private static Key node(RegistryKey<?> key) { return new Key(Kind.REGISTRY,key.getRegistry().toString(),key.getValue().toString()); }
    @SuppressWarnings({"rawtypes","unchecked"}) private RegistryEntry.Reference<?> actual(RegistryKey<?> key) {
        Optional<?> registry=registries.getOptional((RegistryKey)RegistryKey.ofRegistry(key.getRegistry()));
        var actualRegistry=(Registry<?>)registry.orElseThrow(()->new Failure("REQUIRED_REGISTRY_MISSING"));
        Optional<?> entry=((Registry)actualRegistry).getEntry((RegistryKey)key);
        return (RegistryEntry.Reference<?>)entry.orElseThrow(()->new Failure("REQUIRED_REGISTRY_TARGET_MISSING"));
    }
    private static void nativeType(Object value,Class<?> expected) {
        require(value!=null && value.getClass()==expected,"NATIVE_DEFINITION_OWNER");
        var source=value.getClass().getProtectionDomain().getCodeSource();var nativeSource=SharedConstants.class.getProtectionDomain().getCodeSource();
        require(source!=null && nativeSource!=null && source.getLocation().equals(nativeSource.getLocation()),"NATIVE_DEFINITION_SOURCE");
    }
    private static void require(boolean condition,String code) { if(!condition)throw new Failure(code); }

}
