package com.nullfuscator.obf;

import com.nullfuscator.obf.core.*;
import com.nullfuscator.obf.transform.*;
import com.nullfuscator.obf.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.concurrent.*;

public final class RuntimeOverheadRegression implements Opcodes {
    public static class Fixture {
        public static int counter;
        public static int unread;
        public static final int CONSTANT = 17;
        public static int integer(int x) { return x < 0 ? x : x ^ 123; }
        public static long wide(long x) { return x; }
        public static float floating(float x) { return x; }
        public static double decimal(double x) { return x; }
        public static Object reference(Object x) { return x; }
        public static int[] array(int[] x) { return x; }
        public static void nothing() { counter++; }
        public static int recursion(int n) { return n == 0 ? 1 : n * recursion(n - 1); }
        public static int failure(int x) { return 100 / x; }
        public static int caught(int x) { try { return 100 / x; } catch (ArithmeticException e) { return -1; } }
    }

    private static ObfContext context(String config) {
        return new ObfContext(ObfConfig.parse(config), 42, new ObfLog(false),
                new NameGenerator(new String[]{"a", "b"}));
    }

    private static ClassNode fixture() throws Exception {
        ClassNode cn = new ClassNode();
        try (var in = RuntimeOverheadRegression.class.getResourceAsStream("RuntimeOverheadRegression$Fixture.class")) {
            new ClassReader(in).accept(cn, 0);
        }
        return cn;
    }

    private static Class<?> load(ObfContext ctx, String name) throws Exception {
        Map<String, byte[]> bytes = new HashMap<>();
        for (ClassNode cn : ctx.classes()) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(writer);
            bytes.put(cn.name.replace('/', '.'), writer.toByteArray());
        }
        return new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] data = bytes.get(name);
                if (data == null) throw new ClassNotFoundException(name);
                return defineClass(name, data, 0, data.length);
            }
        }.loadClass(name.replace('/', '.'));
    }

    private static void returns() throws Exception {
        ObfContext ctx = context("exceptionReturn { enabled:true, percent:100 }");
        ClassNode cn = fixture();
        ctx.putClass(cn);
        ctx.initializePolicies();
        MethodNode helper = new MethodNode(ACC_PUBLIC | ACC_STATIC, "helper", "()I", null, null);
        helper.instructions.add(new InsnNode(ICONST_1));
        helper.instructions.add(new InsnNode(IRETURN));
        cn.methods.add(helper);
        new ExceptionReturnTransformer().transform(ctx);
        check(helper.tryCatchBlocks.isEmpty());
        Class<?> cls = load(ctx, cn.name);
        for (int x : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
            check(cls.getMethod("integer", int.class).invoke(null, x).equals(Fixture.integer(x)));
        for (long x : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE})
            check(cls.getMethod("wide", long.class).invoke(null, x).equals(x));
        for (int bits : new int[]{0, 0x80000000, 0x7fc01234, 0xff800000}) {
            float value = (float) cls.getMethod("floating", float.class).invoke(null, Float.intBitsToFloat(bits));
            check(Float.floatToRawIntBits(value) == bits);
        }
        for (long bits : new long[]{0, Long.MIN_VALUE, 0x7ff8000000001234L, 0xfff0000000000000L}) {
            double value = (double) cls.getMethod("decimal", double.class).invoke(null, Double.longBitsToDouble(bits));
            check(Double.doubleToRawLongBits(value) == bits);
        }
        Object marker = new Object();
        check(cls.getMethod("reference", Object.class).invoke(null, marker) == marker);
        check(cls.getMethod("reference", Object.class).invoke(null, new Object[]{null}) == null);
        int[] array = {1, 2};
        check(cls.getMethod("array", int[].class).invoke(null, array) == array);
        cls.getMethod("nothing").invoke(null);
        check(cls.getField("counter").getInt(null) == 1);
        check(cls.getMethod("caught", int.class).invoke(null, 0).equals(-1));
        try {
            cls.getMethod("failure", int.class).invoke(null, 0);
            throw new AssertionError("application exception was swallowed");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(e.getCause() instanceof ArithmeticException);
        }
        var recursion = cls.getMethod("recursion", int.class);
        var reference = cls.getMethod("reference", Object.class);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) jobs.add(() -> {
                Object local = new Object();
                for (int i = 0; i < 2000; i++) {
                    check(recursion.invoke(null, 8).equals(40320));
                    check(reference.invoke(null, local) == local);
                }
                return null;
            });
            for (Future<Void> result : pool.invokeAll(jobs)) result.get();
        } finally { pool.shutdownNow(); }
        System.out.println("PASS compact exception returns: all types, raw bits, recursion, concurrency, exceptions");
    }

    private static void accessors() throws Exception {
        ObfContext ctx = context("fieldIndirection { enabled:true, percent:100 }");
        ClassNode cn = fixture();
        ctx.putClass(cn);
        Set<MethodNode> original = new HashSet<>(cn.methods);
        new FieldIndirectionTransformer().transform(ctx);
        long generated = cn.methods.stream().filter(m -> !original.contains(m)).count();
        check(generated == 2);
        Class<?> cls = load(ctx, cn.name);
        cls.getMethod("nothing").invoke(null);
        check(cls.getField("counter").getInt(null) == 1);
        System.out.println("PASS field indirection: only used accessors retained and executable");
    }

    private static void guards() throws Exception {
        ObfContext ctx = context("antiDebug { enabled:true, checkPercent:100, detectors:2 }");
        ClassNode cn = fixture();
        MethodNode input = cn.methods.stream().filter(m -> m.name.equals("integer")).findFirst().orElseThrow();
        input.access |= ACC_SYNTHETIC;
        ctx.putClass(cn);
        ctx.initializePolicies();
        MethodNode helper = new MethodNode(ACC_STATIC | ACC_PUBLIC, "helper", "()V", null, null);
        helper.instructions.add(new InsnNode(RETURN));
        cn.methods.add(helper);
        new AntiDebugTransformer().transform(ctx);
        check(helper.instructions.size() == 1);
        check(input.instructions.getFirst() instanceof MethodInsnNode call && call.name.equals("detected"));
        Class<?> cls = load(ctx, cn.name);
        check(cls.getMethod("integer", int.class).invoke(null, 1).equals(122));
        System.out.println("PASS debugger guards: input synthetic method protected, generated helper not guarded again");
    }

    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception { returns(); accessors(); guards(); }
}
