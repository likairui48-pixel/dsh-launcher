package com.dsh.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

/** 单 Activity + 三个页面（启动 / 文件 / 设置），底部导航自绘。 */
public class MainActivity extends Activity {

    private static final String[] TAB_GLYPHS = {"spark", "folder", "settings"};

    private Prefs prefs;
    private FrameLayout host;
    private LinearLayout navBar;
    private LaunchPage launchPage;
    private FilesPage filesPage;
    private SettingsPage settingsPage;
    private int tab;

    private final ArrayList<View> navItems = new ArrayList<>();
    private final ArrayList<GlyphView> navGlyphs = new ArrayList<>();
    private final ArrayList<TextView> navLabels = new ArrayList<>();
    private final HashMap<String, Integer> reqMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLog.breadcrumb("MA:onCreate 开始");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        CrashLog.breadcrumb("MA:setContentView 完成");
        prefs = Prefs.get(this);

        host = findViewById(R.id.pageHost);
        navBar = findViewById(R.id.navBar);
        CrashLog.breadcrumb("MA:view 绑定完成");

        launchPage = new LaunchPage(this);
        CrashLog.breadcrumb("MA:LaunchPage 构造完成");
        filesPage = new FilesPage(this);
        CrashLog.breadcrumb("MA:FilesPage 构造完成");
        settingsPage = new SettingsPage(this);
        CrashLog.breadcrumb("MA:SettingsPage 构造完成");

        host.addView(launchPage.view(), match());
        host.addView(filesPage.view(), match());
        host.addView(settingsPage.view(), match());
        CrashLog.breadcrumb("MA:页面挂载完成");

        buildNav();
        CrashLog.breadcrumb("MA:底部导航构建完成");
        showTab(0);
        CrashLog.breadcrumb("MA:showTab(0) 完成");
        maybeProbe();
        CrashLog.breadcrumb("MA:onCreate 全部完成");
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void buildNav() {
        int[] labels = {R.string.nav_launch, R.string.nav_files, R.string.nav_settings};
        int want = Util.dp(this, 24);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setBackgroundResource(R.drawable.bg_nav_item);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            item.setPadding(0, Util.dp(this, 8), 0, Util.dp(this, 8));

            GlyphView g = new GlyphView(this);
            g.setGlyph(TAB_GLYPHS[i]);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(want, want);
            item.addView(g, glp);

            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(11f);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Util.dp(this, 3);
            item.addView(t, tlp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            navBar.addView(item);
            navItems.add(item);
            navGlyphs.add(g);
            navLabels.add(t);
        }
    }

    private void showTab(int i) {
        tab = i;
        launchPage.view().setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        filesPage.view().setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        settingsPage.view().setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        for (int k = 0; k < navItems.size(); k++) {
            boolean on = k == i;
            int color = getColor(on ? R.color.brand : R.color.textTertiary);
            navGlyphs.get(k).setGlyphTint(color);
            navLabels.get(k).setTextColor(color);
            navLabels.get(k).setTypeface(null,
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (i == 0) {
            launchPage.onShow();
        } else if (i == 1) {
            filesPage.onShow();
        } else {
            settingsPage.onShow();
        }
    }

    /** 首次启动做一次环境探测：工作区路径不存在时自动回落到 Termux HOME。 */
    private void maybeProbe() {
        if (prefs.probed()) {
            return;
        }
        FsClient.probe(this, new FsClient.Cb<HashMap<String, String>>() {
            @Override
            public void done(HashMap<String, String> v, String error) {
                if (error != null) {
                    launchPage.log("环境探测失败：" + error);
                    return;
                }
                prefs.setProbed(true);
                String home = v.get("HOME");
                String def = v.get("WS_DEFAULT");
                boolean exists = "1".equals(v.get("WS_EXISTS"));
                String ws = prefs.workspace();
                if (ws == null || ws.length() == 0) {
                    ws = Prefs.DEF_WORKSPACE;
                }
                boolean wsOnDefault = def != null && ws.equals(def);
                if (!exists && wsOnDefault && home != null) {
                    prefs.setWorkspace(home);
                    launchPage.log("工作区目录不存在，已回落到 " + home);
                }
                if (!"1".equals(v.get("SHARED_OK"))) {
                    launchPage.log("提示：共享存储不可写，导出功能会失败");
                }
                launchPage.log("环境探测完成：HOME=" + home);
                filesPage.resetToWorkspace();
                settingsPage.onShow();
                launchPage.refreshInfo();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        CrashLog.breadcrumb("MA:onResume");
        if (launchPage != null) {
            launchPage.refreshInfo();
        }
    }

    @Override
    public void onBackPressed() {
        if (tab == 1 && filesPage.onBack()) {
            return;
        }
        if (tab != 0) {
            showTab(0);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        filesPage.onImportResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TermuxBridge.REQ_PERMISSION) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            launchPage.onPermissionResult(ok);
        }
    }

    /* ---------------- 给页面用的回调 ---------------- */

    public void requestRunPermission() {
        try {
            requestPermissions(new String[]{TermuxBridge.PERM_RUN_COMMAND},
                    TermuxBridge.REQ_PERMISSION);
        } catch (Throwable t) {
            launchPage.log("✗ 权限申请失败：" + t.getClass().getSimpleName());
        }
    }

    public void onSettingsChanged() {
        filesPage.resetToWorkspace();
        launchPage.refreshInfo();
    }

    public void gotoFiles(String path) {
        showTab(1);
        if (path != null) {
            filesPage.open(path);
        }
    }

    public LaunchPage launch() {
        return launchPage;
    }

    public FilesPage files() {
        return filesPage;
    }
}
