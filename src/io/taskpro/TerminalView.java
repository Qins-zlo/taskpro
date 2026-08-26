package io.taskpro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.util.AttributeSet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 沉浸式终端视图:
 *  - 纯黑背景 + 等宽字体 + 绿色提示符 + 闪烁块状光标
 *  - 执行命令(通过 Runtime.exec, 不依赖 PTY)
 *  - 流式显示 stdout/stderr
 *  - 软键盘直接在终端输入 (不再需要外部输入框)
 *  - 会话状态: 持久化工作目录 (cd 生效), 命令历史
 *  - 触摸滚动查看历史输出
 *  - ANSI 16 色渲染 (转义序列)
 */
public class TerminalView extends View {
    private static final String HISTORY_FILE = "term_history.txt";
    private static final int MAX_HISTORY = 500;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint promptPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cursorPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final List<String> lines = new ArrayList<String>();
    private final StringBuilder current = new StringBuilder();
    private final List<Integer> lineColors = new ArrayList<Integer>(); // 每行颜色

    private Process process;
    private volatile boolean running = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> history = new ArrayList<String>();
    private int historyIdx = -1;
    private boolean cursorVisible = true;
    private final Runnable cursorBlink = new Runnable() {
        public void run() {
            cursorVisible = !cursorVisible;
            postInvalidate();
            ui.postDelayed(this, 530);
        }
    };

    // 会话工作目录 (持久化, 支持 cd)
    private String cwd = null;
    // 滚动偏移 (行数, 0 = 底部)
    private int scrollOffset = 0;
    // 触摸滚动
    private float lastTouchY = 0;
    private static final int MAX_LINES = 2000;
    // 字体尺寸
    private float fontSize = 0;
    private float lineHeight = 0;

    // ANSI 16 色表
    private static final int[] ANSI_COLORS = {
        0xFF000000, // 0 black
        0xFFCC0000, // 1 red
        0xFF4E9A06, // 2 green
        0xFFC4A000, // 3 yellow
        0xFF3465A4, // 4 blue
        0xFF75507B, // 5 magenta
        0xFF06989A, // 6 cyan
        0xFFD3D7CF, // 7 white
        0xFF555753, // 8 bright black
        0xFFEF2929, // 9 bright red
        0xFF8AE234, // 10 bright green
        0xFFFCE94F, // 11 bright yellow
        0xFF729FCF, // 12 bright blue
        0xFFAD7FA8, // 13 bright magenta
        0xFF34E2E2, // 14 bright cyan
        0xFFEEEEEC, // 15 bright white
    };

    public TerminalView(Context context) {
        super(context);
        init();
    }
    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private float dp(int v) {
        return getResources().getDisplayMetrics().density * v;
    }

    private void init() {
        if (cwd == null) cwd = getContext().getFilesDir().getAbsolutePath();
        fontSize = dp(13);
        // lineHeight 从 fontMetrics 推导, 与正文排版同源
        textPaint.setTextSize(fontSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);

        textPaint.setColor(Color.parseColor("#E6E6E6"));
        textPaint.setTextSize(fontSize);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setLetterSpacing(0.03f);

        promptPaint.set(textPaint);
        promptPaint.setColor(Color.parseColor("#4CAF50"));

        cursorPaint.set(textPaint);
        cursorPaint.setColor(Color.parseColor("#FFFFFF"));

        bgPaint.setColor(Color.parseColor("#000000"));
        setBackgroundColor(bgPaint.getColor());
        setFocusable(true);
        setFocusableInTouchMode(true);
        // 点击显示键盘 — 通过 onTouchEvent 处理, 不依赖 OnClickListener
        // 启动光标闪烁
        ui.postDelayed(cursorBlink, 530);
        // 欢迎信息
        appendLine("TaskPro 终端");
        appendLine("");
        appendLine("输入命令回车执行（python3 / sh / busybox）");
        appendLine("上滑查看历史 · cd 切换目录");
        appendLine("");
        appendPrompt();
    }

    private void appendPrompt() {
        // Termux 风格: 绿色 ~ 白色 $ 空格
        String shortCwd = cwd.equals(getContext().getFilesDir().getAbsolutePath()) ? "~" : cwd.equals("/") ? "/" : cwd;
        if (shortCwd.equals(getContext().getFilesDir().getAbsolutePath())) shortCwd = "~";
        // 用 ~ 替代 home 路径
        String home = getContext().getFilesDir().getAbsolutePath();
        if (cwd.startsWith(home)) {
            shortCwd = "~" + cwd.substring(home.length());
        }
        appendRaw("\u001b[32m" + shortCwd + "\u001b[0m $ ");
    }

    /** 解析 ANSI 转义序列, 提取纯文本和颜色 */
    private String stripAnsi(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
    }

    /** 获取 ANSI 序列中的颜色值 (只处理前景色 30-37 / 90-97) */
    private int parseAnsiFg(String seq) {
        try {
            String num = seq.replaceAll("[^0-9;]", "");
            if (num.isEmpty()) return -1;
            String[] parts = num.split(";");
            for (String p : parts) {
                int v = Integer.parseInt(p);
                if (v >= 30 && v <= 37) return ANSI_COLORS[v - 30];
                if (v >= 90 && v <= 97) return ANSI_COLORS[v - 90 + 8];
                if (v == 0) return -1;  // reset
                if (v == 1) continue;   // bold — skip for now
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return -1;
    }

    private void appendRaw(String s) {
        // 简化: 直接剥离 ANSI 存文本, 颜色由 lineColors 控制
        String clean = stripAnsi(s);
        int fgColor = parseAnsiFg(s);
        for (char c : clean.toCharArray()) {
            if (c == '\n') {
                lines.add(current.toString());
                lineColors.add(fgColor);
                current.setLength(0);
                trimLines();
            } else {
                current.append(c);
            }
        }
        postInvalidate();
    }

    private void trimLines() {
        while (lines.size() > MAX_LINES) lines.remove(0);
    }

    private void appendLine(String s) {
        appendRaw(s + "\n");
    }

    /** 复制全部输出 (含当前未换行的内容) */
    public String getFullText() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        if (current.length() > 0) sb.append(current);
        return sb.toString();
    }

    /** 输入字符(软键盘/硬件键) */
    public void inputChar(char c) {
        if (c == '\n') {
            String fullLine = current.toString();
            // 从 fullLine 中提取实际命令: 去掉提示符前缀 (如 "~ $ " 或 "~/path $ ")
            String cmdLine = "";
            int dollarIdx = fullLine.lastIndexOf("$ ");
            if (dollarIdx >= 0) {
                cmdLine = fullLine.substring(dollarIdx + 2).trim();
            } else {
                cmdLine = fullLine.trim();
            }
            lines.add(fullLine); // 保存含提示符的完整行
            current.setLength(0);
            trimLines();
            scrollOffset = 0;
            if (!cmdLine.isEmpty()) {
                history.add(cmdLine);
                historyIdx = history.size();
                execute(cmdLine);
            } else {
                appendPrompt();
            }
            postInvalidate();
        } else if (c == 0x7f || c == '\b') { // backspace
            if (current.length() > 0) current.deleteCharAt(current.length() - 1);
            postInvalidate();
        } else if (c >= 32) {
            current.append(c);
            postInvalidate();
        }
    }

    /** 历史命令: -1=上一个 1=下一个 */
    public void historyNav(int dir) {
        if (history.isEmpty()) return;
        historyIdx += dir;
        if (historyIdx < 0) historyIdx = 0;
        if (historyIdx > history.size()) historyIdx = history.size();
        // 保留提示符前缀, 只替换命令部分
        String promptPrefix = "";
        int dollarIdx = current.indexOf("$ ");
        if (dollarIdx >= 0) {
            promptPrefix = current.substring(0, dollarIdx + 2); // 保留 "~ $ "
        } else {
            promptPrefix = "~ $ "; // 兜底
        }
        current.setLength(0);
        current.append(promptPrefix);
        if (historyIdx < history.size()) current.append(history.get(historyIdx));
        postInvalidate();
    }

    /** 执行命令 */
    public void execute(String cmd) {
        // 注意: 输入行已在 current 中显示, 不重复 appendLine("$ " + cmd)
        // 否则会显示两次: 一次来自提示符+输入, 一次来自 appendLine
        if (cmd.equals("clear") || cmd.equals("cls")) {
            lines.clear();
            current.setLength(0);
            scrollOffset = 0;
            appendPrompt();
            postInvalidate();
            return;
        }
        if (cmd.equals("exit")) {
            appendLine("终端会话保持打开, 输入 clear 清屏");
            appendPrompt();
            postInvalidate();
            return;
        }
        // 处理 cd (内置, 持久化工作目录)
        if (cmd.startsWith("cd ")) {
            String target = cmd.substring(3).trim();
            handleCd(target);
            return;
        }
        if (cmd.equals("cd")) {
            cwd = "/";
            appendLine("当前目录: " + cwd);
            appendPrompt();
            postInvalidate();
            return;
        }
        if (cmd.equals("pwd")) {
            appendLine(cwd);
            appendPrompt();
            postInvalidate();
            return;
        }
        // 运行时未就绪时提示
        if (!RuntimeManager.isReady(getContext())) {
            appendLine("[运行时未就绪, 正在准备...]");
            ensureRuntimeThenExec(cmd);
            return;
        }
        // 前缀 cd 到当前会话目录, 注入运行时环境
        // 注意: 先 rewriteCommand (把 pip/py3 → 完整路径), 再 buildCommand (加环境变量)
        String rewrittenCmd = RuntimeManager.rewriteCommand(getContext(), cmd);
        String fullCmd = "cd \"" + cwd + "\"; " + RuntimeManager.buildCommand(getContext(), rewrittenCmd);
        execAsync(fullCmd);
    }

    /** 处理 cd: 用 sh 解析路径合法性, 更新会话 cwd */
    private void handleCd(final String target) {
        final String base = cwd;
        final String resolved = (target.startsWith("/") ? "" : base + "/") + target;
        final String norm = normalizePath(resolved);
        execAsync2("cd \"" + norm + "\" && pwd", new CmdResult() {
            public void onDone(String out, int code) {
                String p = out.trim();
                if (!p.isEmpty() && code == 0) {
                    cwd = p;
                    appendLine("→ " + cwd);
                } else {
                    appendLine("[目录不存在: " + norm + "]");
                }
                appendPrompt();
                postInvalidate();
            }
        });
    }

    private String normalizePath(String p) {
        // 简化: 去掉 ./ 和 ..
        String[] parts = p.split("/");
        List<String> stack = new ArrayList<String>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(s);
            }
        }
        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < stack.size(); i++) {
            sb.append(stack.get(i));
            if (i < stack.size() - 1) sb.append("/");
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /** 运行时未就绪: 触发解压, 完成后执行 */
    private void ensureRuntimeThenExec(final String cmd) {
        appendLine("[解压运行时中, 约 1 分钟, 请稍候...]");
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = RuntimeManager.ensureReady(getContext(), null);
                ui.post(new Runnable() {
                    public void run() {
                        if (ok) {
                            appendLine("[运行时就绪]");
                            String fullCmd = "cd \"" + cwd + "\"; " + RuntimeManager.buildCommand(getContext(), cmd);
                            execAsync(fullCmd);
                        } else {
                            appendLine("[运行时解压失败]");
                            appendPrompt();
                            postInvalidate();
                        }
                    }
                });
            }
        }).start();
    }

    private interface CmdResult {
        void onDone(String out, int code);
    }

    /** 执行并收集完整输出 (用于 cd 等需要结果的命令) */
    private void execAsync2(final String cmd, final CmdResult cb) {
        new Thread(new Runnable() {
            public void run() {
                StringBuilder out = new StringBuilder();
                int code = -1;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) out.append(l).append("\n");
                    code = p.waitFor();
                } catch (Exception e) {
                    out.append(e.toString());
                }
                final String o = out.toString();
                final int c = code;
                ui.post(new Runnable() { public void run() { cb.onDone(o, c); } });
            }
        }).start();
    }

    /** 流式执行 */
    private void execAsync(final String fullCmd) {
        final StringBuilder outBuf = new StringBuilder();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", fullCmd});
                    process = p;
                    running = true;
                    Thread t1 = new Thread(new Runnable() {
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                                String line;
                                while ((line = r.readLine()) != null) {
                                    final String l = line;
                                    ui.post(new Runnable() { public void run() { appendLine(l); } });
                                }
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    t1.start();
                    Thread t2 = new Thread(new Runnable() {
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                                String line;
                                while ((line = r.readLine()) != null) {
                                    final String l = line;
                                    ui.post(new Runnable() { public void run() { appendLine("! " + l); } });
                                }
                            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                        }
                    });
                    t2.start();
                    int code = p.waitFor();
                    running = false;
                    ui.post(new Runnable() {
                        public void run() {
                            appendLine("");
                            appendLine("[退出码 " + code + "]");
                            appendPrompt();
                        }
                    });
                } catch (Exception e) {
                    running = false;
                    ui.post(new Runnable() {
                        public void run() {
                            appendLine("[执行失败: " + e.toString() + "]");
                            appendPrompt();
                        }
                    });
                }
            }
        }).start();
    }

    /** 当前是否正在执行命令 */
    public boolean isRunning() { return running; }

    /** 当前字号 */
    public float getFontSize() { return fontSize; }

    /** 设置字号 (重新排版) */
    public void setFontSize(float sp) {
        if (sp < 8f) sp = 8f;
        if (sp > 30f) sp = 30f;
        fontSize = sp;
        textPaint.setTextSize(fontSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);
        promptPaint.set(textPaint);
        cursorPaint.set(textPaint);
        postInvalidate();
    }

    /** 获取当前会话工作目录 */
    public String getCwd() { return cwd; }

    /** 中断当前命令 */
    public void interrupt() {
        if (process != null && running) {
            try { process.destroy(); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            running = false;
        }
    }

    // ---------- 滚动 + 点按弹出键盘 + 长按粘贴 + 双击清屏 ----------
    private long downTime = 0;
    private boolean longPressFired = false;
    private long lastTapTime = 0;
    private Runnable longPressTask = null;   // 长按粘贴定时器, 便于取消
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = event.getY();
                downTime = System.currentTimeMillis();
                longPressFired = false;
                // 请求焦点但不立即弹键盘, 避免滑动时也弹出
                requestFocus();
                // 长按 600ms 触发粘贴 → 需要系统剪贴板服务
                longPressTask = new Runnable() {
                    public void run() {
                        if (!longPressFired) {
                            longPressFired = true;
                            doPaste();
                        }
                    }
                };
                postDelayed(longPressTask, 600);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - lastTouchY;
                if (Math.abs(dy) > dp(8)) { // 防误触: 移动超过 8dp 才滚
                    cancelPasteTask();      // 滑动取消长按粘贴
                    longPressFired = true;
                    float lineH = lineHeight;
                    scrollOffset += (int) (dy / lineH);
                    if (scrollOffset < 0) scrollOffset = 0;
                    postInvalidate();
                }
                lastTouchY = event.getY();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dy = Math.abs(event.getY() - lastTouchY);
                // 双击清屏: 两次点按间隔 <300ms 且无位移
                long now = System.currentTimeMillis();
                if (dy < dp(8) && !longPressFired && now - lastTapTime < 300 && lastTapTime > 0) {
                    lastTapTime = 0;
                    cancelPasteTask();      // 双击清屏前取消残留定时器
                    clearScreen();
                    return true;
                }
                lastTapTime = now;
                // 点按(无明显滑动)才弹键盘; 定时器在松手时取消, 快速点击不再误粘贴
                if (dy < dp(8) && !longPressFired) {
                    cancelPasteTask();
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(this, 0);
                }
                longPressFired = false;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /** 取消尚未触发(600ms内松手/滑动)的长按粘贴定时器 */
    private void cancelPasteTask() {
        if (longPressTask != null) {
            removeCallbacks(longPressTask);
            longPressTask = null;
        }
    }

    /** 长按粘贴: 读取系统剪贴板, 逐字符输入 (多行命令自动逐行执行) */
    private void doPaste() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                android.widget.Toast.makeText(getContext(), "剪贴板为空", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip == null || clip.length() == 0) {
                android.widget.Toast.makeText(getContext(), "剪贴板为空", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String text = clip.toString();
            // 逐字符输入; 换行符触发行执行
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') inputChar('\n');
                else inputChar(c);
            }
            android.widget.Toast.makeText(getContext(), "已粘贴 " + text.length() + " 字符", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(getContext(), "粘贴失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 双击清屏: 清空输出历史, 保留提示符 (等效 clear 命令) */
    public void clearScreen() {
        lines.clear();
        lineColors.clear();
        current.setLength(0);
        scrollOffset = 0;
        appendPrompt();
        postInvalidate();
        android.widget.Toast.makeText(getContext(), "已清屏 (双击清屏)", android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(bgPaint.getColor());
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        final float paddingLeft = 16;
        final float availWidth = Math.max(1, getWidth() - paddingLeft * 2);
        final float charWidth = textPaint.measureText("M");
        final int charsPerLine = Math.max(1, (int) (availWidth / charWidth));
        final int defaultColor = 0xFFE6E6E6;
        final int greenColor = 0xFF4CAF50;

        // 收集所有行（含当前输入）
        List<String> all = new ArrayList<String>(lines);
        String currentLine = current.toString();
        boolean hasInput = current.length() > 0;
        if (hasInput) all.add(currentLine);
        int total = all.size();

        // 计算每行拆成几段, 构建展平后的视觉行列表
        List<String> visualLines = new ArrayList<String>();
        List<Integer> visualLineToSource = new ArrayList<Integer>(); // 视觉行→源行索引
        for (int si = 0; si < total; si++) {
            String line = all.get(si);
            if (line.length() == 0) line = " ";
            int len = line.length();
            int pos = 0;
            while (pos < len) {
                int end = Math.min(pos + charsPerLine, len);
                // 尽量不在单词中间断开, 但这里简单截断
                visualLines.add(line.substring(pos, end));
                visualLineToSource.add(si);
                pos = end;
            }
        }

        int maxLines = (int) (getHeight() / lineHeight);
        int visualTotal = visualLines.size();
        int startFromBottom = visualTotal - maxLines - scrollOffset;
        int start = Math.max(0, startFromBottom);

        // 记录最后实际绘制的视觉行信息（用于光标定位）
        float lastLineY = lineHeight;
        int lastVisualRow = -1;
        int lastSourceRow = -1;

        for (int vi = start; vi < visualTotal; vi++) {
            String line = visualLines.get(vi);
            float y = lineHeight + (vi - start) * lineHeight;
            if (y + (fm.descent - fm.ascent) > getHeight() - dp(2)) break;
            lastLineY = y;
            lastVisualRow = vi;
            lastSourceRow = visualLineToSource.get(vi);

            int si = visualLineToSource.get(vi);
            int paintColor = defaultColor;
            if (si < lines.size() && si < lineColors.size()) {
                int c = lineColors.get(si);
                if (c != -1) paintColor = c;
            }

            // 提示符行: 绿色 ~ 白色 $
            boolean isPrompt = line.startsWith("~") || line.startsWith("$ ");
            // 只在提示符的第一段才画绿色 ~, 折行段不画

            if (isPrompt && si == total - 1 && hasInput && (vi == 0 || visualLineToSource.get(vi - 1) != si)) {
                // 分段绘制 ~ (绿) 与 $ 及后续 (白)
                float x = paddingLeft;
                int tildeIdx = line.indexOf('~');
                if (tildeIdx >= 0) {
                    promptPaint.setColor(greenColor);
                    canvas.drawText("~", x, y, promptPaint);
                    x += promptPaint.measureText("~");
                    promptPaint.setColor(defaultColor);
                    canvas.drawText(line.substring(tildeIdx + 1), x, y, promptPaint);
                } else {
                    canvas.drawText(line, paddingLeft, y, textPaint);
                }
            } else {
                if (paintColor != defaultColor) {
                    textPaint.setColor(paintColor);
                    canvas.drawText(line, paddingLeft, y, textPaint);
                    textPaint.setColor(defaultColor);
                } else {
                    canvas.drawText(line, paddingLeft, y, textPaint);
                }
            }
        }

        // ---- 闪烁块状光标: 定位到当前输入行的最后一个视觉行 ----
        if (hasInput && cursorVisible && lastVisualRow >= 0) {
            // 找到当前输入行对应的最后一个视觉行
            String cursorLine = null;
            int cursorVisualRow = -1;
            if (lastSourceRow == total - 1 && hasInput) {
                cursorVisualRow = lastVisualRow;
                cursorLine = visualLines.get(lastVisualRow);
            }
            if (cursorLine != null) {
                // 计算这个视觉行末尾的列号
                int col = cursorLine.length();
                if (col > charsPerLine) col = charsPerLine; // 安全
                float cursorX = paddingLeft + col * charWidth;
                float cursorY = lastLineY + fm.ascent; // 从基线回退到 ascent
                float cursorH = fm.descent - fm.ascent; // 精确高度
                Paint cBg = new Paint();
                cBg.setColor(0xCCFFFFFF);
                canvas.drawRect(cursorX, cursorY, cursorX + charWidth, cursorY + cursorH, cBg);
            }
        }

        // 滚动指示
        if (scrollOffset > 0) {
            Paint ind = new Paint(Paint.ANTI_ALIAS_FLAG);
            ind.setColor(0x99FFFFFF);
            ind.setTextSize(dp(10));
            canvas.drawText("▲ 上滑浏览历史 (" + scrollOffset + ")", 16, getHeight() - dp(4), ind);
        }
    }

    // ---------- 输入法 ----------
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND;
        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n') inputChar('\n');
                    else inputChar(c);
                }
                return true;
            }
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                inputChar((char) 0x7f);
                return true;
            }
            @Override
            public boolean sendKeyEvent(android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    int code = event.getKeyCode();
                    if (code == android.view.KeyEvent.KEYCODE_ENTER) {
                        inputChar('\n');
                        return true;
                    }
                    if (code == android.view.KeyEvent.KEYCODE_DEL) {
                        inputChar((char) 0x7f);
                        return true;
                    }
                    if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        historyNav(-1);
                        return true;
                    }
                    if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                        historyNav(1);
                        return true;
                    }
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            inputChar('\n');
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DEL) {
            inputChar((char) 0x7f);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            historyNav(-1);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            historyNav(1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void saveHistory(String line) {
        if (line == null || line.trim().isEmpty()) return;
        try {
            java.io.File f = new java.io.File(getContext().getFilesDir(), HISTORY_FILE);
            java.util.List<String> lines = new java.util.ArrayList<String>();
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String l;
                while ((l = r.readLine()) != null) { lines.add(l); }
                r.close();
            }
            lines.add(line);
            while (lines.size() > MAX_HISTORY) lines.remove(0);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            for (String l : lines) { out.write((l + "\n").getBytes("UTF-8")); }
            out.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }
    public java.util.List<String> loadHistory() {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        try {
            java.io.File f = new java.io.File(getContext().getFilesDir(), HISTORY_FILE);
            if (!f.exists()) return lines;
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
            r.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return lines;
    }
}
