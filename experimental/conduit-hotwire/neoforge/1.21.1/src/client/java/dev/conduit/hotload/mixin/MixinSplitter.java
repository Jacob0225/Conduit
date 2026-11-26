package dev.conduit.hotload.mixin;

import dev.conduit.hotload.HotLoader;
import dev.conduit.hotload.jvm.Companions;
import dev.conduit.hotload.runtime.HotRuntime;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassDefinition;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Applies new mixins to classes that are already loaded, without changing their shape.
 *
 * <p>Input per class: its bytes as currently loaded, and the bytes the real Mixin library
 * produces for it now that the new configs are active ("patched"). The difference is split:
 * <ul>
 *   <li>methods present in both: take the patched body and redefine (a JVM-legal change);</li>
 *   <li>methods only in patched (injector handlers, {@code @Unique}, interface and accessor
 *       implementations, lambda bodies): move into a hidden nestmate companion as static
 *       methods ({@code this} becomes the first parameter);</li>
 *   <li>fields only in patched: per-object side store in {@link HotRuntime};</li>
 *   <li>interfaces only in patched: recorded, and every call site that relies on them is
 *       rewritten ({@link CallSiteRewriter});</li>
 *   <li>new static initialiser code: extracted and run once from the companion.</li>
 * </ul>
 */
public final class MixinSplitter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/MixinSplitter");

    /** Shared across hot-loads: later mods may use members moved for earlier ones. */
    static final ShapeRegistry REGISTRY = new ShapeRegistry();

    public record Target(Class<?> type, ClassNode current, ClassNode patched) {}

    public record Result(int classes, int changedBodies, int movedMethods, int movedFields, int interfaces,
                         int staticInits, List<String> notes) {}

    private static int companionCounter;
    /** "iface owner" pairs already registered with HotRuntime. */
    private static final java.util.Set<String> REGISTERED_IMPLEMENTERS = new java.util.HashSet<>();
    /** Static-init instructions already run from a companion, per class (so they never run twice). */
    private static final Map<String, List<String>> RAN_STATIC_INIT = new HashMap<>();

    private MixinSplitter() {}

    public static Result split(List<Target> targets, ClassLoader loader,
                               java.lang.instrument.Instrumentation inst) throws Throwable {
        Hierarchy hierarchy = new Hierarchy(loader);
        Function<String, String> superOf = hierarchy::superOf;
        CallSiteRewriter rewriter = new CallSiteRewriter(REGISTRY, hierarchy);
        List<String> notes = new ArrayList<>();
        int moved = 0, fields = 0, ifaces = 0, inits = 0, bodies = 0;

        List<Object[]> inheritedOverrides = new ArrayList<>();
        // Pass 1: work out what is new, so every later rewrite knows the full picture.
        Map<Target, List<MethodNode>> added = new LinkedHashMap<>();
        for (Target t : targets) {
            String owner = t.current().name;
            REGISTRY.splitClasses.put(owner, t.type());
            Map<String, MethodNode> current = byNameDesc(t.current().methods);
            List<MethodNode> newMethods = new ArrayList<>();
            for (MethodNode m : t.patched().methods) {
                if (current.containsKey(m.name + m.desc) || m.name.equals("<clinit>")) continue;
                // Moved by an earlier hot-load: its companion is already bound.
                if (REGISTRY.methods.getOrDefault(owner, Map.of()).containsKey(m.name + m.desc)) continue;
                if (m.name.equals("<init>")) throw new UnsupportedOperationException("mixin adds a constructor to " + owner);
                if ((m.access & Opcodes.ACC_STATIC) == 0 && (m.access & Opcodes.ACC_PRIVATE) == 0
                        && overridesInherited(t.type(), m)) {
                    // Calls still resolve to the inherited method: dispatch from there (pass 2b).
                    inheritedOverrides.add(new Object[]{t.type(), m.name, m.desc, ShapeRegistry.methodKey(owner, m.name, m.desc)});
                }
                newMethods.add(m);
                REGISTRY.methods.computeIfAbsent(owner, k -> new HashMap<>()).put(m.name + m.desc,
                        new ShapeRegistry.MovedMethod(ShapeRegistry.methodKey(owner, m.name, m.desc),
                                (m.access & Opcodes.ACC_STATIC) != 0));
            }
            added.put(t, newMethods);
            moved += newMethods.size();

            Map<String, FieldNode> currentFields = new HashMap<>();
            for (FieldNode f : t.current().fields) currentFields.put(f.name + ":" + f.desc, f);
            for (FieldNode f : t.patched().fields) {
                if (currentFields.containsKey(f.name + ":" + f.desc)) continue;
                // Moved by an earlier hot-load: keep its slot so stored values survive.
                if (REGISTRY.fields.getOrDefault(owner, Map.of()).containsKey(f.name + ":" + f.desc)) continue;
                boolean isStatic = (f.access & Opcodes.ACC_STATIC) != 0;
                int slot = HotRuntime.newSlot(f.desc);
                if (isStatic && f.value != null) HotRuntime.putStatic(f.value, slot); // ConstantValue
                REGISTRY.fields.computeIfAbsent(owner, k -> new HashMap<>())
                        .put(f.name + ":" + f.desc, new ShapeRegistry.MovedField(slot, isStatic, f.desc));
                fields++;
            }
            for (String i : t.patched().interfaces) {
                if (!t.current().interfaces.contains(i) && REGISTRY.interfaces.add(i)) ifaces++;
            }
        }

        // Pass 1b: added overrides of inherited methods dispatch from the inherited implementation
        // (before companions are rewritten, so super calls in them know the method is shimmed;
        // the prologue looks the companion up lazily, at call time).
        for (Object[] o : inheritedOverrides) {
            FinalShim.shimInherited((Class<?>) o[0], (String) o[1], (String) o[2], (String) o[3], inst);
        }

        // Pass 2: companions (hidden nestmates), bind their methods, run new static init code.
        for (Target t : targets) {
            String owner = t.current().name;
            List<MethodNode> newMethods = added.get(t);
            InsnList staticInit = extractNewStaticInit(find(t.current().methods, "<clinit>"),
                    find(t.patched().methods, "<clinit>"), notes, owner);
            if (newMethods.isEmpty() && staticInit == null) continue;

            ClassNode companion = new ClassNode();
            companion.version = Opcodes.V21;
            companion.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
            companion.name = owner + "$ConduitHot" + (++companionCounter);
            companion.superName = "java/lang/Object";
            companion.sourceFile = "ConduitHotCompanion.java"; // some code assumes every frame has a file name
            Map<String, String> movedDescs = new HashMap<>();
            for (MethodNode m : newMethods) {
                boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
                String desc = isStatic ? m.desc : "(L" + owner + ";" + m.desc.substring(1);
                MethodNode copy = new MethodNode(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, m.name, desc, null,
                        m.exceptions.toArray(String[]::new));
                m.accept(copy);
                copy.access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
                copy.desc = desc;
                copy.signature = null;
                copy.parameters = null;
                copy.visibleParameterAnnotations = null;
                copy.invisibleParameterAnnotations = null;
                copy.visibleAnnotableParameterCount = 0;
                copy.invisibleAnnotableParameterCount = 0;
                rewriter.rewrite(copy, owner, owner);
                companion.methods.add(copy);
                movedDescs.put(m.name + m.desc, desc);
            }
            if (staticInit != null) {
                MethodNode init = new MethodNode(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "conduit$clinit", "()V", null, null);
                init.instructions = staticInit;
                init.instructions.add(new InsnNode(Opcodes.RETURN));
                rewriter.rewrite(init, owner, owner);
                companion.methods.add(init);
                inits++;
            }
            ClassWriter writer = new TclClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            companion.accept(writer);
            Class<?> type = Companions.defineNestmate(t.type(), writer.toByteArray());
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, HotLoader.implLookup());
            for (MethodNode m : newMethods) {
                MethodType mt = MethodType.fromMethodDescriptorString(movedDescs.get(m.name + m.desc), loader);
                HotRuntime.bindMethod(ShapeRegistry.methodKey(owner, m.name, m.desc), lookup.findStatic(type, m.name, mt));
            }
            if (staticInit != null) {
                lookup.findStatic(type, "conduit$clinit", MethodType.methodType(void.class)).invoke();
            }
        }

        // Pass 3: register which loaded classes "implement" the interfaces they couldn't get.
        for (Target t : targets) {
            String owner = t.current().name;
            for (String iface : t.patched().interfaces) {
                if (t.current().interfaces.contains(iface)) continue;
                if (!REGISTERED_IMPLEMENTERS.add(iface + " " + owner)) continue;
                Class<?> ifaceType = Class.forName(iface.replace('/', '.'), false, loader);
                Map<String, String> keys = new HashMap<>();
                MethodHandles.Lookup hostLookup = MethodHandles.privateLookupIn(t.type(), HotLoader.implLookup());
                for (Method im : ifaceType.getMethods()) {
                    if (Modifier.isStatic(im.getModifiers())) continue;
                    String sig = im.getName() + MethodType.methodType(im.getReturnType(), im.getParameterTypes()).toMethodDescriptorString();
                    ShapeRegistry.MovedMethod mm = REGISTRY.method(owner, im.getName(), sig.substring(im.getName().length()), superOf);
                    if (mm != null) {
                        keys.put(sig, mm.key());
                    } else if (!im.isDefault()) {
                        // Implemented by a method the class already had.
                        String key = "existing:" + owner + "." + sig;
                        HotRuntime.bindMethod(key, hostLookup.findVirtual(t.type(), im.getName(),
                                MethodType.methodType(im.getReturnType(), im.getParameterTypes())));
                        keys.put(sig, key);
                    }
                }
                HotRuntime.addImplementer(iface, t.type(), keys);
            }
        }

        // Pass 4: same shape, new bodies. Redefine all classes in one atomic call.
        List<ClassDefinition> definitions = new ArrayList<>();
        for (Target t : targets) {
            ClassNode out = t.current();
            Map<String, MethodNode> patched = byNameDesc(t.patched().methods);
            for (MethodNode m : out.methods) {
                MethodNode p = patched.get(m.name + m.desc);
                if (p != null && !m.name.equals("<clinit>") && !sameCode(m, p)) {
                    m.instructions = p.instructions;
                    m.tryCatchBlocks = p.tryCatchBlocks;
                    m.localVariables = p.localVariables;
                    m.visibleLocalVariableAnnotations = p.visibleLocalVariableAnnotations;
                    m.invisibleLocalVariableAnnotations = p.invisibleLocalVariableAnnotations;
                    m.visibleTypeAnnotations = p.visibleTypeAnnotations;
                    m.invisibleTypeAnnotations = p.invisibleTypeAnnotations;
                    m.maxLocals = p.maxLocals;
                    m.maxStack = p.maxStack;
                    bodies++;
                    // A new body from Mixin lost the final-override prologue: put it back.
                    if (FinalShim.isShimmed(out.name, m.name, m.desc)) FinalShim.insertPrologue(out.name, m);
                }
                rewriter.rewrite(m, null, out.name);
            }
            ClassWriter writer = new TclClassWriter(ClassWriter.COMPUTE_MAXS, loader);
            out.accept(writer);
            definitions.add(new ClassDefinition(t.type(), writer.toByteArray()));
        }
        inst.redefineClasses(definitions.toArray(ClassDefinition[]::new));
        LOGGER.info("Split {} loaded classes: {} bodies changed, {} methods moved, {} fields moved, {} interfaces, {} static inits",
                targets.size(), bodies, moved, fields, ifaces, inits);
        return new Result(targets.size(), bodies, moved, fields, ifaces, inits, notes);
    }

    private static boolean overridesInherited(Class<?> type, MethodNode m) {
        if ((m.access & Opcodes.ACC_PRIVATE) != 0) return false;
        java.util.ArrayDeque<Class<?>> itfs = new java.util.ArrayDeque<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) itfs.addAll(List.of(c.getInterfaces()));
        while (!itfs.isEmpty()) {
            Class<?> itf = itfs.poll();
            for (Method d : itf.getDeclaredMethods()) {
                if (d.isDefault() && d.getName().equals(m.name) && MethodType.methodType(d.getReturnType(),
                        d.getParameterTypes()).toMethodDescriptorString().equals(m.desc)) return true;
            }
            itfs.addAll(List.of(itf.getInterfaces()));
        }
        for (Class<?> c = type.getSuperclass(); c != null; c = c.getSuperclass()) {
            for (Method declared : c.getDeclaredMethods()) {
                if (declared.getName().equals(m.name) && !Modifier.isPrivate(declared.getModifiers())
                        && !Modifier.isStatic(declared.getModifiers())
                        && MethodType.methodType(declared.getReturnType(), declared.getParameterTypes())
                        .toMethodDescriptorString().equals(m.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, MethodNode> byNameDesc(List<MethodNode> methods) {
        Map<String, MethodNode> out = new HashMap<>();
        for (MethodNode m : methods) out.put(m.name + m.desc, m);
        return out;
    }

    private static MethodNode find(List<MethodNode> methods, String name) {
        for (MethodNode m : methods) if (m.name.equals(name)) return m;
        return null;
    }

    private static boolean sameCode(MethodNode a, MethodNode b) {
        return normalize(a.instructions).equals(normalize(b.instructions));
    }

    /**
     * The part of the patched static initialiser that the loaded class never ran: the
     * longest common subsequence of real instructions is the code both share; the rest
     * is what new mixins appended (Mixin merges their {@code <clinit>} code into the target's).
     */
    private static InsnList extractNewStaticInit(MethodNode current, MethodNode patched, List<String> notes, String owner) {
        if (patched == null) return null;
        List<AbstractInsnNode> b = real(patched.instructions);
        List<AbstractInsnNode> a = current == null ? List.of() : real(current.instructions);
        List<String> na = new ArrayList<>(a.stream().map(MixinSplitter::describe).toList());
        na.addAll(RAN_STATIC_INIT.getOrDefault(owner, List.of()));
        List<String> nb = b.stream().map(MixinSplitter::describe).toList();
        if ((long) na.size() * nb.size() > 40_000_000L) {
            notes.add("static init of " + owner + " too large to diff; new static init skipped");
            return null;
        }
        boolean[] matched = lcsMatches(na, nb);
        InsnList out = new InsnList();
        Map<org.objectweb.asm.tree.LabelNode, org.objectweb.asm.tree.LabelNode> labels = new HashMap<>();
        for (int i = 0; i < b.size(); i++) {
            AbstractInsnNode insn = b.get(i);
            if (matched[i] || insn.getOpcode() == Opcodes.RETURN) continue;
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                notes.add("new static init of " + owner + " has branches; skipped");
                return null;
            }
            out.add(insn.clone(labels));
        }
        if (out.size() == 0) return null;
        RAN_STATIC_INIT.computeIfAbsent(owner, k -> new ArrayList<>()).addAll(normalize(out));
        return out;
    }

    private static List<AbstractInsnNode> real(InsnList list) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode insn : list) if (insn.getOpcode() >= 0) out.add(insn);
        return out;
    }

    private static List<String> normalize(InsnList list) {
        return real(list).stream().map(MixinSplitter::describe).toList();
    }

    private static String describe(AbstractInsnNode insn) {
        String op = Integer.toString(insn.getOpcode());
        if (insn instanceof MethodInsnNode m) return op + " " + m.owner + "." + m.name + m.desc;
        if (insn instanceof FieldInsnNode f) return op + " " + f.owner + "." + f.name + ":" + f.desc;
        if (insn instanceof TypeInsnNode t) return op + " " + t.desc;
        if (insn instanceof LdcInsnNode l) return op + " " + l.cst;
        if (insn instanceof IntInsnNode n) return op + " " + n.operand;
        if (insn instanceof VarInsnNode v) return op + " " + v.var;
        if (insn instanceof IincInsnNode n) return op + " " + n.var + " " + n.incr;
        if (insn instanceof InvokeDynamicInsnNode d) return op + " " + d.name + d.desc + " " + d.bsm;
        if (insn instanceof MultiANewArrayInsnNode n) return op + " " + n.desc + " " + n.dims;
        return op;
    }

    private static boolean[] lcsMatches(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        boolean[] matched = new boolean[m];
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) { matched[j] = true; i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
            else j++;
        }
        return matched;
    }
}
