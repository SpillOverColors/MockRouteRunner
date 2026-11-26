package io.github.dsh.mockroute;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** App-private canonical route file. The service reads this file, so it does not depend on the activity. */
final class RouteStore {
    private RouteStore() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "current_route.json");
    }

    static RouteModel.RouteSet load(Context c) throws IOException {
        File f = file(c);
        if (!f.isFile()) return null;
        String text = readFile(f);
        return RouteModel.parseJson(text, f.getName());
    }

    static void save(Context c, RouteModel.RouteSet rs) throws IOException {
        File f = file(c);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(RouteModel.toJson(rs).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (f.exists() && !f.delete()) throw new IOException("无法替换旧路线文件");
        if (!tmp.renameTo(f)) throw new IOException("无法保存路线文件");
    }

    static RouteModel.RouteSet importUri(Context c, Uri uri) throws IOException {
        String name = uri.getLastPathSegment();
        String text = readUri(c, uri);
        RouteModel.RouteSet rs = RouteModel.parseAuto(text, name);
        save(c, rs);
        return rs;
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }

    private static String readUri(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开所选文件");
        try { return readAll(in); } finally { in.close(); }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 20 * 1024 * 1024) throw new IOException("文件过大（上限 20 MB）");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
