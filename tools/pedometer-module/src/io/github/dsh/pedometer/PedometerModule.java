package io.github.dsh.pedometer;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPatch-embedded adapter for the Android SensorManager pedometer path.
 * It only runs inside a patched app process and only wraps step sensors.
 *
 * Mapping is (SensorManager identity, listener identity, sensor identity), so
 * one listener may hold separate step_counter and step_detector registrations,
 * and two SensorManager instances never remove each other's registrations.
 * Non-step registrations are never wrapped.
 */
public final class PedometerModule implements IXposedHookLoadPackage {
    private static final RegistrationTable<StepProxy> REGS = new RegistrationTable<>();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String pkg = lp.packageName == null ? "" : lp.packageName;
            if (pkg.length() == 0 || pkg.equals("android") || pkg.startsWith("com.android.")
                    || pkg.startsWith("com.miui.") || pkg.startsWith("com.google.")) return;
            // Only the app's main process owns the loopback control server; secondary
            // processes still get hooks but cannot create a second server/port.
            boolean mainProcess = lp.processName == null || lp.processName.length() == 0
                    || lp.processName.equals(pkg);
            StepControl.configureTarget(mainProcess ? pkg : "");
            hookRegister(lp.classLoader, new Class[]{SensorEventListener.class, Sensor.class, int.class});
            hookRegister(lp.classLoader, new Class[]{SensorEventListener.class, Sensor.class, int.class, Handler.class});
            hookRegister(lp.classLoader, new Class[]{SensorEventListener.class, Sensor.class, int.class, int.class});
            hookRegister(lp.classLoader, new Class[]{SensorEventListener.class, Sensor.class, int.class, int.class, Handler.class});
            hookUnregister(lp.classLoader, new Class[]{SensorEventListener.class});
            hookUnregister(lp.classLoader, new Class[]{SensorEventListener.class, Sensor.class});
            android.util.Log.i("PedometerAdapter", "loaded for " + pkg + " process=" + lp.processName
                    + " control=" + mainProcess);
        } catch (Throwable t) {
            android.util.Log.e("PedometerAdapter", "init failed", t);
        }
    }

    private static Handler lastHandler(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof Handler) return (Handler) args[i];
        }
        return null;
    }

    private static StepProxy find(SensorManager manager, SensorEventListener listener, Sensor sensor) {
        return REGS.find(manager, listener, sensor);
    }

    private static void removeProxy(StepProxy p) {
        REGS.removeValue(p);
        StepControl.unregister(p);
    }

    private static void hookRegister(ClassLoader cl, final Class<?>[] params) {
        try {
            Object[] call = new Object[params.length + 1];
            System.arraycopy(params, 0, call, 0, params.length);
            call[params.length] = new XC_MethodHook() {
                private final ThreadLocal<StepProxy> created = new ThreadLocal<>();

                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    created.remove();
                    try {
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.thisObject instanceof SensorManager)) return;
                        Object l = param.args[0];
                        Object s = param.args[1];
                        if (!(l instanceof SensorEventListener) || !(s instanceof Sensor)) return;
                        Sensor sensor = (Sensor) s;
                        int type = sensor.getType();
                        if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return;
                        if (l instanceof StepProxy) return; // re-entrant / already wrapped
                        SensorEventListener real = (SensorEventListener) l;
                        SensorManager manager = (SensorManager) param.thisObject;
                        Handler handler = lastHandler(param.args);
                        StepProxy proxy = find(manager, real, sensor);
                        if (proxy == null) {
                            proxy = new StepProxy(manager, real, sensor, handler);
                            REGS.put(manager, real, sensor, proxy);
                            StepControl.register(proxy);
                            created.set(proxy);
                        } else {
                            // Re-registering the same pair must honor the new Handler.
                            proxy.updateHandler(handler);
                        }
                        param.args[0] = proxy;
                    } catch (Throwable t) {
                        android.util.Log.e("PedometerAdapter", "register hook", t);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    StepProxy p = created.get();
                    created.remove();
                    if (p == null) return;
                    boolean failed = param.hasThrowable()
                            || Boolean.FALSE.equals(param.getResult());
                    if (failed) removeProxy(p);
                }
            };
            XposedHelpers.findAndHookMethod(SensorManager.class.getName(), cl, "registerListener", call);
        } catch (Throwable ignored) {
        }
    }

    private static void hookUnregister(ClassLoader cl, final Class<?>[] params) {
        try {
            Object[] call = new Object[params.length + 1];
            System.arraycopy(params, 0, call, 0, params.length);
            call[params.length] = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.thisObject instanceof SensorManager)) return;
                        Object l = param.args[0];
                        if (l instanceof StepProxy) return;
                        if (!(l instanceof SensorEventListener)) return;
                        SensorManager manager = (SensorManager) param.thisObject;
                        Sensor sensor = null;
                        if (params.length >= 2 && param.args[1] instanceof Sensor) sensor = (Sensor) param.args[1];
                        java.util.List<StepProxy> remove = REGS.remove(manager, l, sensor);
                        for (StepProxy p : remove) StepControl.unregister(p);
                        if (sensor != null) {
                            if (remove.size() == 1) {
                                param.args[0] = remove.get(0);
                            } else if (remove.size() > 1) {
                                param.setResult(null);
                                for (StepProxy p : remove) {
                                    try {
                                        XposedHelpers.callMethod(param.thisObject, "unregisterListener",
                                                new Class[]{SensorEventListener.class}, p);
                                    } catch (Throwable ignored) {}
                                }
                            }
                            // If no step proxy matched, the real listener/sensor is
                            // non-step and the original call must still run unchanged.
                        } else if (!remove.isEmpty()) {
                            // Unregister every wrapped step listener, then also call
                            // the original single-arg method for the REAL listener so
                            // its non-step registrations are not leaked.
                            param.setResult(null);
                            for (StepProxy p : remove) {
                                try {
                                    XposedHelpers.callMethod(param.thisObject, "unregisterListener",
                                            new Class[]{SensorEventListener.class}, p);
                                } catch (Throwable ignored) {}
                            }
                            try {
                                XposedHelpers.callMethod(param.thisObject, "unregisterListener",
                                        new Class[]{SensorEventListener.class}, l);
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("PedometerAdapter", "unregister hook", t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(SensorManager.class.getName(), cl, "unregisterListener", call);
        } catch (Throwable ignored) {
        }
    }
}
