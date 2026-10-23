import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 檢查來源包的 Controller CODEC 身分與發布 class 實際引用是否一致。 */
public final class SourceRemapOracle {
    private static final String OWNER = "dev/quantumchamber/chamber/ChamberControllerBlock";
    private static final String FIELD_DESCRIPTOR = "Lcom/mojang/serialization/MapCodec;";

    private SourceRemapOracle() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要 sources JAR 與 release JAR");
        byte[] source = readUniqueEntry(Path.of(args[0]), OWNER + ".java");
        byte[] release = readUniqueEntry(Path.of(args[1]), OWNER + ".class");
        checkSource(new String(source, StandardCharsets.UTF_8));
        checkRelease(release);
        System.out.println("SOURCE_REMAP_ORACLE PASS: ChamberControllerBlock.CODEC declaration/reference/release Fieldref");
    }

    private static byte[] readUniqueEntry(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var matches = zip.stream().filter(entry -> entry.getName().equals(name)).toList();
            if (matches.size() != 1) throw new IOException(jar + " 的 " + name + " 數量為 " + matches.size());
            try (var stream = zip.getInputStream(matches.getFirst())) { return stream.readAllBytes(); }
        }
    }

    private static void checkSource(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("需要 JDK compiler");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + OWNER + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(unit));
        CompilationUnitTree tree = task.parse().iterator().next();
        if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)) {
            throw new IllegalStateException("來源 Java 語法錯誤：" + diagnostics.getDiagnostics());
        }
        ClassTree controller = null;
        for (Tree type : tree.getTypeDecls()) {
            if (type instanceof ClassTree c && c.getSimpleName().contentEquals("ChamberControllerBlock")) {
                if (controller != null) throw new IllegalStateException("重複 Controller 類別");
                controller = c;
            }
        }
        if (controller == null) throw new IllegalStateException("缺少 Controller 類別");
        int ownCodecFields = 0;
        int codecMethods = 0;
        for (Tree member : controller.getMembers()) {
            if (member instanceof VariableTree field && field.getName().contentEquals("CODEC")) {
                if (!(field.getType() instanceof ParameterizedTypeTree type)
                        || !(type.getType() instanceof IdentifierTree raw) || !raw.getName().contentEquals("MapCodec")
                        || type.getTypeArguments().size() != 1
                        || !(type.getTypeArguments().getFirst() instanceof IdentifierTree arg)
                        || !arg.getName().contentEquals("ChamberControllerBlock")) {
                    throw new IllegalStateException("本類 CODEC 型別不符");
                }
                ownCodecFields++;
            }
            if (member instanceof MethodTree method && method.getName().contentEquals("method_53969")) {
                codecMethods++;
                if (method.getBody() == null || method.getBody().getStatements().size() != 1
                        || !(method.getBody().getStatements().getFirst() instanceof ReturnTree ret)) {
                    throw new IllegalStateException("getCodec 方法結構不符");
                }
                boolean localName = ret.getExpression() instanceof IdentifierTree id && id.getName().contentEquals("CODEC");
                boolean qualifiedOwnName = ret.getExpression() instanceof MemberSelectTree select
                        && select.getExpression() instanceof IdentifierTree owner && owner.getName().contentEquals("ChamberControllerBlock")
                        && select.getIdentifier().contentEquals("CODEC");
                if (!localName && !qualifiedOwnName) throw new IllegalStateException("getCodec 未引用本類 CODEC：" + ret.getExpression());
            }
        }
        if (ownCodecFields != 1 || codecMethods != 1) {
            throw new IllegalStateException("本類 CODEC 欄位數=" + ownCodecFields + "、getCodec 方法數=" + codecMethods);
        }
    }

    private static void checkRelease(byte[] classBytes) {
        var seen = new int[3];
        var reader = new ClassReader(classBytes);
        if (!OWNER.equals(reader.getClassName())) throw new IllegalStateException("發布 class owner 不符");
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name.equals("CODEC")) {
                    if (!descriptor.equals(FIELD_DESCRIPTOR)
                            || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                               != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
                        throw new IllegalStateException("發布 CODEC 欄位 API 不符");
                    }
                    seen[0]++;
                }
                return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("method_53969") && !name.equals("<clinit>")) return null;
                if (name.equals("method_53969") && !descriptor.equals("()" + FIELD_DESCRIPTOR)) {
                    throw new IllegalStateException("發布 getCodec descriptor 不符");
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String type) {
                        if (name.equals("method_53969") && opcode == Opcodes.GETSTATIC) {
                            if (!owner.equals(OWNER) || !field.equals("CODEC") || !type.equals(FIELD_DESCRIPTOR))
                                throw new IllegalStateException("發布 getCodec Fieldref 不符：" + owner + "." + field);
                            seen[1]++;
                        }
                        if (name.equals("<clinit>") && opcode == Opcodes.PUTSTATIC && field.equals("CODEC")) {
                            if (!owner.equals(OWNER) || !type.equals(FIELD_DESCRIPTOR))
                                throw new IllegalStateException("發布 CODEC 寫入 owner 不符");
                            seen[2]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (seen[0] != 1 || seen[1] != 1 || seen[2] != 1) {
            throw new IllegalStateException("發布 CODEC 欄位／讀取／寫入計數不符：" + List.of(seen[0], seen[1], seen[2]));
        }
    }
}
