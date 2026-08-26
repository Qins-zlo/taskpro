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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.taskpro.md.MdButton;
import io.taskpro.md.MdCard;
import io.taskpro.md.MdDialog;
import io.taskpro.md.MdSnackbar;
import io.taskpro.md.MdTextField;
import io.taskpro.md.MdTheme;

public class TaskPanel {
    private final Activity a;
    private final LinearLayout root;
    private final Runnable onTabSwitch;
    private final java.util.List<JSONObject> advTasks;

    public TaskPanel(Activity activity, LinearLayout rootView, java.util.List<JSONObject> tasks, Runnable switchCallback) {
        this.a = activity;
        this.root = rootView;
        this.advTasks = tasks;
        this.onTabSwitch = switchCallback;
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
        hint.setText("\u5b9a\u65f6\u4efb\u52a1 (cron \u8868\u8fbe\u5f0f\u8c03\u5ea6)");
        hint.setTextColor(MdTheme.onSurfaceVariant(a));
        hint.setTextSize(13);
        topRow.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(topRow);

        MdCard rt = new MdCard(a, MdCard.OUTLINED, false);
        LinearLayout.LayoutParams rtp = new LinearLayout.LayoutParams(-1, -2);
        rtp.topMargin = UIHelper.dp(a, 4);
        rtp.bottomMargin = UIHelper.dp(a, 8);
        rt.setLayoutParams(rtp);
        TextView rtText = new TextView(a);
        if (RuntimeManager.isReady(a)) {
            rtText.setText("\u25cf \u8fd0\u884c\u65f6\u5df2\u5c31\u7eea (python3 + busybox)");
            rtText.setTextColor(MdTheme.primary(a));
        } else {
            rtText.setText("\u25cb \u8fd0\u884c\u65f6\u672a\u5c31\u7eea, \u6253\u5f00\u7ec8\u7aef\u6216\u91cd\u542f App \u4f1a\u81ea\u52a8\u89e3\u538b");
            rtText.setTextColor(MdTheme.onSurfaceVariant(a));
        }
        rtText.setTextSize(12);
        rtText.setPadding(UIHelper.dp(a, 12), UIHelper.dp(a, 10), UIHelper.dp(a, 12), UIHelper.dp(a, 10));
        rt.addView(rtText);
        page.addView(rt);

        if (advTasks.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText("\u6682\u65e0\u9ad8\u7ea7\u4efb\u52a1\n\u70b9\u53f3\u4e0b\u89d2 \uff0b \u6dfb\u52a0");
            empty.setTextColor(MdTheme.onSurfaceVariant(a));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UIHelper.dp(a, 40), 0, UIHelper.dp(a, 20));
            page.addView(empty);
        }
        for (int i = 0; i < advTasks.size(); i++) {
            final int idx = i;
            final JSONObject o = advTasks.get(i);
            MdCard card = new MdCard(a, MdCard.OUTLINED, false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = UIHelper.dp(a, 8);
            card.setLayoutParams(cp);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.VERTICAL);
            card.addView(row);
            TextView name = new TextView(a);
            name.setText(o.optString("name", "?"));
            name.setTextColor(MdTheme.onSurface(a));
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);
            TextView meta = new TextView(a);
            String cron = o.optString("cron", "");
            String script = o.optString("script", "");
            boolean en = o.optBoolean("enabled", true);
            meta.setText((en ? "\u25cf" : "\u25cb") + " cron: " + cron + "  |  \u811a\u672c: " + script);
            meta.setTextColor(en ? MdTheme.primary(a) : MdTheme.onSurfaceVariant(a));
            meta.setTextSize(11);
            meta.setPadding(0, UIHelper.dp(a, 4), 0, 0);
            row.addView(meta);
            LinearLayout ops = new LinearLayout(a);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, UIHelper.dp(a, 6), 0, 0);
            row.addView(ops);
            ops.addView(UIHelper.smallBtn(a, "\u7acb\u5373\u6267\u884c", false, new Runnable() { public void run() { runAdvTask(idx); } }));
            ops.addView(UIHelper.smallBtn(a, "\u7f16\u8f91", false, new Runnable() { public void run() { editAdvTask(idx); } }));
            ops.addView(UIHelper.smallBtn(a, "\u5220\u9664", true, new Runnable() { public void run() { deleteTask(idx); } }));
            page.addView(card);
        }
        TextView pad = new TextView(a);
        pad.setHeight(UIHelper.dp(a, 72));
        page.addView(pad);
    }

    private void runAdvTask(int idx) {
        JSONObject o = advTasks.get(idx);
        if (o == null) return;
        String scriptName = o.optString("script", "");
        String name = o.optString("name", "");
        if (scriptName.isEmpty()) { MdSnackbar.show(root, "\u672a\u914d\u7f6e\u811a\u672c"); return; }
        String content = ScriptStore.read(a, scriptName);
        if (content.isEmpty()) { MdSnackbar.show(root, "\u811a\u672c\u4e0d\u5b58\u5728: " + scriptName); return; }
        String type = ScriptStore.typeOf(scriptName);
        MdSnackbar.show(root, "\u6267\u884c " + name);
        ScriptRunner.markRunning(name);
        try { if (a instanceof AdvActivity) ((AdvActivity)a).refreshScripts(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        final String cmd = buildScriptCommand(a, scriptName, content, type);
        new Thread(new Runnable() {
            public void run() {
                if (a instanceof AdvActivity) {
                    ((AdvActivity)a).runShellCommand(RuntimeManager.buildCommand(a, cmd), name);
                }
            }
        }).start();
    }

    private void editAdvTask(final int idx) {
        final JSONObject o = advTasks.get(idx);
        if (o == null) return;
        final MdDialog d = new MdDialog(a);
        d.title("\u7f16\u8f91\u4efb\u52a1");
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        final MdTextField nameF = new MdTextField(a, "\u4efb\u52a1\u540d", false);
        nameF.setText(o.optString("name", ""));
        form.addView(nameF);
        final MdTextField cronF = new MdTextField(a, "cron \u8868\u8fbe\u5f0f (\u5982 */5 * * * *)", false);
        cronF.setText(o.optString("cron", ""));
        form.addView(cronF);
        final MdTextField scriptF = new MdTextField(a, "\u811a\u672c\u6587\u4ef6\u540d", false);
        scriptF.setText(o.optString("script", ""));
        form.addView(scriptF);
        d.content(form);
        d.action("\u53d6\u6d88", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("\u4fdd\u5b58", new Runnable() {
            public void run() {
                try {
                    o.put("name", nameF.getText());
                    o.put("cron", cronF.getText());
                    o.put("script", scriptF.getText());
                    saveAdvTasks();
                    d.dismiss();
                    render();
                    MdSnackbar.show(root, "\u5df2\u4fdd\u5b58");
                } catch (Exception e) { MdSnackbar.show(root, "\u4fdd\u5b58\u5931\u8d25: " + e.getMessage()); }
            }
        });
        d.show();
    }

    private void deleteTask(final int idx) {
        final MdDialog d = new MdDialog(a);
        d.title("\u5220\u9664\u4efb\u52a1");
        d.message("\u786e\u5b9a\u5220\u9664\u300c" + advTasks.get(idx).optString("name") + "\u300d\u5417?");
        d.action("\u53d6\u6d88", new Runnable() { public void run() { d.dismiss(); } });
        d.actionPrimary("\u5220\u9664", new Runnable() {
            public void run() {
                advTasks.remove(idx);
                saveAdvTasks();
                d.dismiss();
                render();
            }
        });
        d.show();
    }

    private void saveAdvTasks() {
        android.content.SharedPreferences sp = a.getSharedPreferences("adv_tasks", android.content.Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (JSONObject o : advTasks) arr.put(o);
        sp.edit().putString("tasks", arr.toString()).apply();
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
