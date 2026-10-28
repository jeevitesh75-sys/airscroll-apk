package com.jeevitesh.airscroll;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.Arrays;
import java.util.List;

/**
 * Small, light camera loop: front camera at about 320x240 and at most 12 frames a second, hand tracking in a background
 * thread, camera off whenever the screen is off. Frames that arrive while the tracker is busy are dropped, so it never queues up.
 */
public class AirService extends Service {
    static volatile boolean running = false;
    static volatile String dbg = "not started";
    static volatile String note = "-";
    private String trackerName = "?"; private int frames = 0; private long fpsT = 0, fpsN = 0; private String lastDir = "-"; private int fps = 0;
    private static final String CH = "airscroll";
    private static final long MIN_GAP_MS = 40; // as fast as the phone can manage (frames that arrive while busy are dropped)

    private HandlerThread thread;
    private Handler bg;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandLandmarker landmarker;
    private GestureLogic logic;
    private Bitmap bmp;
    private int[] argb;
    private long lastRun = 0;
    private boolean busy = false, screenOn = true;
    private int sensor = 270;
    private String camId;
    private android.content.SharedPreferences prefs;
    private boolean pointerShown = false;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { screenOn = false; bg.post(AirService.this::closeCamera); }
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) { screenOn = true; bg.post(AirService.this::openCamera); }
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "AirScroll", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, AirService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH).setContentTitle("AirScroll is on")
                .setContentText("Show 1-5 fingers: up, down, left, right, double tap").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        if (!running) {
            running = true;
            thread = new HandlerThread("air"); thread.start(); bg = new Handler(thread.getLooper());
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screen, f);
            bg.post(() -> { initTracker(); if (screenOn) openCamera(); });
        }
        return START_NOT_STICKY;
    }

    private void initTracker() {
        for (Delegate d : new Delegate[]{Delegate.GPU, Delegate.CPU}) {
            try {
                HandLandmarker.HandLandmarkerOptions o = HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(d).build())
                        .setRunningMode(RunningMode.VIDEO).setNumHands(1)
                        .setMinHandDetectionConfidence(0.5f).setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f).build();
                landmarker = HandLandmarker.createFromOptions(this, o);
                trackerName = d.name(); dbg = "tracker ready (" + trackerName + ")";
                return;
            } catch (Throwable e) { landmarker = null; dbg = "tracker " + d.name() + " failed: " + String.valueOf(e.getMessage()).replace("\n", " "); }
        }
        dbg = "TRACKER FAILED: " + dbg; // keep the service alive so the message stays on screen
    }

    private void openCamera() {
        if (cam != null || landmarker == null) return;
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            camId = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { camId = id; break; }
            }
            if (camId == null) { dbg = "no front camera found"; return; }
            CameraCharacteristics cc = cm.getCameraCharacteristics(camId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION); sensor = so == null ? 270 : so;
            prefs = getSharedPreferences("air", MODE_PRIVATE);
            logic = new GestureLogic(sensor, true);
            logic.setSensitivity(prefs.getFloat("sens", 1.3f));
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (s.getWidth() < 320 || s.getHeight() < 240) continue;
                if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
            }
            if (best == null) best = map.getOutputSizes(ImageFormat.YUV_420_888)[0];
            final Size size = best;
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> onFrame(r), bg);
            bmp = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
            argb = new int[size.getWidth() * size.getHeight()];
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { cam = d; startSession(d); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); cam = null; retry(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); cam = null; dbg = "camera error code " + e + " (is another app using the front camera?)"; retry(); }
            }, bg);
        } catch (SecurityException | CameraAccessException e) { dbg = "camera error: " + e.getMessage(); retry(); }
    }

    private void retry() { if (running && screenOn) bg.postDelayed(() -> { closeCamera(); openCamera(); }, 2500); }

    @SuppressWarnings("deprecation")
    private void startSession(CameraDevice d) {
        try {
            Surface surf = reader.getSurface();
            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(surf);
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            Range<Integer>[] ranges = cm.getCameraCharacteristics(camId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> pick = null;
            if (ranges != null) for (Range<Integer> r : ranges) if (r.getUpper() >= 15 && r.getUpper() <= 30 && (pick == null || r.getUpper() < pick.getUpper())) pick = r;
            if (pick != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick);
            d.createCaptureSession(Arrays.asList(surf), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try { s.setRepeatingRequest(b.build(), null, bg); } catch (Exception e) { retry(); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { retry(); }
            }, bg);
        } catch (Exception e) { retry(); }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (cam != null) cam.close(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        session = null; cam = null; reader = null;
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (busy || landmarker == null || now - lastRun < MIN_GAP_MS) return;   // drop this frame
            busy = true; lastRun = now;
            toBitmap(img);
            MPImage mp = new BitmapImageBuilder(bmp).build();
            HandLandmarkerResult res = landmarker.detectForVideo(mp, now);
            float[] x = null, y = null;
            if (res.landmarks() != null && !res.landmarks().isEmpty()) {
                List<NormalizedLandmark> l = res.landmarks().get(0);
                if (l.size() >= 21) { x = new float[21]; y = new float[21]; for (int i = 0; i < 21; i++) { x[i] = l.get(i).x(); y[i] = l.get(i).y(); } }
            }
            int pm = prefs == null ? 0 : prefs.getInt("pointer", 0);   // 0 auto (home screen only), 1 always, 2 off
            AirAccessibility acc = AirAccessibility.inst;
            if (acc != null && pm == 0) acc.pollFrontApp();
            boolean pointerOn = pm == 1 || (pm == 0 && AirAccessibility.onHome);
            int act = logic.feed(x, y, now, pointerOn);
            long sec = now / 1000; if (sec != fpsT) { fps = (int) fpsN; fpsN = 0; fpsT = sec; } fpsN++;
            dbg = "camera ok " + fps + " fps | hand: " + (x == null ? "NOT SEEN" : "seen") + " | fingers: " + logic.frameFingers + " (held: " + logic.fingers + ")" + shape(x, y)
                    + " | pointer mode: " + (pointerOn ? "ON" : "off") + " | front app: " + AirAccessibility.frontPkg + " | last: " + lastDir + " (" + note + ")";
            AirAccessibility a = AirAccessibility.inst;
            if (a != null) {
                if (logic.pointState != GestureLogic.HIDDEN) { a.showPointer(logic.px, logic.py, logic.pointState == GestureLogic.SELECTED); pointerShown = true; }
                else if (pointerShown) { a.hidePointer(); pointerShown = false; }
            }
            if (act != GestureLogic.NONE) {
                lastDir = new String[]{"-", "UP", "DOWN", "LEFT", "RIGHT", "DOUBLE TAP", "OPEN"}[act];
                if (a == null) dbg = "gesture seen but Accessibility is OFF";
                else {
                    boolean sent;
                    if (act == GestureLogic.DOUBLE_TAP) sent = a.doubleTap();
                    else if (act == GestureLogic.OPEN) sent = a.tapNorm(logic.lockX, logic.lockY);
                    else {
                        int dir = act;
                        if (prefs != null && prefs.getBoolean("swapLR", false)) { if (act == GestureLogic.LEFT) dir = GestureLogic.RIGHT; else if (act == GestureLogic.RIGHT) dir = GestureLogic.LEFT; }
                        sent = a.swipe(dir);
                    }
                    if (sent) buzz(); else note = "action REFUSED (accessibility)";
                }
            }
        } catch (Throwable e) {
            dbg = "frame error: " + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            busy = false;
            if (img != null) img.close();
        }
    }

    /** which fingers the camera thinks are up, e.g. " [I M R . T]" - handy when a gesture is misread */
    private static String shape(float[] x, float[] y) {
        if (x == null) return "";
        int m = GestureLogic.fingerMask(x, y);
        return " [" + ((m & 1) != 0 ? "I" : ".") + ((m & 2) != 0 ? " M" : " .") + ((m & 4) != 0 ? " R" : " .") + ((m & 8) != 0 ? " P" : " .") + (GestureLogic.thumbOut(x, y) ? " T" : " .") + "]";
    }

    private void buzz() {
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(25, 120)); } catch (Exception ignored) { }
    }

    /** YUV_420_888 -> ARGB bitmap (small image, so a plain loop is fast enough) */
    private void toBitmap(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        java.nio.ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int ys = p[0].getRowStride(), us = p[1].getRowStride(), up = p[1].getPixelStride(), vs = p[2].getRowStride(), vp = p[2].getPixelStride();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int Y = yb.get(j * ys + i) & 0xff;
                int U = (ub.get((j >> 1) * us + (i >> 1) * up) & 0xff) - 128;
                int V = (vb.get((j >> 1) * vs + (i >> 1) * vp) & 0xff) - 128;
                int R = Y + ((1436 * V) >> 10), G = Y - ((352 * U + 731 * V) >> 10), B = Y + ((1815 * U) >> 10);
                R = R < 0 ? 0 : Math.min(R, 255); G = G < 0 ? 0 : Math.min(G, 255); B = B < 0 ? 0 : Math.min(B, 255);
                argb[j * w + i] = 0xff000000 | (R << 16) | (G << 8) | B;
            }
        }
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
    }

    @Override public void onDestroy() {
        running = false;
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        if (bg != null) bg.post(() -> { closeCamera(); try { if (landmarker != null) landmarker.close(); } catch (Exception ignored) { } landmarker = null; if (thread != null) thread.quitSafely(); });
        super.onDestroy();
    }
}
