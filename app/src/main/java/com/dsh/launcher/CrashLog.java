package com.dsh.launcher;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃与执行轨迹记录。
 *
 * 为什么需要它：这台设备上 Termux 读不到别的 App 的 logcat（UID 隔离），
 * 也没法从后台把 App 拉起来。所以 App 必须自己把崩溃写到 **Termux 读得到的地方**：
 *
 *   1. MediaStore Downloads  -> /sdcard/Download/dsh-crash.log      （主通道）
 *   2. getExternalMediaDirs  -> /sdcard/Android/media/<pkg>/...     （备通道）
 *   3. getExternalFilesDir   -> /sdcard/Android/data/<pkg>/files/...（兜底）
 *   4. RUN_COMMAND           -> Termux 的 $HOME/dsh-crash.log        （拿到权限后可用）
 *
 * 除了崩溃栈，还会记录一串「执行轨迹」（breadcrumb），
 * 这样即使 App 是被系统杀掉或卡死，也能看出它走到哪一步。
 */
public final class CrashLog {

    public static final String NAME = "dsh-crash.log";
    private static final int MAX_TRAIL = 4000;

    private static final Object LOCK = new Object();
    private static final StringBuilder TRAIL = new StringBuilder();
    private static Context ctx;
    private static boolean ready;

    private CrashLog() {
    }

    public static void install(Application app) {
        ctx = app.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable e) {
                    try {
                        writeAll(report(thread, e));
                    } catch (Throwable ignored) {
                    }
                    if (prev != null) {
                        prev.uncaughtException(thread, e);
                    }
                }
            });
            ready = true;
        } catch (Throwable ignored) {
        }
        breadcrumb("app:onCreate 开始");
    }

    public static boolean ready() {
        return ready;
    }

    /** 记录一步执行轨迹；同时刷到文件，App 被强杀也能留下痕迹。 */
    public static void breadcrumb(String msg) {
        if (!ready || msg == null) {
            return;
        }
        String line = "[" + time() + "] " + msg + "\n";
        synchronized (LOCK) {
            TRAIL.append(line);
            if (TRAIL.length() > MAX_TRAIL) {
                TRAIL.delete(0, TRAIL.length() - MAX_TRAIL);
            }
        }
        // 只走最便宜的文件通道，避免影响启动速度
        try {
            File f = trailFile();
            if (f != null) {
                FileOutputStream out = new FileOutputStream(f, true);
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String trail() {
        synchronized (LOCK) {
            return TRAIL.toString();
        }
    }

    public static String time() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /* ===================== 写通道 ===================== */

    private static void writeAll(String text) {
        if (ctx == null) {
            return;
        }
        // 1) MediaStore：落到 /sdcard/Download，Termux 一定读得到
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = findDownload(cr);
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            }
            if (uri != null) {
                OutputStream os = cr.openOutputStream(uri, "wt");
                if (os != null) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) Android/media/<pkg>
        try {
            File[] dirs = ctx.getExternalMediaDirs();
            if (dirs != null) {
                for (File d : dirs) {
                    if (d != null) {
                        writeFile(new File(d, NAME), text);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 3) 应用私有外部目录
        try {
            File d = ctx.getExternalFilesDir(null);
            if (d != null) {
                writeFile(new File(d, NAME), text);
            }
        } catch (Throwable ignored) {
        }
        // 4) 借 Termux 落一份到 $HOME（需要 RUN_COMMAND 权限）
        try {
            String b64 = Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            TermuxBridge.exec(ctx,
                    "printf '%s' '" + b64 + "' | base64 -d >> \"$HOME/" + NAME + "\"",
                    null, 8000, new TermuxBridge.Cb() {
                        @Override
                        public void done(TermuxBridge.Res r) {
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static Uri findDownload(ContentResolver cr) {
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{NAME, Environment.DIRECTORY_DOWNLOADS + "/"}, null);
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static File trailFile() {
        try {
            File[] dirs = ctx.getExternalMediaDirs();
            if (dirs != null && dirs.length > 0 && dirs[0] != null) {
                File d = dirs[0];
                if (!d.exists()) {
                    d.mkdirs();
                }
                return new File(d, "dsh-trail.log");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void writeFile(File f, String text) {
        FileOutputStream out = null;
        try {
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            out = new FileOutputStream(f, false);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ===================== 报告内容 ===================== */

    private static String report(Thread thread, Throwable e) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("================ DSH 启动器 崩溃报告 ================\n");
        sb.append("时间   : ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("线程   : ").append(thread == null ? "?" : thread.getName()).append('\n');
        sb.append("机型   : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("应用   : ").append(version()).append('\n');
        sb.append("\n---------------- 执行轨迹 ----------------\n");
        sb.append(trail());
        sb.append("\n---------------- 异常堆栈 ----------------\n");
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        } catch (Throwable t) {
            sb.append("(堆栈打印失败: ").append(t).append(")\n");
        }
        sb.append("=====================================================\n");
        return sb.toString();
    }

    public static String version() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName
                    + " (" + ctx.getPackageName() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 给设置页展示用：返回上一次的崩溃报告（没有则空串）。 */
    public static String lastReport() {
        if (ctx == null) {
            return "";
        }
        try {
            File[] dirs = ctx.getExternalMediaDirs();
            if (dirs != null && dirs.length > 0 && dirs[0] != null) {
                File f = new File(dirs[0], NAME);
                if (f.exists() && f.length() > 0) {
                    byte[] buf = new byte[(int) Math.min(f.length(), 6000)];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    int n = in.read(buf);
                    in.close();
                    if (n > 0) {
                        return new String(buf, 0, n, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static void clear() {
        if (ctx == null) {
            return;
        }
        try {
            File[] dirs = ctx.getExternalMediaDirs();
            if (dirs != null && dirs.length > 0 && dirs[0] != null) {
                new File(dirs[0], NAME).delete();
                new File(dirs[0], "dsh-trail.log").delete();
            }
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            TRAIL.setLength(0);
        }
        try {
            File d = ctx.getExternalFilesDir(null);
            if (d != null) {
                new File(d, NAME).delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
