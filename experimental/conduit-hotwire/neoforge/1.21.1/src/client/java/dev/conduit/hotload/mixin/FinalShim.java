package dev.conduit.hotload.mixin;

import dev.conduit.hotload.jvm.JvmAccess;
import dev.conduit.hotload.runtime.HotRuntime;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets hot-loaded mods override a method that their access transformer un-finals
 * ({@code -f}) on a class that was already loaded with it final.
 *
 * <ul>
 *   <li>The loaded method gets a prologue: if the receiver's class has a registered override,
 *       call it and return; otherwise run the original body.</li>
 *   <li>Mod classes are rewritten as they load: the override is renamed
 *       {@code conduit$final$<name>} (so the JVM accepts the class), and {@code super.<name>()}
 *       calls go to the next override up, or to the original body with a one-shot bypass.</li>
 * </ul>
 */
public final class FinalShim {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/FinalShim");
    static final String PREFIX = "conduit$final$";

    /** Shimmed methods: "owner.name+desc". */
    private static final Set<String> SHIMMED = ConcurrentHashMap.newKeySet();

    private FinalShim() {}

    static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    static boolean isShimmed(String owner, String name, String desc) {
        return SHIMMED.contains(key(owner, name, desc));
    }

    /** Shimmed in some class (also interface defaults, which the superclass walk can't see). */
    static boolean isShimmedAnywhere(String name, String desc) {
        String suffix = "." + name + desc;
        for (String k : SHIMMED) if (k.endsWith(suffix)) return true;
        return false;
    }

    /** Shims every final method the new ATs un-final on classes that are already loaded. */
    public static int apply(Instrumentation inst, ClassLoader loader) throws Exception {
        Hierarchy hierarchy = new Hierarchy(loader);
        Map<Class<?>, List<String[]>> byClass = new LinkedHashMap<>();
        for (String[] entry : AccessRegistry.finalRemovals()) {
            String owner = entry[0], member = entry[1];
            int paren = member.indexOf('(');
            if (paren < 0) continue; // fields: rerouted writes handle these
            String name = member.substring(0, paren), desc = member.substring(paren);
            if (isShimmed(owner, name, desc) || !hierarchy.isLoaded(owner)) continue;
            Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
            boolean isFinal = false;
            for (Method m : type.getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isFinal(m.getModifiers())
                        && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) {
                    isFinal = true;
                }
            }
            if (!isFinal) continue;
            byClass.computeIfAbsent(type, k -> new ArrayList<>()).add(new String[]{name, desc});
        }
        if (byClass.isEmpty()) return 0;
        List<ClassDefinition> defs = new ArrayList<>();
        int count = 0;
        for (var e : byClass.entrySet()) {
            ClassNode node = new ClassNode();
            new ClassReader(JvmAccess.currentBytes(e.getKey())).accept(node, 0);
            for (String[] nd : e.getValue()) {
                for (MethodNode m : node.methods) {
                    if (m.name.equals(nd[0]) && m.desc.equals(nd[1])) {
                        SHIMMED.add(key(node.name, m.name, m.desc));
                        insertPrologue(node.name, m);
                        count++;
                    }
                }
            }
            JvmAccess.addReads(e.getKey().getModule(), FinalShim.class.getModule());
            ClassWriter w = new TclClassWriter(ClassWriter.COMPUTE_MAXS, loader);
            node.accept(w);
            defs.add(new ClassDefinition(e.getKey(), w.toByteArray()));
        }
        inst.redefineClasses(defs.toArray(ClassDefinition[]::new));
        LOGGER.info("Shimmed {} final methods so hot-loaded mods can override them: {}", count, SHIMMED);
        return count;
    }

    /**
     * A mixin added an override of an inherited method to an already-loaded class. Calls on that
     * class still resolve to the inherited implementation, so give that one the dispatch prologue
     * and register the companion as the override for {@code target}.
     */
    static void shimInherited(Class<?> target, String name, String desc, String companionKey,
                              Instrumentation inst) throws Exception {
        Method resolved = resolvedImplementation(target, name, desc);
        if (resolved == null) {
            throw new UnsupportedOperationException("mixin adds " + name + desc + " to " + target.getName()
                    + " but no inherited implementation was found to dispatch from");
        }
        Class<?> owner = resolved.getDeclaringClass();
        String ownerName = owner.getName().replace('.', '/');
        if (!isShimmed(ownerName, name, desc)) {
            ClassNode node = new ClassNode();
            new ClassReader(JvmAccess.currentBytes(owner)).accept(node, 0);
            for (MethodNode m : node.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) {
                    SHIMMED.add(key(ownerName, name, desc));
                    insertPrologue(ownerName, m);
                }
            }
            JvmAccess.addReads(owner.getModule(), FinalShim.class.getModule());
            ClassWriter w = new TclClassWriter(ClassWriter.COMPUTE_MAXS, owner.getClassLoader());
            node.accept(w);
            inst.redefineClasses(new ClassDefinition(owner, w.toByteArray()));
        }
        HotRuntime.addCompanionOverride(key(ownerName, name, desc), target.getName().replace('.', '/'), companionKey);
        LOGGER.info("Added override {}{} on {} dispatches from {}", name, desc, target.getName(), owner.getName());
    }

    /** What a virtual call of name+desc on an instance of {@code target} runs today. */
    private static Method resolvedImplementation(Class<?> target, String name, String desc) {
        for (Class<?> c = target.getSuperclass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (matches(m, name, desc) && !Modifier.isAbstract(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) return m;
            }
        }
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        for (Class<?> c = target; c != null; c = c.getSuperclass()) queue.addAll(java.util.List.of(c.getInterfaces()));
        while (!queue.isEmpty()) {
            Class<?> itf = queue.poll();
            for (Method m : itf.getDeclaredMethods()) if (matches(m, name, desc) && m.isDefault()) return m;
            queue.addAll(java.util.List.of(itf.getInterfaces()));
        }
        return null;
    }

    private static boolean matches(Method m, String name, String desc) {
        return m.getName().equals(name)
                && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc);
    }

    /**
     * <pre>
     *   MethodHandle h = HotRuntime.finalOverride(this, key);
     *   if (h != null) return h.invokeExact(this, args...);
     *   ...original body...
     * </pre>
     */
    static void insertPrologue(String owner, MethodNode m) {
        Type[] args = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        InsnList p = new InsnList();
        LabelNode original = new LabelNode();
        p.add(new VarInsnNode(Opcodes.ALOAD, 0));
        p.add(new LdcInsnNode(key(owner, m.name, m.desc)));
        p.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CallSiteRewriter.RUNTIME, "finalOverride",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;"));
        p.add(new InsnNode(Opcodes.DUP));
        p.add(new JumpInsnNode(Opcodes.IFNULL, original));
        p.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (Type a : args) {
            p.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
            slot += a.getSize();
        }
        p.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                "(L" + owner + ";" + m.desc.substring(1)));
        p.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        p.add(original);
        p.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/invoke/MethodHandle"}));
        p.add(new InsnNode(Opcodes.POP));
        m.instructions.insert(p);
    }

    /**
     * For a hot-loaded class being defined: rename overrides of shimmed methods and fix
     * super calls to them. Returns true if anything changed.
     */
    static boolean rewriteClass(ClassNode node, Hierarchy hierarchy) {
        if (SHIMMED.isEmpty()) return false;
        boolean changed = false;
        for (MethodNode m : node.methods) {
            if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.name.startsWith("<")) continue;
            String owner = shimmedAncestor(node.superName, m.name, m.desc, hierarchy);
            if (owner == null) continue;
            HotRuntime.addFinalOverrider(key(owner, m.name, m.desc), node.name);
            m.name = PREFIX + m.name;
            m.access &= ~Opcodes.ACC_FINAL;
            changed = true;
        }
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || call.name.startsWith("<")) continue;
                String owner = shimmedAncestor(call.owner, call.name, call.desc, hierarchy);
                if (owner == null) continue;
                String k = key(owner, call.name, call.desc);
                // Nearest override at or above call.owner, else the original body.
                String target = null;
                for (String c = call.owner; c != null && !c.equals(owner); c = hierarchy.superOf(c)) {
                    if (HotRuntime.isFinalOverrider(k, c)) { target = c; break; }
                }
                if (target != null) {
                    call.owner = target;
                    call.name = PREFIX + call.name;
                } else {
                    m.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, CallSiteRewriter.RUNTIME,
                            "bypassNextFinal", "()V"));
                }
                changed = true;
            }
        }
        return changed;
    }

    /** The shimmed class at or above {@code start} that declares name+desc, or null. */
    private static String shimmedAncestor(String start, String name, String desc, Hierarchy hierarchy) {
        for (String c = start; c != null && !c.startsWith("java/"); c = hierarchy.superOf(c)) {
            if (isShimmed(c, name, desc)) return c;
        }
        return null;
    }
}
