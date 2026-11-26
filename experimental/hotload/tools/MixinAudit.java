import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Audits the mixins in unpacked mod jars: for each mixin, which classes it targets
 * and what it would add to them. Anything added to an already-loaded class is a
 * "shape change" a stock JVM can't apply at runtime.
 *
 * usage: java -cp asm.jar;asm-tree.jar MixinAudit.java <unpacked jar dir>... > report.tsv
 * Writes the target list to targets.txt (for the runtime "already loaded?" check).
 */
public class MixinAudit {

    static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    static final Set<String> ACCESSORS = Set.of(
            "Lorg/spongepowered/asm/mixin/gen/Accessor;", "Lorg/spongepowered/asm/mixin/gen/Invoker;");

    public static void main(String[] args) throws IOException {
        Set<String> allTargets = new TreeSet<>();
        System.out.println("jar\tconfig\tside\tmixin\ttargets\tkind\tinjectors\toverwrites\taddedMethods\taddedFields\taddedInterfaces\taccessors");
        for (String dir : args) {
            Path root = Path.of(dir);
            try (Stream<Path> files = Files.list(root)) {
                for (Path cfg : files.filter(p -> p.getFileName().toString().endsWith(".mixins.json")).toList()) {
                    audit(root, cfg, allTargets);
                }
            }
        }
        Files.write(Path.of("targets.txt"), allTargets);
    }

    static void audit(Path root, Path cfg, Set<String> allTargets) throws IOException {
        String json = Files.readString(cfg);
        String pkg = string(json, "package");
        for (String side : List.of("mixins", "client", "server")) {
            for (String name : array(json, side)) {
                Path cls = root.resolve((pkg + "." + name).replace('.', '/') + ".class");
                if (!Files.exists(cls)) continue;
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(cls)).accept(node, ClassReader.SKIP_CODE);
                List<String> targets = targets(node);
                allTargets.addAll(targets);
                boolean isInterface = (node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
                int injectors = 0, overwrites = 0, added = 0, accessors = 0;
                for (MethodNode m : node.methods) {
                    if (m.name.equals("<clinit>") || m.name.equals("<init>")) continue;
                    Set<String> anns = annotations(m.visibleAnnotations, m.invisibleAnnotations);
                    if (anns.stream().anyMatch(INJECTORS::contains)) injectors++;
                    else if (anns.contains(OVERWRITE)) overwrites++;
                    else if (anns.stream().anyMatch(ACCESSORS::contains)) accessors++;
                    else if (anns.contains(SHADOW)) { /* reference only */ }
                    else if ((m.access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) == 0) added++;
                }
                int fields = 0;
                for (FieldNode f : node.fields) {
                    if (!annotations(f.visibleAnnotations, f.invisibleAnnotations).contains(SHADOW)) fields++;
                }
                String kind = isInterface ? "accessor-interface" : "class";
                System.out.println(String.join("\t", root.getFileName().toString(), cfg.getFileName().toString(), side,
                        name, String.join(",", targets), kind, "" + injectors, "" + overwrites, "" + added, "" + fields,
                        "" + node.interfaces.size(), "" + accessors));
            }
        }
    }

    static List<String> targets(ClassNode node) {
        List<String> out = new ArrayList<>();
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations, true)) {
            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || a.values == null) continue;
            for (int i = 0; i < a.values.size(); i += 2) {
                String key = (String) a.values.get(i);
                Object value = a.values.get(i + 1);
                if (value instanceof List<?> list) {
                    for (Object v : list) {
                        if (key.equals("value") && v instanceof Type t) out.add(t.getClassName());
                        if (key.equals("targets") && v instanceof String s) out.add(s.replace('/', '.'));
                    }
                }
            }
        }
        return out;
    }

    @SafeVarargs
    static Set<String> annotations(List<AnnotationNode>... lists) {
        Set<String> out = new HashSet<>();
        for (List<AnnotationNode> l : lists) if (l != null) for (AnnotationNode a : l) out.add(a.desc);
        return out;
    }

    static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b, boolean unused) {
        List<AnnotationNode> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    static String string(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static List<String> array(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (s.find()) out.add(s.group(1));
        return out;
    }
}
