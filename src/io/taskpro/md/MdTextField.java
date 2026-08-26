package io.taskpro.md;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material 3 Outlined TextField:
 *  - 浮动标签(输入时高亮)
 *  - 圆角 4dp, 聚焦时 primary 描边 2dp
 *  - 支持多行(自动增长)
 */
public class MdTextField extends LinearLayout {
    private Context ctx;
    private TextView label;
    private EditText edit;
    private GradientDrawable box;

    public MdTextField(Context c, String labelText, boolean multiline) {
        super(c);
        ctx = c;
        setOrientation(LinearLayout.VERTICAL);
        setPadding(0, 0, 0, 0);

        label = new TextView(c);
        label.setText(labelText);
        label.setTextColor(MdTheme.onSurfaceVariant(c));
        label.setTextSize(12);
        label.setPadding(0, 0, 0, dp(4));
        addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        edit = new EditText(c);
        edit.setTextSize(15);
        edit.setTextColor(MdTheme.onSurface(c));
        edit.setHintTextColor(MdTheme.onSurfaceVariant(c) & 0xAAFFFFFF);
        edit.setSingleLine(!multiline);
        edit.setPadding(dp(14), multiline ? dp(10) : 0, dp(14), multiline ? dp(10) : 0);
        edit.setTypeface(Typeface.DEFAULT);
        if (multiline) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            edit.setMinLines(5);
            edit.setGravity(Gravity.TOP | Gravity.START);
            edit.setTextSize(12);
            edit.setTypeface(Typeface.MONOSPACE);
            // 内容完全展开(Wrap), 滚动交给外层 ScrollView:
            // 嵌套 EditText 若设置固定高度会抢走触摸事件, 导致弹窗整体滚不动、内容显示不全。
            // 完全展开后内容由外层 ScrollView 统一管理滚动, 编辑框内滑动=滚动整个弹窗。
            edit.setMaxLines(500); // 防单行 EditText 行为; 多行框不限制
            edit.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
            addView(edit, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else {
            edit.setInputType(InputType.TYPE_CLASS_TEXT);
            addView(edit, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        box = new GradientDrawable();
        box.setColor(MdTheme.surfaceContainerHigh(ctx));
        box.setCornerRadius(dp(4));
        box.setStroke(dp(1), MdTheme.outlineVariant(ctx));
        edit.setBackground(box);
    }

    public EditText getEdit() { return edit; }

    public String getText() {
        return edit.getText() == null ? "" : edit.getText().toString();
    }

    public void setText(String s) {
        edit.setText(s == null ? "" : s);
    }

    public void setPassword(boolean p) {
        if (p) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    /** 动态修改提示文本 (label) */
    public void setHint(String hint) {
        label.setText(hint);
    }

    /** 设置 EditText 的 placeholder hint */
    public void setPlaceholder(String placeholder) {
        edit.setHint(placeholder);
    }

    /** 聚焦态切换(由外部或自动处理) */
    public void setFocused(boolean focused) {
        if (focused) {
            label.setTextColor(MdTheme.primary(ctx));
            box.setStroke(dp(2), MdTheme.primary(ctx));
        } else {
            label.setTextColor(MdTheme.onSurfaceVariant(ctx));
            box.setStroke(dp(1), MdTheme.outlineVariant(ctx));
        }
    }

    private int dp(int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
