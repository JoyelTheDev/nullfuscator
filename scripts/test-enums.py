#!/usr/bin/env python3
"""Regression for enum symbol renaming and runtime identities (offline)."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "build/nullfuscator-obf.jar"

with tempfile.TemporaryDirectory(prefix="enum-regression-") as directory:
    work = Path(directory)
    source = work / "EnumFixture.java"
    source.write_text('''
import java.io.*;
import java.lang.annotation.*;
import java.util.*;
interface Paused { boolean shouldPauseCombat(); }
enum Mode implements Paused {
    COMBAT { public int score() { return 7; } }, RENDER;
    public int combatPauseTicks;
    public void tickCombatPause() { combatPauseTicks--; }
    public boolean shouldPauseCombat() { return combatPauseTicks > 0; }
    public int score() { return 3; }
}
enum External { PLAYER }
@Retention(RetentionPolicy.RUNTIME)
@interface Marker { Mode value(); }
@Marker(Mode.COMBAT)
public class EnumFixture {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        boolean hidden = Boolean.parseBoolean(args[0]);
        check(Mode.COMBAT.name().equals("COMBAT"));
        check(Mode.valueOf("COMBAT") == Mode.COMBAT);
        check(Enum.valueOf(Mode.class, "RENDER") == Mode.RENDER);
        check(Arrays.equals(Mode.values(), Mode.class.getEnumConstants()));
        check(Mode.COMBAT.ordinal() == 0 && Mode.RENDER.ordinal() == 1);
        check(Mode.COMBAT.score() == 7 && Mode.RENDER.score() == 3);
        Mode.COMBAT.combatPauseTicks = 2;
        Mode.COMBAT.tickCombatPause();
        check(((Paused) Mode.COMBAT).shouldPauseCombat());
        Mode.COMBAT.tickCombatPause();
        check(!Mode.COMBAT.shouldPauseCombat());
        check(EnumSet.allOf(Mode.class).size() == 2);
        EnumMap<Mode, Integer> map = new EnumMap<>(Mode.class);
        map.put(Mode.COMBAT, 17);
        check(map.get(Mode.COMBAT) == 17);
        check(EnumFixture.class.getAnnotation(Marker.class).value() == Mode.COMBAT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new ObjectOutputStream(bytes).writeObject(Mode.COMBAT);
        check(new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject() == Mode.COMBAT);
        var fields = Arrays.stream(Mode.class.getDeclaredFields()).map(f -> f.getName()).toList();
        check(fields.contains("COMBAT") != hidden);
        check(!fields.contains("combatPauseTicks"));
        check(External.class.getField("PLAYER").get(null) == External.PLAYER);
        var methods = Arrays.stream(Mode.class.getDeclaredMethods()).map(m -> m.getName()).toList();
        check(!methods.contains("tickCombatPause") && !methods.contains("shouldPauseCombat"));
        check(methods.contains("values") && methods.contains("valueOf"));
        System.out.println("enum contracts preserved");
    }
}
''', encoding="utf-8")
    subprocess.run(["javac", "--release", "17", "-d", str(work), str(source)], check=True)
    original = work / "input.jar"
    entries = [arg for p in sorted(work.glob("*.class")) for arg in ("-C", str(work), p.name)]
    subprocess.run(["jar", "--create", "--file", str(original), "--main-class", "EnumFixture", *entries], check=True)
    for hide in (False, True):
        config = work / "enum.hocon"
        config.write_text('''
methodRenamer { enabled:true, renameVirtual:true, renamePublic:true }
fieldRenamer { enabled:true, enumConstantsInclude: %s }
classRenamer { enabled:true }
stringEncryption { enabled:true }
''' % ('["class{^Mode$}"]' if hide else '[]'), encoding="utf-8")
        output = work / "output.jar"
        subprocess.run(["java", "-jar", str(JAR), "-i", str(original), "-o", str(output),
                        "-c", str(config), "--seed", "27", "--no-mapping"], check=True,
                       stdout=subprocess.DEVNULL)
        actual = subprocess.check_output(["java", "-Xverify:all", "-jar", str(output), str(hide).lower()])
        assert actual == b"enum contracts preserved\n", actual
        print("PASS enum methods, fields, factories, subclass dispatch, annotations, serialization; constants:", hide)
