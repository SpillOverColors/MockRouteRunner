package io.github.dsh.pedometer;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import de.robv.android.xposed.XposedHelpers;

/** One (SensorManager, real listener, sensor) registration wrapped with synthetic step events. */
final class StepProxy implements SensorEventListener {
    private final SensorManager manager;
    private final SensorEventListener real;
    private final Sensor sensor;
    private volatile Handler handler;
    private final DeliveryGate gate = new DeliveryGate();
    private long lastDeliveredCounter;

    StepProxy(SensorManager manager, SensorEventListener real, Sensor sensor, Handler handler) {
        this.manager = manager;
        this.real = real;
        this.sensor = sensor;
        this.handler = handler;
    }

    SensorEventListener real() { return real; }
    Sensor sensor() { return sensor; }
    SensorManager manager() { return manager; }

    void updateHandler(Handler h) { this.handler = h; }

    /** Invalidate events already queued for this registration. */
    void deactivate() { gate.deactivate(); }

    @Override public void onSensorChanged(SensorEvent event) {
        int type = event.sensor == null ? -1 : event.sensor.getType();
        float raw = event.values != null && event.values.length > 0 ? event.values[0] : 0;
        StepControl.noteReal(type, raw);
        if ((type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR)
                && StepControl.isSuppressingReal()) {
            return;
        }
        if (type == Sensor.TYPE_STEP_COUNTER && Float.isFinite(raw)) {
            long value = (long) raw;
            // After STOP the platform counter may be below the last synthetic value.
            // Drop the out-of-order sample until the real counter catches up; never
            // make the target observe a backwards counter.
            if (value < lastDeliveredCounter) return;
            lastDeliveredCounter = value;
        }
        real.onSensorChanged(event);
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) {
        real.onAccuracyChanged(s, accuracy);
    }

    void inject(int value, long timestampNanos, final long generation) {
        final long gateToken = gate.token();
        if (gateToken == 0 || !StepControl.deliveryActive(generation)) return;
        try {
            final SensorEvent e = (SensorEvent) XposedHelpers.newInstance(SensorEvent.class, 1);
            XposedHelpers.setObjectField(e, "sensor", sensor);
            e.values[0] = value;
            e.timestamp = timestampNanos;
            e.accuracy = 0;
            Runnable delivery = new Runnable() {
                @Override public void run() {
                    // A POSTed event can outlive PAUSE/STOP or unregister. Both the
                    // engine generation and this registration's gate must still hold.
                    if (!gate.allows(gateToken)
                            || !StepControl.deliveryActive(generation)) return;
                    if (sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                        lastDeliveredCounter = Math.max(lastDeliveredCounter, (long) value);
                    }
                    real.onSensorChanged(e);
                }
            };
            Handler h = handler;
            if (h != null) h.post(delivery);
            else delivery.run();
        } catch (Throwable t) {
            android.util.Log.e("PedometerAdapter", "inject failed", t);
        }
    }
}
