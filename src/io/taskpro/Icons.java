package io.taskpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * 手绘矢量图标: 归一化 Path 渲染成按 density 高清的位图, 替代 emoji。
 * 所有图形按 0~1 归一化坐标手绘, 任意尺寸不模糊。
 */
public class Icons {
    public static final int FOLDER = 0; // 文件夹 (脚本库选择)
    public static final int DOC = 1;    // 放大镜 (开发文档)
    public static final int FILE = 2;   // 折角文件 (脚本文件)
    public static final int GEAR = 3;   // 齿轮 (高级模式)
    public static final int PLAY = 4;   // 播放三角 (执行)
    public static final int CHECK = 5;  // 对勾 (成功)
    public static final int CROSS = 6;  // 叉 (失败)
    public static final int DOWNLOAD = 7; // 下载箭头 (脚本市场)
    public static final int AI_AVATAR = 8; // AI 头像 (✨星形)

    public static Drawable make(Context c, int type, int color, int sizeDp) {
        float density = c.getResources().getDisplayMetrics().density;
        int px = Math.max(4, Math.round(sizeDp * density));
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeWidth(Math.max(1.5f, px / 13f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float u = px; // 归一化系数
        switch (type) {
            case FOLDER: {
                p.setStyle(Paint.Style.FILL);
                Path path = new Path();
                path.moveTo(0.15f * u, 0.30f * u);
                path.lineTo(0.44f * u, 0.30f * u);
                path.lineTo(0.53f * u, 0.41f * u);
                path.lineTo(0.85f * u, 0.41f * u);
                path.lineTo(0.85f * u, 0.70f * u);
                path.lineTo(0.15f * u, 0.70f * u);
                path.close();
                cv.drawPath(path, p);
                break;
            }
            case DOC: { // 放大镜
                p.setStyle(Paint.Style.STROKE);
                cv.drawCircle(0.42f * u, 0.42f * u, 0.22f * u, p);
                Path h = new Path();
                h.moveTo(0.57f * u, 0.57f * u);
                h.lineTo(0.80f * u, 0.80f * u);
                cv.drawPath(h, p);
                break;
            }
            case FILE: { // 折角文件
                p.setStyle(Paint.Style.STROKE);
                Path path = new Path();
                path.moveTo(0.30f * u, 0.14f * u);
                path.lineTo(0.62f * u, 0.14f * u);
                path.lineTo(0.76f * u, 0.28f * u);
                path.lineTo(0.76f * u, 0.86f * u);
                path.lineTo(0.30f * u, 0.86f * u);
                path.close();
                cv.drawPath(path, p);
                Path fold = new Path();
                fold.moveTo(0.62f * u, 0.14f * u);
                fold.lineTo(0.62f * u, 0.28f * u);
                fold.lineTo(0.76f * u, 0.28f * u);
                cv.drawPath(fold, p);
                Path l1 = new Path();
                l1.moveTo(0.40f * u, 0.48f * u);
                l1.lineTo(0.66f * u, 0.48f * u);
                Path l2 = new Path();
                l2.moveTo(0.40f * u, 0.62f * u);
                l2.lineTo(0.66f * u, 0.62f * u);
                cv.drawPath(l1, p);
                cv.drawPath(l2, p);
                break;
            }
            case GEAR: { // 齿轮: 外圆 + 8 齿 + 中心孔
                p.setStyle(Paint.Style.STROKE);
                cv.drawCircle(0.5f * u, 0.5f * u, 0.26f * u, p);
                Path teeth = new Path();
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                    teeth.moveTo((0.5f + 0.30f * cx) * u, (0.5f + 0.30f * sy) * u);
                    teeth.lineTo((0.5f + 0.42f * cx) * u, (0.5f + 0.42f * sy) * u);
                }
                cv.drawPath(teeth, p);
                cv.drawCircle(0.5f * u, 0.5f * u, 0.09f * u, p);
                break;
            }
            case PLAY: {
                p.setStyle(Paint.Style.FILL);
                Path path = new Path();
                path.moveTo(0.30f * u, 0.20f * u);
                path.lineTo(0.74f * u, 0.50f * u);
                path.lineTo(0.30f * u, 0.80f * u);
                path.close();
                cv.drawPath(path, p);
                break;
            }
            case CHECK: {
                p.setStyle(Paint.Style.STROKE);
                Path path = new Path();
                path.moveTo(0.20f * u, 0.52f * u);
                path.lineTo(0.40f * u, 0.72f * u);
                path.lineTo(0.80f * u, 0.30f * u);
                cv.drawPath(path, p);
                break;
            }
            case CROSS: {
                p.setStyle(Paint.Style.STROKE);
                Path a = new Path();
                a.moveTo(0.28f * u, 0.28f * u);
                a.lineTo(0.72f * u, 0.72f * u);
                Path b = new Path();
                b.moveTo(0.72f * u, 0.28f * u);
                b.lineTo(0.28f * u, 0.72f * u);
                cv.drawPath(a, p);
                cv.drawPath(b, p);
                break;
            }
            case DOWNLOAD: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, px / 13f));
                cv.drawLine(0.5f * u, 0.15f * u, 0.5f * u, 0.62f * u, p); // 箭头杆
                Path arrow = new Path();
                arrow.moveTo(0.30f * u, 0.46f * u);
                arrow.lineTo(0.50f * u, 0.66f * u);
                arrow.lineTo(0.70f * u, 0.46f * u);
                cv.drawPath(arrow, p);
                p.setStrokeWidth(Math.max(1.5f, px / 16f));
                cv.drawLine(0.18f * u, 0.78f * u, 0.82f * u, 0.78f * u, p); // 托盘
                break;
            }
            case AI_AVATAR: { // 四角星 (sparkle) ✨
                p.setStyle(Paint.Style.FILL);
                Path path = new Path();
                float cx = 0.5f * u, cy = 0.5f * u;
                float r1 = 0.40f * u; // 外径
                float r2 = 0.08f * u; // 内径
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8 - Math.PI / 2;
                    float r = (i % 2 == 0) ? r1 : r2;
                    if (i == 0) path.moveTo(cx + r * (float)Math.cos(a), cy + r * (float)Math.sin(a));
                    else path.lineTo(cx + r * (float)Math.cos(a), cy + r * (float)Math.sin(a));
                }
                path.close();
                cv.drawPath(path, p);
                break;
            }
        }
        Drawable d = new BitmapDrawable(c.getResources(), bmp);
        d.setBounds(0, 0, px, px);
        return d;
    }
}
