package com.vox.shellbridge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ShellBridge v2.2 — native cluster dashboard (self-drawn Canvas).
 *
 * Data source: HUD broadcast action=com.vox.hud.UPDATE, extra "json"
 * (HudService.broadcastMerged). No hud-apk assets/UI code is referenced.
 * Launched on the cluster display via ShellBridge "#CLUSTER START"
 * (am start --display N -n com.vox.shellbridge/.DashboardActivity).
 *
 * Visual: dark background + single cyan accent — deliberately NOT a copy
 * of HUD's dashboard.html look.
 */
public class DashboardActivity extends Activity {

    /** HUD data broadcast (see hud-apk HudService.ACTION_UPDATE). */
    public static final String ACTION_HUD_UPDATE = "com.vox.hud.UPDATE";
    /** JSON payload extra key (HudService.EXTRA_JSON). */
    public static final String EXTRA_JSON = "json";
    /** ShellBridge "#CLUSTER STOP" sends this to close the dashboard. */
    public static final String ACTION_FINISH = "com.vox.shellbridge.FINISH_DASHBOARD";

    private DashboardView dash;

    private final BroadcastReceiver hudReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String json = intent.getStringExtra(EXTRA_JSON);
            if (json != null) dash.applyJson(json);
        }
    };

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dash = new DashboardView(this);
        setContentView(dash);
        try {
            registerReceiver(finishReceiver, new IntentFilter(ACTION_FINISH));
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // targetSdk 31: implicit broadcast via runtime-registered receiver is valid.
        try {
            registerReceiver(hudReceiver, new IntentFilter(ACTION_HUD_UPDATE));
        } catch (Exception ignored) { }
        dash.invalidate();
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(hudReceiver); } catch (Exception ignored) { }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(finishReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /**
     * Self-drawn cluster view: big speed, RPM segmented bar, gear,
     * coolant / voltage / battery / fuel cells, status line.
     * Field mapping (tolerant, unknown fields ignored):
     *   speed←speed  rpm←rpm  gear←gear  coolant←coolantTemp
     *   volt←voltage  batt←power  fuel←fuelLevel  odo←odo  heading←heading
     * Missing fields (gear/coolantTemp are not in the current broadcast
     * JSON) resolve to -1 and render as "--".
     */
    static class DashboardView extends View {

        private static final int  MAX_RPM        = 7000;
        private static final int  SEGMENTS       = 36;
        private static final long DATA_STALE_MS  = 8000L;

        private static final int BG      = Color.rgb(5, 8, 12);
        private static final int ACCENT  = Color.rgb(64, 200, 255);  // single accent
        private static final int INK     = Color.rgb(235, 244, 250); // primary text
        private static final int DIM     = Color.rgb(28, 42, 54);    // inactive track
        private static final int LABEL   = Color.rgb(110, 132, 148); // secondary text
        private static final int REDLINE = Color.rgb(255, 92, 82);   // redline segments

        private final Paint fill  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        // last known values (-1 / -1f / -1d = unknown)
        private int    speed = -1, rpm = -1, gear = -1, coolant = -1, fuel = -1;
        private int    power = -1, heading = -1;
        private double voltage = -1, odo = -1;
        private long   lastRxMs = 0;

        DashboardView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            text.setTypeface(Typeface.SANS_SERIF);
            text.setTextAlign(Paint.Align.CENTER);
        }

        /** Called on main thread from the HUD receiver; tolerant of unknown fields. */
        void applyJson(String json) {
            try {
                JSONObject o = new JSONObject(json);
                speed   = o.optInt("speed", -1);
                rpm     = o.optInt("rpm", -1);
                gear    = o.optInt("gear", -1);         // not in current broadcast → -1
                coolant = o.optInt("coolantTemp", -1);  // not in current broadcast → -1
                voltage = o.optDouble("voltage", -1);
                fuel    = o.optInt("fuelLevel", -1);
                power   = o.optInt("power", -1);        // vehicle battery total %
                heading = o.optInt("heading", -1);
                odo     = o.optDouble("odo", -1);
                lastRxMs = System.currentTimeMillis();
                invalidate();
            } catch (Exception ignored) {
                // malformed frame: keep last good values
            }
        }

        private int dp(float v) { return Math.round(v * density); }

        private static String num(double v, int decimals) {
            if (v < 0) return "--";
            return decimals <= 0 ? String.valueOf((long) v)
                    : String.format(Locale.US, "%." + decimals + "f", v);
        }

        private static float frac(double v, double lo, double hi) {
            if (v < 0) return 0f;
            return (float) Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            canvas.drawColor(BG);
            boolean live = lastRxMs > 0
                    && System.currentTimeMillis() - lastRxMs < DATA_STALE_MS;
            float pad = dp(16);

            // ── top bar: identity + status ──
            text.setTextSize(dp(11));
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(LABEL);
            canvas.drawText("SHELLBRIDGE · CLUSTER", pad, pad + dp(10), text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(live ? ACCENT : LABEL);
            String status = live
                    ? "SRC: HUD " + new SimpleDateFormat("HH:mm:ss", Locale.US)
                            .format(new Date(lastRxMs))
                    : "WAITING FOR HUD";
            canvas.drawText(status, w - pad, pad + dp(10), text);
            text.setTextAlign(Paint.Align.CENTER);

            // ── RPM segmented bar ──
            float barTop = pad + dp(22);
            float barH   = dp(10);
            float segW   = (w - pad * 2) / SEGMENTS;
            int lit = rpm < 0 ? 0
                    : Math.min(SEGMENTS, (int) (rpm * SEGMENTS / (float) MAX_RPM));
            for (int i = 0; i < SEGMENTS; i++) {
                fill.setColor(i < lit
                        ? (i >= SEGMENTS - 5 ? REDLINE : ACCENT)
                        : DIM);
                float l = pad + i * segW;
                canvas.drawRect(l, barTop, l + segW - dp(2), barTop + barH, fill);
            }
            text.setTextSize(dp(11));
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(LABEL);
            canvas.drawText("RPM", pad, barTop + barH + dp(14), text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(rpm < 0 ? LABEL
                    : (rpm > MAX_RPM * 0.85 ? REDLINE : ACCENT));
            canvas.drawText(num(rpm, 0), w - pad, barTop + barH + dp(14), text);
            text.setTextAlign(Paint.Align.CENTER);

            // ── big speed + gear badge ──
            float areaTop = barTop + barH + dp(20);
            float areaBot = h - dp(78);
            float bigSize = Math.min((areaBot - areaTop) * 0.62f, w * 0.30f);
            float midY    = (areaTop + areaBot) / 2f;
            String sp = speed < 0 ? "--" : String.valueOf(speed);
            text.setTextSize(bigSize);
            text.setColor(live ? INK : Color.rgb(90, 105, 118));
            canvas.drawText(sp, w / 2f, midY + bigSize * 0.35f, text);
            float half = text.measureText(sp) / 2f;
            text.setTextSize(dp(11));
            text.setColor(LABEL);
            canvas.drawText("KM/H", w / 2f, midY + bigSize * 0.35f + dp(16), text);
            String g = gear < 0 ? "--" : (gear == 0 ? "N" : String.valueOf(gear));
            text.setTextSize(bigSize * 0.42f);
            text.setColor(live ? ACCENT : Color.rgb(90, 105, 118));
            canvas.drawText(g, Math.min(w - pad - dp(10), w / 2f + half + dp(28)),
                    midY + bigSize * 0.15f, text);

            // ── bottom cells: coolant / volt / battery / fuel ──
            String[] names  = { "COOLANT", "VOLT", "BATT", "FUEL" };
            String[] values = {
                    coolant < 0 ? "--" : num(coolant, 0) + "\u00B0C",
                    num(voltage, 1),
                    power < 0 ? "--" : num(power, 0) + "%",
                    fuel  < 0 ? "--" : num(fuel, 0) + "%"
            };
            float[] fracs = {
                    frac(coolant, 0, 130), frac(voltage, 10, 16),
                    frac(power, 0, 100),   frac(fuel, 0, 100)
            };
            float cellW   = (w - pad * 2) / 4f;
            float cellTop = h - dp(64);
            for (int i = 0; i < 4; i++) {
                float cx = pad + cellW * i + cellW / 2f;
                text.setTextSize(dp(10));
                text.setColor(LABEL);
                canvas.drawText(names[i], cx, cellTop, text);
                text.setTextSize(dp(15));
                text.setColor(live ? INK : Color.rgb(90, 105, 118));
                canvas.drawText(values[i], cx, cellTop + dp(18), text);
                float bw = cellW - dp(14);
                fill.setColor(DIM);
                canvas.drawRect(cx - bw / 2f, cellTop + dp(24),
                        cx + bw / 2f, cellTop + dp(27), fill);
                if (fracs[i] > 0) {
                    fill.setColor(ACCENT);
                    canvas.drawRect(cx - bw / 2f, cellTop + dp(24),
                            cx - bw / 2f + bw * fracs[i], cellTop + dp(27), fill);
                }
            }

            // ── footer: odometer + heading ──
            text.setTextSize(dp(10));
            text.setColor(LABEL);
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("ODO " + num(odo, 1), pad, h - dp(8), text);
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("HDG " + num(heading, 0) + "\u00B0", w - pad, h - dp(8), text);
            text.setTextAlign(Paint.Align.CENTER);

            if (!live) postInvalidateDelayed(1000);
        }
    }
}
