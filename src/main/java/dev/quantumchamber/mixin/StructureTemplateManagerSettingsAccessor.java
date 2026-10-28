package dev.quantumchamber.mixin;

import com.mojang.datafixers.DataFixer;
import dev.quantumchamber.universe.Minecraft121TemplateSettingsAdapter;
import dev.quantumchamber.universe.Minecraft121TemplateSettingsAdapter.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.*;
import net.minecraft.nbt.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.datafixer.Schemas;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.*;

/** safe consumer驗權威後才讀欄位，於同一live lease內擷取來源並私有重建。 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerSettingsAccessor implements Minecraft121TemplateSettingsAdapter.ManagerAccess {
    @Shadow @Final private Map<Identifier,Optional<StructureTemplate>> templates;
    @Shadow private ResourceManager resourceManager;
    @Shadow @Final private Path generatedPath;
    @Shadow @Final private DataFixer dataFixer;
    @Shadow @Final private RegistryEntryLookup<Block> blockLookup;

    @Override @Unique
    public Capture quantumchamber$captureSettings(CapturePermit permit,LevelStorage.Session session,Identifier id) {
        if(permit==null)throw new IllegalStateException("TEMPLATE_CAPTURE_PERMIT_REQUIRED");
        permit.requireManager((StructureTemplateManager)(Object)this);
        quantumchamber$validateInputs(permit,session);
        if(templates==null||templates.getClass()!=ConcurrentHashMap.class)
            return new Capture(CacheKind.UNOBSERVABLE,SourceKind.UNKNOWN,null,null);
        var map=templates;var rm=resourceManager;var path=generatedPath;var fixer=dataFixer;var lookup=blockLookup;
        boolean development=net.minecraft.SharedConstants.isDevelopment;String devDirectory=net.minecraft.test.StructureTestUtil.testStructuresDirectoryName;
        boolean present=map.containsKey(id);Object entry=map.get(id);OwnedTemplate value=null;
        CacheKind kind=CacheKind.NO_ENTRY;
        if(present) {
            if(!(entry instanceof Optional<?> optional))return new Capture(CacheKind.UNOBSERVABLE,SourceKind.UNKNOWN,null,null);
            if(optional.isEmpty())kind=CacheKind.NEGATIVE;
            else {
                Object raw=optional.get();Minecraft121TemplateSettingsAdapter.nativeType(raw,StructureTemplate.class);
                value=((TemplateAccess)raw).quantumchamber$captureSettings(permit);
                kind=value.constructorBlank()?CacheKind.PRESENT_CONSTRUCTOR_BLANK:CacheKind.OTHER_PRESENT;
                var after=((TemplateAccess)raw).quantumchamber$captureSettings(permit);
                if(!value.sameValue(after))throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_CACHE_UNSTABLE");
            }
        }
        var source=quantumchamber$readSource(permit,id);
        boolean absent=!source.contains("Raw");
        var rebuilt=quantumchamber$privateRebuild(permit,session,absent?null:source.getCompound("Raw"),absent);
        var sourceAfter=quantumchamber$readSource(permit,id);
        if(!source.equals(sourceAfter))throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_UNSTABLE");
        if(value!=null) {
            var raw=((Optional<?>)entry).orElseThrow();
            if(!value.sameValue(((TemplateAccess)raw).quantumchamber$captureSettings(permit)))
                throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_CACHE_UNSTABLE");
        }
        quantumchamber$validateInputs(permit,session);
        if(map!=templates||present!=map.containsKey(id)||entry!=map.get(id)||rm!=resourceManager||path!=generatedPath
                ||fixer!=dataFixer||lookup!=blockLookup||development!=net.minecraft.SharedConstants.isDevelopment
                ||!java.util.Objects.equals(devDirectory,net.minecraft.test.StructureTestUtil.testStructuresDirectoryName))throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_UNSTABLE");
        permit.checkpoint();return new Capture(kind,absent?SourceKind.PROVEN_ABSENT:SourceKind.PRESENT,value,rebuilt);
    }

    /** 來源證據只在當次窗口內持有；不把實體路徑／來源stamp放進canonical payload。 */
    @Unique private NbtCompound quantumchamber$readSource(CapturePermit permit,Identifier id) {
        permit.checkpoint();
        try {
            Path saved=((StructureTemplateManager)(Object)this).getTemplatePath(id,".nbt");
            var selected=quantumchamber$readFile(permit,saved,false);
            if(selected!=null){selected.putString("Origin","SAVED");return selected;}
            if(net.minecraft.SharedConstants.isDevelopment) {
                Path directory=Path.of(net.minecraft.test.StructureTestUtil.testStructuresDirectoryName);
                Path dev=net.minecraft.util.PathUtil.getResourcePath(directory,id.getPath(),".snbt");
                selected=quantumchamber$readFile(permit,dev,true);
                if(selected!=null){selected.putString("Origin","DEV");return selected;}
            }
            var location=new net.minecraft.resource.ResourceFinder("structure",".nbt").toResourcePath(id);
            Optional<net.minecraft.resource.Resource> resource;
            try {resource=resourceManager.getResource(location);}
            catch(RuntimeException unknown){throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_UNKNOWN");}
            permit.checkpoint();if(resource==null)throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_UNKNOWN");
            if(resource.isEmpty()){var absent=new NbtCompound();absent.putString("Origin","ABSENT");return absent;}
            byte[] bytes;
            try(var input=resource.get().getInputStream()){bytes=quantumchamber$readBytes(permit,input);}
            selected=quantumchamber$decodeSource(permit,bytes,false);
            selected.putString("Origin","RESOURCE");selected.putString("Pack",resource.get().getPackId());return selected;
        } catch(IOException|NbtException|com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_ERROR");
        }
    }
    @Unique private NbtCompound quantumchamber$readFile(CapturePermit permit,Path path,boolean snbt)
            throws IOException,com.mojang.brigadier.exceptions.CommandSyntaxException {
        path=path.toAbsolutePath().normalize();
        // 逐層確認父目錄，NoSuchFile僅在可讀父目錄中採計真absence；symlink/未知stat不推定不存在。
        Path current=path.getRoot();
        for(int i=0;i<path.getNameCount();i++) {
            Path parent=current;current=current.resolve(path.getName(i));permit.checkpoint();
            BasicFileAttributes attrs;
            try {attrs=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
            catch(NoSuchFileException absent) {
                try(var entries=Files.newDirectoryStream(parent)) {
                    entries.iterator().hasNext();permit.checkpoint();
                }
                return null;
            }
            if(attrs.isSymbolicLink()||(i<path.getNameCount()-1?!attrs.isDirectory():!attrs.isRegularFile()))
                throw new IOException("來源路徑型態不支援");
        }
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()>8388608)throw Minecraft121TemplateSettingsAdapter.rejected("BUDGET_RAW");
        byte[] bytes;try(var input=Files.newInputStream(path)){bytes=quantumchamber$readBytes(permit,input);}
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        String stamp=quantumchamber$fileStamp(before);
        if(!stamp.equals(quantumchamber$fileStamp(after)))throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_UNSTABLE");
        var result=quantumchamber$decodeSource(permit,bytes,snbt);result.putString("FileStamp",stamp);return result;
    }
    @Unique private static String quantumchamber$fileStamp(BasicFileAttributes attrs) {
        return attrs.fileKey()+"/"+attrs.size()+"/"+attrs.lastModifiedTime()+"/"+attrs.creationTime()+"/"+attrs.isRegularFile();
    }
    @Unique private static byte[] quantumchamber$readBytes(CapturePermit permit,InputStream input) throws IOException {
        var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1) {
            permit.checkpoint();if(count>8388608-out.size())throw Minecraft121TemplateSettingsAdapter.rejected("BUDGET_RAW");
            permit.chargeOwned(count);out.write(buffer,0,count);
        }
        permit.checkpoint();permit.chargeSource(out.size());return out.toByteArray();
    }
    @Unique private static NbtCompound quantumchamber$decodeSource(CapturePermit permit,byte[] bytes,boolean snbt)
            throws IOException,com.mojang.brigadier.exceptions.CommandSyntaxException {
        NbtCompound raw;
        if(snbt) {
            String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            // 原生SNBT parser遞迴前先界限巢狀深度；引號／跳脫中的括號不是容器。
            int depth=0;char quote=0;boolean escaped=false;
            for(int i=0;i<text.length();i++) {
                char c=text.charAt(i);
                if(quote!=0){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c==quote)quote=0;}
                else if(c=='"'||c=='\'')quote=c;
                else if(c=='{'||c=='['){if(++depth>128)throw Minecraft121TemplateSettingsAdapter.rejected("BUDGET_DEPTH");}
                else if(c=='}'||c==']')depth--;
            }
            raw=NbtHelper.fromNbtProviderString(text);
        } else {
            var tracker=new NbtSizeTracker(67108864,128);
            raw=NbtIo.readCompressed(new ByteArrayInputStream(bytes),tracker);permit.chargeOwned(tracker.getAllocatedBytes());
        }
        Minecraft121TemplateSettingsAdapter.requireOwnedNbt(raw);permit.checkpoint();
        var result=new NbtCompound();result.put("Raw",raw);result.putByteArray("Bytes",bytes);return result;
    }

    @Override @Unique public OwnedTemplate quantumchamber$rebuildSettings(CapturePermit permit,LevelStorage.Session session,net.minecraft.nbt.NbtCompound raw,boolean absent) {
        if(permit==null)throw new IllegalStateException("TEMPLATE_CAPTURE_PERMIT_REQUIRED");
        permit.requireManager((StructureTemplateManager)(Object)this);quantumchamber$validateInputs(permit,session);
        return quantumchamber$privateRebuild(permit,session,raw,absent);
    }
    @Unique private OwnedTemplate quantumchamber$privateRebuild(CapturePermit permit,LevelStorage.Session session,net.minecraft.nbt.NbtCompound raw,boolean absent) {
        var rm=resourceManager;var fixer=dataFixer;var lookup=blockLookup;var path=generatedPath;var liveCache=templates;
        if(!absent)Minecraft121TemplateSettingsAdapter.requireOwnedNbt(raw);
        var isolated=new StructureTemplateManager(rm,session,fixer,lookup);
        quantumchamber$verifyIsolated(isolated,rm,fixer,lookup,path,liveCache);
        permit.checkpoint();
        var template=absent?new StructureTemplate():isolated.createTemplate(raw.copy());
        var value=permit.captureIsolated((StructureTemplateManager)(Object)this,isolated,template,absent);
        quantumchamber$verifyIsolated(isolated,rm,fixer,lookup,path,liveCache);
        quantumchamber$validateInputs(permit,session);
        if(resourceManager!=rm||dataFixer!=fixer||blockLookup!=lookup||generatedPath!=path||templates!=liveCache)
            throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SOURCE_UNSTABLE");
        permit.checkpoint();return value;
    }
    /** 同一Mixin內self type會重定位至target，直接核兩instance的private欄位，不增raw getter。 */
    @Unique private void quantumchamber$verifyIsolated(StructureTemplateManager isolated,ResourceManager rm,DataFixer fixer,
            RegistryEntryLookup<Block> lookup,Path path,Map<Identifier,Optional<StructureTemplate>> liveCache) {
        Minecraft121TemplateSettingsAdapter.nativeType(isolated,StructureTemplateManager.class);
        var own=(StructureTemplateManagerSettingsAccessor)(Object)isolated;
        if(isolated==(Object)this||own.templates==liveCache||own.templates==null||!own.templates.isEmpty())
            throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_ISOLATION_SHARED");
        if(own.resourceManager!=rm||own.dataFixer!=fixer||own.blockLookup!=lookup||!own.generatedPath.equals(path))
            throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_ISOLATED_SOURCE_MISMATCH");
    }
    @Unique private void quantumchamber$validateInputs(CapturePermit permit,LevelStorage.Session session) {
        permit.requireSession(session);permit.requireResources(resourceManager);
        if(!session.getDirectory(WorldSavePath.GENERATED).normalize().equals(generatedPath))
            throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SESSION_PATH_MISMATCH");
        if(dataFixer!=Schemas.getFixer())throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_DATAFIXER_OWNER_MISMATCH");
        if(!(blockLookup instanceof RegistryWrapper.Impl.Delegating<?> delegated)
                ||!blockLookup.getClass().isAnonymousClass()
                ||blockLookup.getClass().getEnclosingClass()!=RegistryWrapper.Impl.class)
            throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_BLOCK_LOOKUP_UNSUPPORTED");
        Minecraft121TemplateSettingsAdapter.nativeSource(blockLookup.getClass());permit.requireBlockBase(delegated.getBase());
    }
}
