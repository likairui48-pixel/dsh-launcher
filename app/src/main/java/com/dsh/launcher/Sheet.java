package com.dsh.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 自绘底部动作面板：比系统 AlertDialog 好看，也比它更好塞图标。 */
public final class Sheet {

    private final Dialog dialog;
    private final LinearLayout body;
    private final ScrollView scroll;
    private int count;

    private Sheet(Context ctx, String t, String sub) {
        dialog = new Dialog(ctx, R.style.AppTheme_Sheet);
        View root = LayoutInflater.from(ctx).inflate(R.layout.view_sheet, null);
        body = root.findViewById(R.id.sheetBody);
        scroll = root.findViewById(R.id.sheetScroll);
        TextView title = root.findViewById(R.id.sheetTitle);
        TextView subtitle = root.findViewById(R.id.sheetSubtitle);
        title.setText(t == null ? "" : t);
        if (sub != null && sub.length() > 0) {
            subtitle.setText(sub);
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        }
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            w.setAttributes(lp);
        }
        dialog.setCanceledOnTouchOutside(true);
    }

    public static Sheet create(Context ctx, String title) {
        return new Sheet(ctx, title, null);
    }

    public static Sheet create(Context ctx, String title, String subtitle) {
        return new Sheet(ctx, title, subtitle);
    }

    public Sheet action(String glyph, String label, int colorRes, final Util.Run run) {
        Context ctx = dialog.getContext();
        View row = LayoutInflater.from(ctx).inflate(R.layout.row_sheet_action, body, false);
        GlyphView g = row.findViewById(R.id.actionGlyph);
        TextView t = row.findViewById(R.id.actionLabel);
        g.setGlyph(glyph);
        if (colorRes != 0) {
            try {
                g.setGlyphTint(ctx.getColor(colorRes));
                t.setTextColor(ctx.getColor(colorRes));
            } catch (Throwable ignored) {
            }
        }
        t.setText(label);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (run != null) {
                    run.run();
                }
            }
        });
        body.addView(row);
        count++;
        return this;
    }

    public Sheet divider() {
        View v = new View(dialog.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(dialog.getContext(), 1));
        lp.leftMargin = Util.dp(dialog.getContext(), 20);
        lp.rightMargin = Util.dp(dialog.getContext(), 20);
        v.setLayoutParams(lp);
        v.setBackgroundColor(dialog.getContext().getColor(R.color.divider));
        body.addView(v);
        return this;
    }

    public Sheet cancel() {
        return action("close", dialog.getContext().getString(R.string.dlg_cancel), 0, null);
    }

    public int size() {
        return count;
    }

    public boolean showing() {
        return dialog.isShowing();
    }

    public void show() {
        try {
            dialog.show();
        } catch (Throwable t) {
            return;
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int max = (int) (dialog.getContext().getResources()
                            .getDisplayMetrics().heightPixels * 0.68f);
                    if (scroll.getHeight() > max) {
                        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                        lp.height = max;
                        scroll.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void dismiss() {
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }
}
