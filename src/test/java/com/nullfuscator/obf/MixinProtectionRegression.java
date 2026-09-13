package com.nullfuscator.obf;

import com.nullfuscator.obf.core.*;
import com.nullfuscator.obf.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.TraceClassVisitor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class MixinProtectionRegression {
    public static void main(String[] args) throws Exception {
        var factory = Main.class.getDeclaredMethod("buildPipeline");
        factory.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Transformer> pipeline = (List<Transformer>) factory.invoke(null);
        for (boolean visible : new boolean[] { false, true }) {
            ObfContext ctx = new ObfContext(ObfConfig.loadPreset("full"), 42,
                    new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));
            ClassNode mixin = new ClassNode();
            mixin.version = Opcodes.V17;
            mixin.access = Opcodes.ACC_PUBLIC;
            mixin.name = "fixture/InjectedCode";
            mixin.superName = "java/lang/Object";
            var annotations = new ArrayList<>(List.of(new AnnotationNode(
                    "Lorg/spongepowered/asm/mixin/Mixin;")));
            if (visible) mixin.visibleAnnotations = annotations;
            else mixin.invisibleAnnotations = annotations;
            FieldNode field = new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    "targetField", "I", null, null);
            field.invisibleAnnotations = new ArrayList<>(List.of(
                    new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;"),
                    new AnnotationNode("Lorg/spongepowered/asm/mixin/Final;")));
            mixin.fields.add(field);
            MethodNode hook = new MethodNode(Opcodes.ACC_PRIVATE, "hook", "()Ljava/lang/String;", null, null);
            hook.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, mixin.name, field.name, "I"));
            hook.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false));
            hook.instructions.add(new InsnNode(Opcodes.ARETURN));
            hook.maxStack = 1;
            hook.maxLocals = 1;
            mixin.methods.add(hook);
            ctx.putClass(mixin);
            String before = trace(mixin);
            new ObfEngine(pipeline).run(ctx);
            ClassNode result = ctx.getClass(mixin.name);
            if (result == null || !before.equals(trace(result)))
                throw new AssertionError("full pipeline changed injectable Mixin code");
            System.out.println("PASS full pipeline preserves Mixin hooks and @Final shadows; visible=" + visible);
        }
    }

    private static String trace(ClassNode node) {
        StringWriter out = new StringWriter();
        node.accept(new TraceClassVisitor(new PrintWriter(out)));
        return out.toString();
    }
}
