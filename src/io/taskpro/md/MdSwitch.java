package io.taskpro.md;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Material 3 风格开关: 圆角轨道 + 圆形滑块, 带切换动画。
 */
public class MdSwitch extends View {
    private boolean checked = false;
    private float anim = 0f; // 0..1 滑块位置
    private Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF trackRect = new RectF();
    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener {
        void onChanged(boolean checked);
    }

    public MdSwitch(Context c) {
        super(c);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                setChecked(!checked);
            }
        });
    }

    public boolean isChecked() { return checked; }

    public void setChecked(boolean b) {
        if (checked == b) return;
        checked = b;
        anim = b ? 1f : 0f;
        invalidate();
        if (listener != null) listener.onChanged(checked);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) { listener = l; }

    @Override
    protected void onMeasure(int ws, int hs) {
        int h = dp(32), w = dp(52);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Context c = getContext();
        float h = getHeight();
        float w = getWidth();
        float radius = h / 2f;
        trackRect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        int trackColor = checked
                ? MdTheme.primary(c)
                : MdTheme.surfaceContainerHigh(c);
        if (!checked) {
            trackColor = blend(MdTheme.surfaceContainerHigh(c), MdTheme.onSurface(c), 0.25f);
        }
        track.setColor(trackColor);
        canvas.drawRoundRect(trackRect, radius, radius, track);
        // 滑块
        float cx = checked ? w - radius : radius;
        int thumbColor = checked ? MdTheme.onPrimary(c) : MdTheme.onSurfaceVariant(c);
        if (!checked) {
            thumbColor = blend(MdTheme.surfaceContainerHigh(c), MdTheme.onSurface(c), 0.6f);
        }
        thumb.setColor(thumbColor);
        canvas.drawCircle(cx, h / 2f, radius - dp(5), thumb);
    }

    static int blend(int c1, int c2, float ratio) {
        int r = (int) (android.graphics.Color.red(c1) * (1 - ratio)
                + android.graphics.Color.red(c2) * ratio);
        int g = (int) (android.graphics.Color.green(c1) * (1 - ratio)
                + android.graphics.Color.green(c2) * ratio);
        int b = (int) (android.graphics.Color.blue(c1) * (1 - ratio)
                + android.graphics.Color.blue(c2) * ratio);
        return android.graphics.Color.rgb(r, g, b);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
