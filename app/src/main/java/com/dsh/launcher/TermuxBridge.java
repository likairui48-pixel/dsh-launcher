package com.dsh.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;

/**
 * 与 Termux 之间的唯一通道。
 *
 * 启动器 App 和 Termux 是两个不同的 UID，/data/data/com.termux 属于 Termux 私有，
 * 普通 File API 一律读不到。Termux 官方提供的 RUN_COMMAND 服务能把 stdout / stderr /
 * exitCode 通过 PendingIntent 回传，这就是读写工作区文件的通道。
 *
 * 关键实现约束：
 *  1. 串行执行 —— PendingIntent 靠 requestCode 区分，并发会让结果串台，所以排队。
 *  2. 请求号写进 stdout（DSHREQ=n）—— 不依赖 PendingIntent extras 的合并行为。
 *  3. 每个请求独立超时，超时按失败回传，绝不挂死 UI。
 */
public final class TermuxBridge {

    /* ---------- Termux RUN_COMMAND 协议常量 ---------- */
    public static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    private static final String X_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String X_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String X_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String X_RUNNER = "com.termux.RUN_COMMAND_RUNNER";
    private static final String X_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String X_REQ_ID = "com.dsh.launcher.REQ_ID";

    private static final String RUNNER_APP_SHELL = "app-shell";

    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh";
    public static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    public static final int REQ_PERMISSION = 1001;

    /* ---------- 结果回传 ---------- */
    private static final String ACTION_RESULT = "com.dsh.launcher.COMMAND_RESULT";
    private static final String KEY_RESULT_BODY = "result";
    private static final String KEY_STDOUT = "stdout";
    private static final String KEY_STDERR = "stderr";
    private static final String KEY_EXIT = "exitCode";
    private static final String KEY_ERR = "err";
    private static final String KEY_ERRMSG = "errmsg";

    /* ---------- 回调 ---------- */
    public interface Cb {
        void done(Res r);
    }

    public static final class Res {
        public boolean delivered;      // 是否真的收到了 Termux 回传
        public int exit = -1;
        public String stdout = "";
        public String stderr = "";
        public String error;           // 桥接层错误：超时 / 权限 / 未安装 / 空结果

        public boolean ok() {
            return delivered && exit == 0 && error == null;
        }

        public String message() {
            if (error != null) {
                return error;
            }
            if (stderr != null && stderr.trim().length() > 0) {
                return stderr.trim();
            }
            if (stdout != null && stdout.trim().length() > 0) {
                return stdout.trim();
            }
            return "exit=" + exit;
        }
    }

    /* ---------- 队列 ---------- */
    private static final class Task {
        int id;
        Context ctx;
        String[] argv;
        long timeoutMs;
        Cb cb;
        Runnable killer;
    }

    private static final ArrayDeque<Task> QUEUE = new ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Task current;
    private static int seq = 1000;
    private static boolean installed = false;

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            Task t = current;
            if (t == null) {
                return;
            }
            int rid = intent.getIntExtra(X_REQ_ID, -1);
            if (rid != -1 && rid != t.id) {
                return;
            }
            if (t.killer != null) {
                MAIN.removeCallbacks(t.killer);
                t.killer = null;
            }
            current = null;
            finish(t, build(intent.getBundleExtra(KEY_RESULT_BODY), t));
        }
    };

    public static void install(Context ctx) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            IntentFilter f = new IntentFilter(ACTION_RESULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(RECEIVER, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(RECEIVER, f);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ===================== 对外 API ===================== */

    /** 跑一段脚本；tailArgs 会作为 $0 $1 ... 传给 sh -c。 */
    public static void exec(Context ctx, String script, String[] tailArgs, long timeoutMs, Cb cb) {
        enqueue(ctx, script, tailArgs, timeoutMs, cb);
    }

    private static synchronized int nextId() {
        seq++;
        if (seq > 1_000_000) {
            seq = 1000;
        }
        return seq;
    }

    private static void enqueue(Context ctx, String script, String[] tailArgs, long timeoutMs,
                               Cb cb) {
        if (ctx == null || cb == null) {
            return;
        }
        if (!isInstalled(ctx)) {
            Res r = new Res();
            r.error = ctx.getString(R.string.no_termux);
            cb.done(r);
            return;
        }
        if (!hasPermission(ctx)) {
            Res r = new Res();
            r.error = ctx.getString(R.string.perm_need);
            cb.done(r);
            return;
        }
        Task t = new Task();
        t.id = nextId();
        t.ctx = ctx.getApplicationContext();
        // 请求号写进 stdout 第一行：结果归属靠它判定，
        // 不依赖 PendingIntent extras 的合并行为（那是框架实现细节，不该赌）。
        String head = "printf 'DSHREQ=%s\\n' " + t.id + "\n";
        if (tailArgs == null || tailArgs.length == 0) {
            t.argv = new String[]{"-c", head + script};
        } else {
            t.argv = new String[tailArgs.length + 2];
            t.argv[0] = "-c";
            t.argv[1] = head + script;
            System.arraycopy(tailArgs, 0, t.argv, 2, tailArgs.length);
        }
        t.timeoutMs = timeoutMs <= 0 ? 25000L : timeoutMs;
        t.cb = cb;
        synchronized (QUEUE) {
            QUEUE.addLast(t);
        }
        pump();
    }

    private static void pump() {
        if (current != null) {
            return;
        }
        Task t;
        synchronized (QUEUE) {
            t = QUEUE.pollFirst();
        }
        if (t == null) {
            return;
        }
        current = t;
        String err = send(t);
        if (err != null) {
            current = null;
            Res r = new Res();
            r.error = err;
            finish(t, r);
            pump();
            return;
        }
        final Task ref = t;
        t.killer = new Runnable() {
            @Override
            public void run() {
                if (current != ref) {
                    return;
                }
                current = null;
                Res r = new Res();
                r.error = "等待 Termux 回传超时";
                finish(ref, r);
                pump();
            }
        };
        MAIN.postDelayed(t.killer, t.timeoutMs);
    }

    /** 返回 null 表示已发出，否则是错误文案。 */
    private static String send(Task t) {
        try {
            Intent resultIntent = new Intent(ACTION_RESULT);
            resultIntent.setPackage(t.ctx.getPackageName());
            resultIntent.putExtra(X_REQ_ID, t.id);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 必须 MUTABLE：Termux 用 send(ctx, code, fillInIntent) 往 extras 里塞结果
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(t.ctx, t.id, resultIntent, flags);

            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TERMUX_PKG, TERMUX_SERVICE));
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra(X_COMMAND_PATH, TERMUX_SH);
            intent.putExtra(X_ARGUMENTS, t.argv);
            intent.putExtra(X_WORKDIR, TERMUX_HOME);
            intent.putExtra(X_RUNNER, RUNNER_APP_SHELL);
            intent.putExtra(X_PENDING_INTENT, pi);
            t.ctx.startService(intent);
            return null;
        } catch (Throwable first) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent resultIntent = new Intent(ACTION_RESULT);
                    resultIntent.setPackage(t.ctx.getPackageName());
                    resultIntent.putExtra(X_REQ_ID, t.id);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        flags |= PendingIntent.FLAG_MUTABLE;
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(t.ctx, t.id, resultIntent, flags);
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(TERMUX_PKG, TERMUX_SERVICE));
                    intent.setAction(ACTION_RUN_COMMAND);
                    intent.putExtra(X_COMMAND_PATH, TERMUX_SH);
                    intent.putExtra(X_ARGUMENTS, t.argv);
                    intent.putExtra(X_WORKDIR, TERMUX_HOME);
                    intent.putExtra(X_RUNNER, RUNNER_APP_SHELL);
                    intent.putExtra(X_PENDING_INTENT, pi);
                    t.ctx.startForegroundService(intent);
                    return null;
                }
                return "无法调用 Termux：" + first.getClass().getSimpleName();
            } catch (Throwable second) {
                return t.ctx.getString(R.string.termux_not_ready);
            }
        }
    }

    private static Res build(Bundle b, Task t) {
        Res r = new Res();
        if (b == null) {
            r.error = "Termux 返回了空结果";
            return r;
        }
        r.delivered = true;
        r.exit = b.getInt(KEY_EXIT, -1);
        r.stdout = safe(b.getString(KEY_STDOUT));
        r.stderr = safe(b.getString(KEY_STDERR));
        // 只认第一行：文件内容里也可能出现 "DSHREQ=" 字样，不能全局搜
        String first = firstLine(r.stdout);
        if (first != null && first.startsWith("DSHREQ=")) {
            String v = first.substring("DSHREQ=".length()).trim();
            if (!String.valueOf(t.id).equals(v)) {
                r.error = "回传结果属于另一次请求（#" + v + " ≠ #" + t.id + "）";
            }
        }
        String err = b.getString(KEY_ERR);
        String errmsg = b.getString(KEY_ERRMSG);
        if (err != null && err.length() > 0) {
            r.error = err + (errmsg == null || errmsg.length() == 0 ? "" : " — " + errmsg);
        }
        return r;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.trim();
    }

    private static void finish(Task t, final Res r) {
        final Cb cb = t.cb;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.done(r);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 把队列清空（切页面 / 退出时避免回调打到已销毁的界面）。 */
    public static void clearQueue() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
    }

    /* ===================== 环境探测 ===================== */

    public static boolean isInstalled(Context c) {
        return hasPackage(c, TERMUX_PKG);
    }

    public static boolean hasPackage(Context c, String pkg) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(pkg, 0);
            return info != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission(Context c) {
        try {
            return c.checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** none / off / on / err */
    public static String shizukuState(Context c) {
        if (!hasPackage(c, "moe.shizuku.privileged.api")) {
            return "none";
        }
        try {
            return rikka.shizuku.Shizuku.pingBinder() ? "on" : "off";
        } catch (Throwable t) {
            return "err";
        }
    }
}
