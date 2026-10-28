package com.jeevitesh.airscroll;

import java.util.ArrayList;
import java.util.List;

public class GestureTest {
    static int n = 0;
    static void ok(String m) { System.out.println("ok " + ++n + " - " + m); }
    static void eq(Object a, Object b, String m) { if (!String.valueOf(a).equals(String.valueOf(b))) throw new AssertionError(m + ": expected " + b + " got " + a); }
    static void near(float a, float b, String m) { if (Math.abs(a - b) > 1e-3) throw new AssertionError(m + ": expected " + b + " got " + a); }

    static final int U = GestureLogic.UP, D = GestureLogic.DOWN, L = GestureLogic.LEFT, R = GestureLogic.RIGHT, DT = GestureLogic.DOUBLE_TAP, OPEN = GestureLogic.OPEN;

    /** hand pointing up, wrist at the bottom. mask bit0 index, bit1 middle, bit2 ring, bit3 pinky. Shifted by (dx,dy). */
    static float[][] hand(int mask, boolean thumb, float dx, float dy) {
        float[] x = new float[21], y = new float[21];
        for (int i = 0; i < 21; i++) { x[i] = 0.5f; y[i] = 0.6f; }
        x[0] = 0.5f; y[0] = 0.8f;
        for (int f = 0; f < 4; f++) {
            float mx = 0.45f + 0.05f * f; boolean ext = ((mask >> f) & 1) == 1;
            x[5 + 4 * f] = mx; y[5 + 4 * f] = 0.6f;
            x[6 + 4 * f] = mx; y[6 + 4 * f] = 0.5f;
            x[8 + 4 * f] = mx; y[8 + 4 * f] = ext ? 0.3f : 0.58f;
        }
        x[2] = 0.42f; y[2] = 0.7f;
        if (thumb) { x[3] = 0.33f; y[3] = 0.65f; x[4] = 0.25f; y[4] = 0.6f; }
        else { x[3] = 0.45f; y[3] = 0.68f; x[4] = 0.52f; y[4] = 0.64f; }
        for (int i = 0; i < 21; i++) { x[i] += dx; y[i] += dy; }
        return new float[][]{x, y};
    }

    static final int FIVE = 15;   // mask for 4 fingers, + thumb flag for 5
    static List<Integer> run(GestureLogic g, int mask, boolean thumb, long t0, long t1, boolean ptr) {
        List<Integer> out = new ArrayList<>();
        for (long t = t0; t <= t1; t += 66) { float[][] h = hand(mask, thumb, 0, 0); int r = g.feed(h[0], h[1], t, ptr); if (r != 0) out.add(r); }
        return out;
    }
    static List<Integer> lost(GestureLogic g, long t0, long t1, boolean ptr) {
        List<Integer> out = new ArrayList<>();
        for (long t = t0; t <= t1; t += 66) { int r = g.feed(null, null, t, ptr); if (r != 0) out.add(r); }
        return out;
    }
    static GestureLogic g() { return new GestureLogic(270, true); }

    public static void main(String[] a) {
        // --- finger counting ---
        eq(GestureLogic.countFingers(hand(1, false, 0, 0)[0], hand(1, false, 0, 0)[1]), 1, "1");
        eq(GestureLogic.countFingers(hand(3, false, 0, 0)[0], hand(3, false, 0, 0)[1]), 2, "2");
        eq(GestureLogic.countFingers(hand(7, false, 0, 0)[0], hand(7, false, 0, 0)[1]), 3, "3");
        eq(GestureLogic.countFingers(hand(15, false, 0, 0)[0], hand(15, false, 0, 0)[1]), 4, "4");
        eq(GestureLogic.countFingers(hand(15, true, 0, 0)[0], hand(15, true, 0, 0)[1]), 5, "5");
        eq(GestureLogic.countFingers(hand(1, true, 0, 0)[0], hand(1, true, 0, 0)[1]), 1, "1 + thumb out still 1");
        for (int bad : new int[]{0, 2, 4, 5, 8, 9}) eq(GestureLogic.countFingers(hand(bad, false, 0, 0)[0], hand(bad, false, 0, 0)[1]), 0, "mask " + bad);
        ok("finger counts 1..5 are recognised, odd shapes (fist, only pinky, index+ring...) count as nothing");

        // --- each gesture fires its action exactly once ---
        eq(run(g(), 1, false, 0, 900, false), "[" + U + "]", "1 finger"); ok("1 finger -> UP");
        eq(run(g(), 3, false, 0, 900, false), "[" + D + "]", "2 fingers"); ok("2 fingers -> DOWN");
        eq(run(g(), 7, false, 0, 900, false), "[" + L + "]", "3 fingers"); ok("3 fingers -> LEFT");
        eq(run(g(), 15, false, 0, 900, false), "[" + R + "]", "4 fingers"); ok("4 fingers -> RIGHT");
        eq(run(g(), 15, true, 0, 900, false), "[" + DT + "]", "5 fingers"); ok("5 fingers -> DOUBLE TAP");
        eq(run(g(), 0, false, 0, 900, false), "[]", "fist"); ok("fist does nothing");

        // --- the original bug: moving the hand up and down must not trigger the opposite action ---
        { GestureLogic g = g(); List<Integer> out = new ArrayList<>();
          for (long t = 0; t <= 1200; t += 66) { float dy = (float) (0.15 * Math.sin(t / 150.0)); float[][] h = hand(1, false, 0, dy); int r = g.feed(h[0], h[1], t, false); if (r != 0) out.add(r); }
          eq(out, "[" + U + "]", "move up/down"); ok("moving the hand up and down with 1 finger gives only UP, never DOWN"); }

        // --- stability ---
        { GestureLogic g = g(); List<Integer> out = new ArrayList<>();
          for (long t = 0; t <= 700; t += 66) { float[][] h = hand(t == 264 ? 3 : 1, false, 0, 0); int r = g.feed(h[0], h[1], t, false); if (r != 0) out.add(r); }
          eq(out, "[" + U + "]", "flicker"); ok("a single bad frame (2 fingers for 66 ms) does not fire DOWN"); }
        { GestureLogic g = g(); List<Integer> out = new ArrayList<>();
          out.addAll(run(g, 1, false, 0, 66, false)); out.addAll(run(g, 3, false, 132, 198, false)); out.addAll(run(g, 7, false, 264, 330, false)); out.addAll(run(g, 15, false, 396, 1100, false));
          eq(out, "[" + R + "]", "pass-through"); ok("going 1 -> 2 -> 3 -> 4 quickly fires only the final gesture (RIGHT)"); }

        // --- repeat / no repeat ---
        { List<Integer> out = run(g(), 1, false, 0, 3300, false); eq(out, "[" + U + ", " + U + "]", "repeat"); ok("holding 1 finger repeats UP every 1.5 s"); }
        eq(run(g(), 15, true, 0, 4000, false), "[" + DT + "]", "dt repeat"); ok("double tap never repeats while held");
        { GestureLogic g = g(); List<Integer> out = new ArrayList<>();
          out.addAll(run(g, 1, false, 0, 600, false)); out.addAll(lost(g, 666, 1000, false)); out.addAll(run(g, 1, false, 1066, 1700, false));
          eq(out, "[" + U + ", " + U + "]", "rearm"); ok("lowering the hand and showing 1 finger again fires again"); }

        // --- sensitivity ---
        { GestureLogic g = g(); g.setSensitivity(0.5f); eq(run(g, 1, false, 0, 600, false), "[]", "slow"); ok("low sensitivity needs a longer hold"); }

        // --- pointer mode ---
        { GestureLogic g = g();
          eq(run(g, 1, false, 0, 600, true), "[]", "pointing no action");
          eq(g.pointState, GestureLogic.POINTING, "state");
          near(g.px, 0.82f, "px"); near(g.py, 0.58f, "py");
          ok("pointer mode: 1 finger fires nothing, shows the circle at the fingertip");
          List<Integer> out = new ArrayList<>();
          for (long t = 666; t <= 1100; t += 66) { float[][] h = hand(3, false, 0, 0.05f); int r = g.feed(h[0], h[1], t, true); if (r != 0) out.add(r); }
          eq(out, "[" + OPEN + "]", "open"); near(g.lockX, 0.82f, "lockX"); near(g.lockY, 0.58f, "lockY");
          ok("opening the middle finger fires OPEN once, at the spot the index finger was pointing at");
          eq(run(g, 3, false, 1166, 4000, false), "[]", "after open"); ok("keeping 2 fingers up after the app opens does not scroll down"); }
        eq(run(g(), 3, false, 0, 800, true), "[]", "no lock"); ok("pointer mode: 2 fingers without pointing first does nothing");
        eq(run(g(), 7, false, 0, 900, true), "[" + L + "]", "ptr left"); ok("pointer mode: 3 fingers is still LEFT");
        { GestureLogic g = g(); run(g, 1, false, 0, 600, false); eq(g.pointState, GestureLogic.HIDDEN, "no circle"); ok("outside pointer mode no circle is shown"); }
        { GestureLogic g = g(); run(g, 1, false, 0, 600, true); lost(g, 666, 1300, true); eq(g.pointState, GestureLogic.HIDDEN, "hide"); ok("circle disappears when the hand leaves"); }

        float[] s = g().toScreen(0.3f, 0f);
        if (!(s[1] < -0.29f && Math.abs(s[0]) < 1e-6)) throw new AssertionError("mapping"); ok("sensor mapping: raw +x is screen up");
        System.out.println("ALL " + n + " CHECKS PASSED");
    }
}
