package com.originos.wiggle;

import android.app.WallpaperManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.text.format.DateFormat;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * OriginOS 7 inspired live wallpaper.
 *
 * Three layers are drawn back to front, each driven by its own damped spring:
 *   1. Background image (moves opposite to the foreground for parallax depth)
 *   2. Interactive glass tiles (liquid wobble, squash and stretch, touch ripples)
 *   3. Foreground depth clock (soft shadow layer + styled text layer)
 *
 * Tilt and shake both come from the accelerometer: the low-passed gravity vector
 * gives tilt, and what remains after removing gravity is the shake signal.
 */
public class WiggleWallpaperService extends WallpaperService {

    // ---- Preference keys shared with WallpaperConfigActivity ----
    public static final String PREFS = "wiggle_prefs";
    public static final String KEY_SENSITIVITY = "shake_sensitivity";
    public static final String KEY_STIFFNESS = "spring_stiffness";
    public static final String KEY_BG_URI = "bg_uri";
    public static final String KEY_CLOCK_STYLE = "clock_style";

    public static final int STYLE_CLEAN = 0;
    public static final int STYLE_FIRE = 1;
    public static final int STYLE_GLOW = 2;
    public static final int STYLE_COSMIC = 3;

    public static final int DEFAULT_SENSITIVITY = 50;
    public static final int DEFAULT_STIFFNESS = 50;
    public static final int DEFAULT_CLOCK_STYLE = STYLE_GLOW;

    // ---- Tuning constants ----
    private static final float BG_SCALE = 1.2f;          // background is drawn 20% oversize
    private static final float STRETCH = 0.00006f;       // squash/stretch per px/s of velocity
    private static final int MAX_EMBERS = 90;
    private static final int STAR_COUNT = 36;

    private static final int[] TILE_ACCENTS = {
            0xFF4FC3F7, 0xFFFF8A65, 0xFF9575CD, 0xFF81C784, 0xFFF06292
    };
    // Different depth / stiffness / damping per tile gives the liquid, out-of-phase wobble.
    private static final float[] TILE_DEPTH = {0.90f, 1.10f, 1.00f, 1.25f, 0.95f};
    private static final float[] TILE_KSCALE = {1.00f, 0.85f, 1.10f, 0.75f, 0.95f};
    private static final float[] TILE_ZETA = {0.24f, 0.30f, 0.22f, 0.28f, 0.26f};

    @Override
    public Engine onCreateEngine() {
        return new WiggleEngine();
    }

    // ------------------------------------------------------------------------------------
    // Physics primitives
    // ------------------------------------------------------------------------------------

    /** 2D damped spring that chases a target. Semi-implicit Euler, stable at small steps. */
    static final class Spring {
        final float depth;   // parallax multiplier applied to tilt/shake offsets (negative = opposite)
        final float kScale;  // multiplier on the user's stiffness
        final float zeta;    // damping ratio (<1 = bouncy)
        float x, y, vx, vy;

        Spring(float depth, float kScale, float zeta) {
            this.depth = depth;
            this.kScale = kScale;
            this.zeta = zeta;
        }

        void step(float tx, float ty, float baseK, float h) {
            float k = baseK * kScale;
            float c = 2f * zeta * (float) Math.sqrt(k);
            vx += (-k * (x - tx) - c * vx) * h;
            vy += (-k * (y - ty) - c * vy) * h;
            x += vx * h;
            y += vy * h;
        }

        boolean atRest(float tx, float ty) {
            return Math.abs(x - tx) < 0.8f && Math.abs(y - ty) < 0.8f
                    && Math.abs(vx) < 3f && Math.abs(vy) < 3f;
        }

        void kick(float dvx, float dvy) {
            vx += dvx;
            vy += dvy;
        }
    }

    static final class Tile {
        final RectF base = new RectF();
        final int accent;
        final Spring spring;

        Tile(int accent, Spring spring) {
            this.accent = accent;
            this.spring = spring;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------------------------

    private final class WiggleEngine extends Engine implements
            SensorEventListener,
            Choreographer.FrameCallback,
            SharedPreferences.OnSharedPreferenceChangeListener {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable idleRunnable = this::scheduleFrame;
        private final Random rnd = new Random();
        private final long startNs = System.nanoTime();

        private SharedPreferences prefs;
        private SensorManager sensorManager;
        private Sensor accelerometer;

        private boolean visible;
        private boolean framePending;
        private long lastFrameNs;
        private long lastActivityNs;

        private int viewW, viewH;
        private float density = 1f;

        // ---- Preferences ----
        private int sensitivity = DEFAULT_SENSITIVITY;
        private int stiffness = DEFAULT_STIFFNESS;
        private int clockStyle = DEFAULT_CLOCK_STYLE;
        private String bgUri;
        private float shakeGain;   // px of offset per m/s^2 of linear acceleration
        private float baseK;       // base spring stiffness (1/s^2)

        // ---- Sensor state ----
        private final float[] gravity = new float[3];
        private boolean gravityInit;
        private float shakeOx, shakeOy;
        private float rawTiltX, rawTiltY;
        private float neutralX, neutralY;
        private boolean neutralInit;
        private float tiltX, tiltY;
        private float lastWakeTiltX, lastWakeTiltY;
        private float scrollShift;

        // ---- Layers ----
        private final Spring bgSpring = new Spring(-0.35f, 0.9f, 0.7f);
        private final Spring clockShadowSpring = new Spring(1.0f, 0.8f, 0.32f);
        private final Spring clockSpring = new Spring(1.6f, 1.0f, 0.28f);
        private Tile[] tiles;
        private Spring[] layers;

        // ---- Background ----
        private volatile Bitmap bgBitmap;
        private int bgToken;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint scrimPaint = new Paint();

        // ---- Tile paints ----
        private final Paint tileShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tileFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tileStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tileContentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectA = new RectF();
        private final RectF rectB = new RectF();

        // ---- Clock ----
        private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shaderMatrix = new Matrix();
        private Shader fireShader;
        private Shader cosmicShader;
        private float timeSize, dateSize, digitH, clockCx, clockBaseline;
        private String timeStr = "", dateStr = "";
        private float timeW;
        private long lastMinute = -1;
        private boolean last24;

        // Embers (fire style)
        private final float[] emX = new float[MAX_EMBERS];
        private final float[] emY = new float[MAX_EMBERS];
        private final float[] emVy = new float[MAX_EMBERS];
        private final float[] emLife = new float[MAX_EMBERS];
        private final float[] emMax = new float[MAX_EMBERS];
        private final float[] emPhase = new float[MAX_EMBERS];
        private final float[] emSize = new float[MAX_EMBERS];
        private float emberSpawnAcc;

        // Stars (cosmic style)
        private final float[] starNX = new float[STAR_COUNT];
        private final float[] starNY = new float[STAR_COUNT];
        private final float[] starPhase = new float[STAR_COUNT];
        private final float[] starSpeed = new float[STAR_COUNT];
        private final float[] starSize = new float[STAR_COUNT];

        private float dp(float v) {
            return v * density;
        }

        private float animTime() {
            return (System.nanoTime() - startNs) / 1e9f;
        }

        // ------------------------------------------------------------------ lifecycle

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            density = getResources().getDisplayMetrics().density;

            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }

            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(this);

            setupPaints();
            for (int i = 0; i < STAR_COUNT; i++) {
                starNX[i] = -0.08f + rnd.nextFloat() * 1.16f;
                starNY[i] = -0.5f + rnd.nextFloat() * 2.4f;
                starPhase[i] = rnd.nextFloat() * 6.28f;
                starSpeed[i] = 1.2f + rnd.nextFloat() * 2.8f;
                starSize[i] = 0.7f + rnd.nextFloat() * 1.6f;
            }
            loadPrefs();
        }

        @Override
        public void onDestroy() {
            visible = false;
            stopFrames();
            unregisterSensors();
            if (prefs != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(this);
            }
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            viewW = width;
            viewH = height;
            buildLayers();
            setupClockMetrics();
            scrimPaint.setShader(new LinearGradient(0, 0, 0, viewH * 0.5f,
                    0x66000000, 0x00000000, Shader.TileMode.CLAMP));
            lastMinute = -1;
            loadBackground();
            scheduleFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            stopFrames();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                neutralInit = false;   // re-calibrate "resting" tilt each time we come back
                lastFrameNs = 0;
                registerSensors();
                scheduleFrame();
            } else {
                unregisterSensors();
                stopFrames();
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                     int xPixels, int yPixels) {
            scrollShift = -(xOffset - 0.5f) * 0.12f * viewW;
            lastActivityNs = System.nanoTime();
            scheduleFrame();
        }

        // ------------------------------------------------------------------ preferences

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            handler.post(() -> {
                loadPrefs();
                lastActivityNs = System.nanoTime();
                scheduleFrame();
            });
        }

        private void loadPrefs() {
            sensitivity = prefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
            stiffness = prefs.getInt(KEY_STIFFNESS, DEFAULT_STIFFNESS);
            int newStyle = prefs.getInt(KEY_CLOCK_STYLE, DEFAULT_CLOCK_STYLE);
            if (newStyle != clockStyle) {
                clockStyle = newStyle;
                java.util.Arrays.fill(emLife, 0f);
                java.util.Arrays.fill(emMax, 0f);
            }
            shakeGain = (0.2f + sensitivity / 100f * 2.8f) * density;
            baseK = 40f + stiffness * 5f;

            String uri = prefs.getString(KEY_BG_URI, null);
            boolean changed = (uri == null) ? (bgUri != null) : !uri.equals(bgUri);
            bgUri = uri;
            if (changed) {
                loadBackground();
            }
        }

        // ------------------------------------------------------------------ sensors

        private void registerSensors() {
            if (sensorManager != null && accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            }
        }

        private void unregisterSensors() {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
                return;
            }
            float[] v = event.values;
            if (!gravityInit) {
                System.arraycopy(v, 0, gravity, 0, 3);
                gravityInit = true;
            }
            final float a = 0.92f;   // low-pass: isolates gravity (tilt) from motion (shake)
            for (int i = 0; i < 3; i++) {
                gravity[i] = a * gravity[i] + (1f - a) * v[i];
            }
            float lx = deadzone(clamp(v[0] - gravity[0], -25f, 25f), 0.3f);
            float ly = deadzone(clamp(v[1] - gravity[1], -25f, 25f), 0.3f);

            // Content lags behind the phone's acceleration (inertia).
            shakeOx = -lx * shakeGain;
            shakeOy = ly * shakeGain;   // device +y is up, canvas +y is down

            rawTiltX = clamp(gravity[0] / SensorManager.GRAVITY_EARTH, -1f, 1f);
            rawTiltY = clamp(gravity[1] / SensorManager.GRAVITY_EARTH, -1f, 1f);

            float dTilt = Math.abs(rawTiltX - lastWakeTiltX) + Math.abs(rawTiltY - lastWakeTiltY);
            if (dTilt > 0.012f || Math.abs(lx) + Math.abs(ly) > 0.8f) {
                lastWakeTiltX = rawTiltX;
                lastWakeTiltY = rawTiltY;
                lastActivityNs = System.nanoTime();
                scheduleFrame();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        private float deadzone(float v, float dz) {
            if (v > dz) return v - dz;
            if (v < -dz) return v + dz;
            return 0f;
        }

        // ------------------------------------------------------------------ touch

        @Override
        public void onTouchEvent(MotionEvent e) {
            super.onTouchEvent(e);
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                poke(e.getX(), e.getY(), 1f);
            } else if (action == MotionEvent.ACTION_MOVE) {
                poke(e.getX(), e.getY(), 0.35f);
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                                boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action)
                    || WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)) {
                poke(x, y, 1.2f);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        /** Pushes nearby layers away from a touch point; farther layers react less (ripple). */
        private void poke(float px, float py, float strength) {
            if (tiles == null) return;
            float reach = Math.min(viewW, viewH) * 0.7f;
            for (Tile t : tiles) {
                kickFrom(t.spring, t.base.centerX() + t.spring.x, t.base.centerY() + t.spring.y,
                        px, py, reach, strength * dp(900f));
            }
            kickFrom(clockSpring, clockCx + clockSpring.x, clockBaseline - digitH / 2f,
                    px, py, reach, strength * dp(500f));
            kickFrom(clockShadowSpring, clockCx, clockBaseline - digitH / 2f,
                    px, py, reach, strength * dp(380f));
            lastActivityNs = System.nanoTime();
            scheduleFrame();
        }

        private void kickFrom(Spring s, float cx, float cy, float px, float py,
                              float reach, float speed) {
            float dx = cx - px;
            float dy = cy - py;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d >= reach) return;
            float f = 1f - d / reach;
            f *= f;
            s.kick(dx / (d + 1f) * speed * f, dy / (d + 1f) * speed * f);
        }

        // ------------------------------------------------------------------ frame loop

        private void scheduleFrame() {
            if (!visible || framePending || viewW == 0) return;
            handler.removeCallbacks(idleRunnable);
            framePending = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void stopFrames() {
            Choreographer.getInstance().removeFrameCallback(this);
            handler.removeCallbacks(idleRunnable);
            framePending = false;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            framePending = false;
            if (!visible) return;

            float dt = (lastFrameNs == 0) ? (1f / 60f) : (frameTimeNanos - lastFrameNs) / 1e9f;
            lastFrameNs = frameTimeNanos;
            dt = clamp(dt, 0.001f, 0.05f);

            boolean settled = stepPhysics(dt);
            draw(dt);

            boolean recentlyActive = (System.nanoTime() - lastActivityNs) < 1_500_000_000L;
            if (!settled || recentlyActive) {
                scheduleFrame();   // full vsync rate while anything is moving
            } else {
                // Idle: animated clock styles tick at ~30 fps, the clean style barely wakes up.
                handler.postDelayed(idleRunnable, clockStyle == STYLE_CLEAN ? 500 : 33);
            }
        }

        // ------------------------------------------------------------------ physics

        private void updateTilt(float dt) {
            if (!gravityInit) return;
            if (!neutralInit) {
                neutralX = rawTiltX;
                neutralY = rawTiltY;
                neutralInit = true;
            }
            // The "centre" slowly follows how you hold the phone, so only changes register.
            float a = Math.min(1f, dt / 6f);
            neutralX += (rawTiltX - neutralX) * a;
            neutralY += (rawTiltY - n
