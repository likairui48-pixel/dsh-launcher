package com.dsh.launcher;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 极简线稿图标。全部用 Canvas 几何图元现画，不依赖任何图标库 /
 * vector 路径数据 —— 少一个依赖，少一类「编译能过、运行崩掉」的风险。
 */
public class GlyphView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private String glyph = "file";
    private int tint = 0xFF4D6BFE;
    private float strokeWidth = 0f;

    public GlyphView(Context c) {
        super(c);
        init(c, null);
    }

    public GlyphView(Context c, AttributeSet a) {
        super(c, a);
        init(c, a);
    }

    public GlyphView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init(c, a);
    }

    private void init(Context c, AttributeSet a) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(tint);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(tint);
        if (a != null) {
            TypedArray ta = null;
            try {
                ta = c.obtainStyledAttributes(a, R.styleable.GlyphView);
                String g = ta.getString(R.styleable.GlyphView_glyph);
                if (g != null && g.length() > 0) {
                    glyph = g;
                }
                tint = ta.getColor(R.styleable.GlyphView_glyphTint, tint);
                strokeWidth = ta.getDimension(R.styleable.GlyphView_glyphStroke, 0f);
            } catch (Throwable ignored) {
                // 资源异常时保持默认值，不影响绘制
            } finally {
                if (ta != null) {
                    ta.recycle();
                }
            }
        }
        stroke.setColor(tint);
        fill.setColor(tint);
        setWillNotDraw(false);
    }

    public void setGlyph(String g) {
        if (g == null) {
            return;
        }
        glyph = g;
        invalidate();
    }

    public void setGlyphTint(int color) {
        tint = color;
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    public int glyphColor() {
        return tint;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        float box = Math.min(vw, vh);
        float pad = box * 0.10f;
        float side = box - pad * 2f;
        float ox = (vw - box) / 2f + pad;
        float oy = (vh - box) / 2f + pad;
        stroke.setStrokeWidth(strokeWidth > 0f ? strokeWidth : Math.max(1.5f, side * 0.085f));
        canvas.save();
        canvas.translate(ox, oy);
        drawGlyph(canvas, side);
        canvas.restore();
    }

    private void arrowHead(Canvas cv, float cx, float cy, float r, float deg, float size) {
        double a = Math.toRadians(deg);
        float x = cx + (float) (r * Math.cos(a));
        float y = cy + (float) (r * Math.sin(a));
        float tx = (float) (-Math.sin(a));
        float ty = (float) (Math.cos(a));
        float nx = (float) Math.cos(a);
        float ny = (float) (Math.sin(a));
        float h = size;
        path.reset();
        path.moveTo(x + tx * h, y + ty * h);
        path.lineTo(x - nx * h * 0.75f - tx * h * 0.35f, y - ny * h * 0.75f - ty * h * 0.35f);
        path.lineTo(x + nx * h * 0.75f - tx * h * 0.35f, y + ny * h * 0.75f - ty * h * 0.35f);
        path.close();
        cv.drawPath(path, fill);
    }

    private void drawGlyph(Canvas cv, float w) {
        float c = w / 2f;
        switch (glyph) {
            case "spark": {
                float k = w * 0.09f;
                path.reset();
                path.moveTo(c, 0f);
                path.quadTo(c + k, c - k, w, c);
                path.quadTo(c + k, c + k, c, w);
                path.quadTo(c - k, c + k, 0f, c);
                path.quadTo(c - k, c - k, c, 0f);
                path.close();
                cv.drawPath(path, stroke);
                break;
            }
            case "folder": {
                rect.set(w * 0.06f, w * 0.30f, w * 0.94f, w * 0.88f);
                cv.drawRoundRect(rect, w * 0.11f, w * 0.11f, stroke);
                path.reset();
                path.moveTo(w * 0.10f, w * 0.34f);
                path.lineTo(w * 0.10f, w * 0.21f);
                path.quadTo(w * 0.10f, w * 0.15f, w * 0.17f, w * 0.15f);
                path.lineTo(w * 0.36f, w * 0.15f);
                path.quadTo(w * 0.43f, w * 0.15f, w * 0.47f, w * 0.21f);
                path.lineTo(w * 0.53f, w * 0.30f);
                cv.drawPath(path, stroke);
                break;
            }
            case "file":
            case "doc": {
                path.reset();
                path.moveTo(w * 0.24f, w * 0.08f);
                path.lineTo(w * 0.60f, w * 0.08f);
                path.lineTo(w * 0.80f, w * 0.28f);
                path.lineTo(w * 0.80f, w * 0.92f);
                path.lineTo(w * 0.24f, w * 0.92f);
                path.close();
                cv.drawPath(path, stroke);
                path.reset();
                path.moveTo(w * 0.60f, w * 0.08f);
                path.lineTo(w * 0.60f, w * 0.28f);
                path.lineTo(w * 0.80f, w * 0.28f);
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.36f, w * 0.50f, w * 0.68f, w * 0.50f, stroke);
                cv.drawLine(w * 0.36f, w * 0.64f, w * 0.68f, w * 0.64f, stroke);
                cv.drawLine(w * 0.36f, w * 0.78f, w * 0.56f, w * 0.78f, stroke);
                break;
            }
            case "image": {
                rect.set(w * 0.10f, w * 0.16f, w * 0.90f, w * 0.84f);
                cv.drawRoundRect(rect, w * 0.14f, w * 0.14f, stroke);
                fill.setColor(stroke.getColor());
                cv.drawCircle(w * 0.34f, w * 0.36f, w * 0.07f, fill);
                path.reset();
                path.moveTo(w * 0.18f, w * 0.74f);
                path.lineTo(w * 0.40f, w * 0.50f);
                path.lineTo(w * 0.56f, w * 0.66f);
                path.lineTo(w * 0.68f, w * 0.56f);
                path.lineTo(w * 0.84f, w * 0.74f);
                cv.drawPath(path, stroke);
                break;
            }
            case "code": {
                path.reset();
                path.moveTo(w * 0.34f, w * 0.26f);
                path.lineTo(w * 0.10f, w * 0.50f);
                path.lineTo(w * 0.34f, w * 0.74f);
                cv.drawPath(path, stroke);
                path.reset();
                path.moveTo(w * 0.66f, w * 0.26f);
                path.lineTo(w * 0.90f, w * 0.50f);
                path.lineTo(w * 0.66f, w * 0.74f);
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.57f, w * 0.20f, w * 0.43f, w * 0.80f, stroke);
                break;
            }
            case "archive": {
                rect.set(w * 0.10f, w * 0.36f, w * 0.90f, w * 0.88f);
                cv.drawRoundRect(rect, w * 0.10f, w * 0.10f, stroke);
                cv.drawLine(w * 0.05f, w * 0.36f, w * 0.95f, w * 0.36f, stroke);
                path.reset();
                path.moveTo(w * 0.34f, w * 0.36f);
                path.lineTo(w * 0.34f, w * 0.22f);
                path.lineTo(w * 0.66f, w * 0.22f);
                path.lineTo(w * 0.66f, w * 0.36f);
                cv.drawPath(path, stroke);
                break;
            }
            case "audio": {
                cv.drawLine(w * 0.46f, w * 0.72f, w * 0.46f, w * 0.20f, stroke);
                path.reset();
                path.moveTo(w * 0.46f, w * 0.20f);
                path.quadTo(w * 0.74f, w * 0.24f, w * 0.78f, w * 0.44f);
                cv.drawPath(path, stroke);
                cv.drawCircle(w * 0.30f, w * 0.72f, w * 0.16f, stroke);
                break;
            }
            case "video": {
                rect.set(w * 0.08f, w * 0.20f, w * 0.92f, w * 0.80f);
                cv.drawRoundRect(rect, w * 0.14f, w * 0.14f, stroke);
                fill.setColor(stroke.getColor());
                path.reset();
                path.moveTo(w * 0.42f, w * 0.36f);
                path.lineTo(w * 0.68f, w * 0.50f);
                path.lineTo(w * 0.42f, w * 0.64f);
                path.close();
                cv.drawPath(path, fill);
                break;
            }
            case "apk": {
                rect.set(w * 0.10f, w * 0.10f, w * 0.90f, w * 0.90f);
                cv.drawRoundRect(rect, w * 0.20f, w * 0.20f, stroke);
                cv.drawLine(w * 0.50f, w * 0.28f, w * 0.50f, w * 0.60f, stroke);
                path.reset();
                path.moveTo(w * 0.36f, w * 0.48f);
                path.lineTo(w * 0.50f, w * 0.62f);
                path.lineTo(w * 0.64f, w * 0.48f);
                cv.drawPath(path, stroke);
                break;
            }
            case "back": {
                cv.drawLine(w * 0.82f, w * 0.50f, w * 0.20f, w * 0.50f, stroke);
                path.reset();
                path.moveTo(w * 0.44f, w * 0.26f);
                path.lineTo(w * 0.20f, w * 0.50f);
                path.lineTo(w * 0.44f, w * 0.74f);
                cv.drawPath(path, stroke);
                break;
            }
            case "chevron": {
                path.reset();
                path.moveTo(w * 0.38f, w * 0.22f);
                path.lineTo(w * 0.64f, w * 0.50f);
                path.lineTo(w * 0.38f, w * 0.78f);
                cv.drawPath(path, stroke);
                break;
            }
            case "refresh": {
                rect.set(w * 0.16f, w * 0.16f, w * 0.84f, w * 0.84f);
                cv.drawArc(rect, 40f, 280f, false, stroke);
                arrowHead(cv, c, c, w * 0.34f, 40f, w * 0.13f);
                break;
            }
            case "more": {
                fill.setColor(stroke.getColor());
                cv.drawCircle(c, w * 0.22f, w * 0.075f, fill);
                cv.drawCircle(c, w * 0.50f, w * 0.075f, fill);
                cv.drawCircle(c, w * 0.78f, w * 0.075f, fill);
                break;
            }
            case "dot": {
                fill.setColor(stroke.getColor());
                cv.drawCircle(c, c, w * 0.22f, fill);
                break;
            }
            case "plus": {
                cv.drawLine(w * 0.50f, w * 0.18f, w * 0.50f, w * 0.82f, stroke);
                cv.drawLine(w * 0.18f, w * 0.50f, w * 0.82f, w * 0.50f, stroke);
                break;
            }
            case "close": {
                cv.drawLine(w * 0.22f, w * 0.22f, w * 0.78f, w * 0.78f, stroke);
                cv.drawLine(w * 0.78f, w * 0.22f, w * 0.22f, w * 0.78f, stroke);
                break;
            }
            case "search": {
                cv.drawCircle(w * 0.44f, w * 0.44f, w * 0.30f, stroke);
                cv.drawLine(w * 0.66f, w * 0.66f, w * 0.88f, w * 0.88f, stroke);
                break;
            }
            case "check": {
                path.reset();
                path.moveTo(w * 0.16f, w * 0.54f);
                path.lineTo(w * 0.40f, w * 0.78f);
                path.lineTo(w * 0.86f, w * 0.24f);
                cv.drawPath(path, stroke);
                break;
            }
            case "trash": {
                cv.drawLine(w * 0.10f, w * 0.26f, w * 0.90f, w * 0.26f, stroke);
                path.reset();
                path.moveTo(w * 0.24f, w * 0.26f);
                path.lineTo(w * 0.29f, w * 0.88f);
                path.lineTo(w * 0.71f, w * 0.88f);
                path.lineTo(w * 0.76f, w * 0.26f);
                cv.drawPath(path, stroke);
                path.reset();
                path.moveTo(w * 0.38f, w * 0.26f);
                path.lineTo(w * 0.40f, w * 0.14f);
                path.lineTo(w * 0.60f, w * 0.14f);
                path.lineTo(w * 0.62f, w * 0.26f);
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.44f, w * 0.40f, w * 0.45f, w * 0.74f, stroke);
                cv.drawLine(w * 0.56f, w * 0.40f, w * 0.55f, w * 0.74f, stroke);
                break;
            }
            case "edit": {
                path.reset();
                path.moveTo(w * 0.20f, w * 0.80f);
                path.lineTo(w * 0.27f, w * 0.58f);
                path.lineTo(w * 0.68f, w * 0.16f);
                path.lineTo(w * 0.84f, w * 0.32f);
                path.lineTo(w * 0.43f, w * 0.74f);
                path.close();
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.60f, w * 0.24f, w * 0.76f, w * 0.40f, stroke);
                break;
            }
            case "share": {
                cv.drawCircle(w * 0.72f, w * 0.22f, w * 0.14f, stroke);
                cv.drawCircle(w * 0.26f, w * 0.50f, w * 0.14f, stroke);
                cv.drawCircle(w * 0.72f, w * 0.78f, w * 0.14f, stroke);
                cv.drawLine(w * 0.39f, w * 0.43f, w * 0.59f, w * 0.29f, stroke);
                cv.drawLine(w * 0.39f, w * 0.57f, w * 0.59f, w * 0.71f, stroke);
                break;
            }
            case "play": {
                fill.setColor(stroke.getColor());
                path.reset();
                path.moveTo(w * 0.28f, w * 0.16f);
                path.lineTo(w * 0.86f, w * 0.50f);
                path.lineTo(w * 0.28f, w * 0.84f);
                path.close();
                cv.drawPath(path, fill);
                break;
            }
            case "settings": {
                float[] ys = {w * 0.26f, w * 0.50f, w * 0.74f};
                float[] xs = {w * 0.34f, w * 0.68f, w * 0.44f};
                for (int i = 0; i < 3; i++) {
                    cv.drawLine(w * 0.10f, ys[i], w * 0.90f, ys[i], stroke);
                    cv.drawCircle(xs[i], ys[i], w * 0.11f, stroke);
                }
                break;
            }
            case "terminal": {
                rect.set(w * 0.06f, w * 0.14f, w * 0.94f, w * 0.86f);
                cv.drawRoundRect(rect, w * 0.14f, w * 0.14f, stroke);
                path.reset();
                path.moveTo(w * 0.26f, w * 0.36f);
                path.lineTo(w * 0.40f, w * 0.50f);
                path.lineTo(w * 0.26f, w * 0.64f);
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.50f, w * 0.64f, w * 0.72f, w * 0.64f, stroke);
                break;
            }
            case "download": {
                cv.drawLine(w * 0.50f, w * 0.12f, w * 0.50f, w * 0.56f, stroke);
                path.reset();
                path.moveTo(w * 0.32f, w * 0.40f);
                path.lineTo(w * 0.50f, w * 0.58f);
                path.lineTo(w * 0.68f, w * 0.40f);
                cv.drawPath(path, stroke);
                path.reset();
                path.moveTo(w * 0.14f, w * 0.64f);
                path.lineTo(w * 0.14f, w * 0.86f);
                path.lineTo(w * 0.86f, w * 0.86f);
                path.lineTo(w * 0.86f, w * 0.64f);
                cv.drawPath(path, stroke);
                break;
            }
            case "upload": {
                cv.drawLine(w * 0.50f, w * 0.60f, w * 0.50f, w * 0.16f, stroke);
                path.reset();
                path.moveTo(w * 0.32f, w * 0.34f);
                path.lineTo(w * 0.50f, w * 0.16f);
                path.lineTo(w * 0.68f, w * 0.34f);
                cv.drawPath(path, stroke);
                path.reset();
                path.moveTo(w * 0.14f, w * 0.64f);
                path.lineTo(w * 0.14f, w * 0.86f);
                path.lineTo(w * 0.86f, w * 0.86f);
                path.lineTo(w * 0.86f, w * 0.64f);
                cv.drawPath(path, stroke);
                break;
            }
            case "external": {
                path.reset();
                path.moveTo(w * 0.50f, w * 0.16f);
                path.lineTo(w * 0.84f, w * 0.16f);
                path.lineTo(w * 0.84f, w * 0.50f);
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.84f, w * 0.16f, w * 0.44f, w * 0.56f, stroke);
                path.reset();
                path.moveTo(w * 0.70f, w * 0.58f);
                path.lineTo(w * 0.70f, w * 0.86f);
                path.lineTo(w * 0.14f, w * 0.86f);
                path.lineTo(w * 0.14f, w * 0.30f);
                path.lineTo(w * 0.42f, w * 0.30f);
                cv.drawPath(path, stroke);
                break;
            }
            case "info": {
                cv.drawCircle(c, c, w * 0.40f, stroke);
                cv.drawLine(c, w * 0.44f, c, w * 0.70f, stroke);
                fill.setColor(stroke.getColor());
                cv.drawCircle(c, w * 0.29f, w * 0.055f, fill);
                break;
            }
            case "home": {
                path.reset();
                path.moveTo(w * 0.08f, w * 0.46f);
                path.lineTo(w * 0.50f, w * 0.12f);
                path.lineTo(w * 0.92f, w * 0.46f);
                cv.drawPath(path, stroke);
                path.reset();
                path.moveTo(w * 0.20f, w * 0.42f);
                path.lineTo(w * 0.20f, w * 0.88f);
                path.lineTo(w * 0.80f, w * 0.88f);
                path.lineTo(w * 0.80f, w * 0.42f);
                cv.drawPath(path, stroke);
                break;
            }
            case "sd": {
                path.reset();
                path.moveTo(w * 0.18f, w * 0.10f);
                path.lineTo(w * 0.60f, w * 0.10f);
                path.lineTo(w * 0.86f, w * 0.36f);
                path.lineTo(w * 0.86f, w * 0.90f);
                path.lineTo(w * 0.18f, w * 0.90f);
                path.close();
                cv.drawPath(path, stroke);
                cv.drawLine(w * 0.60f, w * 0.10f, w * 0.60f, w * 0.36f, stroke);
                cv.drawLine(w * 0.60f, w * 0.36f, w * 0.86f, w * 0.36f, stroke);
                break;
            }
            case "target": {
                cv.drawCircle(c, c, w * 0.40f, stroke);
                cv.drawCircle(c, c, w * 0.20f, stroke);
                fill.setColor(stroke.getColor());
                cv.drawCircle(c, c, w * 0.07f, fill);
                break;
            }
            case "sort": {
                cv.drawLine(w * 0.12f, w * 0.26f, w * 0.88f, w * 0.26f, stroke);
                cv.drawLine(w * 0.12f, w * 0.50f, w * 0.66f, w * 0.50f, stroke);
                cv.drawLine(w * 0.12f, w * 0.74f, w * 0.44f, w * 0.74f, stroke);
                break;
            }
            case "copy": {
                rect.set(w * 0.08f, w * 0.08f, w * 0.62f, w * 0.62f);
                cv.drawRoundRect(rect, w * 0.12f, w * 0.12f, stroke);
                rect.set(w * 0.38f, w * 0.38f, w * 0.92f, w * 0.92f);
                cv.drawRoundRect(rect, w * 0.12f, w * 0.12f, stroke);
                break;
            }
            case "eye": {
                path.reset();
                path.moveTo(w * 0.06f, w * 0.50f);
                path.quadTo(w * 0.50f, w * 0.10f, w * 0.94f, w * 0.50f);
                path.quadTo(w * 0.50f, w * 0.90f, w * 0.06f, w * 0.50f);
                path.close();
                cv.drawPath(path, stroke);
                cv.drawCircle(c, c, w * 0.13f, stroke);
                break;
            }
            case "globe": {
                cv.drawCircle(c, c, w * 0.40f, stroke);
                rect.set(w * 0.30f, w * 0.10f, w * 0.70f, w * 0.90f);
                cv.drawOval(rect, stroke);
                cv.drawLine(w * 0.10f, w * 0.50f, w * 0.90f, w * 0.50f, stroke);
                break;
            }
            case "arrow": {
                cv.drawLine(w * 0.12f, w * 0.50f, w * 0.82f, w * 0.50f, stroke);
                path.reset();
                path.moveTo(w * 0.60f, w * 0.26f);
                path.lineTo(w * 0.86f, w * 0.50f);
                path.lineTo(w * 0.60f, w * 0.74f);
                cv.drawPath(path, stroke);
                break;
            }
            case "save": {
                rect.set(w * 0.10f, w * 0.10f, w * 0.90f, w * 0.90f);
                cv.drawRoundRect(rect, w * 0.12f, w * 0.12f, stroke);
                rect.set(w * 0.32f, w * 0.10f, w * 0.68f, w * 0.40f);
                cv.drawRect(rect, stroke);
                rect.set(w * 0.26f, w * 0.56f, w * 0.74f, w * 0.90f);
                cv.drawRect(rect, stroke);
                break;
            }
            default: {
                path.reset();
                path.moveTo(w * 0.24f, w * 0.08f);
                path.lineTo(w * 0.60f, w * 0.08f);
                path.lineTo(w * 0.80f, w * 0.28f);
                path.lineTo(w * 0.80f, w * 0.92f);
                path.lineTo(w * 0.24f, w * 0.92f);
                path.close();
                cv.drawPath(path, stroke);
                break;
            }
        }
    }
}
