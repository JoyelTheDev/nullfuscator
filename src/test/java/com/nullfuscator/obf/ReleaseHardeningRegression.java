package com.nullfuscator.obf;

import com.nullfuscator.obf.core.ObfConfig;
import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.transform.ReleaseHardeningTransformer;
import com.nullfuscator.obf.transform.ResourceRenamer;
import com.nullfuscator.obf.transform.AnnotationSanitizerTransformer;
import com.nullfuscator.obf.util.NameGenerator;
import com.nullfuscator.obf.util.ObfLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

public final class ReleaseHardeningRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ObfContext context = new ObfContext(ObfConfig.parse("""
                resourceRenamer { enabled: true, depth: 4, excludeNamespaces: [ "minecraft" ], excludeDirectories: [ "fonts" ] }
                releaseHardening { enabled: true }
                annotationSanitizer { enabled: true, descriptors: [ "fixture/Aliases" ] }
                """), 17L, new ObfLog(false), new NameGenerator(new String[] { "I", "l", "1" }));
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "Fixture";
        node.superName = "java/lang/Object";
        node.visibleAnnotations = new java.util.ArrayList<>(java.util.List.of(
                new AnnotationNode("Lfixture/Aliases;"), new AnnotationNode("Lfixture/Keep;")));
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup", "()V", null, null);
        method.instructions.add(new LdcInsnNode("/icons/menu/health.png"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        LdcInsnNode dynamicPrefix = new LdcInsnNode("fonts/");
        method.instructions.add(dynamicPrefix);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        context.putClass(node);
        context.resources().put("assets/fixture/icons/menu/health.png", new byte[] { 1, 2, 3 });
        context.resources().put("assets/fixture/ui.json",
                "{\"icon\":\"icons/menu/health.png\"}".getBytes(StandardCharsets.UTF_8));
        context.resources().put("assets/minecraft/textures/keep.png", new byte[] { 4 });
        context.resources().put("assets/fixture/fonts/inter-semi.png", new byte[] { 6 });
        context.resources().put("assets/fixture/fonts/inter-semi.json", "{}".getBytes(StandardCharsets.UTF_8));
        context.resources().put("META-INF/maven/example/pom.xml", new byte[] { 5 });
        context.resources().put("fabric.mod.json",
                "{\"id\":\"fixture\",\"description\":\"internal product text\"}".getBytes(StandardCharsets.UTF_8));

        new ResourceRenamer().transform(context);
        new ReleaseHardeningTransformer().transform(context);
        new AnnotationSanitizerTransformer().transform(context);

        check(context.resources().keySet().stream().noneMatch(p -> p.contains("icons/menu/health.png")),
                "semantic asset filename remains in archive");
        check(context.resources().containsKey("assets/minecraft/textures/keep.png"),
                "Minecraft namespace must be preserved");
        check(context.resources().containsKey("assets/fixture/" + dynamicPrefix.cst + "inter-semi.png")
                        && context.resources().containsKey("assets/fixture/" + dynamicPrefix.cst + "inter-semi.json"),
                "dynamic font lookup lost its directory or paired assets");
        String literal = (String) ((LdcInsnNode) method.instructions.getFirst()).cst;
        check(!literal.contains("icons/menu/health.png") && literal.endsWith(".png"),
                "class lookup was not remapped");
        String json = new String(context.resources().entrySet().stream()
                .filter(e -> e.getKey().endsWith(".json") && !e.getKey().equals("fabric.mod.json"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        check(!json.contains("icons/menu/health.png"), "JSON lookup was not remapped");
        check(context.resources().keySet().stream().noneMatch(p -> p.startsWith("META-INF/maven/")),
                "Maven metadata remains");
        String fabric = new String(context.resources().get("fabric.mod.json"), StandardCharsets.UTF_8);
        check(fabric.contains("\"description\":\"\"") && !fabric.contains("internal product text"),
                "Fabric description was not stripped");
        check(node.visibleAnnotations.size() == 1 && "Lfixture/Keep;".equals(node.visibleAnnotations.get(0).desc),
                "selected runtime annotation was not stripped");
        System.out.println("PASS release hardening: assets, lookups and metadata");
    }
}
