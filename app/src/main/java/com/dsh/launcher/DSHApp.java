package com.dsh.launcher;

import android.app.Application;
import android.os.StrictMode;

public class DSHApp extends Application {

    @Override
    public void onCreate() {
        // 第一件事就是装崩溃捕获：后面任何一步炸了，都能在 /sdcard/Download/dsh-crash.log 看到
        CrashLog.install(this);
        super.onCreate();

        // 「用其他文件管理器打开」需要把 file:// 路径交给外部 App。
        // Android 7 起默认会抛 FileUriExposedException（StrictMode 的检测策略）。
        // 这是本地个人工具，主动关掉这条检测，让路径能直接递出去。
        try {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        } catch (Throwable ignored) {
        }
        CrashLog.breadcrumb("app:StrictMode 处理完成");

        TermuxBridge.install(this);
        CrashLog.breadcrumb("app:结果广播已注册，onCreate 完成");
    }
}
