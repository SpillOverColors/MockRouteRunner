package io.github.dsh.mockroute;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.util.Locale;

/**
 * Foreground service that owns the test provider. The provider is removed only
 * by this service, only if this run successfully added it. The app does not
 * change the system mock-location app-op.
 */
public final class MockRouteService extends Service {
    static final String ACTION_START = "io.github.dsh.mockroute.action.START";
    static final String ACTION_STOP = "io.github.dsh.mockroute.action.STOP";
    static final String ACTION_PAUSE = "io.github.dsh.mockroute.action.PAUSE";
    static final String ACTION_RESUME = "io.github.dsh.mockroute.action.RESUME";
    static final String EXTRA_FAST = "fast";
    static final String EXTRA_SLOW = "slow";
    static final String EXTRA_DISTANCE = "distance";
    static final String EXTRA_LATERAL = "lateral";
    static final String EXTRA_CADENCE_MIN = "cadence_min";
    static final String EXTRA_CADENCE_MAX = "cadence_max";
    static final String EXTRA_SEED = "seed";
    static final String EXTRA_ROUTE_FILE = "route_file";
    static final String EXTRA_TARGET_PACKAGE = "target_package";

    private static final String CHANNEL_ID = "mock_route";
    private static final int NOTIFICATION_ID = 4101;
    private static final long INJECT_INTERVAL_MS = 1000L;

    private volatile boolean stopRequested;
    private volatile boolean pauseRequested;
    private volatile boolean resumeRequested;
    private boolean checkpointSaveFailed;
    private Thread worker;
    private NotificationManager notificationManager;
    private RouteModel.GeoPoint lastInjectedPoint;
    private long lastInjectedNanos;
    private volatile boolean networkProviderAdded;
    private volatile boolean fusedProviderAdded;
    private final ProviderOwnership providerOwnership = new ProviderOwnership();

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        startInForeground("准备中", "等待启动参数");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "模拟定位运行",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("标准显式 mock 定位前台服务");
            notificationManager.createNotificationChannel(ch);
        }
    }

    private void startInForeground(String title, String text) {
        Notification n = buildNotification(title, text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MockRouteService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止并清理", stopPi);
        return b.build();
    }

    private void updateNotification(String title, String text) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            pauseRequested = false;
            resumeRequested = false;
            RunState.append("通知/界面收到停止请求");
            if (worker == null) {
                finishService("已停止", "没有运行中的注入");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            if (worker != null && worker.isAlive() && !RunState.paused) {
                pauseRequested = true;
                resumeRequested = false;
                RunState.state = "暂停中";
                RunState.append("界面请求暂停；到达下一个安全点后保存断点");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            if (worker != null && worker.isAlive()) {
                if (RunState.paused) {
                    resumeRequested = true;
                    pauseRequested = false;
                    RunState.state = "恢复中";
                    RunState.append("界面请求从断点继续");
                }
                return START_NOT_STICKY;
            }
            startResumeWorker();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            return START_NOT_STICKY;
        }
        pauseRequested = false;
        resumeRequested = false;
        final int fast = intent.getIntExtra(EXTRA_FAST, 390);
        final int slow = intent.getIntExtra(EXTRA_SLOW, 470);
        final double distance = intent.getDoubleExtra(EXTRA_DISTANCE, 2000);
        final double lateral = intent.getDoubleExtra(EXTRA_LATERAL, 0.3);
        final double cadenceMin = intent.getDoubleExtra(EXTRA_CADENCE_MIN, 100);
        final double cadenceMax = intent.getDoubleExtra(EXTRA_CADENCE_MAX, 130);
        final long seed = intent.getLongExtra(EXTRA_SEED, System.nanoTime());
        final String routeFile = intent.getStringExtra(EXTRA_ROUTE_FILE);
        final String targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
        stopRequested = false;
        RunState.reset(distance);
        RunState.append("前台服务启动；仅使用标准 gps 测试提供器");
        RunState.append("可选步频增强通过 LSPatch 目标包桥接；未改包时仅本机显示");
        worker = new Thread(new Runnable() {
            @Override public void run() {
                runSimulation(fast, slow, distance, lateral, cadenceMin, cadenceMax, seed,
                        routeFile, targetPackage, null);
            }
        }, "mock-route");
        worker.start();
        return START_NOT_STICKY;
    }

    private void startResumeWorker() {
        final RunCheckpoint.Saved saved = RunCheckpoint.load(this);
        if (saved == null || saved.routeFile == null) {
            RunState.append("没有可用断点，忽略继续请求");
            finishService("空闲", "没有可用断点");
            return;
        }
        if (!new File(saved.routeFile).isFile()) {
            RunState.append("断点路线文件不存在：" + saved.routeFile);
            RunState.error = "断点路线文件不存在";
            finishService("失败", "断点路线文件不存在");
            return;
        }
        stopRequested = false;
        pauseRequested = false;
        resumeRequested = false;
        RunState.reset(saved.distance);
        RunState.paused = true;
        RunState.state = "恢复中";
        RunState.checkpointAvailable = true;
        RunState.checkpointMessage = "已读取断点";
        RunState.append("读取断点：已跑 " + String.format(Locale.US, "%.1f", saved.snapshot.total)
                + " / " + String.format(Locale.US, "%.0f", saved.distance) + " m");
        worker = new Thread(new Runnable() {
            @Override public void run() {
                runSimulation(saved.fast, saved.slow, saved.distance, saved.lateral,
                        saved.cadenceMin, saved.cadenceMax, saved.seed, saved.routeFile,
                        saved.targetPackage, saved);
            }
        }, "mock-route-resume");
        worker.start();
    }

    private void runSimulation(int fast, int slow, double distance, double lateral,
                               double cadenceMin, double cadenceMax, long seed, String routeFile,
                               String targetPackage, RunCheckpoint.Saved saved) {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean networkAdded = false;
        boolean createdAny = false;
        boolean cleanupFailed = false;
        String terminal = "已停止";
        String terminalText = "用户停止，已清理";
        int consecutiveFailures = 0;
        try {
            if (routeFile == null) throw new IllegalArgumentException("缺少路线文件");
            File rf = new File(routeFile);
            String text = readText(rf);
            RouteModel.RouteSet routes = RouteModel.parseJson(text, rf.getName());
            MotionSimulator sim = saved == null
                    ? new MotionSimulator(routes, fast, slow, distance, lateral, seed)
                    : MotionSimulator.restore(routes, fast, slow, distance, lateral, seed, saved.snapshot);
            if (saved != null) RunState.checkpointAvailable = true;

            if (!isMockAppopAllowed()) {
                throw new SecurityException("未获得 android:mock_location appop。请在开发者选项把本应用选为“选择模拟位置信息应用”，或由 ADB 授予。本应用不会自行更改 appop。");
            }
            if (!locationEnabled(lm)) {
                throw new IllegalStateException("系统定位总开关未开启");
            }
            if (!hasFinePermission()) {
                throw new SecurityException("缺少精确位置权限");
            }
            if (recentMockLocation(lm)) {
                throw new SecurityException("最近一次 gps/network 位置带 mock 标记，可能已有其他模拟位置应用或旧测试提供器。为避免覆盖，拒绝启动；请先停止其他模拟应用并在开发者选项确认。");
            }
            RunState.append("预检：公开 API 无法枚举其他应用测试提供器；最近位置未发现 mock 标记");
            RunState.append("addTestProvider 会替换同名 provider；本应用只清理本次运行成功创建的 provider，"
                    + "无法证明旧 provider 归属时不会自动删除");

            lm.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, true, false, false, false, false, false,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            providerOwnership.created(LocationManager.GPS_PROVIDER);
            createdAny = true;
            try {
                lm.addTestProvider(LocationManager.NETWORK_PROVIDER,
                        true, false, false, false, false, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
                networkAdded = true;
                networkProviderAdded = true;
                providerOwnership.created(LocationManager.NETWORK_PROVIDER);
                createdAny = true;
            } catch (RuntimeException e) {
                RunState.append("network 测试提供器不可用，仅使用 gps：" + safe(e.getMessage()));
            }
            boolean fusedAdded = false;
            try {
                lm.addTestProvider("fused",
                        true, false, false, false, false, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                fusedAdded = true;
                fusedProviderAdded = true;
                providerOwnership.created("fused");
                createdAny = true;
            } catch (RuntimeException e) {
                RunState.append("fused 测试提供器不可用：" + safe(e.getMessage()));
            }
            if (saved == null) {
                RunCheckpoint.clear(this);
                RunState.checkpointAvailable = false;
                RunState.checkpointMessage = "无";
            }
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            if (networkAdded) lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            if (fusedAdded) lm.setTestProviderEnabled("fused", true);
            RunState.append(networkAdded
                    ? "已创建标准 gps/network 测试提供器（系统会标记 mock）"
                    : "已创建标准 gps 测试提供器（系统会标记 mock）");
            inject(lm, sim.point(), SystemClock.elapsedRealtimeNanos());
            RunState.injections = 1;
            RunState.hasPoint = true;
            RunState.lat = sim.point().lat;
            RunState.lon = sim.point().lon;
            RunState.running = true;
            RunState.paused = false;
            RunState.state = "运行中";
            PedometerBridge.start(targetPackage, cadenceMin, cadenceMax, seed);
            RunState.append(String.format(Locale.US, "总距离 %.1f m；配速范围 %d–%d s/km；扰动上限 %.2f m",
                    distance, fast, slow, lateral));

            long lastNanos = SystemClock.elapsedRealtimeNanos();
            long nextNanos = lastNanos + nextWaitNanos(sim);
            long lastNotif = SystemClock.elapsedRealtime();
            while (!stopRequested) {
                if (pauseRequested) {
                    pauseRequested = false;
                    RunState.paused = true;
                    RunState.state = "已暂停";
                    boolean savedNow = RunCheckpoint.save(this, routeFile, fast, slow, distance, lateral,
                            cadenceMin, cadenceMax, seed, targetPackage, sim);
                    RunState.checkpointAvailable = savedNow || RunCheckpoint.exists(this);
                    RunState.checkpointMessage = savedNow ? "已保存"
                            : (RunState.checkpointAvailable ? "保存失败，沿用旧断点" : "保存失败");
                    checkpointSaveFailed = !savedNow;
                    if (!savedNow) {
                        RunState.append("断点保存失败：上一份断点保留；请检查应用内部存储空间");
                    }
                    PedometerBridge.pause();
                    updateNotification("模拟定位已暂停",
                            savedNow ? "断点已保存；不补偿暂停期间的距离/时间"
                                  : "断点保存失败；上一份断点保留，请检查存储");
                    if (savedNow) RunState.append("已暂停并保存断点；位置保持在当前点，不补偿距离/时间");
                    else RunState.append("已暂停；当前点未写入新断点，恢复可能从上一份断点继续");
                    while (!stopRequested && !resumeRequested) {
                        try { Thread.sleep(50); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                    if (stopRequested) break;
                    resumeRequested = false;
                    RunState.paused = false;
                    RunState.state = "运行中";
                    lastNanos = SystemClock.elapsedRealtimeNanos();
                    nextNanos = lastNanos + nextWaitNanos(sim);
                    PedometerBridge.resume(cadenceMin, cadenceMax, seed);
                    updateNotification("模拟定位运行中", "从断点继续");
                    RunState.append("从断点继续；暂停期间不补偿距离/时间");
                    continue;
                }
                while (!stopRequested && !pauseRequested && SystemClock.elapsedRealtimeNanos() < nextNanos) {
                    long remainMs = (nextNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000L;
                    int sleepMs = (int) Math.max(1, Math.min(50, remainMs));
                    try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                if (stopRequested) break;
                if (pauseRequested) continue;
                long nowNanos = SystemClock.elapsedRealtimeNanos();
                double dt = (nowNanos - lastNanos) / 1_000_000_000.0;
                lastNanos = nowNanos;
                if (dt <= 0) continue;
                sim.advance(dt);
                try {
                    inject(lm, sim.point(), nowNanos);
                    consecutiveFailures = 0;
                    RunState.injections++;
                    RunState.hasPoint = true;
                    RunState.lat = sim.point().lat;
                    RunState.lon = sim.point().lon;
                } catch (RuntimeException ex) {
                    consecutiveFailures++;
                    RunState.append("位置注入失败 " + consecutiveFailures + " 次：" + ex.getMessage());
                    if (consecutiveFailures >= 3) throw new IllegalStateException("连续 3 次位置注入失败", ex);
                }
                RunState.distance = sim.totalDistance();
                RunState.paceSecPerKm = sim.paceSecPerKm();
                RunState.cadence = sim.cadence(cadenceMin, cadenceMax);
                RunState.loopName = sim.loopName();
                RunState.crossings = sim.junctionCrossings();
                RunState.elapsedMs = Math.round(sim.elapsedSeconds() * 1000.0);
                long wall = SystemClock.elapsedRealtime();
                if (wall - lastNotif >= 5000) {
                    lastNotif = wall;
                    boolean savedNow = RunCheckpoint.save(this, routeFile, fast, slow, distance, lateral,
                            cadenceMin, cadenceMax, seed, targetPackage, sim);
                    RunState.checkpointAvailable = savedNow || RunCheckpoint.exists(this);
                    RunState.checkpointMessage = savedNow ? "已保存"
                            : (RunState.checkpointAvailable ? "保存失败，沿用旧断点" : "保存失败");
                    if (!savedNow) {
                        if (!checkpointSaveFailed) {
                            checkpointSaveFailed = true;
                            RunState.append("周期断点保存失败：上一份断点保留");
                        }
                    } else if (checkpointSaveFailed) {
                        checkpointSaveFailed = false;
                        RunState.append("周期断点保存已恢复");
                    }
                    updateNotification("模拟定位运行中",
                            String.format(Locale.US, "%.1f / %.0f m，模拟配速 %s/km",
                                    sim.totalDistance(), distance, SimFmt(sim)));
                }
                if (sim.reachedTarget()) {
                    terminal = "已完成";
                    terminalText = "已达到总距离，正在清理";
                    RunState.append("已达到总距离，停止并清理");
                    break;
                }
                nextNanos = SystemClock.elapsedRealtimeNanos() + nextWaitNanos(sim);
            }
            if (stopRequested) RunState.append("收到停止请求，停止并清理");
        } catch (Throwable ex) {
            terminal = "失败";
            terminalText = "错误：" + safe(ex.getMessage());
            RunState.error = safe(ex.getMessage());
            RunState.append("错误：" + safe(ex.getMessage()));
        } finally {
            boolean cadenceStopped = PedometerBridge.stopAndWait(2500);
            if (!cadenceStopped) RunState.append("警告：步频桥 STOP 未在 2.5 秒内完成，需目标端确认");
            if (providerOwnership.owns(LocationManager.GPS_PROVIDER)) {
                try {
                    lm.removeTestProvider(LocationManager.GPS_PROVIDER);
                    providerOwnership.removed(LocationManager.GPS_PROVIDER);
                    RunState.append("已移除本应用创建的 gps 测试提供器");
                } catch (Throwable ex) {
                    cleanupFailed = true;
                    RunState.append("移除 gps 测试提供器失败：" + safe(ex.getMessage()));
                    RunState.error = "清理失败：" + safe(ex.getMessage());
                }
            }
            if (providerOwnership.owns(LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.removeTestProvider(LocationManager.NETWORK_PROVIDER);
                    networkProviderAdded = false;
                    providerOwnership.removed(LocationManager.NETWORK_PROVIDER);
                    RunState.append("已移除本应用创建的 network 测试提供器");
                } catch (Throwable ex) {
                    cleanupFailed = true;
                    RunState.append("移除 network 测试提供器失败：" + safe(ex.getMessage()));
                    RunState.error = "清理失败：" + safe(ex.getMessage());
                }
            }
            if (providerOwnership.owns("fused")) {
                try {
                    lm.removeTestProvider("fused");
                    fusedProviderAdded = false;
                    providerOwnership.removed("fused");
                    RunState.append("已移除本应用创建的 fused 测试提供器");
                } catch (Throwable ex) {
                    cleanupFailed = true;
                    RunState.append("移除 fused 测试提供器失败：" + safe(ex.getMessage()));
                    RunState.error = "清理失败：" + safe(ex.getMessage());
                }
            }
            if (!createdAny) {
                RunState.append("没有创建过测试提供器，无需移除");
            }
            RunState.running = false;
            RunState.paused = false;
            RunState.finished = true;
            RunState.state = Outcome.state(cleanupFailed, terminal);
            if (cleanupFailed) terminalText = "清理失败，可能仍存在 mock 位置：" + safe(RunState.error);
            if (stopRequested || "已完成".equals(terminal)) {
                RunCheckpoint.clear(this);
                RunState.checkpointAvailable = false;
                RunState.checkpointMessage = "无";
            }
            RunState.append("会话结束：" + RunState.state);
            updateNotification(Outcome.notificationTitle(cleanupFailed, terminal), terminalText);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private static String SimFmt(MotionSimulator sim) {
        return MotionSimulator.formatPace(sim.paceSecPerKm());
    }

    /** Shorten the tick at an open endpoint so the turn does not straddle one output step. */
    private long nextWaitNanos(MotionSimulator sim) {
        double remaining = sim.remainingDistance();
        if (remaining <= 0) return 1_000_000L;
        double speed = Math.max(0.5, sim.baseSpeed());
        double targetSeconds = remaining / speed;
        double endpointSeconds = sim.secondsToNextEndpoint();
        double seconds = Math.min(targetSeconds, endpointSeconds);
        long ms = (long) Math.ceil(seconds * 1000.0);
        if (ms < 1) ms = 1;
        if (ms > INJECT_INTERVAL_MS) ms = INJECT_INTERVAL_MS;
        return ms * 1_000_000L;
    }

    private void inject(LocationManager lm, RouteModel.GeoPoint p, long elapsedNanos) {
        Location loc = locationFor(LocationManager.GPS_PROVIDER, p, elapsedNanos);
        Location net = networkProviderAdded
                ? locationFor(LocationManager.NETWORK_PROVIDER, p, elapsedNanos) : null;
        Location fused = fusedProviderAdded
                ? locationFor("fused", p, elapsedNanos) : null;
        lastInjectedPoint = p;
        lastInjectedNanos = elapsedNanos;
        lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        if (net != null) lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, net);
        if (fused != null) lm.setTestProviderLocation("fused", fused);
    }

    private Location locationFor(String provider, RouteModel.GeoPoint p, long elapsedNanos) {
        Location loc = new Location(provider);
        loc.setLatitude(p.lat);
        loc.setLongitude(p.lon);
        loc.setAccuracy(5.0f);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(elapsedNanos);
        Bundle extras = new Bundle();
        extras.putInt("satellites", 12);
        extras.putInt("satellitesVisible", 12);
        extras.putInt("meanCn0", 40);
        extras.putInt("maxCn0", 45);
        loc.setExtras(extras);
        if (lastInjectedPoint != null && elapsedNanos > lastInjectedNanos) {
            double dt = (elapsedNanos - lastInjectedNanos) / 1_000_000_000.0;
            double distance = RouteModel.GeoMath.distance(lastInjectedPoint, p);
            loc.setSpeed((float) (distance / dt));
            loc.setBearing((float) RouteModel.GeoMath.bearing(lastInjectedPoint, p));
        } else {
            loc.setSpeed(0f);
            loc.setBearing(0f);
        }
        return loc;
    }

    private boolean isMockAppopAllowed() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Best-effort only: public Android APIs cannot enumerate other apps' test providers. */
    @SuppressWarnings("deprecation")
    private boolean recentMockLocation(LocationManager lm) {
        long now = System.currentTimeMillis();
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (String p : providers) {
            try {
                Location last = lm.getLastKnownLocation(p);
                if (last != null && last.isFromMockProvider()
                        && now - last.getTime() < 10 * 60 * 1000L) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Permission/race conditions are handled by the regular preflight.
            }
        }
        return false;
    }

    private boolean hasFinePermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationEnabled(LocationManager lm) {
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String readText(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private void finishService(String state, String text) {
        RunState.running = false;
        RunState.finished = true;
        RunState.state = state;
        RunState.append("会话结束：" + state);
        updateNotification("模拟定位" + state, text);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private static String safe(String s) { return s == null ? "未知错误" : s; }

    @Override
    public void onDestroy() {
        stopRequested = true;
        pauseRequested = false;
        resumeRequested = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
