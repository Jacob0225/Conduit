package dev.conduit.hotload.mixin;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.function.Function;

/**
 * Redirects code that uses members a mixin could not add to an already-loaded class:
 * <ul>
 *   <li>calls to moved methods → {@code invokedynamic} into the companion;</li>
 *   <li>lambdas whose body moved → {@code HotRuntime.metafactory};</li>
 *   <li>reads/writes of moved fields → {@code HotRuntime} side store;</li>
 *   <li>{@code checkcast} to a missing interface → removed (the verifier treats interface
 *       types like {@code Object}), {@code instanceof} and interface calls → {@code HotRuntime}.</li>
 * </ul>
 * Only changes instructions; the stack shape at every branch target stays the same,
 * so existing stack map frames remain valid (max stack is recomputed by the writer).
 */
final class CallSiteRewriter {

    static final String RUNTIME = "dev/conduit/hotload/runtime/HotRuntime";
    private static final String LOOKUP = "Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
    private static final Handle INVOKE = bsm("invoke", "(" + LOOKUP + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;");
    private static final Handle IFACE_CALL = bsm("ifaceCall",
            "(" + LOOKUP + "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;");
    private static final Handle INSTANCE_OF = bsm("instanceOf", "(" + LOOKUP + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;");
    private static final Handle METAFACTORY = bsm("metafactory", "(" + LOOKUP
            + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private final ShapeRegistry registry;
    private static final Handle SPECIAL = bsm("special",
            "(" + LOOKUP + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;");
    private static final Handle ACCESS = bsm("access",
            "(" + LOOKUP + "ILjava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;");
    // Kinds understood by HotRuntime.access.
    static final int GET_FIELD = 1, PUT_FIELD = 2, GET_STATIC = 3, PUT_STATIC = 4, INVOKE_VIRTUAL = 5,
            INVOKE_STATIC = 6, NEW = 7, CAST = 8, KIND_INSTANCE_OF = 9, CLASS_CONSTANT = 10, NEW_ARRAY = 11;

    private final Function<String, String> superOf;
    private final Hierarchy hierarchy;
    final AccessInfo access;
    private final java.util.function.Predicate<String> isHotClass;

    CallSiteRewriter(ShapeRegistry registry, Hierarchy hierarchy) {
        this.registry = registry;
        this.hierarchy = hierarchy;
        this.superOf = hierarchy::superOf;
        this.access = new AccessInfo(hierarchy.loader(), hierarchy);
        this.isHotClass = HotMixins::isHotClass;
    }

    private static Handle bsm(String name, String desc) {
        return new Handle(Opcodes.H_INVOKESTATIC, RUNTIME, name, desc, false);
    }

    /**
     * @param companionOf internal name of the split class when {@code method} now lives in its
     *                    companion (so {@code invokespecial} on the host's private methods must
     *                    become {@code invokevirtual}), else null
     * @return true if anything changed
     */
    boolean rewrite(MethodNode method, String companionOf) {
        return rewrite(method, companionOf, null);
    }

    /**
     * @param from when non-null, the class the code runs as: references it may not make to
     *             already-loaded classes (their access flags predate the mod's ATs and Mixin's
     *             accessor widening) are rerouted through privileged method handles
     */
    /** Name of the method being rewritten (final fields may only be written in &lt;init&gt;/&lt;clinit&gt;). */
    private String currentMethod = "";

    boolean rewrite(MethodNode method, String companionOf, String from) {
        currentMethod = method.name;
        boolean changed = from != null && rerouteConstructors(method.instructions, from);
        boolean fromHot = from != null && isHotClass.test(from);
        InsnList insns = method.instructions;
        for (AbstractInsnNode insn : insns.toArray()) {
            if (from != null && rerouteAccess(insns, insn, from)) {
                changed = true;
            } else if (!fromHot && from != null && eraseHotDescriptor(insn)) {
                changed = true;
            } else if (insn instanceof MethodInsnNode call) {
                changed |= rewriteCall(insns, call, companionOf);
            } else if (insn instanceof FieldInsnNode field) {
                changed |= rewriteField(insns, field);
            } else if (insn instanceof TypeInsnNode type && registry.interfaces.contains(type.desc)) {
                if (type.getOpcode() == Opcodes.CHECKCAST) {
                    insns.remove(type);
                    changed = true;
                } else if (type.getOpcode() == Opcodes.INSTANCEOF) {
                    insns.set(type, new InvokeDynamicInsnNode("instanceOf", "(Ljava/lang/Object;)Z", INSTANCE_OF, type.desc));
                    changed = true;
                }
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                changed |= rewriteLambda(insns, indy);
            }
        }
        return changed;
    }

    // ── References hot code may not make to already-loaded classes ───────────

    private boolean rerouteAccess(InsnList insns, AbstractInsnNode insn, String from) {
        if (insn instanceof FieldInsnNode f) {
            if (isHotClass.test(f.owner)) return false;
            if (!access.inaccessibleMember(from, f.owner, f.name, f.desc, true)
                    && !access.descriptorInaccessible(f.desc, from)
                    && !illegalFinalWrite(f, from)) return false;
            String decl = access.declaring(f.owner, f.name, f.desc, true);
            int kind; String desc;
            switch (f.getOpcode()) {
                case Opcodes.GETFIELD -> { kind = GET_FIELD; desc = "(Ljava/lang/Object;)" + f.desc; }
                case Opcodes.PUTFIELD -> { kind = PUT_FIELD; desc = "(Ljava/lang/Object;" + f.desc + ")V"; }
                case Opcodes.GETSTATIC -> { kind = GET_STATIC; desc = "()" + f.desc; }
                default -> { kind = PUT_STATIC; desc = "(" + f.desc + ")V"; }
            }
            insns.set(f, new InvokeDynamicInsnNode(f.name, desc, ACCESS, kind, decl, f.desc));
            return true;
        }
        if (insn instanceof MethodInsnNode m && !m.name.startsWith("<") && m.getOpcode() != Opcodes.INVOKESPECIAL) {
            if (isHotClass.test(m.owner)) return false;
            if (!access.inaccessibleMember(from, m.owner, m.name, m.desc, false)
                    && !access.descriptorInaccessible(m.desc, from)) return false;
            if (registry.method(m.owner, m.name, m.desc, superOf) != null) return false; // moved: handled below
            String decl = access.declaring(m.owner, m.name, m.desc, false);
            boolean isStatic = m.getOpcode() == Opcodes.INVOKESTATIC;
            String desc = isStatic ? m.desc : "(Ljava/lang/Object;" + m.desc.substring(1);
            insns.set(m, new InvokeDynamicInsnNode(m.name, desc, ACCESS, isStatic ? INVOKE_STATIC : INVOKE_VIRTUAL, decl, m.desc));
            return true;
        }
        if (insn instanceof TypeInsnNode t && t.getOpcode() != Opcodes.NEW) {
            String owner = t.desc;
            if (owner.startsWith("[") || !access.inaccessibleClass(owner, from)) return false;
            switch (t.getOpcode()) {
                case Opcodes.CHECKCAST -> insns.set(t, new InvokeDynamicInsnNode("cast", "(Ljava/lang/Object;)L" + owner + ";", ACCESS, CAST, owner, ""));
                case Opcodes.INSTANCEOF -> insns.set(t, new InvokeDynamicInsnNode("instanceOf", "(Ljava/lang/Object;)Z", ACCESS, KIND_INSTANCE_OF, owner, ""));
                case Opcodes.ANEWARRAY -> insns.set(t, new InvokeDynamicInsnNode("newArray", "(I)[L" + owner + ";", ACCESS, NEW_ARRAY, owner, ""));
                default -> { return false; }
            }
            return true;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
            String owner = type.getInternalName();
            if (!access.inaccessibleClass(owner, from)) return false;
            insns.set(ldc, new InvokeDynamicInsnNode("type", "()Ljava/lang/Class;", ACCESS, CLASS_CONSTANT, owner, ""));
            return true;
        }
        return false;
    }

    /**
     * A write to a field that is final in the loaded class (Mixin's {@code @Mutable} and AT
     * {@code -f} came too late) is only legal in the declaring class's own initialiser.
     */
    private boolean illegalFinalWrite(FieldInsnNode f, String from) {
        int op = f.getOpcode();
        if (op != Opcodes.PUTFIELD && op != Opcodes.PUTSTATIC) return false;
        if (f.owner.startsWith("java/") || !hierarchy.isLoaded(f.owner) || !access.finalField(f.owner, f.name)) return false;
        String decl = access.declaring(f.owner, f.name, f.desc, true);
        String initializer = op == Opcodes.PUTFIELD ? "<init>" : "<clinit>";
        return !(decl.equals(from) && currentMethod.equals(initializer));
    }

    /**
     * In code that is not hot-loaded (companions, patched Minecraft bodies), calls into hot classes
     * must use their erased descriptors (see {@link #eraser}).
     */
    private boolean eraseHotDescriptor(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode m && isHotClass.test(m.owner)) {
            String erased = eraser(m.owner).mapMethodDesc(m.desc);
            if (erased.equals(m.desc)) return false;
            m.desc = erased;
            return true;
        }
        if (insn instanceof FieldInsnNode f && isHotClass.test(f.owner)) {
            String erased = eraser(f.owner).mapDesc(f.desc);
            if (erased.equals(f.desc)) return false;
            f.desc = erased;
            return true;
        }
        return false;
    }

    /** Maps classes that {@code from} may not name to their nearest public superclass. */
    org.objectweb.asm.commons.Remapper eraser(String from) {
        return new org.objectweb.asm.commons.Remapper() {
            @Override
            public String map(String internalName) {
                return access.inaccessibleClass(internalName, from) ? access.erasure(internalName) : internalName;
            }
        };
    }

    /**
     * {@code new C; dup; args...; invokespecial C.<init>} that {@code from} may not perform becomes
     * {@code args...; invokedynamic new(args)C}. Skipped when a stack map frame sits in between
     * (it would mention the uninitialised object).
     */
    private boolean rerouteConstructors(InsnList insns, String from) {
        boolean changed = false;
        java.util.ArrayDeque<TypeInsnNode> pending = new java.util.ArrayDeque<>();
        for (AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) {
                pending.push(t);
            } else if (insn instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKESPECIAL && m.name.equals("<init>")
                    && !pending.isEmpty() && pending.peek().desc.equals(m.owner)) {
                TypeInsnNode alloc = pending.pop();
                if (isHotClass.test(m.owner)) continue;
                if (!access.inaccessibleMember(from, m.owner, "<init>", m.desc, false)
                        && !access.descriptorInaccessible(m.desc, from)) continue;
                AbstractInsnNode dup = alloc.getNext();
                while (dup != null && dup.getOpcode() < 0) dup = dup.getNext();
                if (dup == null || dup.getOpcode() != Opcodes.DUP) continue;
                boolean frameBetween = false;
                for (AbstractInsnNode i = dup; i != m; i = i.getNext()) {
                    if (i instanceof org.objectweb.asm.tree.FrameNode) { frameBetween = true; break; }
                }
                if (frameBetween) continue;
                insns.remove(alloc);
                insns.remove(dup);
                String desc = m.desc.substring(0, m.desc.lastIndexOf(')') + 1) + "L" + m.owner + ";";
                insns.set(m, new InvokeDynamicInsnNode("new", desc, ACCESS, NEW, m.owner, m.desc));
                changed = true;
            }
        }
        return changed;
    }

    private boolean rewriteCall(InsnList insns, MethodInsnNode call, String companionOf) {
        if (call.name.equals("<init>") || call.name.equals("<clinit>")) return false;
        ShapeRegistry.MovedMethod moved = registry.method(call.owner, call.name, call.desc, superOf);
        if (moved != null) {
            String desc = moved.isStatic() ? call.desc
                    : "(L" + call.owner + ";" + call.desc.substring(1);
            insns.set(call, new InvokeDynamicInsnNode(call.name, desc, INVOKE, moved.key()));
            return true;
        }
        if (call.getOpcode() == Opcodes.INVOKEINTERFACE && registry.interfaces.contains(call.owner)) {
            String desc = "(Ljava/lang/Object;" + call.desc.substring(1);
            insns.set(call, new InvokeDynamicInsnNode(call.name, desc, IFACE_CALL, call.owner, call.desc));
            return true;
        }
        if (companionOf != null && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(companionOf)) {
            // A private call on the host from its companion: nestmates use invokevirtual.
            call.setOpcode(Opcodes.INVOKEVIRTUAL);
            return true;
        }
        if (companionOf != null && call.getOpcode() == Opcodes.INVOKESPECIAL) {
            // super.m() from code moved out of the host: only the host may invokespecial its
            // superclass, so use a findSpecial handle with the host as special caller. If m has a
            // dispatch prologue it would send the call straight back to an override: bypass it once.
            if (shimmedAbove(call)) {
                insns.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "bypassNextFinal", "()V"));
            }
            String desc = "(L" + companionOf + ";" + call.desc.substring(1);
            insns.set(call, new InvokeDynamicInsnNode(call.name, desc, SPECIAL, call.owner, call.desc, companionOf));
            return true;
        }
        if (false) {
            throw new UnsupportedOperationException("super call " + call.owner + "." + call.name + call.desc
                    + " in code moved out of " + companionOf);
        }
        return false;
    }

    private boolean shimmedAbove(MethodInsnNode call) {
        for (String c = call.owner; c != null && !c.startsWith("java/"); c = superOf.apply(c)) {
            if (FinalShim.isShimmed(c, call.name, call.desc)) return true;
        }
        return FinalShim.isShimmedAnywhere(call.name, call.desc);
    }

    private boolean rewriteField(InsnList insns, FieldInsnNode field) {
        ShapeRegistry.MovedField moved = registry.field(field.owner, field.name, field.desc, superOf);
        if (moved == null) return false;
        Type type = Type.getType(field.desc);
        InsnList replacement = new InsnList();
        switch (field.getOpcode()) {
            case Opcodes.GETFIELD -> {
                replacement.add(new LdcInsnNode(moved.slot()));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "getField", "(Ljava/lang/Object;I)Ljava/lang/Object;"));
                unbox(replacement, type);
            }
            case Opcodes.GETSTATIC -> {
                replacement.add(new LdcInsnNode(moved.slot()));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "getStatic", "(I)Ljava/lang/Object;"));
                unbox(replacement, type);
            }
            case Opcodes.PUTFIELD -> {
                box(replacement, type);
                replacement.add(new LdcInsnNode(moved.slot()));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "putField", "(Ljava/lang/Object;Ljava/lang/Object;I)V"));
            }
            case Opcodes.PUTSTATIC -> {
                box(replacement, type);
                replacement.add(new LdcInsnNode(moved.slot()));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "putStatic", "(Ljava/lang/Object;I)V"));
            }
            default -> { return false; }
        }
        insns.insert(field, replacement);
        insns.remove(field);
        return true;
    }

    private boolean rewriteLambda(InsnList insns, InvokeDynamicInsnNode indy) {
        if (!indy.bsm.getOwner().equals(LAMBDA_METAFACTORY) || indy.bsmArgs.length < 3) return false;
        if (!(indy.bsmArgs[1] instanceof Handle impl)) return false;
        ShapeRegistry.MovedMethod moved = registry.method(impl.getOwner(), impl.getName(), impl.getDesc(), superOf);
        if (moved == null) return false;
        if (!indy.bsm.getName().equals("metafactory")) {
            throw new UnsupportedOperationException("altMetafactory lambda over moved method " + impl.getName());
        }
        insns.set(indy, new InvokeDynamicInsnNode(indy.name, indy.desc, METAFACTORY,
                indy.bsmArgs[0], moved.key(), indy.bsmArgs[2]));
        return true;
    }

    private static void box(InsnList out, Type type) {
        String wrapper = wrapper(type);
        if (wrapper == null) return;
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(" + type.getDescriptor() + ")L" + wrapper + ";"));
    }

    private static void unbox(InsnList out, Type type) {
        String wrapper = wrapper(type);
        if (wrapper == null) {
            if (!type.getInternalName().equals("java/lang/Object")) {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
            }
            return;
        }
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, type.getClassName() + "Value", "()" + type.getDescriptor()));
    }

    private static String wrapper(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }
}
