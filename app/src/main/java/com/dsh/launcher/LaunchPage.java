package com.dsh.launcher;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 启动页：拉起 DSH、看状态、看日志。 */
public class LaunchPage {

    private static final Pattern URL_PATTERN = Pattern.compile("DSH_URL=(\\S+)");
    private static final Pattern LAN_PATTERN = Pattern.compile("DSH_LAN_URL=(\\S+)");
    private static final Pattern ALIVE_PATTERN = Pattern.compile("DSH_ALIVE=(\\S+)");
    private static final int MAX_LOG_LINES = 80;

    private final MainActivity act;
    private final Prefs prefs;
    private final View root;
    private final TextView tvState;
    private final TextView tvStateDesc;
    private final TextView tvLog;
    private final LinearLayout infoBox;
    private final TextView btnLaunch;
    private final LinkedHashMap<String, TextView> infoValues = new LinkedHashMap<>();

    private boolean busy;
    private String lastUrl;
    private String lanUrl;

    public LaunchPage(MainActivity act) {
        this.act = act;
        this.prefs = Prefs.get(act);
        root = LayoutInflater.from(act).inflate(R.layout.page_launch, null);

        tvState = root.findViewById(R.id.tvState);
        tvStateDesc = root.findViewById(R.id.tvStateDesc);
        tvLog = root.findViewById(R.id.tvLog);
        infoBox = root.findViewById(R.id.infoBox);
        btnLaunch = root.findViewById(R.id.btnLaunch);

        addInfo("url", act.getString(R.string.info_url));
        addInfo("port", act.getString(R.string.info_port));
        addInfo("workspace", act.getString(R.string.info_workspace));
        addInfo("termux", act.getString(R.string.info_termux));
        addInfo("shizuku", act.getString(R.string.info_shizuku));

        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch();
            }
        });
        root.findViewById(R.id.btnWeb).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWeb();
            }
        });
        root.findViewById(R.id.btnCopyLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Util.copy(act, "log", tvLog.getText().toString());
                Util.toast(act, act.getString(R.string.copied));
            }
        });
        root.findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvLog.setText(act.getString(R.string.log_empty));
            }
        });

        setState("unknown");
        log("就绪。点「启动并打开网页」即可。");
    }

    public View view() {
        return root;
    }

    public void onShow() {
        refreshInfo();
        checkAlive();
    }

    /* ---------------- 信息行 ---------------- */

    private void addInfo(String key, String label) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Util.dp(act, 9), 0, Util.dp(act, 9));

        TextView l = new TextView(act);
        l.setText(label);
        l.setTextSize(13f);
        l.setTextColor(act.getColor(R.color.textSecondary));
        row.addView(l, new LinearLayout.LayoutParams(Util.dp(act, 76),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(act);
        v.setTextSize(13f);
        v.setTextColor(act.getColor(R.color.textPrimary));
        v.setGravity(Gravity.END);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String text = v.getText().toString();
                if (text.length() > 0) {
                    Util.copy(act, "info", text);
                    Util.toast(act, act.getString(R.string.copied));
                }
            }
        });

        if (infoBox.getChildCount() > 0) {
            View line = new View(act);
            line.setBackgroundColor(act.getColor(R.color.divider));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(act, 1));
            line.setLayoutParams(lp);
            infoBox.addView(line);
        }
        infoBox.addView(row);
        infoValues.put(key, v);
    }

    private void setInfo(String key, String value) {
        TextView v = infoValues.get(key);
        if (v != null) {
            v.setText(value);
        }
    }

    public void refreshInfo() {
        setInfo("port", String.valueOf(prefs.port()));
        setInfo("workspace", prefs.workspace());
        setInfo("termux", TermuxBridge.isInstalled(act)
                ? (TermuxBridge.hasPermission(act) ? "已就绪 ✓" : "缺少 RUN_COMMAND 权限") : "未安装 ✗");
        if (lastUrl == null) {
            setInfo("url", lanUrl != null ? lanUrl : "http://127.0.0.1:" + prefs.port() + "/");
        } else {
            setInfo("url", lastUrl);
        }
        String sz = TermuxBridge.shizukuState(act);
        String text;
        if ("on".equals(sz)) {
            text = act.getString(R.string.shizuku_on) + " ✓";
        } else if ("off".equals(sz)) {
            text = act.getString(R.string.shizuku_off);
        } else if ("none".equals(sz)) {
            text = act.getString(R.string.shizuku_none);
        } else {
            text = act.getString(R.string.shizuku_err);
        }
        setInfo("shizuku", text);
    }

    /* ---------------- 状态 ---------------- */

    private void setState(String s) {
        int color = act.getColor(R.color.textOnBrand);
        if ("running".equals(s)) {
            tvState.setText(R.string.state_running);
            tvStateDesc.setText(R.string.state_desc_running);
        } else if ("starting".equals(s)) {
            tvState.setText(R.string.state_starting);
            tvStateDesc.setText(R.string.state_desc_starting);
        } else if ("down".equals(s)) {
            tvState.setText(R.string.state_down);
            tvStateDesc.setText(R.string.state_desc_down);
        } else if ("stopped".equals(s)) {
            tvState.setText(R.string.state_stopped);
            tvStateDesc.setText(R.string.state_desc_idle);
        } else {
            tvState.setText(R.string.state_unknown);
            tvStateDesc.setText(R.string.state_desc_idle);
        }
        tvState.setTextColor(color);
    }

    public void checkAlive() {
        int port = prefs.port();
        String script = "if curl -s -o /dev/null --max-time 2 http://127.0.0.1:" + port
                + "/ 2>/dev/null; then echo DSH_ALIVE=1; else echo DSH_ALIVE=0; fi";
        TermuxBridge.exec(act, script, null, 12000, new TermuxBridge.Cb() {
            @Override
            public void done(TermuxBridge.Res r) {
                if (!r.ok()) {
                    setState("unknown");
                    return;
                }
                Matcher m = ALIVE_PATTERN.matcher(r.stdout);
                if (m.find() && "1".equals(m.group(1))) {
                    setState("running");
                } else {
                    setState("stopped");
                }
            }
        });
    }

    /* ---------------- 启动 ---------------- */

    public void launch() {
        if (busy) {
            log("上一次还在执行中，请稍候…");
            return;
        }
        if (!TermuxBridge.isInstalled(act)) {
            log("✗ " + act.getString(R.string.no_termux));
            setState("down");
            return;
        }
        if (!TermuxBridge.hasPermission(act)) {
            log(act.getString(R.string.perm_need));
            act.requestRunPermission();
            return;
        }
        String script = Util.readAsset(act, "dsh-launch.sh");
        if (script == null) {
            log("✗ 内置启动脚本读取失败");
            return;
        }
        if (prefs.lan()) {
            script = "export DSH_HOST=0.0.0.0\n" + script;
        }
        busy = true;
        setState("starting");
        log("→ 已请求 Termux 执行启动脚本，等待回传…");
        TermuxBridge.exec(act, script, null, 90000, new TermuxBridge.Cb() {
            @Override
            public void done(TermuxBridge.Res r) {
                busy = false;
                onLaunchResult(r);
            }
        });
    }

    public void onPermissionResult(boolean granted) {
        if (granted) {
            log("✓ 已获得 Termux 运行权限");
            launch();
        } else {
            log("✗ " + act.getString(R.string.perm_denied));
        }
    }

    private void onLaunchResult(TermuxBridge.Res r) {
        if (!r.delivered) {
            log("✗ " + r.message());
            setState("down");
            return;
        }
        log("← Termux 回传 exitCode=" + r.exit);
        String out = r.stdout == null ? "" : r.stdout;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.length() == 0 || t.startsWith("DSHREQ=")) {
                continue;
            }
            if (t.startsWith("DSH_URL=")) {
                log("  地址：" + t.substring("DSH_URL=".length()));
            } else if (t.startsWith("DSH_LAN_URL=")) {
                log("  局域网：" + t.substring("DSH_LAN_URL=".length()));
            } else if (t.startsWith("DSH_STATE=")) {
                log("  状态：" + t.substring("DSH_STATE=".length()));
            } else if (!t.startsWith("DSH_OPENED=")) {
                log("  " + t);
            }
        }
        if (r.stderr != null && r.stderr.trim().length() > 0) {
            log("stderr: " + r.stderr.trim());
        }

        lanUrl = firstMatch(LAN_PATTERN, out, lanUrl);
        lastUrl = firstMatch(URL_PATTERN, out, lastUrl);
        refreshInfo();

        boolean openedByScript = out.contains("DSH_OPENED=1");
        if (out.contains("DSH_STATE=running") || lastUrl != null) {
            setState("running");
        } else {
            setState("down");
        }
        if (openedByScript) {
            log("✓ Termux 已自行打开浏览器（冷启动路径）");
            return;
        }
        openWeb();
    }

    private String firstMatch(Pattern p, String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher m = p.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last == null ? fallback : last;
    }

    private void openWeb() {
        String url = lastUrl;
        if (url == null) {
            url = "http://127.0.0.1:" + prefs.port() + "/";
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(view);
            log("→ 已交给浏览器：" + url);
        } catch (Throwable t) {
            log("✗ 打开浏览器失败：" + t.getClass().getSimpleName());
            Util.toast(act, "没有可用的浏览器");
        }
    }

    /* ---------------- 日志 ---------------- */

    public void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String current = tvLog.getText().toString();
        if (current.startsWith("（暂无日志）")) {
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
