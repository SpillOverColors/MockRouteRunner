package io.github.dsh.mockroute;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Text-only WGS84 waypoint editor. No map dependency. */
public final class RouteEditorActivity extends Activity {
    private static final class EditLoop {
        String name;
        ArrayList<RouteModel.GeoPoint> points;
        EditLoop(String n, List<RouteModel.GeoPoint> p) {
            name = n;
            points = new ArrayList<>(p);
        }
    }

    private final List<EditLoop> loops = new ArrayList<>();
    private RouteModel.GeoPoint junction;
    private int selectedLoop;
    private int selectedPoint = -1;
    private EditText etJLat, etJLon;
    private TextView tvStatus, tvPointHint;
    private Spinner spinner;
    private LinearLayout container;
    private CheckBox cbOpenRoute;
    private Button btnApplyJunction, btnAddPoint, btnEditPoint, btnDeletePoint, btnMoveUp, btnMoveDown;
    private boolean openRoute;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_editor);
        etJLat = findViewById(R.id.etJunctionLat);
        etJLon = findViewById(R.id.etJunctionLon);
        spinner = findViewById(R.id.spinnerLoop);
        container = findViewById(R.id.pointsContainer);
        tvStatus = findViewById(R.id.tvEditStatus);
        tvPointHint = findViewById(R.id.tvPointHint);
        cbOpenRoute = findViewById(R.id.cbOpenRoute);
        btnApplyJunction = findViewById(R.id.btnApplyJunction);
        btnAddPoint = findViewById(R.id.btnAddPoint);
        btnEditPoint = findViewById(R.id.btnEditPoint);
        btnDeletePoint = findViewById(R.id.btnDeletePoint);
        btnMoveUp = findViewById(R.id.btnMoveUp);
        btnMoveDown = findViewById(R.id.btnMoveDown);
        try {
            RouteModel.RouteSet rs = RouteStore.load(this);
            if (rs == null) { toast("没有已导入的路线"); finish(); return; }
            junction = rs.junction;
            for (RouteModel.RouteLoop l : rs.loops) loops.add(new EditLoop(l.name, l.points));
            openRoute = rs.isOpenSingle();
            etJLat.setText(fmt(junction.lat));
            etJLon.setText(fmt(junction.lon));
        } catch (Exception ex) {
            toast("路线读取失败：" + ex.getMessage());
            finish();
            return;
        }
        cbOpenRoute.setChecked(openRoute);
        cbOpenRoute.setEnabled(loops.size() <= 1);
        cbOpenRoute.setOnCheckedChangeListener((button, checked) -> {
            openRoute = checked;
            selectedPoint = -1;
            applyMode();
            rebuildPoints();
        });
        List<String> names = new ArrayList<>();
        for (EditLoop l : loops) names.add(l.name);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLoop = position;
                selectedPoint = -1;
                rebuildPoints();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        findViewById(R.id.btnApplyJunction).setOnClickListener(v -> applyJunction());
        btnAddPoint.setOnClickListener(v -> addPoint());
        btnEditPoint.setOnClickListener(v -> editSelected());
        btnDeletePoint.setOnClickListener(v -> deleteSelected());
        btnMoveUp.setOnClickListener(v -> moveSelected(-1));
        btnMoveDown.setOnClickListener(v -> moveSelected(1));
        findViewById(R.id.btnSaveRoute).setOnClickListener(v -> save());
        findViewById(R.id.btnCancelEdit).setOnClickListener(v -> finish());
        applyMode();
        rebuildPoints();
    }

    private void applyMode() {
        etJLat.setEnabled(!openRoute);
        etJLon.setEnabled(!openRoute);
        btnApplyJunction.setEnabled(!openRoute);
        tvPointHint.setText(openRoute
                ? "开放折线：首尾都是端点，点击任一点可编辑/删除/上移/下移；运行时到端点原路折返。"
                : "闭环：共享路口固定在列表外，点击中间途经点可编辑/删除/上移/下移。");
    }

    private int firstEditablePoint() { return openRoute ? 0 : 1; }
    private int minimumPoints() { return openRoute ? 2 : 3; }

    private void applyJunction() {
        if (openRoute) { status("开放折线不使用共享路口。"); return; }
        try {
            RouteModel.GeoPoint p = new RouteModel.GeoPoint(parse(etJLat, "lat"), parse(etJLon, "lon"));
            junction = p;
            for (EditLoop l : loops) l.points.set(0, p);
            selectedPoint = -1;
            rebuildPoints();
            status("共享路口已更新；所有闭环的首尾点同步。");
        } catch (Exception ex) {
            status("路口无效：" + ex.getMessage());
        }
    }

    private void rebuildPoints() {
        container.removeAllViews();
        if (loops.isEmpty()) return;
        EditLoop loop = loops.get(Math.max(0, Math.min(selectedLoop, loops.size() - 1)));
        int start = firstEditablePoint();
        if (loop.points.size() <= start) {
            TextView v = new TextView(this);
            v.setText(openRoute ? "没有可编辑点。" : "没有中间途经点。请新增点。");
            container.addView(v);
            return;
        }
        for (int i = start; i < loop.points.size(); i++) {
            final int idx = i;
            RouteModel.GeoPoint p = loop.points.get(i);
            TextView row = new TextView(this);
            row.setText(String.format(Locale.US, "点 %d: %.7f, %.7f", i, p.lat, p.lon));
            row.setPadding(8, 10, 8, 10);
            row.setTextSize(13);
            row.setBackgroundColor(idx == selectedPoint ? 0xFFC8E6C9 : 0x00000000);
            row.setOnClickListener(v -> { selectedPoint = idx; rebuildPoints(); });
            container.addView(row);
        }
    }

    private void addPoint() {
        EditLoop loop = currentLoop();
        if (loop == null) return;
        int first = firstEditablePoint();
        RouteModel.GeoPoint base = selectedPoint >= first && selectedPoint < loop.points.size()
                ? loop.points.get(selectedPoint) : loop.points.get(loop.points.size() - 1);
        askPoint("新增 WGS84 途经点", base, (p) -> {
            int at = selectedPoint >= first ? selectedPoint + 1 : loop.points.size();
            loop.points.add(at, p);
            selectedPoint = at;
            rebuildPoints();
        });
    }

    private void editSelected() {
        EditLoop loop = currentLoop();
        if (loop == null) return;
        if (selectedPoint < firstEditablePoint() || selectedPoint >= loop.points.size()) {
            toast("先点击选择一个可编辑点"); return;
        }
        askPoint("编辑 WGS84 途经点", loop.points.get(selectedPoint), (p) -> {
            loop.points.set(selectedPoint, p);
            rebuildPoints();
        });
    }

    private void deleteSelected() {
        EditLoop loop = currentLoop();
        if (loop == null) return;
        if (selectedPoint < firstEditablePoint() || selectedPoint >= loop.points.size()) {
            toast("先点击选择一个可编辑点"); return;
        }
        if (loop.points.size() <= minimumPoints()) {
            toast(openRoute ? "开放折线至少保留 2 个端点" : "闭环至少保留 3 个不同顶点");
            return;
        }
        loop.points.remove(selectedPoint);
        selectedPoint = -1;
        rebuildPoints();
    }

    private void moveSelected(int delta) {
        EditLoop loop = currentLoop();
        if (loop == null) return;
        int first = firstEditablePoint();
        if (selectedPoint < first || selectedPoint >= loop.points.size()) {
            toast("先点击选择一个可编辑点"); return;
        }
        int target = selectedPoint + delta;
        if (target < first || target >= loop.points.size()) {
            toast(delta < 0 ? "已经是最前位置" : "已经是最后位置");
            return;
        }
        Collections.swap(loop.points, selectedPoint, target);
        selectedPoint = target;
        rebuildPoints();
    }

    private EditLoop currentLoop() {
        if (loops.isEmpty()) return null;
        return loops.get(Math.max(0, Math.min(selectedLoop, loops.size() - 1)));
    }

    private interface PointConsumer { void accept(RouteModel.GeoPoint p); }

    private void askPoint(String title, RouteModel.GeoPoint initial, PointConsumer consumer) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20, 8, 20, 0);
        TextView note = new TextView(this);
        note.setText("WGS84；顺序 lat,lon（纬度,经度）");
        box.addView(note);
        EditText lat = new EditText(this);
        lat.setHint("lat");
        lat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lat.setText(fmt(initial.lat));
        EditText lon = new EditText(this);
        lon.setHint("lon");
        lon.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lon.setText(fmt(initial.lon));
        box.addView(lat);
        box.addView(lon);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        consumer.accept(new RouteModel.GeoPoint(parse(lat, "lat"), parse(lon, "lon")));
                    } catch (Exception ex) {
                        toast("坐标无效：" + ex.getMessage());
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void save() {
        try {
            if (openRoute) {
                if (loops.size() != 1) throw new IllegalArgumentException("开放折线请只保留一条路线");
                EditLoop l = loops.get(0);
                if (l.points.size() < 2) throw new IllegalArgumentException("开放折线至少需要 2 个端点");
                RouteModel.RouteLoop rl = new RouteModel.RouteLoop(l.name, l.points, false);
                if (rl.length < 10) throw new IllegalArgumentException("开放折线长度不足 10 m");
                RouteModel.RouteSet rs = new RouteModel.RouteSet(
                        l.points.get(0), Collections.singletonList(rl), "edited-open");
                RouteStore.save(this, rs);
                toast("开放折线已保存：端点原路折返");
                finish();
                return;
            }
            junction = new RouteModel.GeoPoint(parse(etJLat, "lat"), parse(etJLon, "lon"));
            List<RouteModel.RouteLoop> out = new ArrayList<>();
            for (EditLoop l : loops) {
                if (l.points.size() < 3) throw new IllegalArgumentException("闭环“" + l.name + "”不足 3 个不同顶点");
                l.points.set(0, junction);
                RouteModel.RouteLoop rl = new RouteModel.RouteLoop(l.name, l.points, true);
                if (rl.length < 10) throw new IllegalArgumentException("闭环“" + l.name + "”长度不足 10 m");
                out.add(rl);
            }
            RouteModel.RouteSet rs = new RouteModel.RouteSet(junction, out, "edited");
            RouteStore.save(this, rs);
            toast("路线已保存：" + out.size() + " 条闭环");
            finish();
        } catch (Exception ex) {
            status("保存失败：" + ex.getMessage());
            new AlertDialog.Builder(this).setTitle("无法保存路线")
                    .setMessage(ex.getMessage()).setPositiveButton("知道了", null).show();
        }
    }

    private static double parse(EditText e, String label) {
        try {
            double v = Double.parseDouble(e.getText().toString().trim());
            if (!Double.isFinite(v)) throw new NumberFormatException();
            return v;
        } catch (Exception ex) {
            throw new IllegalArgumentException(label + " 必须是有限数字");
        }
    }

    private static String fmt(double d) { return String.format(Locale.US, "%.7f", d); }
    private void status(String s) { tvStatus.setText(s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
