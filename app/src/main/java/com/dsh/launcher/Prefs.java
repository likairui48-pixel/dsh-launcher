package com.dsh.launcher;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String DEF_WORKSPACE = TERMUX_HOME + "/usr/DSH";
    public static final String DEF_SHARED = "/sdcard/Download/DSH-Workspace";
    public static final int DEF_PORT = 3080;

    private static final String NAME = "dsh_launcher";

    private static Prefs instance;

    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context c) {
        if (instance == null) {
            instance = new Prefs(c);
        }
        return instance;
    }

    public String workspace() {
        return sp.getString("workspace", DEF_WORKSPACE);
    }

    public void setWorkspace(String v) {
        sp.edit().putString("workspace", v).apply();
    }

    public String sharedDir() {
        return sp.getString("shared", DEF_SHARED);
    }

    public void setSharedDir(String v) {
        sp.edit().putString("shared", v).apply();
    }

    public int port() {
        int p = sp.getInt("port", DEF_PORT);
        return (p > 0 && p < 65536) ? p : DEF_PORT;
    }

    public void setPort(int p) {
        sp.edit().putInt("port", p).apply();
    }

    public boolean lan() {
        return sp.getBoolean("lan", false);
    }

    public void setLan(boolean v) {
        sp.edit().putBoolean("lan", v).apply();
    }

    public boolean showHidden() {
        return sp.getBoolean("hidden", false);
    }

    public void setShowHidden(boolean v) {
        sp.edit().putBoolean("hidden", v).apply();
    }

    public String sortKey() {
        return sp.getString("sortKey", "name");
    }

    public boolean sortAsc() {
        return sp.getBoolean("sortAsc", true);
    }

    public boolean folderFirst() {
        return sp.getBoolean("folderFirst", true);
    }

    public void setSort(String key, boolean asc, boolean folderFirst) {
        sp.edit().putString("sortKey", key).putBoolean("sortAsc", asc)
                .putBoolean("folderFirst", folderFirst).apply();
    }

    public boolean probed() {
        return sp.getBoolean("probed", false);
    }

    public void setProbed(boolean v) {
        sp.edit().putBoolean("probed", v).apply();
    }

    public void reset() {
        sp.edit().clear().apply();
    }
}
