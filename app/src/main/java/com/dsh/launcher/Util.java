package com.dsh.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Util {

    public interface TextCb {
        void run(String text);
    }

    public interface Run {
        void run();
    }

    private Util() {
    }

    /* ---------------- 尺寸 / 显示 ---------------- */

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static void toast(Context c, String msg) {
        if (c == null || msg == null || msg.length() == 0) {
            return;
        }
        try {
            Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    public static void copy(Context c, String label, String text) {
        if (c == null || text == null) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
            }
        } catch (Throwable ignored) {
        }
    }

    public static String size(long b) {
        if (b < 0) {
            return "-";
        }
        if (b < 1024) {
            return b + " B";
        }
        double kb = b / 1024.0;
        if (kb < 1024) {
            return trim(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return trim(mb) + " MB";
        }
        return trim(mb / 1024.0) + " GB";
    }

    private static String trim(double v) {
        if (v >= 100) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.US, "%.1f", v);
    }

    public static String time(long ms) {
        if (ms <= 0) {
            return "-";
        }
        long now = System.currentTimeMillis();
        long diff = now - ms;
        if (diff >= 0 && diff < 60_000L) {
            return "刚刚";
        }
        if (diff >= 0 && diff < 3600_000L) {
            return (diff / 60_000L) + " 分钟前";
        }
        if (diff >= 0 && diff < 86_400_000L) {
            return (diff / 3600_000L) + " 小时前";
        }
        if (diff >= 0 && diff < 7 * 86_400_000L) {
            return (diff / 86_400_000L) + " 天前";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    /* ---------------- 路径 ---------------- */

    public static String join(String dir, String name) {
        if (dir == null || dir.length() == 0) {
            return "/" + name;
        }
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }

    public static String baseName(String p) {
        if (p == null) {
            return "";
        }
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    public static String parent(String p) {
        if (p == null || p.length() == 0) {
            return "/";
        }
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int i = s.lastIndexOf('/');
        if (i <= 0) {
            return "/";
        }
        return s.substring(0, i);
    }

    public static boolean isShared(String p) {
        if (p == null) {
            return false;
        }
        return p.equals("/sdcard") || p.startsWith("/sdcard/")
                || p.equals("/storage/emulated/0") || p.startsWith("/storage/emulated/0/");
    }

    public static String ext(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1).toLowerCase(Locale.US);
    }

    public static String mimeOf(String name) {
        String e = ext(name);
        if (e.length() == 0) {
            return "*/*";
        }
        String m = null;
        try {
            m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        } catch (Throwable ignored) {
        }
        return m == null ? "*/*" : m;
    }

    /* ---------------- 对话框 ---------------- */

    public static void input(Activity act, String title, String hint, String initial,
                             String okText, final TextCb cb) {
        if (act == null || act.isFinishing()) {
            return;
        }
        final EditText edt = new EditText(act);
        edt.setBackgroundResource(R.drawable.bg_input);
        int p = dp(act, 14);
        edt.setPadding(p, p, p, p);
        edt.setTextSize(15f);
        edt.setHint(hint == null ? "" : hint);
        edt.setInputType(InputType.TYPE_CLASS_TEXT);
        edt.setSingleLine(true);
        if (initial != null) {
            edt.setText(initial);
            edt.setSelection(initial.length());
        }
        FrameLayout wrap = new FrameLayout(act);
        int outer = dp(act, 20);
        wrap.setPadding(outer, dp(act, 8), outer, 0);
        wrap.addView(edt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(act, R.style.AppTheme_Dialog)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(okText == null ? act.getString(R.string.dlg_ok) : okText,
                        null)
                .setNegativeButton(R.string.dlg_cancel, null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = edt.getText().toString().trim();
                if (text.length() == 0) {
                    Util.toast(act, act.getString(R.string.err_bad_name));
                    return;
                }
                dlg.dismiss();
                cb.run(text);
            }
        });
    }

    public static void confirm(Activity act, String title, String msg, String okText,
                               boolean danger, final Run onOk) {
        if (act == null || act.isFinishing()) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(act, R.style.AppTheme_Dialog)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton(R.string.dlg_cancel, null);
        if (danger) {
            b.setPositiveButton(okText, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    onOk.run();
                }
            });
        } else {
            b.setPositiveButton(okText, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    onOk.run();
                }
            });
        }
        b.show();
    }

    public static String readAsset(Context c, String name) {
        StringBuilder sb = new StringBuilder();
        java.io.InputStream in = null;
        try {
            in = c.getAssets().open(name);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }
}
