package io.github.dsh.mockroute;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Pure-Java route model, parser and serializer (no Android or third-party runtime). */
public final class RouteModel {
    private RouteModel() {}

    public static final class GeoPoint {
        public final double lat;
        public final double lon;
        public GeoPoint(double lat, double lon) {
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalArgumentException("坐标无效：纬度须在 -90..90，经度须在 -180..180（WGS84）");
            }
            this.lat = lat;
            this.lon = lon;
        }
        @Override public String toString() {
            return String.format(Locale.US, "%.7f,%.7f", lat, lon);
        }
    }

    public static final class RouteLoop {
        public final String name;
        public final boolean closed;
        public final List<GeoPoint> points; // raw editor points; closed: points[0] is shared junction
        private final GeoPoint[] path;      // rounded-corner runtime polyline
        private final double[] cum;
        public final double length;

        RouteLoop(String name, List<GeoPoint> pts) {
            this(name, pts, true);
        }

        RouteLoop(String name, List<GeoPoint> pts, boolean closed) {
            if (pts == null || pts.size() < (closed ? 3 : 2)) {
                throw new IllegalArgumentException(closed ? "闭环至少需要 3 个不同顶点" : "开放折线至少需要 2 个不同顶点");
            }
            this.name = name == null || name.trim().length() == 0 ? "路线" : name.trim();
            this.closed = closed;
            this.points = Collections.unmodifiableList(new ArrayList<>(pts));
            List<GeoPoint> smooth = new ArrayList<>();
            smooth.add(this.points.get(0));
            int n = this.points.size();
            for (int i = 1; i < n; i++) {
                GeoPoint cur = this.points.get(i);
                GeoPoint prev = this.points.get(i - 1);
                GeoPoint next = i + 1 < n ? this.points.get(i + 1)
                        : (closed ? this.points.get(0) : null);
                if (next == null) { smooth.add(cur); continue; }
                double d1 = GeoMath.distance(cur, prev);
                double d2 = GeoMath.distance(cur, next);
                if (d1 < 0.05 || d2 < 0.05) { smooth.add(cur); continue; }
                double r = Math.min(2.0, Math.min(d1, d2) * 0.30);
                GeoPoint a = GeoMath.interpolate(cur, prev, r / d1);
                GeoPoint b = GeoMath.interpolate(cur, next, r / d2);
                smooth.add(a);
                for (int k = 1; k <= 3; k++) {
                    double t = k / 4.0;
                    GeoPoint p01 = GeoMath.interpolate(a, cur, t);
                    GeoPoint p12 = GeoMath.interpolate(cur, b, t);
                    smooth.add(GeoMath.interpolate(p01, p12, t));
                }
                smooth.add(b);
            }
            path = smooth.toArray(new GeoPoint[0]);
            cum = new double[path.length + 1];
            double d = 0;
            for (int i = 0; i < path.length; i++) {
                cum[i] = d;
                if (closed) d += GeoMath.distance(path[i], path[(i + 1) % path.length]);
                else if (i + 1 < path.length) d += GeoMath.distance(path[i], path[i + 1]);
            }
            cum[path.length] = d;
            length = d;
        }

        public GeoPoint pointAt(double s) {
            if (length <= 0) return path[0];
            s = Math.max(0, Math.min(length, s));
            if (s >= length) return closed ? path[0] : path[path.length - 1];
            int lo = 0, hi = path.length;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] <= s) lo = mid; else hi = mid;
            }
            GeoPoint a = path[lo];
            GeoPoint b = closed ? path[(lo + 1) % path.length] : path[Math.min(path.length - 1, lo + 1)];
            double seg = cum[lo + 1] - cum[lo];
            double f = seg <= 1e-9 ? 0 : (s - cum[lo]) / seg;
            return GeoMath.interpolate(a, b, f);
        }

        /** Local unit east/north direction at arc distance s. */
        public double[] directionAt(double s) {
            if (length <= 0) return new double[] {1, 0};
            double a = Math.max(0, Math.min(length, s - 0.5));
            double b = Math.max(0, Math.min(length, s + 0.5));
            GeoPoint pa = pointAt(a);
            GeoPoint pb = pointAt(b);
            double east = (pb.lon - pa.lon) * Math.cos(Math.toRadians((pa.lat + pb.lat) / 2)) * 111320.0;
            double north = (pb.lat - pa.lat) * 110540.0;
            double n = Math.hypot(east, north);
            return n < 1e-9 ? new double[] {1, 0} : new double[] {east / n, north / n};
        }

        /** Left normal from a short-window average, so corners do not create a positional jump. */
        public double[] normalAt(double s) {
            double se = 0, sn = 0;
            for (int k = -3; k <= 3; k++) {
                double ss = s + k * 3.0;
                ss = Math.max(0, Math.min(length, ss));
                double[] d = directionAt(ss);
                se += d[0]; sn += d[1];
            }
            double n = Math.hypot(se, sn);
            if (n < 1e-9) {
                double[] d = directionAt(s);
                return new double[] {-d[1], d[0]};
            }
            se /= n; sn /= n;
            return new double[] {-sn, se};
        }
    }

    public static final class RouteSet {
        public final GeoPoint junction;
        public final List<RouteLoop> loops;
        public final String source;

        public RouteSet(GeoPoint junction, List<RouteLoop> loops, String source) {
            if (loops == null || loops.isEmpty()) throw new IllegalArgumentException("至少需要一条路线");
            this.loops = Collections.unmodifiableList(new ArrayList<>(loops));
            this.source = source == null ? "" : source;
            boolean anyOpen = false;
            for (RouteLoop l : this.loops) if (!l.closed) anyOpen = true;
            if (anyOpen && this.loops.size() > 1) {
                throw new IllegalArgumentException("多路线目前仅支持共享路口的闭环；开放折线请使用单条路线");
            }
            if (anyOpen) {
                this.junction = this.loops.get(0).points.get(0);
            } else {
                if (junction == null) throw new IllegalArgumentException("闭环缺少共享路口");
                this.junction = junction;
                for (RouteLoop l : this.loops) {
                    if (GeoMath.distance(l.points.get(0), junction) > 0.01) {
                        throw new IllegalArgumentException("内部错误：路线起点未对齐共享路口");
                    }
                }
            }
        }

        public boolean isOpenSingle() { return loops.size() == 1 && !loops.get(0).closed; }
        public boolean hasJunction() { return !isOpenSingle(); }

        public RouteLoop nextLoop(java.util.Random random, int current) {
            if (loops.size() == 1) return loops.get(0);
            int idx;
            if (current < 0) idx = random.nextInt(loops.size());
            else {
                idx = random.nextInt(loops.size() - 1);
                if (idx >= current) idx++;
            }
            return loops.get(idx);
        }

        public int indexOf(RouteLoop loop) { return loops.indexOf(loop); }
        public double totalRouteLength() {
            double d = 0; for (RouteLoop l : loops) d += l.length; return d;
        }
    }

    public static final class GeoMath {
        private static final double R = 6371008.8;
        public static double distance(GeoPoint a, GeoPoint b) {
            double p1 = Math.toRadians(a.lat), p2 = Math.toRadians(b.lat);
            double dp = p2 - p1, dl = Math.toRadians(b.lon - a.lon);
            double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                    + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
            return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
        }
        public static GeoPoint interpolate(GeoPoint a, GeoPoint b, double f) {
            double lonB = b.lon;
            if (lonB - a.lon > 180) lonB -= 360;
            else if (a.lon - lonB > 180) lonB += 360;
            double lon = a.lon + (lonB - a.lon) * f;
            if (lon > 180) lon -= 360;
            if (lon < -180) lon += 360;
            return new GeoPoint(a.lat + (b.lat - a.lat) * f, lon);
        }
        public static double bearing(GeoPoint a, GeoPoint b) {
            double lat = Math.toRadians((a.lat + b.lat) / 2.0);
            double east = (b.lon - a.lon) * Math.cos(lat);
            double north = b.lat - a.lat;
            double deg = Math.toDegrees(Math.atan2(east, north));
            return (deg + 360.0) % 360.0;
        }

        public static GeoPoint offset(GeoPoint p, double east, double north) {
            double lat = p.lat + Math.toDegrees(north / R);
            double c = Math.max(1e-6, Math.cos(Math.toRadians(p.lat)));
            double lon = p.lon + Math.toDegrees(east / (R * c));
            if (lon > 180) lon -= 360;
            if (lon < -180) lon += 360;
            return new GeoPoint(Math.max(-90, Math.min(90, lat)), lon);
        }
    }

    private static final double CLOSURE_TOLERANCE_M = 20.0;

    private static final class RawLoop {
        final String name;
        final List<GeoPoint> pts;
        final Boolean closed;
        RawLoop(String name, List<GeoPoint> pts) { this(name, pts, null); }
        RawLoop(String name, List<GeoPoint> pts, Boolean closed) {
            this.name = name;
            this.pts = pts;
            this.closed = closed;
        }
    }

    public static RouteSet parseGpx(String xml, String source) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
            try { f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (Exception ignored) {}
            try { f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); } catch (Exception ignored) {}
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            List<RawLoop> raw = new ArrayList<>();
            NodeList segs = doc.getElementsByTagNameNS("*", "trkseg");
            for (int i = 0; i < segs.getLength(); i++) {
                Element seg = (Element) segs.item(i);
                List<GeoPoint> pts = new ArrayList<>();
                NodeList ch = seg.getChildNodes();
                for (int j = 0; j < ch.getLength(); j++) {
                    Node n = ch.item(j);
                    if (n instanceof Element && "trkpt".equals(local(n))) pts.add(gpxPoint((Element) n));
                }
                String name = childText((Element) seg.getParentNode(), "name");
                raw.add(new RawLoop(name == null ? "路线" + (raw.size() + 1) : name, pts));
            }
            NodeList rtes = doc.getElementsByTagNameNS("*", "rte");
            for (int i = 0; i < rtes.getLength(); i++) {
                Element rte = (Element) rtes.item(i);
                List<GeoPoint> pts = new ArrayList<>();
                NodeList ch = rte.getChildNodes();
                for (int j = 0; j < ch.getLength(); j++) {
                    Node n = ch.item(j);
                    if (n instanceof Element && "rtept".equals(local(n))) pts.add(gpxPoint((Element) n));
                }
                String name = childText(rte, "name");
                raw.add(new RawLoop(name == null ? "路线" + (raw.size() + 1) : name, pts));
            }
            if (raw.isEmpty()) {
                NodeList trks = doc.getElementsByTagNameNS("*", "trk");
                for (int i = 0; i < trks.getLength(); i++) {
                    Element trk = (Element) trks.item(i);
                    List<GeoPoint> pts = new ArrayList<>();
                    NodeList ch = trk.getChildNodes();
                    for (int j = 0; j < ch.getLength(); j++) {
                        Node n = ch.item(j);
                        if (n instanceof Element && "trkpt".equals(local(n))) pts.add(gpxPoint((Element) n));
                    }
                    if (!pts.isEmpty()) raw.add(new RawLoop(childText(trk, "name"), pts));
                }
            }
            if (raw.isEmpty()) throw new IllegalArgumentException("GPX 中没有 trkseg/rte 轨迹点");
            return build(raw, source);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("GPX 解析失败：" + e.getMessage(), e);
        }
    }

    public static RouteSet parseJson(String json, String source) {
        try {
            Object root = MiniJson.parse(json);
            List<RawLoop> raw = new ArrayList<>();
            if (root instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) root;
                if (m.containsKey("routes")) {
                    Object arr = m.get("routes");
                    if (!(arr instanceof List)) throw new IllegalArgumentException("JSON 的 routes 必须是数组");
                    int idx = 0;
                    for (Object o : (List<?>) arr) raw.add(jsonRoute(o, "路线" + (++idx)));
                } else if (m.containsKey("points")) {
                    raw.add(new RawLoop(string(m.get("name"), "路线1"), points(m.get("points"))));
                } else {
                    throw new IllegalArgumentException("JSON 需包含 routes 或 points");
                }
            } else if (root instanceof List) {
                List<?> a = (List<?>) root;
                if (a.isEmpty()) throw new IllegalArgumentException("JSON 点数组为空");
                if (looksLikePoint(a.get(0))) {
                    raw.add(new RawLoop("路线1", points(a)));
                } else {
                    int idx = 0;
                    for (Object o : a) raw.add(jsonRoute(o, "路线" + (++idx)));
                }
            } else {
                throw new IllegalArgumentException("JSON 根节点必须是对象或数组");
            }
            return build(raw, source);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * Content-based import. File name/MIME are only labels; a .gpx file can be
     * served as application/octet-stream, and a mislabeled file must not decide
     * the parser. XML-looking content is GPX, {/[ content is JSON.
     */
    public static RouteSet parseAuto(String text, String source) {
        String label = source == null || source.trim().length() == 0 ? "所选文件" : source;
        if (text == null) throw new IllegalArgumentException("文件为空：" + label);
        String t = text;
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        t = t.trim();
        if (t.length() == 0) throw new IllegalArgumentException("文件为空：" + label);
        if (t.startsWith("<")) return parseGpx(t, label);
        if (t.startsWith("{") || t.startsWith("[")) return parseJson(t, label);
        RuntimeException gpxError = null;
        RuntimeException jsonError = null;
        try {
            return parseGpx(t, label);
        } catch (RuntimeException e) {
            gpxError = e;
        }
        try {
            return parseJson(t, label);
        } catch (RuntimeException e) {
            jsonError = e;
        }
        throw new IllegalArgumentException("无法按内容识别 GPX 或 JSON（" + label + "）。"
                + "GPX: " + gpxError.getMessage() + "；JSON: " + jsonError.getMessage());
    }

    private static RawLoop jsonRoute(Object o, String defaultName) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            Boolean closed = m.get("closed") instanceof Boolean ? (Boolean) m.get("closed") : null;
            return new RawLoop(string(m.get("name"), defaultName), points(m.get("points")), closed);
        }
        if (o instanceof List) return new RawLoop(defaultName, points(o));
        throw new IllegalArgumentException("JSON 路线必须是对象或点数组");
    }

    private static List<GeoPoint> points(Object o) {
        if (!(o instanceof List)) throw new IllegalArgumentException("points 必须是数组");
        List<?> a = (List<?>) o;
        if (a.size() > 200000) throw new IllegalArgumentException("点数过多（上限 200000）");
        List<GeoPoint> out = new ArrayList<>(a.size());
        int idx = 0;
        for (Object p : a) {
            idx++;
            if (p instanceof List) {
                List<?> pair = (List<?>) p;
                if (pair.size() < 2) throw new IllegalArgumentException("第 " + idx + " 个点应为 [lat,lon]");
                out.add(new GeoPoint(num(pair.get(0), idx), num(pair.get(1), idx)));
            } else if (p instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) p;
                out.add(new GeoPoint(num(m.get("lat"), idx), num(m.get("lon"), idx)));
            } else {
                throw new IllegalArgumentException("第 " + idx + " 个点格式错误；使用 [lat,lon]（WGS84）");
            }
        }
        return out;
    }

    private static boolean looksLikePoint(Object o) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            return m.containsKey("lat") && m.containsKey("lon");
        }
        if (o instanceof List) {
            List<?> a = (List<?>) o;
            return a.size() >= 2 && a.get(0) instanceof Number && a.get(1) instanceof Number;
        }
        return false;
    }

    private static double num(Object o, int idx) {
        if (!(o instanceof Number)) throw new IllegalArgumentException("第 " + idx + " 个点的经纬度必须是数字");
        double v = ((Number) o).doubleValue();
        if (!Double.isFinite(v)) throw new IllegalArgumentException("第 " + idx + " 个点的经纬度不是有限数");
        return v;
    }

    private static String string(Object o, String def) {
        return o instanceof String && ((String) o).trim().length() > 0 ? ((String) o).trim() : def;
    }

    private static RouteSet build(List<RawLoop> raw, String source) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("没有可用路线");
        if (raw.size() > 100) throw new IllegalArgumentException("路线数量过多（上限 100）");
        GeoPoint junction = null;
        List<RouteLoop> loops = new ArrayList<>();
        for (int r = 0; r < raw.size(); r++) {
            RawLoop rr = raw.get(r);
            if (rr.pts == null || rr.pts.size() < 2) {
                throw new IllegalArgumentException("路线“" + rr.name + "”点数不足");
            }
            List<GeoPoint> pts = dedupe(rr.pts);
            if (pts.size() < 2) throw new IllegalArgumentException("路线“" + rr.name + "”去重后点数不足");
            GeoPoint first = pts.get(0);
            double endGap = GeoMath.distance(pts.get(pts.size() - 1), first);
            boolean closed;
            if (rr.closed != null) {
                closed = rr.closed;
            } else {
                closed = !(raw.size() == 1 && endGap > CLOSURE_TOLERANCE_M);
            }
            if (!closed) {
                if (raw.size() > 1) {
                    throw new IllegalArgumentException("多条路线必须闭环并共享同一路口；开放折线请只保留一条路线");
                }
                if (pts.size() < 2) throw new IllegalArgumentException("开放折线至少需要 2 个不同顶点");
                RouteLoop open = new RouteLoop(rr.name, pts, false);
                if (open.length < 10) {
                    throw new IllegalArgumentException("路线“" + rr.name + "”长度不足 10 m");
                }
                if (junction == null) junction = first;
                loops.add(open);
                continue;
            }
            if (pts.size() < 3) {
                throw new IllegalArgumentException("路线“" + rr.name + "”点数不足；闭环至少需要 3 个不同顶点");
            }
            if (junction == null) junction = first;
            double startGap = GeoMath.distance(first, junction);
            if (startGap > CLOSURE_TOLERANCE_M) {
                throw new IllegalArgumentException("路线“" + rr.name + "”起点距共享路口 " +
                        String.format(Locale.US, "%.1f", startGap) + " m，超过 20 m；多条路线必须在同一路口闭合");
            }
            if (endGap > CLOSURE_TOLERANCE_M) {
                throw new IllegalArgumentException("路线“" + rr.name + "”未闭合（首尾差 " +
                        String.format(Locale.US, "%.1f", endGap) + " m）；请用同一路口作为首尾点，或显式设置 closed=false");
            }
            // Drop the closing point (closure is implicit) and any near-junction tail.
            if (pts.size() > 1) pts.remove(pts.size() - 1);
            while (pts.size() > 1 && GeoMath.distance(pts.get(pts.size() - 1), junction) < 1.0) {
                pts.remove(pts.size() - 1);
            }
            if (pts.size() < 3) {
                throw new IllegalArgumentException("路线“" + rr.name + "”闭合后不足 3 个不同顶点");
            }
            pts.set(0, junction);
            RouteLoop loop = new RouteLoop(rr.name, pts, true);
            if (loop.length < 10) {
                throw new IllegalArgumentException("路线“" + rr.name + "”长度不足 10 m");
            }
            loops.add(loop);
        }
        return new RouteSet(junction, loops, source);
    }

    private static List<GeoPoint> dedupe(List<GeoPoint> in) {
        List<GeoPoint> out = new ArrayList<>();
        for (GeoPoint p : in) {
            if (out.isEmpty() || GeoMath.distance(out.get(out.size() - 1), p) >= 0.01) out.add(p);
        }
        return out;
    }

    private static GeoPoint gpxPoint(Element e) {
        String la = e.getAttribute("lat"), lo = e.getAttribute("lon");
        if (la == null || lo == null || la.length() == 0 || lo.length() == 0) {
            throw new IllegalArgumentException("GPX trkpt/rtept 缺少 lat 或 lon");
        }
        try {
            return new GeoPoint(Double.parseDouble(la), Double.parseDouble(lo));
        } catch (NumberFormatException x) {
            throw new IllegalArgumentException("GPX 坐标不是有效数字：" + la + "," + lo);
        }
    }

    private static String local(Node n) {
        String s = n.getLocalName();
        return s == null ? n.getNodeName() : s;
    }

    private static String childText(Element parent, String name) {
        if (parent == null) return null;
        NodeList ch = parent.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n instanceof Element && name.equals(local(n))) {
                String t = n.getTextContent();
                if (t != null && t.trim().length() > 0) return t.trim();
            }
        }
        return null;
    }

    public static String toJson(RouteSet rs) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"format\": \"mock-route/v1\",\n  \"wgs84\": true,\n");
        if (rs.hasJunction()) {
            b.append("  \"junction\": [")
                    .append(fmt(rs.junction.lat)).append(", ").append(fmt(rs.junction.lon)).append("],\n");
        }
        b.append("  \"routes\": [\n");
        for (int i = 0; i < rs.loops.size(); i++) {
            RouteLoop l = rs.loops.get(i);
            b.append("    {\"name\": \"").append(esc(l.name)).append("\", \"closed\": ")
                    .append(l.closed).append(", \"points\": [");
            for (int j = 0; j < l.points.size(); j++) {
                if (j > 0) b.append(", ");
                GeoPoint p = l.points.get(j);
                b.append('[').append(fmt(p.lat)).append(", ").append(fmt(p.lon)).append(']');
            }
            if (l.closed) {
                b.append(", [").append(fmt(rs.junction.lat)).append(", ").append(fmt(rs.junction.lon)).append("]");
            }
            b.append("]}");
            if (i + 1 < rs.loops.size()) b.append(',');
            b.append('\n');
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    private static String fmt(double d) { return String.format(Locale.US, "%.7f", d); }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
