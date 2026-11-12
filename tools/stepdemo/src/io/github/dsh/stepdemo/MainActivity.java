package io.github.dsh.stepdemo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/** Minimal consumer of TYPE_STEP_COUNTER, for LSPatch module validation only. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "StepDemo";
    private TextView text;
    private SensorManager sm;
    private Sensor sensor;
    private boolean registered;
    private long events;
    private long lastEventNanos;
    private float lastValue;
    private double steadyCadence;
    private boolean firstAfterGap;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        text = new TextView(this);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setTextSize(18);
        text.setPadding(32, 32, 32, 32);
        text.setText("StepDemo: requesting ACTIVITY_RECOGNITION...");
        setContentView(text);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 10);
        } else {
            startSensor();
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == 10 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startSensor();
        else text.setText("StepDemo: ACTIVITY_RECOGNITION denied");
    }

    private void startSensor() {
        if (registered) return;
        Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) { text.setText("StepDemo: no step counter sensor"); return; }
        this.sensor = sensor;
        boolean ok = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                new Handler(Looper.getMainLooper()));
        registered = ok;
        text.setText("StepDemo: registered=" + ok + " sensor=" + sensor.getName());
        Log.i(TAG, "registered=" + ok + " sensor=" + sensor.getName());
    }

    @Override protected void onDestroy() {
        if (registered && sm != null && sensor != null) {
            sm.unregisterListener(this, sensor);
            sm.unregisterListener(this);
            registered = false;
            Log.i(TAG, "listener unregistered");
        }
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        events++;
        long timestamp = e.timestamp;
        float value = e.values != null && e.values.length > 0 ? e.values[0] : 0;
        double dt = 0;
        float delta = 0;
        double eventCadence = 0;
        firstAfterGap = false;
        if (lastEventNanos > 0 && timestamp > lastEventNanos) {
            dt = (timestamp - lastEventNanos) / 1_000_000_000.0;
            delta = Math.max(0, value - lastValue);
            eventCadence = delta * 60.0 / dt;
            // The first event after PAUSE has a real timestamp gap that includes
            // the paused wall time. It is a boundary sample, not steady cadence.
            firstAfterGap = dt > 1.2;
            if (!firstAfterGap) steadyCadence = eventCadence;
        }
        lastEventNanos = timestamp;
        lastValue = value;
        final String line = "events=" + events + " value=" + value
                + " dt=" + String.format(java.util.Locale.US, "%.3f", dt)
                + " dSteps=" + delta
                + " eventCadence=" + String.format(java.util.Locale.US, "%.1f", eventCadence)
                + " steady=" + String.format(java.util.Locale.US, "%.1f", steadyCadence)
                + (firstAfterGap ? " RESUME_FIRST(excluded)" : "");
        runOnUiThread(() -> {
            text.setText("StepDemo (LSPatch test)\n" + line
                    + "\n\nThis app is a synthetic consumer, not the target app.\n"
                    + "Cadence uses SensorEvent.timestamp + counter delta; first sample after a >1.2 s gap is excluded.\n"
                    + "control: loopback package-derived port 39000-39999 (HELLO/START/PAUSE/RESUME/STOP/STATUS)");
        });
        Log.i(TAG, line);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
