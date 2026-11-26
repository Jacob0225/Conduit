package dev.conduit.hotload.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM access rules for references from hot-loaded code to classes that were loaded before the
 * hot-load. Their access flags are fixed (access transformers and Mixin accessor widening came
 * too late), so a reference the mod was compiled to expect may be illegal at runtime.
 *
 * <p>Reads class headers from class files (never loads a class), so it is safe while classes are
 * being defined. Answers are conservative: rerouting an access that would have worked is harmless.
 */
final class AccessInfo {

    private record Info(int access, String superName, List<String> interfaces,
                        Map<String, Integer> fields, Map<String, Integer> methods) {}

    private static final Info NONE = new Info(0, null, List.of(), Map.of(), Map.of());
    private final ClassLoader loader;
    private final Hierarchy hierarchy;
    private final Map<String, Info> infos = new ConcurrentHashMap<>();
    private final Map<String, String> erasures = new ConcurrentHashMap<>();

    AccessInfo(ClassLoader loader, Hierarchy hierarchy) {
        this.loader = loader;
        this.hierarchy = hierarchy;
    }

    private Info info(String internal) {
        return infos.computeIfAbsent(internal, name -> {
            try (InputStream in = loader.getResourceAsStream(name + ".class")) {
                if (in == null) return NONE;
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                Map<String, Integer> fields = new HashMap<>();
                for (FieldNode f : node.fields) fields.put(f.name, f.access);
                Map<String, Integer> methods = new HashMap<>();
                for (MethodNode m : node.methods) methods.put(m.name + m.desc, m.access);
                return new Info(node.access, node.superName, List.copyOf(node.interfaces), fields, methods);
            } catch (Exception e) {
                return NONE;
            }
        });
    }

    /** Top-level class of a nest (inner classes share it). */
    private static String outer(String internal) {
        int dollar = internal.indexOf('$', internal.lastIndexOf('/') + 1);
        return dollar < 0 ? internal : internal.substring(0, dollar);
    }

    private static String pkg(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    /** True if {@code owner} is a loaded class that {@code from} may not name. */
    boolean inaccessibleClass(String owner, String from) {
        if (owner.startsWith("java/") || owner.startsWith("[")) return false;
        if (!hierarchy.isLoaded(owner)) return false;
        Info i = info(owner);
        return i != NONE && (i.access() & Opcodes.ACC_PUBLIC) == 0 && !pkg(owner).equals(pkg(from));
    }

    /** True if the member reference from {@code from} would fail the JVM's access check. */
    boolean inaccessibleMember(String from, String owner, String name, String desc, boolean field) {
        if (owner.startsWith("java/") || owner.startsWith("[") || !hierarchy.isLoaded(owner)) return false;
        if (inaccessibleClass(owner, from)) return true;
        // Find the declaring class (fields and methods: superclasses; methods: also interfaces).
        for (String c = owner; c != null; c = info(c).superName()) {
            Info i = info(c);
            if (i == NONE) return false;
            Integer access = field ? i.fields().get(name) : i.methods().get(name + desc);
            if (access == null && !field) access = interfaceMethod(i.interfaces(), name + desc);
            if (access == null) continue;
            if ((access & Opcodes.ACC_PUBLIC) != 0) return false;
            if ((access & Opcodes.ACC_PRIVATE) != 0) return !outer(c).equals(outer(from)); // nestmates may
            if (pkg(c).equals(pkg(from))) return false;
            if ((access & Opcodes.ACC_PROTECTED) != 0) return !isSubclass(from, c);
            return true; // package-private, other package
        }
        return false;
    }

    private Integer interfaceMethod(List<String> interfaces, String sig) {
        for (String itf : interfaces) {
            Info i = info(itf);
            Integer a = i.methods().get(sig);
            if (a != null) return a;
            a = interfaceMethod(i.interfaces(), sig);
            if (a != null) return a;
        }
        return null;
    }

    private boolean isSubclass(String from, String declaring) {
        for (String c = from; c != null; c = hierarchy.superOf(c)) if (c.equals(declaring)) return true;
        return false;
    }

    /** True if the field (found from {@code owner} upwards) is final in the class as originally loaded. */
    boolean finalField(String owner, String name) {
        for (String c = owner; c != null; c = info(c).superName()) {
            Info i = info(c);
            if (i == NONE) return false;
            Integer access = i.fields().get(name);
            if (access != null) return (access & Opcodes.ACC_FINAL) != 0;
        }
        return false;
    }

    /** The class that declares the member (owner itself if it can't be found). */
    String declaring(String owner, String name, String desc, boolean field) {
        for (String c = owner; c != null; c = info(c).superName()) {
            Info i = info(c);
            if (i == NONE) break;
            if (field ? i.fields().containsKey(name) : i.methods().containsKey(name + desc)) return c;
        }
        return owner;
    }

    /** True if any class in the descriptor is a loaded class {@code from} may not name. */
    boolean descriptorInaccessible(String desc, String from) {
        if (desc.indexOf('L') < 0) return false;
        Type t = Type.getType(desc);
        Type[] types = t.getSort() == Type.METHOD ? concat(t.getArgumentTypes(), t.getReturnType()) : new Type[]{t};
        for (Type x : types) {
            Type e = x.getSort() == Type.ARRAY ? x.getElementType() : x;
            if (e.getSort() == Type.OBJECT && inaccessibleClass(e.getInternalName(), from)) return true;
        }
        return false;
    }

    private static Type[] concat(Type[] args, Type ret) {
        Type[] out = new Type[args.length + 1];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = ret;
        return out;
    }

    /** Nearest public superclass of an inaccessible class (interfaces erase to Object). */
    String erasure(String internal) {
        return erasures.computeIfAbsent(internal, name -> {
            Info i = info(name);
            if ((i.access() & Opcodes.ACC_INTERFACE) != 0) return "java/lang/Object";
            for (String c = i.superName(); c != null; c = info(c).superName()) {
                if (c.startsWith("java/") || (info(c).access() & Opcodes.ACC_PUBLIC) != 0) return c;
            }
            return "java/lang/Object";
        });
    }
}
