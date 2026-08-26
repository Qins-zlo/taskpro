package io.taskpro;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.taskpro.md.MdTheme;

/**
 * 轻量 Markdown 渲染视图。
 * 支持: #/##/### 标题、- 无序列表、1. 有序列表、``` 代码块、
 *       > 引用、--- 分隔线、**加粗**、`行内代码`、普通段落。
 * 用于 App 内置脚本开发文档 (ScriptDoc.DOC) 单页面展示。
 */
public class ScriptDocView extends LinearLayout {

    public ScriptDocView(Context ctx, String markdown) {
        super(ctx);
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(10), dp(18), dp(24));
        render(ctx, markdown);
    }

    private void render(Context ctx, String md) {
        if (md == null) return;
        String[] lines = md.split("\n");
        StringBuilder codeBuf = null;
        String codeLang = "";
        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            String trim = line.trim();
            // 代码块开关
            if (trim.startsWith("```")) {
                if (codeBuf == null) {
                    codeBuf = new StringBuilder();
                    codeLang = trim.length() > 3 ? trim.substring(3).trim() : "";
                } else {
                    addCodeBlock(ctx, codeBuf.toString(), codeLang);
                    codeBuf = null;
                    codeLang = "";
                }
                continue;
            }
            if (codeBuf != null) {
                codeBuf.append(line).append("\n");
                continue;
            }
            if (trim.isEmpty()) { addSpace(dp(4)); continue; }
            if (trim.matches("(-{3,}|\\*{3,}|_{3,})")) { addDivider(ctx); continue; }
            if (trim.startsWith("### ")) { addHeading(ctx, trim.substring(4), 3); continue; }
            if (trim.startsWith("## ")) { addHeading(ctx, trim.substring(3), 2); continue; }
            if (trim.startsWith("# ")) { addHeading(ctx, trim.substring(2), 1); continue; }
            if (trim.startsWith("> ")) { addQuote(ctx, trim.substring(2)); continue; }
            if (trim.matches("^\\d+\\.\\s.*")) {
                addListItem(ctx, trim.replaceFirst("^\\d+\\.\\s+", ""), true);
                continue;
            }
            if (trim.startsWith("- ") || trim.startsWith("* ")) {
                addListItem(ctx, trim.substring(2), false);
                continue;
            }
            addParagraph(ctx, line);
        }
        if (codeBuf != null) addCodeBlock(ctx, codeBuf.toString(), codeLang);
    }

    // ---------- 行内解析: **粗体** / `行内代码` ----------
    private CharSequence inline(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\*\\*[^*]+\\*\\*|`[^`]+`)");
        java.util.regex.Matcher m = p.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(text.substring(last, m.start()));
            String tok = m.group();
            if (tok.startsWith("**")) {
                String content = tok.substring(2, tok.length() - 2);
                sb.append(content);
                sb.setSpan(new StyleSpan(Typeface.BOLD),
                        sb.length() - content.length(), sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                String content = tok.substring(1, tok.length() - 1);
                int start = sb.length();
                sb.append(content);
                sb.setSpan(new BackgroundColorSpan(0x22000000), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF4A90D9), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb;
    }

    // ---------- 标题 ----------
    private void addHeading(Context ctx, String text, int level) {
        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        tv.setTextColor(MdTheme.onSurface(ctx));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (level == 1) {
            tv.setTextSize(22);
            tv.setTextColor(MdTheme.primary(ctx));
            lp.topMargin = dp(12);
            lp.bottomMargin = dp(8);
        } else if (level == 2) {
            tv.setTextSize(18);
            tv.setTextColor(MdTheme.primary(ctx));
            lp.topMargin = dp(18);
            lp.bottomMargin = dp(6);
        } else {
            tv.setTextSize(15);
            lp.topMargin = dp(14);
            lp.bottomMargin = dp(4);
        }
        addView(tv, lp);
    }

    // ---------- 代码块 ----------
    private void addCodeBlock(Context ctx, String code, String lang) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(MdTheme.isDark(ctx) ? 0xFF14181C : 0xFFF2F4F7);
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(6);
        blp.bottomMargin = dp(6);
        addView(box, blp);

        if (lang != null && !lang.isEmpty()) {
            TextView langTv = new TextView(ctx);
            langTv.setText(lang.toUpperCase());
            langTv.setTextSize(9);
            langTv.setTextColor(MdTheme.primary(ctx));
            langTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            langTv.setGravity(Gravity.END);
            box.addView(langTv);
        }

        TextView codeTv = new TextView(ctx);
        codeTv.setText(code);
        codeTv.setTextSize(12);
        codeTv.setTypeface(Typeface.MONOSPACE);
        codeTv.setTextColor(MdTheme.isDark(ctx) ? 0xFFE6E1E8 : 0xFF2C2C2E);
        codeTv.setLineSpacing(dp(2), 1f);
        box.addView(codeTv);
    }

    // ---------- 引用 ----------
    private void addQuote(Context ctx, String text) {
        LinearLayout q = new LinearLayout(ctx);
        q.setOrientation(HORIZONTAL);
        q.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(2);
        qlp.bottomMargin = dp(2);
        addView(q, qlp);

        View bar = new View(ctx);
        bar.setBackgroundColor(MdTheme.primary(ctx));
        q.addView(bar, new LinearLayout.LayoutParams(dp(3), -2));

        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        tv.setTextSize(12);
        tv.setTextColor(MdTheme.onSurfaceVariant(ctx));
        tv.setPadding(dp(8), 0, 0, 0);
        tv.setLineSpacing(dp(1), 1f);
        q.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
    }

    // ---------- 列表项 ----------
    private void addListItem(Context ctx, String text, boolean ordered) {
        TextView tv = new TextView(ctx);
        tv.setText(inline((ordered ? "" : "•  ") + text));
        tv.setTextSize(13);
        tv.setTextColor(MdTheme.onSurface(ctx));
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(ordered ? 0 : 6), 0, 0, 0);
        tv.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(1);
        lp.bottomMargin = dp(1);
        if (!ordered) lp.leftMargin = dp(2);
        addView(tv, lp);
    }

    // ---------- 段落 ----------
    private void addParagraph(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(inline(text));
        tv.setTextSize(13);
        tv.setTextColor(MdTheme.onSurface(ctx));
        tv.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        addView(tv, lp);
    }

    // ---------- 分隔线 ----------
    private void addDivider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(MdTheme.outlineVariant(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        addView(v, lp);
    }

    // ---------- 空白 ----------
    private void addSpace(int h) {
        View v = new View(getContext());
        addView(v, new LinearLayout.LayoutParams(-1, h));
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}