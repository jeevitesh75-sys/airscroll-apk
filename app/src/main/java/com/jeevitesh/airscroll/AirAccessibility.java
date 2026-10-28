package com.jeevitesh.airscroll;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;

/** Performs swipes and taps and draws the pointer circle. It never reads what is on your screen (only which app is in front). */
public class AirAccessibility extends AccessibilityService {
    static volatile AirAccessibility inst;
    /** true while a launcher (home screen) is the front app */
    static volatile boolean onHome = false;
    static volatile String frontPkg = "?";
    private long lastPollT = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Circle circle;
    private boolean attached = false;

    @Override protected void onServiceConnected() { super.onServiceConnected(); inst = this; wm = (WindowManager) getSystemService(WINDOW_SERVICE); }
    @Override public boolean onUnbind(Intent i) { inst = null; onHome = false; removeCircle(); return super.onUnbind(i); }
    @Override public void onInterrupt() { }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        applyPackage(e.getPackageName().toString());
    }

    private void applyPackage(String p) {
        String lp = p.toLowerCase();
        if (p.equals("com.android.systemui") || lp.contains("inputmethod") || lp.contains("keyboard")) return;   // shade / keyboard do not change the front app
        frontPkg = p;
        onHome = homePackages().contains(p) || lp.contains("launcher");
    }

    /** Asks Android which app is in front (only the app's name is looked at, never its content). At most twice a second. */
    void pollFrontApp() {
        long now = SystemClock.uptimeMillis();
        if (now - lastPollT < 500) return;
        lastPollT = now;
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) { CharSequence pk = r.getPackageName(); r.recycle(); if (pk != null) applyPackage(pk.toString()); }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private HashSet<String> homePackages() {
        HashSet<String> s = new HashSet<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) s.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        return s;
    }

    private final GestureResultCallback cb = new GestureResultCallback() {
        @Override public void onCompleted(GestureDescription g) { AirService.note = "done"; }
        @Override public void onCancelled(GestureDescription g) { AirService.note = "CANCELLED by Android"; }
    };

    private boolean send(GestureDescription g) {
        try { return dispatchGesture(g, cb, null); } catch (Exception e) { return false; }
    }

    /** dir: GestureLogic.UP/DOWN/LEFT/RIGHT. The finger moves the same way as before (UP = finger swipes up = next reel). */
    boolean swipe(int dir) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels, cx = w / 2f, cy = h / 2f;
        Path p = new Path();
        switch (dir) {
            case GestureLogic.UP: p.moveTo(cx, h * 0.74f); p.lineTo(cx, h * 0.26f); break;
            case GestureLogic.DOWN: p.moveTo(cx, h * 0.26f); p.lineTo(cx, h * 0.74f); break;
            case GestureLogic.LEFT: p.moveTo(w * 0.85f, cy); p.lineTo(w * 0.15f, cy); break;
            case GestureLogic.RIGHT: p.moveTo(w * 0.15f, cy); p.lineTo(w * 0.85f, cy); break;
            default: return false;
        }
        return send(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 170)).build());
    }

    private static GestureDescription.StrokeDescription tapStroke(float x, float y, long start) {
        Path p = new Path(); p.moveTo(x, y);
        return new GestureDescription.StrokeDescription(p, start, 40);
    }

    /** two quick taps in the middle of the screen (like on a reel) */
    boolean doubleTap() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f, y = dm.heightPixels / 2f;
        return send(new GestureDescription.Builder().addStroke(tapStroke(x, y, 0)).addStroke(tapStroke(x, y, 130)).build());
    }

    /** one tap at a spot given as 0..1 of the screen */
    boolean tapNorm(float nx, float ny) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return send(new GestureDescription.Builder().addStroke(tapStroke(nx * dm.widthPixels, ny * dm.heightPixels, 0)).build());
    }

    // ---- pointer circle (an accessibility overlay: needs no extra permission, touches pass straight through it) ----

    private static final class Circle extends View {
        float cx, cy; boolean selected;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];
        private final float dn;
        Circle(Context c) {
            super(c); dn = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE); fill.setStyle(Paint.Style.FILL); dot.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas cv) {
            getLocationOnScreen(loc);
            float x = cx - loc[0], y = cy - loc[1], r = (selected ? 42 : 34) * dn;
            int col = selected ? 0xFF5CFF8A : 0xFF7CF2FF;
            fill.setColor((col & 0x00FFFFFF) | 0x33000000); cv.drawCircle(x, y, r, fill);
            ring.setColor(col); ring.setStrokeWidth((selected ? 6 : 4) * dn); cv.drawCircle(x, y, r, ring);
            dot.setColor(col); cv.drawCircle(x, y, 4 * dn, dot);
        }
    }

    /** nx, ny: 0..1 of the screen. selected = green (middle finger opened, about to open the app). Safe to call from any thread. */
    void showPointer(float nx, float ny, boolean selected) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float x = nx * dm.widthPixels, y = ny * dm.heightPixels; final boolean sel = selected;
        ui.post(() -> {
            try {
                if (wm == null) return;
                if (!attached) {
                    circle = new Circle(this);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    wm.addView(circle, lp); attached = true;
                }
                circle.cx = x; circle.cy = y; circle.selected = sel; circle.setVisibility(View.VISIBLE); circle.invalidate();
            } catch (Exception e) { AirService.note = "circle error: " + e.getMessage(); }
        });
    }

    void hidePointer() {
        ui.post(() -> { if (attached && circle != null) circle.setVisibility(View.INVISIBLE); });
    }

    private void removeCircle() {
        ui.post(() -> { try { if (attached && wm != null && circle != null) wm.removeView(circle); } catch (Exception ignored) { } attached = false; circle = null; });
    }
}
