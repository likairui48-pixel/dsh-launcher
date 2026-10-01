package com.dsh.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** 轻量「处理中」弹窗：不需要 AndroidX 的 ProgressDialog。 */
public final class Busy {

    private Busy() {
    }

    public static Dialog show(Context c, String msg) {
        Dialog d = new Dialog(c, R.style.AppTheme_Sheet);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        int p = Util.dp(c, 18);
        box.setPadding(p, p, Util.dp(c, 22), p);

        ProgressBar pb = new ProgressBar(c);
        box.addView(pb, new LinearLayout.LayoutParams(Util.dp(c, 22), Util.dp(c, 22)));

        TextView tv = new TextView(c);
        tv.setText(msg == null ? "" : msg);
        tv.setTextSize(14f);
        tv.setTextColor(c.getColor(R.color.textPrimary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Util.dp(c, 14);
        box.addView(tv, lp);

        d.setContentView(box);
        d.setCancelable(false);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        try {
            d.show();
        } catch (Throwable ignored) {
        }
        return d;
    }

    public static void hide(Dialog d) {
        if (d == null) {
            return;
        }
        try {
            d.dismiss();
        } catch (Throwable ignored) {
        }
    }
}
