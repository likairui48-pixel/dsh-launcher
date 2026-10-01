package com.dsh.launcher;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 文件管理能力的上层封装：把「操作」翻译成 dsh-fs.sh 的 payload，
 * 再把 Termux 回传的 base64 解析成模型对象。
 */
public final class FsClient {

    public interface Cb<T> {
        void done(T value, String error);
    }

    /** 单次传输的原始字节上限：base64 后约 171KB，UTF-16 计约 342KB，留在 Binder 1MB 事务上限里足够安全 */
    public static final int CHUNK = 128 * 1024;
    /** 编辑器里允许打开的最大字节数 */
    public static final long MAX_EDIT = 1024 * 1024;

    public static final long T_OP = 25000L;
    public static final long T_READ = 40000L;
    public static final long T_BIG = 90000L;

    private static final String ASSET = "dsh-fs.sh";
    private static String cached;

    public static final class ReadResult {
        public String text = "";
        public long size;
        public boolean truncated;
        public boolean binary;
    }

    private FsClient() {
    }

    /* ===================== 资源脚本 ===================== */

    private static synchronized String script(Context c) {
        if (cached == null) {
            cached = readAsset(c, ASSET);
        }
        return cached;
    }

    private static String readAsset(Context c, String name) {
        StringBuilder sb = new StringBuilder();
        InputStream in = null;
        BufferedReader reader = null;
        try {
            in = c.getAssets().open(name);
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                } else if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }

    /* ===================== 底层调用 ===================== */

    private static final class Resp {
        boolean rc;
        String msg = "";
        String b64 = "";
        String dest = "";
        long size = -1;
        boolean bin;
        final HashMap<String, String> map = new HashMap<>();

        String get(String k) {
            String v = map.get(k);
            return v == null ? "" : v;
        }
    }

    private interface Raw {
        void done(Resp r, String error);
    }

    private static String b64(String s) {
        if (s == null) {
            s = "";
        }
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static void exec(Context c, String[] kv, long timeout, final Raw cb) {
        final String sc = script(c);
        if (sc == null) {
            cb.done(null, "内置脚本读取失败");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : kv) {
            sb.append(s).append('\n');
        }
        String payload = Base64.encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        TermuxBridge.exec(c, sc, new String[]{"dsh-fs", payload}, timeout,
                new TermuxBridge.Cb() {
                    @Override
                    public void done(TermuxBridge.Res r) {
                        if (!r.ok()) {
                            cb.done(null, r.message());
                            return;
                        }
                        Resp p = parse(r.stdout);
                        if (p == null) {
                            cb.done(null, "回传内容无法解析");
                            return;
                        }
                        if (!p.rc) {
                            cb.done(null, p.msg.length() == 0 ? "操作失败" : p.msg);
                            return;
                        }
                        cb.done(p, null);
                    }
                });
    }

    private static Resp parse(String stdout) {
        if (stdout == null) {
            return null;
        }
        Resp p = new Resp();
        for (String line : stdout.split("\n")) {
            if (line.length() == 0) {
                continue;
            }
            int i = line.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String k = line.substring(0, i);
            String v = line.substring(i + 1);
            p.map.put(k, v);
            if ("RC".equals(k)) {
                p.rc = "0".equals(v);
            } else if ("MSG".equals(k)) {
                p.msg = v;
            } else if ("B64".equals(k)) {
                p.b64 = v;
            } else if ("DEST".equals(k)) {
                p.dest = v;
            } else if ("BIN".equals(k)) {
                p.bin = "1".equals(v);
            } else if ("SIZE".equals(k)) {
                try {
                    p.size = Long.parseLong(v.trim());
                } catch (Throwable ignored) {
                }
            }
        }
        if (!p.map.containsKey("RC")) {
            return null;
        }
        return p;
    }

    private static byte[] unb64(String s) {
        if (s == null || s.length() == 0) {
            return new byte[0];
        }
        try {
            return Base64.decode(s, Base64.DEFAULT);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /* ===================== 环境探测 ===================== */

    public static void probe(Context c, Cb<HashMap<String, String>> cb) {
        exec(c, new String[]{"OP=probe"}, T_OP, new Raw() {
            @Override
            public void done(Resp r, String error) {
                if (error != null) {
                    cb.done(null, error);
                } else {
                    cb.done(r.map, null);
                }
            }
        });
    }

    /* ===================== 列目录 ===================== */

    public static void list(Context c, String dir, final Cb<ArrayList<FsEntry>> cb) {
        exec(c, new String[]{"OP=list", "P=" + dir}, T_OP, new Raw() {
            @Override
            public void done(Resp r, String error) {
                if (error != null) {
                    cb.done(null, error);
                } else {
                    cb.done(parseList(r.b64, dir), null);
                }
            }
        });
    }

    public static void search(Context c, String dir, String q, final Cb<ArrayList<FsEntry>> cb) {
        exec(c, new String[]{"OP=search", "P=" + dir, "Q=" + q}, T_BIG, new Raw() {
            @Override
            public void done(Resp r, String error) {
                if (error != null) {
                    cb.done(null, error);
                } else {
                    cb.done(parseList(r.b64, dir), null);
                }
            }
        });
    }

    private static ArrayList<FsEntry> parseList(String b64, String dir) {
        ArrayList<FsEntry> out = new ArrayList<>();
        byte[] raw = unb64(b64);
        if (raw.length == 0) {
            return out;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.length() == 0) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 5) {
                continue;
            }
            FsEntry e = new FsEntry();
            String type = f[0];
            String targetType = f.length > 1 ? f[1] : "";
            e.link = "l".equals(type);
            e.dir = "d".equals(type) || (e.link && "d".equals(targetType));
            e.size = parseLong(f[2]);
            e.mtime = parseTime(f[3]);
            e.name = f[4];
            if (f.length > 5) {
                e.linkTarget = f[5];
            }
            if (f.length > 6 && f[6].length() > 0) {
                e.path = f[6];
            } else {
                e.path = Util.join(dir, e.name);
            }
            if (e.name.length() > 0) {
                out.add(e);
            }
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long parseTime(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return (long) (d * 1000.0);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /* ===================== 读取 ===================== */

    /** 分块读完整文件（上限 maxTotal 字节），返回 UTF-8 文本。 */
    public static void readAll(final Context c, final String path, final long maxTotal,
                               final Cb<ReadResult> cb) {
        final ReadResult rr = new ReadResult();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        stepRead(c, path, 0L, maxTotal, rr, buf, cb);
    }

    private static void stepRead(final Context c, final String path, final long off,
                                 final long maxTotal, final ReadResult rr,
                                 final ByteArrayOutputStream buf, final Cb<ReadResult> cb) {
        int want = (int) Math.min((long) CHUNK, maxTotal - buf.size());
        if (want <= 0) {
            finishRead(rr, buf, cb);
            return;
        }
        exec(c, new String[]{"OP=read", "P=" + path, "OFF=" + off, "MAX=" + want}, T_READ,
                new Raw() {
                    @Override
                    public void done(Resp r, String error) {
                        if (error != null) {
                            cb.done(null, error);
                            return;
                        }
                        byte[] data = unb64(r.b64);
                        rr.size = r.size;
                        rr.binary = r.bin;
                        if (data.length > 0) {
                            buf.write(data, 0, data.length);
                        }
                        boolean more = !r.bin && data.length >= CHUNK
                                && buf.size() < maxTotal && buf.size() < r.size;
                        if (more) {
                            stepRead(c, path, off + data.length, maxTotal, rr, buf, cb);
                        } else {
                            if (buf.size() < r.size) {
                                rr.truncated = true;
                            }
                            finishRead(rr, buf, cb);
                        }
                    }
                });
    }

    private static void finishRead(ReadResult rr, ByteArrayOutputStream buf, Cb<ReadResult> cb) {
        rr.text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        cb.done(rr, null);
    }

    /* ===================== 写入 ===================== */

    public static void write(final Context c, final String path, final byte[] data,
                             final Cb<Long> cb) {
        if (data.length <= CHUNK) {
            exec(c, new String[]{"OP=write", "P=" + path, "A=0",
                    "B=" + Base64.encodeToString(data, Base64.NO_WRAP)}, T_BIG, new Raw() {
                @Override
                public void done(Resp r, String error) {
                    if (error != null) {
                        cb.done(null, error);
                    } else {
                        cb.done(r.size, null);
                    }
                }
            });
            return;
        }
        writeChunk(c, path, data, 0, true, cb);
    }

    private static void writeChunk(final Context c, final String path, final byte[] data,
                                   final int off, final boolean first, final Cb<Long> cb) {
        if (off >= data.length) {
            cb.done((long) data.length, null);
            return;
        }
        int len = Math.min(CHUNK, data.length - off);
        byte[] slice = new byte[len];
        System.arraycopy(data, off, slice, 0, len);
        exec(c, new String[]{"OP=write", "P=" + path, first ? "A=0" : "A=1",
                "B=" + Base64.encodeToString(slice, Base64.NO_WRAP)}, T_BIG, new Raw() {
            @Override
            public void done(Resp r, String error) {
                if (error != null) {
                    cb.done(null, error);
                } else {
                    writeChunk(c, path, data, off + CHUNK, false, cb);
                }
            }
        });
    }

    /* ===================== 目录 / 文件管理 ===================== */

    public static void mkdir(Context c, String path, Cb<String> cb) {
        simple(c, new String[]{"OP=mkdir", "P=" + path}, cb);
    }

    public static void touch(Context c, String path, Cb<String> cb) {
        simple(c, new String[]{"OP=touch", "P=" + path}, cb);
    }

    public static void rename(Context c, String from, String to, Cb<String> cb) {
        simple(c, new String[]{"OP=rename", "P=" + from, "P2=" + to}, cb);
    }

    public static void delete(Context c, String path, Cb<String> cb) {
        simple(c, new String[]{"OP=delete", "P=" + path}, cb);
    }

    public static void copy(Context c, String from, String to, Cb<String> cb) {
        simple(c, new String[]{"OP=copy", "P=" + from, "P2=" + to}, cb);
    }

    public static void copyout(Context c, String src, String dest, Cb<String> cb) {
        exec(c, new String[]{"OP=copyout", "P=" + src, "P2=" + dest}, T_BIG, new Raw() {
            @Override
            public void done(Resp r, String error) {
                cb.done(error != null ? null : r.dest, error);
            }
        });
    }

    public static void copyin(Context c, String src, String dest, Cb<String> cb) {
        exec(c, new String[]{"OP=copyin", "P=" + src, "P2=" + dest}, T_BIG, new Raw() {
            @Override
            public void done(Resp r, String error) {
                cb.done(error != null ? null : r.dest, error);
            }
        });
    }

    private static void simple(Context c, String[] kv, final Cb<String> cb) {
        exec(c, kv, T_OP, new Raw() {
            @Override
            public void done(Resp r, String error) {
                cb.done(error != null ? null : r.dest, error);
            }
        });
    }

    /* ===================== 导出路径计算 ===================== */

    /** 工作区路径 -> 共享存储镜像路径；工作区外的路径放到 _external 下。 */
    public static String exportDest(String path, String workspace, String sharedRoot) {
        String root = sharedRoot == null || sharedRoot.length() == 0
                ? Prefs.DEF_SHARED : sharedRoot;
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String ws = workspace == null ? "" : workspace;
        while (ws.length() > 1 && ws.endsWith("/")) {
            ws = ws.substring(0, ws.length() - 1);
        }
        if (path != null && ws.length() > 0) {
            if (path.equals(ws)) {
                return root;
            }
            if (path.startsWith(ws + "/")) {
                return root + path.substring(ws.length());
            }
        }
        String name = Util.baseName(path);
        if (name == null || name.length() == 0) {
            name = "item";
        }
        return root + "/_external/" + name.replace("/", "_");
    }
}
