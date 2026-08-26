package io.taskpro;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import io.taskpro.md.MdButton;
import io.taskpro.md.MdCard;
import io.taskpro.md.MdDialog;
import io.taskpro.md.MdSnackbar;
import io.taskpro.md.MdTextField;
import io.taskpro.md.MdTheme;

public class ScriptPanel {
    private final Activity a;
    private final LinearLayout root;
    private final Runnable onRefresh;

    public ScriptPanel(Activity activity, LinearLayout rootView, Runnable refreshCallback) {
        this.a = activity;
        this.root = rootView;
        this.onRefresh = refreshCallback;
    }

    public void render() {
        root.removeAllViews();
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(UIHelper.dp(a, 16), UIHelper.dp(a, 8), UIHelper.dp(a, 16), UIHelper.dp(a, 16));
        ScrollView sc = new ScrollView(a);
        sc.addView(page);
        root.addView(sc, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout topRow = new LinearLayout(a);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = new TextView(a);
        hint.setText("脚本管理 (" + ScriptStore.list(a).size() + " 个)");
        hint.setTextColor(MdTheme.onSurfaceVariant(a));
        hint.setTextSize(13);
        topRow.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(topRow);

        List<ScriptStore.Script> scripts = ScriptStore.list(a);
        if (scripts.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText("暂无脚本\n点右下角 + 新建或从市场安装");
            empty.setTextColor(MdTheme.onSurfaceVariant(a));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UIHelper.dp(a, 40), 0, UIHelper.dp(a, 20));
            page.addView(empty);
        }
        for (int i = 0; i < scripts.size(); i++) {
            final ScriptStore.Script s = scripts.get(i);
            final String name = s.name;
            final boolean running = ScriptRunner.isRunning(name);
            MdCard card = new MdCard(a, MdCard.OUTLINED, false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = UIHelper.dp(a, 8);
            card.setLayoutParams(cp);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.VERTICAL);
            card.addView(row);
            TextView nameView = new TextView(a);
            nameView.setText(s.name + (running ? " ▶ 运行中..." : ""));
            nameView.setTextColor(running ? MdTheme.primary(a) : MdTheme.onSurface(a));
            nameView.setTextSize(14);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(nameView);
            TextView meta = new TextView(a);
            String typeLabel = s.type;
            if ("py".equals(typeLabel)) typeLabel = "Python";
            else if ("js".equals(typeLabel)) typeLabel = "JavaScript";
            else if ("sh".equals(typeLabel)) typeLabel = "Shell";
            meta.setText(typeLabel + "  |  上次修改: " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(s.mtime)));
            meta.setTextColor(MdTheme.onSurfaceVariant(a));
            meta.setTextSize(11);
            meta.setPadding(0, UIHelper.dp(a, 4), 0, 0);
            row.addView(meta);
            LinearLayout ops = new LinearLayout(a);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, UIHelper.dp(a, 6), 0, 0);
            row.addView(ops);
            final String fn = name;
            ops.addView(UIHelper.smallBtn(a, "运行", false, new Runnable() { public void run() { runScript(fn); } }));
            ops.addView(UIHelper.smallBtn(a, "编辑", false, new Runnable() { public void run() { editScript(fn); } }));
            ops.addView(UIHelper.smallBtn(a, "删除", true, new Runnable() { public void run() { deleteScript(fn); } }));
            page.addView(card);
        }
        TextView pad = new TextView(a);
        pad.setHeight(UIHelper.dp(a, 72));
        page.addView(pad);
    }

    private void runScript(final String name) {
        if (!RuntimeManager.isReady(a)) {
            MdSnackbar.show(root, "运行时未就绪, 请先完成解压");
            return;
        }
        final String content = ScriptStore.read(a, name);
        if (content.isEmpty()) { MdSnackbar.show(root, "脚本为空"); return; }
        MdSnackbar.show(root, "运行 " + name);
        String type = ScriptStore.typeOf(name);
        ScriptRunner.markRunning(name);
        render();
        final String cmd = buildScriptCommand(a, name, content, type);
        new Thread(new Runnable() {
            public void run() {
                if (a instanceof AdvActivity) {
                    ((AdvActivity)a).runShellCommand(RuntimeManager.buildCommand(a, cmd), name);
                }
            }
        }).start();
    }

    private void editScript(final String name) {
        final String content = ScriptStore.read(a, name);
        final MdDialog d = new MdDialog(a);
        d.title("编辑: " + name);
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        final EditText editor = new EditText(a);
        editor.setText(content);
        editor.setTextSize(12);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setMinLines(12);
        editor.setMaxLines(24);
        editor.setGravity(Gravity.TOP);
        form.addView(editor);
        d.content(form);
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("保存", new Runnable() {
            public void run() {
                ScriptStore.write(a, name, editor.getText().toString());
                d.dismiss();
                render();
                MdSnackbar.show(root, "已保存");
            }
        });
        d.show();
    }

    private void deleteScript(final String name) {
        final MdDialog d = new MdDialog(a);
        d.title("删除脚本");
        d.message("确定删除「" + name + "」吗?");
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("删除", new Runnable() {
            public void run() {
                ScriptStore.delete(a, name);
                d.dismiss();
                render();
                MdSnackbar.show(root, "已删除");
            }
        });
        d.show();
    }

    private static String buildScriptCommand(Context ctx, String name, String content, String type) {
        String scriptPath = new java.io.File(ScriptStore.dir(ctx), name).getAbsolutePath();
        if ("py".equals(type)) return RuntimeManager.pythonBin(ctx) + " '" + scriptPath + "'";
        if ("js".equals(type)) return RuntimeManager.nodeBin(ctx) + " '" + scriptPath + "'";
        String rewritten = RuntimeManager.rewriteScript(ctx, content);
        String target = scriptPath;
        if (!rewritten.equals(content)) {
            try {
                java.io.File d = new java.io.File(ctx.getFilesDir(), "tmp");
                d.mkdirs();
                java.io.File f = new java.io.File(d, "adv_" + name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(rewritten.getBytes("UTF-8"));
                fos.close();
                target = f.getAbsolutePath();
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        return "/system/bin/sh '" + target + "'";
    }

}
