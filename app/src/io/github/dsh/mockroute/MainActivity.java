package io.github.dsh.mockroute;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_IMPORT = 10;
    private static final int REQ_PERMISSIONS = 11;

    private TextView tvRoute, tvState, tvLive, tvLog;
    private EditText etFast, etSlow, etDistance, etLateral, etCadenceMin, etCadenceMax, etTargetPkg;
    private Button btnStart, btnStop, btnEdit, btnImportGpx, btnImportJson, btnPause, btnResume;
    private RouteModel.RouteSet route;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pendingStart;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshRunState();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvRoute = findViewById(R.id.tvRoute);
        tvState = findViewById(R.id.tvState);
        tvLive = findViewById(R.id.tvLive);
        tvLog = findViewById(R.id.tvLog);
        etFast = findViewById(R.id.etFast);
        etSlow = findViewById(R.id.etSlow);
        etDistance = findViewById(R.id.etDistance);
        etLateral = findViewById(R.id.etLateral);
        etCadenceMin = findViewById(R.id.etCadenceMin);
        etCadenceMax = findViewById(R.id.etCadenceMax);
        etTargetPkg = findViewById(R.id.etTargetPkg);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnPause = findViewById(R.id.btnPause);
        btnResume = findViewById(R.id.btnResume);
        btnEdit = findViewById(R.id.btnEditRoute);
        btnImportGpx = findViewById(R.id.btnImportGpx);
        btnImportJson = findViewById(R.id.btnImportJson);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        btnImportGpx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openRoutePicker(); }
        });
        btnImportJson.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openRoutePicker(); }
        });
        btnEdit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (route == null) { toast("请先导入路线"); return; }
                if (RunState.running) { toast("运行中不能编辑路线"); return; }
                startActivity(new Intent(MainActivity.this, RouteEditorActivity.class));
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(); }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestStop(); }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestPause(); }
        });
        btnResume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestResume(); }
        });
        findViewById(R.id.btnAdvanced).setOnClickListener(v -> {
            View panel = findViewById(R.id.advancedPanel);
            boolean show = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(show ? View.VISIBLE : View.GONE);
            ((Button) v).setText(show ? "高级设置与日志 · 收起" : "高级设置与日志 · 展开");
        });
        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { toast("请到系统设置中找到本应用的省电策略"); }
        });
        findViewById(R.id.btnHelp).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("使用顺序")
                .setMessage("1. 开启系统定位，在开发者选项选择本应用作为模拟位置应用。\n"
                        + "2. 导入路线，设置本次总距离和配速；将本应用省电策略设为无限制。\n"
                        + "3. 开始模拟，确认运行中、目标距离正确、注入次数增加。\n"
                        + "4. 再打开目标应用，确认位置到达路线后开始记录。\n"
                        + "5. 先结束目标应用的记录，再停止并清理。程序不会代你打开或提交目标成绩。")
                .setPositiveButton("知道了", null).show());
        loadRoute();
        handler.post(poll);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        loadRoute();
        refreshRunState();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    private void loadRoute() {
        try {
            route = RouteStore.load(this);
        } catch (Exception ex) {
            route = null;
            toast("路线读取失败：" + ex.getMessage());
        }
        updateRouteSummary();
    }

    private void updateRouteSummary() {
        if (route == null) {
            tvRoute.setText("尚未导入路线。支持多条共起终点的闭合环；WGS84，顺序为 lat,lon。");
            btnEdit.setEnabled(false);
            return;
        }
        StringBuilder b = new StringBuilder();
        if (route.isOpenSingle()) {
            RouteModel.RouteLoop l = route.loops.get(0);
            b.append("开放折线“").append(l.name).append("”：")
                    .append(l.points.size()).append(" 个顶点，")
                    .append(String.format(Locale.US, "%.1f", l.length)).append(" m\n")
                    .append("端点不瞬移；到达端点后原路折返。")
                    .append("WGS84 lat,lon。");
            tvRoute.setText(b.toString());
            btnEdit.setEnabled(!RunState.running);
            return;
        }
        b.append("路线 ").append(route.loops.size()).append(" 条闭环；共享路口 ")
                .append(String.format(Locale.US, "%.7f,%.7f", route.junction.lat, route.junction.lon))
                .append("（WGS84 lat,lon）\n");
        for (RouteModel.RouteLoop l : route.loops) {
            b.append("• ").append(l.name).append("：")
                    .append(String.format(Locale.US, "%.1f", l.length)).append(" m\n");
        }
        b.append("闭环平均长度 ").append(String.format(Locale.US, "%.1f", route.totalRouteLength() / route.loops.size()))
                .append(" m。");
        tvRoute.setText(b.toString());
        btnEdit.setEnabled(!RunState.running);
    }

    private void openRoutePicker() {
        if (RunState.running) { toast("运行中不能导入路线"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // No EXTRA_MIME_TYPES narrowing: system download providers often expose
        // .gpx as application/octet-stream. RouteStore validates the content itself.
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) { toast("未选择文件"); return; }
            try {
                route = RouteStore.importUri(this, uri);
                updateRouteSummary();
                toast("导入成功：" + route.loops.size() + " 条路线");
            } catch (Exception ex) {
                new AlertDialog.Builder(this)
                        .setTitle("路线导入失败")
                        .setMessage(ex.getMessage())
                        .setPositiveButton("知道了", null)
                        .show();
            }
        }
    }

    private void tryStart() {
        if (RunState.running) { toast("已在运行"); return; }
        if (route == null) { toast("请先导入路线"); return; }
        int fast, slow;
        double distance, lateral, cadenceMin, cadenceMax;
        try {
            fast = parseInt(etFast, "最快配速");
            slow = parseInt(etSlow, "最慢配速");
            distance = parseDouble(etDistance, "总距离");
            lateral = parseDouble(etLateral, "位置扰动上限");
            cadenceMin = parseDouble(etCadenceMin, "步频下限");
            cadenceMax = parseDouble(etCadenceMax, "步频上限");
        } catch (IllegalArgumentException ex) {
            toast(ex.getMessage());
            return;
        }
        if (fast < MotionSimulator.MIN_PACE_SEC_PER_KM || slow > MotionSimulator.MAX_PACE_SEC_PER_KM
                || fast > slow) {
            toast("配速须在 180–600 秒/公里，且最快 ≤ 最慢（180=3:00，600=10:00）");
            return;
        }
        if (!(distance > 0) || !Double.isFinite(distance)) {
            toast("总距离须为有限正数（米）");
            return;
        }
        if (lateral < 0 || lateral > 0.5 || !Double.isFinite(lateral)) {
            toast("位置扰动上限须为 0–0.5 米");
            return;
        }
        if (!Double.isFinite(cadenceMin) || !Double.isFinite(cadenceMax)
                || cadenceMin < MotionSimulator.MIN_CADENCE_SPM
                || cadenceMax > MotionSimulator.MAX_CADENCE_SPM
                || cadenceMin > cadenceMax) {
            toast("步频上下限须在 1–300 spm，且下限 ≤ 上限");
            return;
        }
        boolean needFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        if (needFine) {
            pendingStart = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMISSIONS);
            return;
        }
        if (!mockAppopAllowed()) {
            showMockOpDialog();
            return;
        }
        if (!locationEnabled()) {
            new AlertDialog.Builder(this).setTitle("系统定位未开启")
                    .setMessage("请打开系统定位总开关后重试。本应用不会自行更改系统开关。")
                    .setPositiveButton("打开设置", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("取消", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("无法自动枚举其他模拟应用")
                .setMessage("Android 公开 API 无法列出其他应用的测试位置提供器。\n\n"
                        + "本应用会检查最近 gps/network 位置的 mock 标记；同名提供器可能被系统替换；请先停止其他模拟工具。\n\n"                        + "请确认当前没有其他模拟位置应用/测试提供器在运行，再继续。")
                .setPositiveButton("确认并开始", (d, w) ->
                        startMockService(fast, slow, distance, lateral, cadenceMin, cadenceMax))
                .setNegativeButton("取消", null)
                .show();
    }

    private void startMockService(int fast, int slow, double distance, double lateral,
                                  double cadenceMin, double cadenceMax) {
        int seed = (int) (System.nanoTime() & 0x7fffffffL);
        Intent i = new Intent(this, MockRouteService.class).setAction(MockRouteService.ACTION_START);
        i.putExtra(MockRouteService.EXTRA_FAST, fast);
        i.putExtra(MockRouteService.EXTRA_SLOW, slow);
        i.putExtra(MockRouteService.EXTRA_DISTANCE, distance);
        i.putExtra(MockRouteService.EXTRA_LATERAL, lateral);
        i.putExtra(MockRouteService.EXTRA_CADENCE_MIN, cadenceMin);
        i.putExtra(MockRouteService.EXTRA_CADENCE_MAX, cadenceMax);
        i.putExtra(MockRouteService.EXTRA_SEED, (long) seed);
        i.putExtra(MockRouteService.EXTRA_ROUTE_FILE, RouteStore.file(this).getAbsolutePath());
        i.putExtra(MockRouteService.EXTRA_TARGET_PACKAGE, etTargetPkg.getText().toString().trim());
        RunState.reset(distance);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ex) {
            RunState.running = false;
            RunState.finished = true;
            RunState.state = "失败";
            RunState.error = "前台服务启动失败：" + ex.getMessage();
            toast(RunState.error);
            return;
        }
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        btnPause.setEnabled(true);
        btnResume.setEnabled(false);
        toast("正在启动，请检查状态区的目标距离与注入次数");
    }

    private void requestPause() {
        Intent i = new Intent(this, MockRouteService.class).setAction(MockRouteService.ACTION_PAUSE);
        startService(i);
        RunState.append("界面请求暂停并保存断点");
        tvState.setText("状态：暂停中，等待保存断点");
        btnPause.setEnabled(false);
    }

    private void requestResume() {
        Intent i = new Intent(this, MockRouteService.class).setAction(MockRouteService.ACTION_RESUME);
        if (RunState.running) {
            startService(i);
            RunState.append("界面请求继续");
            tvState.setText("状态：恢复中");
        } else if (RunCheckpoint.exists(this)) {
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                RunState.append("界面请求从磁盘断点继续");
                tvState.setText("状态：恢复中");
            } catch (Exception ex) {
                RunState.error = "恢复服务启动失败：" + ex.getMessage();
                toast(RunState.error);
            }
        } else {
            toast("没有可用断点");
        }
        btnResume.setEnabled(false);
    }

    private void requestStop() {
        Intent i = new Intent(this, MockRouteService.class).setAction(MockRouteService.ACTION_STOP);
        startService(i);
        RunState.append("界面请求停止并清理");
        tvState.setText("状态：停止中，等待清理");
        btnStop.setEnabled(false);
    }

    private boolean mockAppopAllowed() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean locationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException e) { return false; }
    }

    private void showMockOpDialog() {
        String msg = "本应用尚未获得 android:mock_location。\n\n"
                + "请在系统 开发者选项 → 选择模拟位置信息应用 中选择“路线模拟器”。\n\n"
                + "或由 ADB 执行：\n adb shell appops set " + getPackageName() + " android:mock_location allow\n\n"
                + "本应用不会自行修改 appop，也不会隐藏 mock 标记。";
        new AlertDialog.Builder(this).setTitle("需要标准模拟位置权限")
                .setMessage(msg).setPositiveButton("知道了", null).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (pendingStart) {
                pendingStart = false;
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    tryStart();
                } else {
                    toast("未授予精确位置权限，无法启动");
                }
            } else {
                toast("通知权限已更新");
            }
        }
    }

    private void refreshRunState() {
        boolean running = RunState.running;
        boolean paused = RunState.paused;
        boolean checkpoint = RunCheckpoint.exists(this);
        RunState.checkpointAvailable = checkpoint;
        runOnUiThread(() -> {
            String state = RunState.state == null ? "空闲" : RunState.state;
            if (RunState.error != null && RunState.error.length() > 0) {
                tvState.setText("状态：" + state + "；" + RunState.error);
            } else {
                tvState.setText("状态：" + state);
            }
            double target = RunState.target;
            String coord = RunState.hasPoint
                    ? String.format(Locale.US, "%.7f,%.7f", RunState.lat, RunState.lon)
                    : "—";
            String routeMode = route != null && route.isOpenSingle() ? "开放折返" : "闭环";
            tvLive.setText(String.format(Locale.US,
                    "距离 %.1f / %.0f m\n模拟配速 %s（程序计算，非目标应用读数）\n程序模拟步频 %.1f（仅本机显示）\n路线 %s [%s]  注入 %d 次  切换 %d 次\n坐标 %s  (WGS84 lat,lon)\n断点 %s  步频桥 %s",
                    RunState.distance, target,
                    MotionSimulator.formatPace(RunState.paceSecPerKm),
                    RunState.cadence,
                    RunState.loopName == null || RunState.loopName.isEmpty() ? "—" : RunState.loopName,
                    routeMode,
                    RunState.injections, RunState.crossings,
                    coord,
                    RunState.checkpointMessage == null ? "无" : RunState.checkpointMessage,
                    RunState.pedometerStatus));
            tvLog.setText(RunState.log);
            btnStart.setEnabled(!running);
            btnStop.setEnabled(running);
            btnPause.setEnabled(running && !paused);
            btnResume.setEnabled((running && paused) || (!running && checkpoint));
            btnImportGpx.setEnabled(!running);
            btnImportJson.setEnabled(!running);
            btnEdit.setEnabled(!running && route != null);
        });
    }

    private static int parseInt(EditText e, String label) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { throw new IllegalArgumentException(label + "必须是整数"); }
    }

    private static double parseDouble(EditText e, String label) {
        try {
            double v = Double.parseDouble(e.getText().toString().trim());
            if (!Double.isFinite(v)) throw new NumberFormatException();
            return v;
        } catch (Exception ex) { throw new IllegalArgumentException(label + "必须是有限数字"); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
