package dev.conduit.hotload;

import dev.conduit.hotload.jvm.Companions;
import dev.conduit.hotload.jvm.JvmAccess;
import net.minecraft.client.gui.components.SplashRenderer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The decisive spike check: change what an already-loaded Minecraft class does,
 * through code that lives in a separate hidden "companion" class, without
 * changing the loaded class's shape.
 *
 * <p>{@code SplashRenderer.render} reads its private {@code splash} field. We redefine
 * the method so that read goes through {@code invokedynamic} into a hidden nestmate
 * companion, which reads the same private field and prefixes it. The title screen's
 * existing SplashRenderer instance must show the new text.
 */
final class RedefineExperiment {
    private static final String HOST = Type.getInternalName(SplashRenderer.class);
    private static final String KEY = "spike/splash";

    private RedefineExperiment() {}

    static void run() throws Throwable {
        // 1. Companion: static String splash(SplashRenderer self) { return "Hot-swapped! " + self.splash; }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HOST + "$ConduitCompanion", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "splash", "(L" + HOST + ";)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("Hot-swapped! ");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "splash", "Ljava/lang/String;"); // private: needs nestmate access
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        Class<?> companion = Companions.defineNestmate(SplashRenderer.class, cw.toByteArray());
        MethodHandle handle = MethodHandles.privateLookupIn(companion, HotLoader.implLookup())
                .findStatic(companion, "splash", MethodType.methodType(String.class, SplashRenderer.class));
        Companions.bind(KEY, handle);

        // 2. Redefine render(): every "this.splash" read becomes invokedynamic -> companion.
        ClassNode node = new ClassNode();
        new ClassReader(JvmAccess.currentBytes(SplashRenderer.class)).accept(node, 0);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(Companions.class), "bootstrap",
                MethodType.methodType(java.lang.invoke.CallSite.class, MethodHandles.Lookup.class, String.class,
                        MethodType.class, String.class).toMethodDescriptorString(), false);
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("render")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                        && f.owner.equals(HOST) && f.name.equals("splash")) {
                    method.instructions.set(insn, new InvokeDynamicInsnNode("splash",
                            "(L" + HOST + ";)Ljava/lang/String;", bootstrap, KEY));
                    patched++;
                }
            }
        }
        if (patched == 0) throw new IllegalStateException("render() has no splash reads to patch");
        ClassWriter out = new ClassWriter(0); // same stack shape: maxs and frames stay valid
        node.accept(out);

        JvmAccess.addReads(SplashRenderer.class.getModule(), Companions.class.getModule());
        JvmAccess.redefine(SplashRenderer.class, out.toByteArray());
    }
}
