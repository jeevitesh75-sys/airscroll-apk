package com.jeevitesh.airscroll;

/**
 * Turns hand landmarks into actions. Plain Java (no Android) so it can be tested on a computer.
 *
 * Static finger-count gestures (no hand movement needed, so there is no "return stroke" problem any more):
 *   1 finger (index)               -> UP
 *   2 fingers (index + middle)     -> DOWN
 *   3 fingers                      -> LEFT
 *   4 fingers (thumb folded)       -> RIGHT
 *   5 fingers (thumb out)          -> DOUBLE TAP
 * A shape must be held steady for a short moment before it fires, so fingers passing through other shapes while you
 * change from one gesture to another do nothing. Holding UP/DOWN/LEFT/RIGHT repeats every 1.5 s. Double tap never repeats.
 *
 * Pointer mode (used on the home screen): 1 finger moves a circle to where the index fingertip points,
 * then opening the middle finger (2 fingers) taps at the circled spot = opens that app.
 */
final class GestureLogic {
    static final int NONE = 0, UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, DOUBLE_TAP = 5, OPEN = 6;
    static final int HIDDEN = 0, POINTING = 1, SELECTED = 2;

    private static final long DEBOUNCE_MS = 100;   // a new shape must last this long to count as "changed"
    private static final long REPEAT_MS = 1500;
    private static final long MIN_GAP_MS = 450;
    private static final long LOCK_VALID_MS = 1500;
    private static final float GAIN = 1.6f;        // hand only needs to cover the middle ~60% of the camera view to reach the whole screen

    private final int sensorOrientation;
    private final boolean mirror;
    private float sens = 1f;

    private int cand = 0, pending = 0;
    private long candSince = 0, pendSince = 0;
    private boolean armed = true;
    private long nextRepeat = Long.MAX_VALUE, lastFire = -100000;
    private long lockT = -100000, lastShowT = -100000, lastPointT = -100000;
    private boolean havePos = false;

    /** stable finger count: 0 = none / unclear shape */
    int fingers = 0;
    /** instantaneous count of the current frame */
    int frameFingers = 0;
    /** what the pointer circle should do, and where (0..1 of the screen) */
    int pointState = HIDDEN;
    float px = 0.5f, py = 0.5f;
    /** where the circle was when the index finger was last seen alone: this is where OPEN taps */
    float lockX = 0.5f, lockY = 0.5f;

    GestureLogic(int sensorOrientation, boolean frontCamera) { this.sensorOrientation = ((sensorOrientation % 360) + 360) % 360; this.mirror = frontCamera; }

    /** 0.4 (slow, needs a long hold) .. 2 (fast). Default in the app is 1.3 -> about 0.3 s hold */
    void setSensitivity(float s) { sens = Math.max(0.4f, Math.min(2f, s)); }

    private static double d(float[] x, float[] y, int a, int b) { return Math.hypot(x[a] - x[b], y[a] - y[b]); }
    /** a finger counts as up only if its tip is clearly past its middle joint AND clearly far from the wrist compared with its base knuckle */
    private static boolean extended(float[] x, float[] y, int tip, int pip) {
        return d(x, y, 0, tip) > d(x, y, 0, pip) * 1.12 && d(x, y, 0, tip) > d(x, y, 0, tip - 3) * 1.4;
    }

    /** bit0 index, bit1 middle, bit2 ring, bit3 pinky */
    static int fingerMask(float[] x, float[] y) {
        return (extended(x, y, 8, 6) ? 1 : 0) | (extended(x, y, 12, 10) ? 2 : 0) | (extended(x, y, 16, 14) ? 4 : 0) | (extended(x, y, 20, 18) ? 8 : 0);
    }

    /** thumb sticking out sideways (works for any hand rotation) */
    static boolean thumbOut(float[] x, float[] y) {
        return d(x, y, 4, 17) > d(x, y, 3, 17) * 1.1 && d(x, y, 4, 5) > d(x, y, 0, 9) * 0.5;
    }

    /** 1..5 for index / +middle / +ring / +pinky / +thumb, 0 for any other shape (fist, only pinky, index+ring, ...) */
    static int countFingers(float[] x, float[] y) {
        int mask = fingerMask(x, y);
        switch (mask) {
            case 1: return 1;
            case 3: return 2;
            case 7: return 3;
            case 15: return thumbOut(x, y) ? 5 : 4;
            default: return 0;
        }
    }

    /** raw camera-image movement -> movement as the user sees it (x right, y down) */
    float[] toScreen(float dx, float dy) {
        float sx, sy;
        switch (sensorOrientation) {
            case 90: sx = -dy; sy = dx; break;
            case 180: sx = -dx; sy = -dy; break;
            case 270: sx = dy; sy = -dx; break;
            default: sx = dx; sy = dy;
        }
        if (mirror) sx = -sx;
        return new float[]{sx, sy};
    }

    private static int fromCount(int c) {
        switch (c) { case 1: return UP; case 2: return DOWN; case 3: return LEFT; case 4: return RIGHT; case 5: return DOUBLE_TAP; default: return NONE; }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : Math.min(v, 1); }

    void reset() {
        cand = pending = 0; candSince = pendSince = 0; armed = true; nextRepeat = Long.MAX_VALUE; lastFire = -100000;
        lockT = lastShowT = lastPointT = -100000; havePos = false; fingers = frameFingers = 0; pointState = HIDDEN;
    }

    /**
     * x,y: 21 normalized landmarks, or null when no hand is seen. t in ms.
     * pointerMode: true on the home screen (1 finger = pointer, 2 fingers = open) instead of UP / DOWN.
     * Returns NONE / UP / DOWN / LEFT / RIGHT / DOUBLE_TAP / OPEN.
     */
    int feed(float[] x, float[] y, long t, boolean pointerMode) {
        int c = x == null ? 0 : countFingers(x, y);
        frameFingers = c;

        // debounce: the stable shape only changes once a new shape has lasted DEBOUNCE_MS
        if (c == cand) pending = c;
        else if (c != pending) { pending = c; pendSince = t; }
        else if (t - pendSince >= DEBOUNCE_MS) { cand = c; candSince = pendSince; armed = true; nextRepeat = Long.MAX_VALUE; }
        fingers = cand;

        // pointer circle: follows the index fingertip while only the index finger is up
        if (pointerMode && x != null && c == 1) {
            float[] s = toScreen(x[8] - 0.5f, y[8] - 0.5f);
            float nx = clamp01(0.5f + s[0] * GAIN), ny = clamp01(0.5f + s[1] * GAIN);
            if (!havePos || t - lastPointT > 600) { px = nx; py = ny; havePos = true; }
            else { px += 0.4f * (nx - px); py += 0.4f * (ny - py); }
            lastPointT = t; lockT = t; lockX = px; lockY = py;
        }
        if (!pointerMode) pointState = HIDDEN;
        else if (x != null && c == 1) { pointState = POINTING; lastShowT = t; }
        else if (x != null && c == 2 && t - lockT < LOCK_VALID_MS) { pointState = SELECTED; lastShowT = t; }
        else if (t - lastShowT > 400) pointState = HIDDEN;

        // actions
        int want = NONE;
        long held = t - candSince;
        float holdMs = 380f / sens;
        if (cand >= 1 && t - lastFire >= MIN_GAP_MS) {
            if (pointerMode && cand == 1) {
                // pointing: no action, just the circle
            } else if (pointerMode && cand == 2) {
                if (armed && held >= holdMs * 0.75f && t - lockT < LOCK_VALID_MS) {
                    want = OPEN; armed = false; nextRepeat = Long.MAX_VALUE; lockT = -100000;
                }
            } else if (armed && held >= holdMs) {
                want = fromCount(cand); armed = false; nextRepeat = cand == 5 ? Long.MAX_VALUE : t + REPEAT_MS;
            } else if (!armed && t >= nextRepeat) {
                want = fromCount(cand); nextRepeat = t + REPEAT_MS;
            }
        }
        if (want != NONE) lastFire = t;
        return want;
    }
}
