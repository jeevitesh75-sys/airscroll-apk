package com.jeevitesh.airscroll;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;
    private SharedPreferences sp;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int color, int size) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size); t.setPadding(0, dp(6), 0, dp(6)); return t;
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(8); b.setLayoutParams(lp); return b;
    }

    private String swapLabel() {
        return sp.getBoolean("swapLR", false) ? "Left/Right: SWAPPED (3 fingers swipes right) - tap to change" : "Left/Right: normal (3 fingers swipes left) - tap to change";
    }

    private String pointerLabel() {
        int m = sp.getInt("pointer", 0);
        return m == 1 ? "Pointer mode: ALWAYS (1 finger = point, 2 = open, everywhere; no up/down) - tap to change" : m == 2 ? "Pointer mode: OFF (1 = up, 2 = down everywhere) - tap to change" : "Pointer mode: AUTO (point/open on home screen, up/down inside apps) - tap to change";
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = getSharedPreferences("air", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFF080510); root.setPadding(dp(20), dp(28), dp(20), dp(28));

        TextView title = text("AIRSCROLL", 0xFF7CF2FF, 26); title.setGravity(Gravity.CENTER); root.addView(title);
        root.addView(text("Scroll with your hand, no touching.\nShow a number of fingers and hold still for a moment:\n1 finger = up\n2 fingers = down\n3 fingers = left\n4 fingers = right\n5 fingers (thumb out) = double tap\nHold the same gesture for 1.5 s to repeat it (not double tap).", 0xFFD8CCF0, 15));
        root.addView(text("Open apps from the home screen:\nPoint with ONE finger (index): a circle follows your fingertip. When it is on the app, open your middle finger too (2 fingers) to open it. On the home screen 1 and 2 fingers are used for this instead of up / down.", 0xFFD8CCF0, 15));

        root.addView(button("1. Allow camera", this::askCamera));
        root.addView(button("2. Turn on AirScroll in Accessibility", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("3. Start", this::start));
        root.addView(button("Stop", () -> { startService(new Intent(this, AirService.class).setAction("STOP")); refreshSoon(); }));

        root.addView(text("Sensitivity (right = reacts faster, shorter hold)", 0xFFD8CCF0, 14));
        SeekBar sb = new SeekBar(this); sb.setMax(100); sb.setProgress(Math.round((sp.getFloat("sens", 1.3f) - 0.6f) * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) { sp.edit().putFloat("sens", 0.6f + p / 100f).apply(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { Toast.makeText(MainActivity.this, "Saved. Press Stop then Start to apply.", Toast.LENGTH_SHORT).show(); }
        });
        root.addView(sb);

        Button pb = button(pointerLabel(), () -> { });
        pb.setOnClickListener(v -> { int m = (sp.getInt("pointer", 0) + 1) % 3; sp.edit().putInt("pointer", m).apply(); pb.setText(pointerLabel()); });
        root.addView(pb);
        Button sw = button("", () -> { });
        sw.setText(swapLabel());
        sw.setOnClickListener(v -> { sp.edit().putBoolean("swapLR", !sp.getBoolean("swapLR", false)).apply(); sw.setText(swapLabel()); });
        root.addView(sw);

        root.addView(button("Test: swipe up in 4 seconds (open Instagram first)", () -> {
            Toast.makeText(this, "Go to a reel now...", Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> { AirAccessibility a = AirAccessibility.inst; if (a != null) a.swipe(GestureLogic.UP); }, 4000);
            moveTaskToBack(true);
        }));

        status = text("", 0xFFFFC933, 14); root.addView(status);
        root.addView(text("The camera turns off when the screen is off. A green camera dot appears while it runs. That is normal.", 0xFF8F84AD, 12));
        ScrollView sv = new ScrollView(this); sv.addView(root); setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); tick.removeCallbacks(loop); tick.post(loop); }

    private void refreshSoon() { new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 700); }

    private boolean camOk() { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private boolean accOk() { return AirAccessibility.inst != null; }

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable loop = new Runnable() { @Override public void run() { refresh(); tick.postDelayed(this, 500); } };
    @Override protected void onPause() { super.onPause(); tick.removeCallbacks(loop); }

    private void refresh() {
        status.setText("Camera: " + (camOk() ? "ok" : "MISSING") + "\nAccessibility: " + (accOk() ? "on" : "OFF (step 2)") + "\nAirScroll: " + (AirService.running ? "RUNNING" : "off") + "\n\nLive: " + AirService.dbg);
    }

    private void askCamera() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.CAMERA, "android.permission.POST_NOTIFICATIONS"}, 3);
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { super.onRequestPermissionsResult(c, p, r); refresh(); }

    private void start() {
        if (!camOk()) { Toast.makeText(this, "Allow the camera first", Toast.LENGTH_LONG).show(); return; }
        if (!accOk()) { Toast.makeText(this, "Turn on AirScroll in Accessibility first (step 2)", Toast.LENGTH_LONG).show(); return; }
        startForegroundService(new Intent(this, AirService.class));
        Toast.makeText(this, "AirScroll is on. Open a reel and flick!", Toast.LENGTH_LONG).show();
        refreshSoon();
    }
}
