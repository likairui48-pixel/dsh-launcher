package com.dsh.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 Android 启动器：
 *   1. 拉起 Termux 里的 .sh 脚本（启动 DSH）
 *   2. 检查 Shizuku 是否在运行
 *   3. 拿到脚本回传的带 token 的 URL，直接交给浏览器
 */
public class MainActivity extends Activity {

    /* ===================== Termux RUN_COMMAND 协议 =====================
     * 全部取自 termux-app 源码 TermuxConstants.TERMUX_APP + RunCommandService
     * ================================================================= */
    private static final String TERMUX_PKG         = "com.termux";
    private static final String TERMUX_SERVICE     = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    private static final String X_COMMAND_PATH     = "com.termux.RUN_COMMAND_PATH";
    private static final String X_ARGUMENTS        = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String X_WORKDIR          = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String X_RUNNER           = "com.termux.RUN_COMMAND_RUNNER";
    private static final String X_PENDING_INTENT   = "com.termux.RUN_COMMAND_PENDING_INTENT";

    /** ExecutionCommand.Runner.APP_SHELL.getRunner() —— 后台跑，不弹终端 */
    private static final String RUNNER_APP_SHELL   = "app-shell";

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_SH   = "/data/data/com.termux/files/usr/bin/sh";

    /* ===================== 权限 ===================== */
    private static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final int    REQ_RUN_COMMAND  = 1001;

    /* ===================== 结果回传 =====================
     * ResultSender.sendCommandResultData() ：
     *   Intent resultIntent = new Intent();
     *   resultIntent.putExtra("result", bundle);   <-- key 是字面量 "result"
     *   pendingIntent.send(context, RESULT_OK, resultIntent);
     * bundle 内 key：stdout / stderr / exitCode / err / errmsg
     * ================================================== */
    private static final String ACTION_RESULT   = "com.dsh.launcher.COMMAND_RESULT";
    private static final String KEY_RESULT_BODY = "result";
    private static final String KEY_STDOUT      = "stdout";
    private static final String KEY_STDERR      = "stderr";
    private static final String KEY_EXIT        = "exitCode";
    private static final String KEY_ERR         = "err";
    private static final String KEY_ERRMSG      = "errmsg";

    private static final String FALLBACK_URL = "http://127.0.0.1:3080/";
    private static final Pattern URL_PATTERN = Pattern.compile("DSH_URL=(\\S+)");
    private static final long TIMEOUT_MS = 45000L;
    private static final int  MAX_LOG_LINES = 60;

    private TextView tvShizuku;
    private TextView tvLog;
    private BroadcastReceiver resultReceiver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable timeoutTask;
    private boolean busy = false;

    /* ===================== 生命周期 ===================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizuku = findViewById(R.id.tvShizuku);
        tvLog     = findViewById(R.id.tvLog);
        Button btnLaunch  = findViewById(R.id.btnLaunch);
        Button btnShizuku = findViewById(R.id.btnShizuku);

        btnLaunch.setOnClickListener(v -> onLaunchClicked());
        btnShizuku.setOnClickListener(v -> {
            updateShizukuStatus();
            log("已重新检测 Shizuku。");
        });

        resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onCommandResult(intent.getBundleExtra(KEY_RESULT_BODY));
            }
        };

        updateShizukuStatus();
        log("就绪。点上面的大按钮即可。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
        updateShizukuStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(resultReceiver);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /* ===================== 功能 2：Shizuku 检测 ===================== */

    private void updateShizukuStatus() {
        String text;
        try {
            if (!isPackageInstalled("moe.shizuku.privileged.api")) {
                text = "Shizuku：未安装";
            } else if (rikka.shizuku.Shizuku.pingBinder()) {
                text = "Shizuku：运行中 ✓";
            } else {
                text = "Shizuku：已安装，但服务未运行 ✗";
            }
        } catch (Throwable t) {
            text = "Shizuku：检测异常（" + t.getClass().getSimpleName() + "）";
        }
        tvShizuku.setText(text);
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return info != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ===================== 功能 1+3：拉起脚本并打开网页 ===================== */

    private void onLaunchClicked() {
        if (busy) {
            log("上一次还在执行中，请稍候…");
            return;
        }

        if (!isPackageInstalled(TERMUX_PKG)) {
            log("✗ 没检测到 Termux，请先安装 Termux。");
            return;
        }

        if (!hasRunCommandPermission()) {
            log("需要 Termux 的 RUN_COMMAND 权限，正在申请…");
            try {
                requestPermissions(new String[]{PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
            } catch (Throwable t) {
                log("✗ 权限申请失败：" + t);
            }
            return;
        }

        String script;
        try {
            script = readAsset("dsh-launch.sh");
        } catch (IOException e) {
            log("✗ 读取内置脚本失败：" + e);
            return;
        }

        Intent resultIntent = new Intent(ACTION_RESULT);
        resultIntent.setPackage(getPackageName());
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 必须是 MUTABLE：Termux 用 send(context, code, fillInIntent) 把结果塞进 extras
            piFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, resultIntent, piFlags);

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(TERMUX_PKG, TERMUX_SERVICE));
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(X_COMMAND_PATH, TERMUX_SH);
        intent.putExtra(X_ARGUMENTS, new String[]{"-c", script});
        intent.putExtra(X_WORKDIR, TERMUX_HOME);
        intent.putExtra(X_RUNNER, RUNNER_APP_SHELL);
        intent.putExtra(X_PENDING_INTENT, pi);

        try {
            startService(intent);
        } catch (Throwable first) {
            log("startService 被拒（" + first.getClass().getSimpleName() + "），改用 startForegroundService 重试…");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    log("✗ 无法调用 Termux：" + first);
                    return;
                }
            } catch (Throwable second) {
                log("✗ 无法调用 Termux：" + second);
                log("   请确认已开启 allow-external-apps=true");
                return;
            }
        }

        busy = true;
        log("已请求 Termux 执行脚本，等待回传结果…");
        armTimeout(TIMEOUT_MS);
    }

    private boolean hasRunCommandPermission() {
        try {
            return checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RUN_COMMAND) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            log("✓ 已获得 Termux 运行权限，正在启动…");
            ui.postDelayed(this::onLaunchClicked, 300L);
        } else {
            log("✗ 权限被拒绝。请到 「设置 → 应用 → DSH 启动器 → 权限」 里手动允许。");
        }
    }

    private void onCommandResult(Bundle body) {
        cancelTimeout();
        busy = false;

        if (body == null) {
            log("⚠ 收到空结果，按默认地址打开。");
            openBrowser(FALLBACK_URL);
            return;
        }

        int exit = body.getInt(KEY_EXIT, -1);
        String out    = body.getString(KEY_STDOUT);
        String errOut = body.getString(KEY_STDERR);
        String err    = body.getString(KEY_ERR);
        String errmsg = body.getString(KEY_ERRMSG);

        log("← Termux 回传（exitCode=" + exit + "）");
        if (out != null && !out.trim().isEmpty()) {
            log(out.trim());
        }
        if (errOut != null && !errOut.trim().isEmpty()) {
            log("stderr: " + errOut.trim());
        }
        if (err != null && !err.isEmpty()) {
            log("err: " + err + (errmsg == null || errmsg.isEmpty() ? "" : " — " + errmsg));
        }

        if (out != null && out.contains("DSH_OPENED=1")) {
            log("✓ Termux 已自行打开浏览器（冷启动路径），不重复打开。");
            return;
        }

        String url = extractUrl(out);
        if (url != null) {
            openBrowser(url);
        } else {
            log("未从输出里解析到 DSH_URL，按默认地址打开。");
            openBrowser(FALLBACK_URL);
        }
    }

    private String extractUrl(String out) {
        if (out == null) {
            return null;
        }
        Matcher m = URL_PATTERN.matcher(out);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private void openBrowser(String url) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            log("→ 已交给浏览器：" + url);
        } catch (ActivityNotFoundException e) {
            log("✗ 没有找到可用的浏览器");
        } catch (Throwable t) {
            log("✗ 打开浏览器失败：" + t);
        }
    }

    /* ===================== 工具 ===================== */

    private void armTimeout(long ms) {
        cancelTimeout();
        timeoutTask = () -> {
            busy = false;
            log("⏱ 等待 Termux 回传超时，直接按默认地址打开浏览器。");
            openBrowser(FALLBACK_URL);
        };
        ui.postDelayed(timeoutTask, ms);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            ui.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
    }

    private String readAsset(String name) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open(name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String current = tvLog.getText().toString();
        if (current.startsWith("（暂无日志）") || current.startsWith("(暂无日志)")) {
            current = "";
        }
        String next = current + "[" + ts + "] " + msg + "\n";
        String[] lines = next.split("\n", -1);
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            next = sb.toString();
        }
        tvLog.setText(next);
    }
}
