package com.mylauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

public class ColorPicker extends View {
    public interface Listener { void onColor(int c); }

    float h = 0, s = 1, v = 1, side = 100;
    int a = 255, mode = 0;
    Listener l;
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ColorPicker(Context c) { super(c); }

    public void setListener(Listener x) { l = x; }

    public void setColor(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        h = hsv[0];
        s = hsv[1];
        v = hsv[2];
        a = Color.alpha(c);
        invalidate();
    }

    public int getColor() { return Color.HSVToColor(a, new float[]{h, s, v}); }

    float dp(float x) { return x * getResources().getDisplayMetrics().density; }

    float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        float sd = w - dp(46);
        setMeasuredDimension(w, (int) (sd + dp(18) + dp(28) + dp(4)));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        side = w - dp(46);
        float hx = side + dp(14);
        float ay = side + dp(18);
        int hueCol = Color.HSVToColor(new float[]{h, 1f, 1f});

        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, side, 0, Color.WHITE, hueCol, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, side, side, dp(8), dp(8), p);
        p.setShader(new LinearGradient(0, 0, 0, side, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(0, 0, side, side, dp(8), dp(8), p);

        int[] cols = new int[7];
        for (int i = 0; i < 7; i++) cols[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        p.setShader(new LinearGradient(0, 0, 0, side, cols, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(hx, 0, w, side, dp(8), dp(8), p);

        p.setShader(null);
        float cell = dp(7);
        c.save();
        c.clipRect(0, ay, w, ay + dp(28));
        for (int i = 0; i * cell < w; i++) {
            for (int j = 0; j < 4; j++) {
                p.setColor(((i + j) % 2 == 0) ? 0xFFFFFFFF : 0xFFBBBBBB);
                c.drawRect(i * cell, ay + j * cell, (i + 1) * cell, ay + (j + 1) * cell, p);
            }
        }
        int solid = Color.HSVToColor(255, new float[]{h, s, v});
        p.setShader(new LinearGradient(0, 0, w, 0, solid & 0x00FFFFFF, solid, Shader.TileMode.CLAMP));
        c.drawRect(0, ay, w, ay + dp(28), p);
        c.restore();
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        float cx = s * side, cy = (1 - v) * side;
        p.setStrokeWidth(dp(3));
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, dp(9), p);
        p.setStrokeWidth(dp(1));
        p.setColor(0xFF000000);
        c.drawCircle(cx, cy, dp(10.5f), p);

        float hy = h / 360f * side;
        p.setStrokeWidth(dp(4));
        p.setColor(0xFFFFFFFF);
        c.drawLine(hx, hy, w, hy, p);
        p.setStrokeWidth(dp(1));
        p.setColor(0xFF000000);
        c.drawLine(hx, hy - dp(2.5f), w, hy - dp(2.5f), p);
        c.drawLine(hx, hy + dp(2.5f), w, hy + dp(2.5f), p);

        float ax = a / 255f * w;
        p.setStrokeWidth(dp(4));
        p.setColor(0xFFFFFFFF);
        c.drawLine(ax, ay, ax, ay + dp(28), p);
        p.setStrokeWidth(dp(1));
        p.setColor(0xFF000000);
        c.drawLine(ax - dp(2.5f), ay, ax - dp(2.5f), ay + dp(28), p);
        c.drawLine(ax + dp(2.5f), ay, ax + dp(2.5f), ay + dp(28), p);
        p.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = getWidth();
        float x = e.getX(), y = e.getY();
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (y > side + dp(8)) mode = 2;
            else if (x > side + dp(6)) mode = 1;
            else mode = 0;
        }
        if (mode == 0) {
            s = clamp(x / side);
            v = 1 - clamp(y / side);
        } else if (mode == 1) {
            h = clamp(y / side) * 359.99f;
        } else {
            a = (int) (clamp(x / w) * 255);
        }
        invalidate();
        if (l != null) l.onColor(getColor());
        return true;
    }
}
