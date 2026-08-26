package io.taskpro;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import io.taskpro.md.MdSnackbar;
import io.taskpro.md.MdTheme;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** AI 助手对话页: 用户请求 → AI 工具循环 (工作区) → 生成脚本 → 一键安装到脚本库 */
public class AIActivity extends Activity {

    private LinearLayout chatBox;
    private ScrollView chatScroll;
    private EditText input;
    private TextView statusLine;
    private TextView aiStatusLine;  // 输入框上方 AI 状态提示
    private LinearLayout filesCard;
    private boolean busy = false;
    private final JSONArray messages = new JSONArray();
    private Map<String, Long> snapshot = new TreeMap<String, Long>();
    private final List<File> generated = new ArrayList<File>();
    private LinearLayout root;
    private final List<String> toolLog = new ArrayList<String>();
    // 流式/转圈状态
    private LinearLayout thinkingBubble = null;
    private TextView streamTv = null;
    private final StringBuilder streamBuf = new StringBuilder();
    private long streamLastUpdate = 0;
    private int msgCount = 0;  // 当前消息数
    // 停止标志: 用于中断 AI 生成
    private volatile boolean stopRequested = false;
    private TextView stopBarBtn;   // 顶栏停止按钮
    // 会话持久化
    private String sessionId;
    private String sessionTitle;
    private TextView titleView;
    private Runnable saveTask = null;
    private static final String PREFS = "ai_session_prefs";
    private static final String KEY_LAST = "last_session_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MdTheme.surface(this));
        setContentView(root);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(MdTheme.surfaceContainer(this));
        bar.setPadding(dp(4), dp(10), dp(4), dp(10));
        // 顶栏底部细线 (替代阴影)
        View barLine = new View(this);
        barLine.setBackgroundColor(MdTheme.isDark(this) ? 0xFF322F35 : 0xFFE0DAE8);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(MdTheme.onSurface(this));
        back.setTextSize(28);
        back.setPadding(dp(14), dp(2), dp(14), dp(2));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setText("AI 助手");
        titleView.setTextColor(MdTheme.onSurface(this));
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        // 长按标题重命名
        titleView.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                final io.taskpro.md.MdDialog d = new io.taskpro.md.MdDialog(AIActivity.this);
                d.title("重命名对话");
                final EditText input = new EditText(AIActivity.this);
                input.setText(sessionTitle == null || sessionTitle.equals("对话") ? "" : sessionTitle);
                input.setHint("输入新标题");
                input.setPadding(dp(10), dp(8), dp(10), dp(8));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(MdTheme.isDark(AIActivity.this) ? 0xFF2A2A2E : 0xFFF1F3F5);
                bg.setCornerRadius(dp(8));
                input.setBackground(bg);
                d.content(input);
                d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.actionPrimary("确定", new Runnable() {
                    public void run() {
                        String t = input.getText().toString().trim();
                        if (t.isEmpty()) { d.dismiss(); return; }
                        sessionTitle = t;
                        titleView.setText(sessionTitle);
                        saveSession();
                        MdSnackbar.show(chatBox, "已重命名");
                        d.dismiss();
                    }
                });
                d.show();
                return true;
            }
        });
        titleBox.addView(titleView);
        statusLine = new TextView(this);
        statusLine.setText(AIConfig.isConfigured(this) ? "● 已连接 · 工作区隔离" : "○ 未配置 API");
        statusLine.setTextColor(AIConfig.isConfigured(this)
                ? (MdTheme.isDark(this) ? 0xFF81C784 : 0xFF2E7D32)
                : (MdTheme.isDark(this) ? 0xFFEF9A9A : 0xFFC62828));
        statusLine.setTextSize(11);
        titleBox.addView(statusLine);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 历史会话按钮 (矢量图标)
        TextView histBtn = new TextView(this);
        histBtn.setText(IconFont.HISTORY);
        histBtn.setTypeface(IconFont.get(this));
        histBtn.setTextSize(20);
        histBtn.setTextColor(MdTheme.onSurfaceVariant(this));
        histBtn.setPadding(dp(12), dp(8), dp(10), dp(8));
        histBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showHistoryDialog(); }
        });
        bar.addView(histBtn);
        // 清空工作区按钮 (删除 AI 产生的临时文件)
        TextView clearBtn = new TextView(this);
        clearBtn.setText(IconFont.DELETE);
        clearBtn.setTypeface(IconFont.get(this));
        clearBtn.setTextSize(19);
        clearBtn.setTextColor(MdTheme.onSurfaceVariant(this));
        clearBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClearWorkspace(); }
        });
        bar.addView(clearBtn);
        // 新建对话按钮 (矢量图标)
        TextView newBtn = new TextView(this);
        newBtn.setText(IconFont.ADD);
        newBtn.setTypeface(IconFont.get(this));
        newBtn.setTextSize(22);
        newBtn.setTextColor(MdTheme.onSurfaceVariant(this));
        newBtn.setPadding(dp(10), dp(8), dp(12), dp(8));
        newBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newConversation(); }
        });
        bar.addView(newBtn);
        // 顶栏停止按钮: 生成期间显示, 随时中断 AI
        stopBarBtn = new TextView(this);
        stopBarBtn.setText("■");
        stopBarBtn.setTextSize(16);
        stopBarBtn.setTextColor(0xFFFF5252);
        stopBarBtn.setPadding(dp(10), dp(8), dp(8), dp(8));
        stopBarBtn.setVisibility(View.GONE);
        stopBarBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopRequested = true;
                AIEngine.requestStop();
                busy = false;
                stopBarBtn.setVisibility(View.GONE);
                if (thinkingBubble != null) {
                    chatBox.removeView(thinkingBubble);
                    thinkingBubble = null;
                }
                if (streamTv != null) {
                    String content = streamBuf.toString().trim();
                    if (!content.isEmpty()) {
                        streamTv.append("\n\n⏹ 已中断");
                        streamBuf.append("\n\n⏹ 已中断");
                        try { messages.put(new JSONObject()
                                .put("role", "assistant")
                                .put("content", streamBuf.toString())); } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                        saveSession();
                    }
                    streamTv = null;
                    streamBuf.setLength(0);
                } else {
                    addAi("⏹ 已中断");
                }
            }
        });
        bar.addView(stopBarBtn);
        root.addView(bar);
        root.addView(barLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 聊天区
        chatScroll = new ScrollView(this);
        chatBox = new LinearLayout(this);
        chatBox.setOrientation(LinearLayout.VERTICAL);
        chatBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        chatScroll.addView(chatBox);
        root.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 输入区
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dp(12), dp(8), dp(10), dp(10));
        inputBar.setBackgroundColor(MdTheme.surfaceContainer(this));
        // 输入框上方细线
        View inputLine = new View(this);
        inputLine.setBackgroundColor(MdTheme.isDark(this) ? 0xFF322F35 : 0xFFE0DAE8);
        input = new EditText(this);
        input.setHint("描述你想要的脚本…");
        input.setHintTextColor(MdTheme.onSurfaceVariant(this));
        input.setTextColor(MdTheme.onSurface(this));
        input.setTextSize(14);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
        g.setCornerRadius(dp(20));
        input.setBackground(g);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        inputBar.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 发送按钮: 圆形 FAB 风格图标
        TextView send = new TextView(this);
        send.setText(IconFont.SEND);
        send.setTypeface(IconFont.get(this));
        send.setTextSize(20);
        send.setTextColor(0xFFFFFFFF);
        send.setGravity(Gravity.CENTER);
        int btnSize = dp(44);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(MdTheme.primary(this));
        sg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        send.setBackground(sg);
        send.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendMessage(); }
        });
        inputBar.addView(send);
        // 输入区: 先放细线, 再放 AI 状态提示行, 再放 inputBar
        root.addView(inputLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        // AI 状态提示行 (输入框上方)
        aiStatusLine = new TextView(this);
        aiStatusLine.setText("就绪 - 输入描述开始对话");
        aiStatusLine.setTextColor(MdTheme.onSurfaceVariant(this) & 0xAAFFFFFF);
        aiStatusLine.setTextSize(10);
        aiStatusLine.setGravity(Gravity.CENTER);
        aiStatusLine.setSingleLine(true);
        aiStatusLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        aiStatusLine.setPadding(0, dp(4), 0, dp(2));
        root.addView(aiStatusLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(inputBar);

        // 恢复上次会话 (误退/杀进程后对话仍在)
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST, null);
        JSONObject saved = last == null ? null : AISessionStore.load(this, last);
        if (saved != null && saved.optJSONArray("messages") != null
                && saved.optJSONArray("messages").length() > 0) {
            sessionId = last;
            sessionTitle = saved.optString("title", "对话");
            titleView.setText(sessionTitle);
            messagesTo(sessionMessages(saved));
            renderMessages(saved);
        } else {
            sessionId = java.util.UUID.randomUUID().toString();
            sessionTitle = "新对话";
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LAST, sessionId).apply();
            // 初始提示
            addAi("你好, 我是脚本助手 \n\n告诉我你想要什么脚本, 我会直接编写并在沙箱里测试好, 然后你一键安装到脚本库。\n\n 试试:\n• 帮我写一个 XX 签到脚本\n• 写个爬虫抓取 XX 网站数据\n• 批量重命名当前目录下的文件");
        }
        snapshot = snapshotWorkspace();
        updateStatusLine();
    }

    // ================= 消息渲染 =================

    private void addUser(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                chatBox.addView(bubble(text, true));
                scrollToBottom();
            }
        });
        scheduleSave();
    }

    private void addAi(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                final View b = bubble(text, false);
                chatBox.addView(b);
                scrollToBottom();
            }
        });
    }

    private void addTool(final String brief, final String detailText) {
        toolLog.add(brief);
        // 工具执行中: 更新状态行
        setAiStatus("正在执行: " + brief, 0xFF26A69A); // 青色
        runOnUiThread(new Runnable() {
            public void run() {
                // 工具行出现: 结束转圈 (若还在)
                if (thinkingBubble != null) {
                    chatBox.removeView(thinkingBubble);
                    thinkingBubble = null;
                }
                // 工具卡片 (点击标题展开/收起完整输出)
                final LinearLayout card = new LinearLayout(AIActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(10), dp(6), dp(10), dp(6));
                android.graphics.drawable.GradientDrawable cardBg =
                        new android.graphics.drawable.GradientDrawable();
                cardBg.setColor(MdTheme.isDark(AIActivity.this) ? 0xFF1E1B22 : 0xFFEDE7F6);
                cardBg.setCornerRadius(dp(10));
                card.setBackground(cardBg);
                // 标题行: 图标 + 工具名 + 展开箭头
                LinearLayout head = new LinearLayout(AIActivity.this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView icon = new TextView(AIActivity.this);
                icon.setText(IconFont.BUILD);
                icon.setTypeface(IconFont.get(AIActivity.this));
                icon.setTextSize(13);
                icon.setTextColor(MdTheme.primary(AIActivity.this));
                icon.setPadding(0, 0, dp(6), 0);
                head.addView(icon);
                TextView tv = new TextView(AIActivity.this);
                tv.setText(brief);
                tv.setTextColor(MdTheme.onSurfaceVariant(AIActivity.this));
                tv.setTextSize(11);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setMaxLines(1);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                head.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                final TextView arrow = new TextView(AIActivity.this);
                arrow.setText(detailText == null || detailText.isEmpty()
                        ? "" : IconFont.EXPAND_MORE);
                arrow.setTypeface(IconFont.get(AIActivity.this));
                arrow.setTextSize(16);
                arrow.setTextColor(MdTheme.onSurfaceVariant(AIActivity.this));
                arrow.setPadding(dp(6), 0, 0, 0);
                head.addView(arrow);
                // 详情区 (默认隐藏)
                final TextView detail = new TextView(AIActivity.this);
                detail.setVisibility(View.GONE);
                detail.setTextColor(MdTheme.onSurfaceVariant(AIActivity.this));
                detail.setTextSize(11);
                detail.setTypeface(Typeface.MONOSPACE);
                detail.setPadding(0, dp(4), 0, 0);
                detail.setTextIsSelectable(true);
                if (detailText != null && !detailText.isEmpty()) {
                    detail.setText(detailText.length() > 2000
                            ? detailText.substring(0, 2000) + "\n…(输出过长已截断)" : detailText);
                } else {
                    detail.setVisibility(View.GONE);
                    arrow.setText("");
                }
                // 点击切换展开/收起
                if (detailText != null && !detailText.isEmpty()) {
                    card.setOnClickListener(new View.OnClickListener() {
                        boolean expanded = false;
                        public void onClick(View v) {
                            expanded = !expanded;
                            detail.setVisibility(expanded ? View.VISIBLE : View.GONE);
                            arrow.setText(expanded ? IconFont.EXPAND_LESS : IconFont.EXPAND_MORE);
                            card.post(new Runnable() {
                                public void run() { scrollToBottom(); }
                            });
                        }
                    });
                }
                card.addView(head);
                card.addView(detail);
                chatBox.addView(card);
                scrollToBottom();
            }
        });
        scheduleSave();
    }

    /** 更新状态栏: 显示连接状态 + 消息计数 */
    private void updateStatusLine() {
        int cnt = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null) {
                String role = m.optString("role", "");
                if ("user".equals(role) || ("assistant".equals(role) && !m.has("tool_calls"))) cnt++;
            }
        }
        msgCount = cnt;
        String base = AIConfig.baseUrl(this);
        boolean configured = AIConfig.isConfigured(this);
        String status;
        if (!configured || base.isEmpty()) {
            status = "○ 未配置 API";
            statusLine.setTextColor(MdTheme.isDark(this) ? 0xFFEF9A9A : 0xFFC62828);
        } else {
            status = "● " + cnt + " 条消息 · 已连接";
            statusLine.setTextColor(MdTheme.isDark(this) ? 0xFF81C784 : 0xFF2E7D32);
        }
        statusLine.setText(status);
    }
    private View bubble(String text, boolean mine) {
        // 行: [可选头像] + 气泡
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(mine ? Gravity.CENTER_VERTICAL | Gravity.END : Gravity.CENTER_VERTICAL | Gravity.START);
        wrap.setPadding(0, dp(3), 0, dp(3));
        // 头像 (AI 侧): Canvas 绘制的四角星图标
        if (!mine) {
            android.widget.ImageView avatar = new android.widget.ImageView(this);
            avatar.setImageDrawable(Icons.make(this, Icons.AI_AVATAR, MdTheme.primary(this), 16));
            avatar.setPadding(0, dp(2), dp(8), dp(2));
            wrap.addView(avatar);
        }
        // 使用 Markdown 渲染
        final View mdView = renderMarkdown(text, mine);
        mdView.setPadding(dp(13), dp(9), dp(13), dp(9));
        mdView.setBackground(setupBubbleBg(mine));
        mdView.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        mdView.setLayoutParams(new LinearLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * 0.78), -2));
        wrap.addView(mdView);
        // 长按复制
        mdView.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                final String fullText = text;
                final io.taskpro.md.MdDialog d = new io.taskpro.md.MdDialog(AIActivity.this);
                d.title("复制");
                d.message("选择复制方式:");
                d.action("复制全部", new Runnable() {
                    public void run() {
                        d.dismiss();
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ai_msg", fullText));
                        io.taskpro.md.MdSnackbar.show(chatBox, "已复制全部内容");
                    }
                });
                d.actionPrimary("取消", new Runnable() { public void run() { d.dismiss(); } });
                d.show();
                return true;
            }
        });
        // 错误消息可点击重试
        if (text.contains("[错误]") || text.contains("出错了") || text.contains("连接中断")) {
            mdView.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (busy) return;
                    String lastUser = null;
                    for (int i = messages.length() - 1; i >= 0; i--) {
                        JSONObject m = messages.optJSONObject(i);
                        if (m != null && "user".equals(m.optString("role"))) {
                            lastUser = m.optString("content", "");
                            break;
                        }
                    }
                    if (lastUser != null && !lastUser.isEmpty()) {
                        input.setText(lastUser);
                        sendMessage();
                    }
                }
            });
        }
        return wrap;
    }

    private boolean isAtBottom() {
        return chatScroll.getScrollY() + chatScroll.getHeight() >= chatScroll.getChildAt(0).getHeight() - dp(60);
    }
    private void scrollToBottom() {
        chatScroll.post(new Runnable() {
            public void run() {
                if (isAtBottom()) chatScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ================= 发送与 AI 循环 =================

    private void sendMessage() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!AIConfig.isConfigured(this)) {
            addAi("尚未配置 AI API。请先到「更多」→「AI 配置」填写接口地址和 Key。");
            return;
        }
        input.setText("");
        hideKeyboard();
        busy = true;
        if (stopBarBtn != null) stopBarBtn.setVisibility(View.VISIBLE);
        // 新对话开始: 重置流式状态, 防止内容写进上一次的气泡
        streamTv = null;
        thinkingBubble = null;
        streamBuf.setLength(0);
        // 首条用户消息作为会话标题
        if ("新对话".equals(sessionTitle)) {
            sessionTitle = text.length() > 12 ? text.substring(0, 12) : text;
            titleView.setText(sessionTitle);
        }
        addUser(text);
        showThinking();
        final JSONObject um = new JSONObject();
        try {
            um.put("role", "user");
            um.put("content", text);
            messages.put(um);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        updateStatusLine();
        new Thread(new Runnable() {
            public void run() {
                runChat();
            }
        }).start();
    }

    /** 工具循环: LLM ↔ 工具, 流式输出, 直到 AI 给出最终答复 */
    /** 网络预检: 对目标 API 域名做 TCP 连接测试 */
    private boolean networkOk(String base) {
        try {
            String host;
            if (base.startsWith("http://")) host = base.substring(7);
            else if (base.startsWith("https://")) host = base.substring(8);
            else host = base;
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host, 443), 5000);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void runChat() {
        stopRequested = false;
        AIEngine.resetStop();
        final String[] finalText = {""};
        try {
            // 网络预检
            String base = AIConfig.baseUrl(AIActivity.this);
            if (!networkOk(base)) {
                addAi("[网络不可达] 无法连接到 " + base + "\n请检查网络后重试。");
                return;
            }
            // 构建 API 消息: system + 持久化的 messages (含历史工具调用)
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", AIEngine.systemPrompt(AIActivity.this));
            msgs.put(sys);
            for (int i = 0; i < messages.length(); i++) msgs.put(messages.get(i));
            JSONArray tools = AIEngine.tools();
            int turns = 0;
            while (turns++ < 12) {
                if (stopRequested) return;
                final JSONArray tcs;
                try {
                    tcs = AIEngine.callLLMStream(AIActivity.this, msgs, tools,
                            new AIEngine.StreamListener() {
                                public void onText(String delta) {
                                    if (stopRequested) return;
                                    appendAiText(delta);
                                }
                                public void onError(String err) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            if (stopRequested) return;
                                            appendAiText("\n[错误] " + err);
                                        }
                                    });
                                }
                                public void onRetry(int attempt) {
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            // 截断已输出内容, 避免重试时重复显示
                                            if (streamTv != null) {
                                                // 去掉最后一行 "[连接中断, 正在重试...]" (稍后由 onText 重新添加)
                                                streamBuf.setLength(0);
                                                streamTv.setText("");
                                            }
                                        }
                                    });
                                }
                            });
                } catch (Exception e) {
                    // 网络中断: 保存已输出内容, 给出诊断
                    final String errMsg = e.getMessage() == null ? "连接中断" : e.getMessage();
                    final String saved = streamBuf.toString().trim();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (stopRequested) return;
                            if (saved.length() > 10) addAi(saved);
                            addAi("[连接中断] " + errMsg + "\n可点击重新发送继续。");
                        }
                    });
                    streamTv = null;
                    streamBuf.setLength(0);
                    break;
                }
                if (tcs == null || tcs.length() == 0) break;   // 回复完成
                if (stopRequested) return;
                // 工具阶段: 记录 assistant 消息 (含 tool_calls) — 同时存入 messages 和 msgs
                JSONObject am = new JSONObject();
                am.put("role", "assistant");
                JSONArray tca = new JSONArray();
                for (int i = 0; i < tcs.length(); i++) {
                    JSONObject tc = tcs.optJSONObject(i);
                    JSONObject tco = new JSONObject();
                    tco.put("id", tc.optString("id", "call_" + i));
                    tco.put("type", "function");
                    tco.put("function", tc.optJSONObject("function"));
                    tca.put(tco);
                }
                am.put("tool_calls", tca);
                am.put("content", streamBuf.toString());
                msgs.put(am);
                // 持久化: 工具调用 assistant 消息也存入 messages, 保证下次对话有上下文
                messages.put(am);
                for (int i = 0; i < tcs.length(); i++) {
                    if (stopRequested) return;
                    JSONObject tc = tcs.optJSONObject(i);
                    String id = tc.optString("id", "call_" + i);
                    JSONObject fn = tc.optJSONObject("function");
                    String name = fn == null ? "" : fn.optString("name", "");
                    String args = fn == null ? "{}" : fn.optString("arguments", "{}");
                    // 工具名称+调用参数摘要 (卡片标题)
                    String argBrief = "";
                    if ("write_file".equals(name)) {
                        try { argBrief = " " + new JSONObject(args).optString("path", ""); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                    if ("run_command".equals(name)) {
                        try { argBrief = " " + new JSONObject(args).optString("cmd", ""); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                    if ("set_env".equals(name)) {
                        try { argBrief = " " + new JSONObject(args).optString("name", ""); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                    if ("install_script".equals(name) || "read_file".equals(name)) {
                        try { argBrief = " " + new JSONObject(args).optString("path", ""); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                    if ("create_task".equals(name)) {
                        try { argBrief = " " + new JSONObject(args).optString("name", "")
                                + " " + new JSONObject(args).optString("cron", ""); } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                    }
                    // 结束本轮流式气泡: 工具卡片独立一行, 下一轮 AI 回复开新气泡
                    streamTv = null;
                    String result;
                    try {
                        result = AIEngine.executeTool(AIActivity.this, name, args);
                    } catch (Exception e) {
                        result = "执行失败: " + e.getMessage();
                    }
                    addTool(name + argBrief, result);
                    // 工具结果 < 5000 字符才持久化, 避免大文件内容撑爆 messages
                    JSONObject tm = new JSONObject();
                    tm.put("role", "tool");
                    tm.put("tool_call_id", id);
                    String resultTruncated = result.length() > 5000 ? result.substring(0, 5000) : result;
                    tm.put("content", resultTruncated);
                    // ★ 关键: 工具结果同时追加到 msgs(本次 API 上下文) 和 messages(持久化),
                    //   否则 OpenAI 兼容 API 会因 assistant(tool_calls) 后缺少 tool 消息而报 400/忽略工具
                    msgs.put(tm);
                    messages.put(tm);
                }
            }
            if (stopRequested) return;
            finalText[0] = streamBuf.toString().trim();
            if (finalText[0].isEmpty()) finalText[0] = "已完成 (可在上方查看生成的文件并一键安装)";
            // 会话记忆: 保存最终 AI 回复
            messages.put(new JSONObject().put("role", "assistant").put("content", finalText[0]));
            // 自动生成标题 (取 AI 回复前 20 字)
            if (sessionTitle == null || sessionTitle.equals("对话") || sessionTitle.equals("AI 助手")) {
                String t = finalText[0].trim();
                if (t.length() > 20) t = t.substring(0, 20) + "…";
                // 去掉首位 emoji/符号
                t = t.replaceAll("^[\\s\\u00A0\\u2000-\\u200F\\u2028-\\u202F\\uFEFF\\u2029\\uFE00-\\uFE0F🌀🎯🔧📦⚡🔔✨]+", "").trim();
                if (t.length() > 4) {
                    sessionTitle = t;
                    titleView.setText(sessionTitle);
                }
            }
            // 截断防膨胀: 从前面删消息, 但保持 tool_calls↔tool 结果配对不被切断
            // 已取消消息限制, 保留完整对话历史
            saveSession();
            runOnUiThread(new Runnable() {
                public void run() {
                    if (stopRequested) return;
                    if (streamTv == null) addAi(finalText[0]);
                    showGenerated();
                }
            });
        } catch (Exception e) {
            String err = e.getMessage() == null ? "未知错误" : e.getMessage();
            String hint = "";
            if (err.startsWith("API 错误 401") || err.startsWith("API 错误 403")) {
                hint = "API Key 无效或被拒绝, 请检查配置。";
            } else if (err.startsWith("API 错误 404") || err.startsWith("API 错误 422")) {
                hint = "API 地址或模型名可能不正确。";
            } else if (err.contains("Socket") || err.contains("Connect") || err.contains("timed out")) {
                hint = "网络连接失败, 请检查网络或 Base URL。";
            } else if (err.contains("read error") || err.contains("Connection") || err.contains("reset")) {
                hint = "连接中断, 可能是网络不稳定。";
            } else if (err.contains("json") || err.contains("JSON") || err.contains("parse")) {
                hint = "API 返回格式异常, 可能是非 OpenAI 兼容接口。";
            }
            if (!hint.isEmpty()) err += "\n " + hint;
            addAi("出错了: " + err + "\n\n更多 → AI 配置 检查地址/Key/模型。");
        } finally {
            busy = false;
            resetAiStatus();
            saveSession();   // 每轮对话结束立即落盘
            runOnUiThread(new Runnable() {
                public void run() { updateStatusLine(); }
            });
        }
    }

    /** 发送后: 显示转圈等待气泡 + 停止按钮 */
    /** 更新 AI 状态提示 (输入框上方) */
    private void setAiStatus(String text, int color) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (aiStatusLine != null) {
                    aiStatusLine.setText(text);
                    aiStatusLine.setTextColor(color);
                }
            }
        });
    }

    /** 重置 AI 状态为就绪 */
    private void resetAiStatus() {
        setAiStatus("就绪", MdTheme.onSurfaceVariant(AIActivity.this) & 0xAAFFFFFF);
    }

    private void showThinking() {
        setAiStatus("思考中...", 0xFFFFA726); // 橙色
        runOnUiThread(new Runnable() {
            public void run() {
                if (thinkingBubble != null) return;
                stopRequested = false;
                LinearLayout wrap = new LinearLayout(AIActivity.this);
                wrap.setOrientation(LinearLayout.HORIZONTAL);
                wrap.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                wrap.setPadding(0, dp(4), 0, dp(4));
                // AI 头像: Canvas 四角星
                android.widget.ImageView avatar = new android.widget.ImageView(AIActivity.this);
                avatar.setImageDrawable(Icons.make(AIActivity.this, Icons.AI_AVATAR, MdTheme.primary(AIActivity.this), 16));
                avatar.setPadding(0, dp(2), dp(8), dp(2));
                wrap.addView(avatar);
                LinearLayout pill = new LinearLayout(AIActivity.this);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                pill.setPadding(dp(14), dp(10), dp(14), dp(10));
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setColor(MdTheme.isDark(AIActivity.this) ? 0xFF2A2A2E : 0xFFF1F3F5);
                g.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14),
                        dp(14), dp(14), dp(4), dp(4)});
                pill.setBackground(g);
                ProgressBar pb = new ProgressBar(AIActivity.this, null,
                        android.R.attr.progressBarStyleSmall);
                pb.getIndeterminateDrawable().setColorFilter(
                        MdTheme.primary(AIActivity.this), android.graphics.PorterDuff.Mode.SRC_IN);
                pill.addView(pb);
                final TextView tv = new TextView(AIActivity.this);
                tv.setText("  思考中...");
                tv.setTextColor(MdTheme.onSurfaceVariant(AIActivity.this));
                tv.setTextSize(13);
                pill.addView(tv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                // 停止按钮
                TextView stopBtn = new TextView(AIActivity.this);
                stopBtn.setText("■ 停止");
                stopBtn.setTextColor(0xFFFF5252);
                stopBtn.setTextSize(12);
                stopBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
                android.graphics.drawable.GradientDrawable stBg =
                        new android.graphics.drawable.GradientDrawable();
                stBg.setColor(0x20FF5252);
                stBg.setCornerRadius(dp(10));
                stopBtn.setBackground(stBg);
                stopBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        stopRequested = true;
                        AIEngine.requestStop();
                        busy = false;
                        if (thinkingBubble != null) {
                            chatBox.removeView(thinkingBubble);
                            thinkingBubble = null;
                        }
                        // 保留已输出内容, 标记中断
                        if (streamTv != null) {
                            String content = streamBuf.toString().trim();
                            if (!content.isEmpty()) {
                                streamTv.append("\n\n⏹ 已中断");
                                streamBuf.append("\n\n⏹ 已中断");
                                try { messages.put(new JSONObject()
                                        .put("role", "assistant")
                                        .put("content", streamBuf.toString())); } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
                                saveSession();
                            } else {
                                addAi("⏹ 已中断");
                            }
                            streamTv = null;
                            streamBuf.setLength(0);
                        } else {
                            addAi("⏹ 已中断");
                        }
                        MdSnackbar.show(chatBox, "已停止");
                    }
                });
                pill.addView(stopBtn);
                wrap.addView(pill);
                thinkingBubble = wrap;
                chatBox.addView(wrap);
                scrollToBottom();
            }
        });
    }
/** 流式文本增量: 切掉转圈, 逐字追加到 AI 气泡 */
    private void appendAiText(final String delta) {
        setAiStatus("正在生成回复...", MdTheme.primary(AIActivity.this));
        runOnUiThread(new Runnable() {
            public void run() {
                if (thinkingBubble != null) {
                    chatBox.removeView(thinkingBubble);
                    thinkingBubble = null;
                }
                if (streamTv == null) {
                    streamBuf.setLength(0);   // 新气泡 = 新轮次, 清空上一轮累积
                    LinearLayout wrap = new LinearLayout(AIActivity.this);
                    wrap.setOrientation(LinearLayout.HORIZONTAL);
                    wrap.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    wrap.setPadding(0, dp(3), 0, dp(3));
                    // AI 头像: Canvas 四角星
                    android.widget.ImageView avatar = new android.widget.ImageView(AIActivity.this);
                    avatar.setImageDrawable(Icons.make(AIActivity.this, Icons.AI_AVATAR, MdTheme.primary(AIActivity.this), 16));
                    avatar.setPadding(0, dp(2), dp(8), dp(2));
                    wrap.addView(avatar);
                    streamTv = new TextView(AIActivity.this);
                    streamTv.setTextSize(14);
                    streamTv.setLineSpacing(dp(2), 1f);
                    streamTv.setTextColor(MdTheme.onSurface(AIActivity.this));
                    streamTv.setPadding(dp(13), dp(9), dp(13), dp(9));
                    streamTv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
                    android.graphics.drawable.GradientDrawable g =
                            new android.graphics.drawable.GradientDrawable();
                    g.setColor(MdTheme.isDark(AIActivity.this) ? 0xFF2A2A2E : 0xFFF1F3F5);
                    g.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14),
                            dp(14), dp(14), dp(4), dp(4)});
                    streamTv.setBackground(g);
                    wrap.addView(streamTv);
                    chatBox.addView(wrap);
                }
                streamBuf.append(delta);
                // 节流: 每 100ms 最多刷新一次, 避免大量小 chunk 导致 UI 卡顿
                long now = System.currentTimeMillis();
                if (now - streamLastUpdate > 100) {
                    streamTv.setText(streamBuf.toString());
                    streamLastUpdate = now;
                    scrollToBottom();
                }
            }
        });
    }

    // ================= 工作区文件与安装 =================

    private Map<String, Long> snapshotWorkspace() {
        Map<String, Long> m = new TreeMap<String, Long>();
        File[] fs = AIEngine.workspace(this).listFiles();
        if (fs != null) {
            for (File f : fs) if (f.isFile()) m.put(f.getName(), f.lastModified());
        }
        return m;
    }

    /** 会话结束后扫描工作区: 列出本次生成/修改的脚本文件 */
    private void showGenerated() {
        Map<String, Long> now = snapshotWorkspace();
        generated.clear();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            Long old = snapshot.get(e.getKey());
            if (old == null || old.longValue() != e.getValue().longValue()) {
                if (e.getKey().endsWith(".py") || e.getKey().endsWith(".js")
                        || e.getKey().endsWith(".sh")) {
                    generated.add(new File(AIEngine.workspace(this), e.getKey()));
                }
            }
        }
        if (generated.isEmpty()) return;
        runOnUiThread(new Runnable() {
            public void run() {
                chatBox.addView(buildGenCard(generated));
                scrollToBottom();
            }
        });
        scheduleSave();   // 生成清单随会话落盘, 下次进入还能看到卡片
    }

    /** 构建"生成文件 → 一键安装"卡片 */
    private View buildGenCard(final List<File> files) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(MdTheme.isDark(this) ? 0xFF1B5E20 : 0xFFE8F5E9);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), MdTheme.isDark(this) ? 0xFF2E7D32 : 0xFFC8E6C9);
        card.setBackground(bg);
        // 标题行: 图标 + 文字
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(6));
        TextView iconTv = new TextView(this);
        iconTv.setText(IconFont.CHECK_CIRCLE);
        iconTv.setTypeface(IconFont.get(this));
        iconTv.setTextSize(16);
        iconTv.setTextColor(MdTheme.isDark(this) ? 0xFFA5D6A7 : 0xFF2E7D32);
        iconTv.setPadding(0, 0, dp(6), 0);
        titleRow.addView(iconTv);
        TextView t1 = new TextView(this);
        t1.setText("生成完成, 可一键安装");
        t1.setTextColor(MdTheme.isDark(this) ? 0xFFA5D6A7 : 0xFF2E7D32);
        t1.setTextSize(13);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(t1);
        card.addView(titleRow);
        for (final File f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            android.graphics.drawable.GradientDrawable rowBg =
                    new android.graphics.drawable.GradientDrawable();
            rowBg.setColor(MdTheme.isDark(this) ? 0xFF243822 : 0xFFF1F8E9);
            rowBg.setCornerRadius(dp(8));
            row.setBackground(rowBg);
            // 文件图标
            TextView fileIcon = new TextView(this);
            fileIcon.setText(IconFont.DOC);
            fileIcon.setTypeface(IconFont.get(this));
            fileIcon.setTextSize(14);
            fileIcon.setTextColor(MdTheme.isDark(this) ? 0xFF81C784 : 0xFF4CAF50);
            fileIcon.setPadding(0, 0, dp(6), 0);
            row.addView(fileIcon);
            TextView fn = new TextView(this);
            fn.setText(f.getName());
            fn.setTextColor(MdTheme.onSurface(this));
            fn.setTextSize(13);
            fn.setMaxLines(1);
            fn.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(fn, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            // 文件大小
            TextView sizeTv = new TextView(this);
            sizeTv.setText(formatSize(f.length()));
            sizeTv.setTextColor(MdTheme.onSurfaceVariant(this));
            sizeTv.setTextSize(11);
            sizeTv.setPadding(dp(8), 0, dp(8), 0);
            row.addView(sizeTv);
            final TextView inst = new TextView(this);
            boolean installed = ScriptStore.exists(this, f.getName());
            inst.setText(installed ? "✓" : IconFont.IMPORT);
            if (!installed) inst.setTypeface(IconFont.get(this));
            inst.setTextSize(installed ? 14 : 16);
            inst.setTextColor(0xFFFFFFFF);
            inst.setGravity(Gravity.CENTER);
            int isz = dp(32);
            android.graphics.drawable.GradientDrawable ibg =
                    new android.graphics.drawable.GradientDrawable();
            ibg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ibg.setColor(installed ? 0xFF4CAF50 : MdTheme.primary(this));
            inst.setBackground(ibg);
            inst.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
            inst.setEnabled(!installed);
            inst.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        String content = new String(
                                java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                        ScriptStore.write(AIActivity.this, f.getName(), content);
                        MdSnackbar.show(chatBox, "已安装: " + f.getName()
                                + " (脚本库可见)");
                        inst.setText("✓");
                        inst.setTypeface(Typeface.DEFAULT);
                        inst.setTextSize(14);
inst.setEnabled(false);
                    } catch (Exception e) {
                        MdSnackbar.show(chatBox, "安装失败: " + e.toString());
                    }
                }
            });
            row.addView(inst);
            card.addView(row);
        }
        return card;
    }

    /** 截断消息数组: 从前面删, 但保持 tool_calls↔tool 结果配对不被切断 */
    private void truncateMessages(JSONArray msgs, int maxLen) {
        while (msgs.length() > maxLen) {
            if (msgs.length() <= 2) break; // 至少保留 system + 最后一条
            // 检查第 0 条 (跳过 system, 从 index 0 开始删)
            JSONObject first = msgs.optJSONObject(0);
            String role = first == null ? "" : first.optString("role", "");
            // 如果第一条是 assistant+tool_calls, 必须连同后续 tool 消息一起删
            if ("assistant".equals(role) && first.has("tool_calls")) {
                // 删除 assistant + 后续所有连续 tool 消息
                msgs.remove(0);
                while (msgs.length() > 0) {
                    JSONObject next = msgs.optJSONObject(0);
                    if (next != null && "tool".equals(next.optString("role", ""))) {
                        msgs.remove(0);
                    } else {
                        break;
                    }
                }
            } else if ("tool".equals(role)) {
                // 孤立的 tool 消息 (理论上不该出现): 直接删
                msgs.remove(0);
            } else {
                // user 或纯文本 assistant: 直接删
                msgs.remove(0);
            }
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    // ================= 会话持久化 =================

    /** 把 JSON 消息数组复制进内存 messages */
    private void messagesTo(JSONArray src) {
        while (messages.length() > 0) messages.remove(0);
        for (int i = 0; i < src.length(); i++) messages.put(src.optJSONObject(i));
    }

    private JSONArray sessionMessages(JSONObject saved) {
        JSONArray a = saved.optJSONArray("messages");
        return a == null ? new JSONArray() : a;
    }

    /** 按内存 messages 渲染整个聊天区 (切换/恢复会话用) */
    private void renderMessages() {
        renderMessages(null);
    }

    /** 渲染聊天区 + 恢复该会话的生成文件卡片 */
    private void renderMessages(JSONObject saved) {
        chatBox.removeAllViews();
        thinkingBubble = null;
        streamTv = null;
        streamBuf.setLength(0);
        JSONArray msgs = saved == null ? messages : sessionMessages(saved);
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            String content = m.optString("content", "");
            // 跳过工具调用消息 (不在聊天区显示, 但保留在 messages 供 API 上下文)
            if ("tool".equals(role)) continue;
            if ("assistant".equals(role) && m.has("tool_calls") && content.isEmpty()) continue;
            if (content.isEmpty()) continue;
            if ("user".equals(role)) chatBox.addView(bubble(content, true));
            else if ("assistant".equals(role)) chatBox.addView(bubble(content, false));
        }
        // 恢复本会话生成过的脚本安装卡片 (文件在工作区持久存在)
        generated.clear();
        if (saved != null) {
            JSONArray gfs = saved.optJSONArray("generatedFiles");
            if (gfs != null) {
                for (int i = 0; i < gfs.length(); i++) {
                    File f = new File(AIEngine.workspace(this), gfs.optString(i));
                    if (f.exists()) generated.add(f);
                }
            }
        }
        if (!generated.isEmpty()) chatBox.addView(buildGenCard(generated));
        scrollToBottom();
    }

    /** 防抖保存: 消息变化后 600ms 内不重复写盘 */
    private void scheduleSave() {
        if (saveTask != null) chatScroll.removeCallbacks(saveTask);
        saveTask = new Runnable() {
            public void run() { saveSession(); }
        };
        chatScroll.postDelayed(saveTask, 600);
    }

    private void saveSession() {
        if (sessionId == null) return;
        JSONArray gfs = new JSONArray();
        for (File f : generated) gfs.put(f.getName());
        AISessionStore.save(this, sessionId, sessionTitle, messages, gfs);
    }

    /** 新建对话: 保存当前 → 清空界面 → 新会话 */
    private void newConversation() {
        if (busy) {
            MdSnackbar.show(chatBox, "AI 正在运行, 请稍候再新建");
            return;
        }
        saveSession();
        sessionId = java.util.UUID.randomUUID().toString();
        sessionTitle = "新对话";
        titleView.setText(sessionTitle);
        while (messages.length() > 0) messages.remove(0);
        chatBox.removeAllViews();
        thinkingBubble = null;
        streamTv = null;
        streamBuf.setLength(0);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST, sessionId).apply();
        snapshot = snapshotWorkspace();
        addAi("你好, 我是脚本助手 \n\n告诉我你想要什么脚本, 我会直接编写并在沙箱里测试好, 然后你一键安装到脚本库。\n\n 试试:\n• 帮我写一个 XX 签到脚本\n• 写个爬虫抓取 XX 网站数据\n• 批量重命名当前目录下的文件");
    }

    /** 清空 AI 工作区: 删除 AI 生成的所有临时文件 (带确认) */
    private void confirmClearWorkspace() {
        if (busy) {
            MdSnackbar.show(chatBox, "AI 正在运行, 请稍候再清理");
            return;
        }
        final File ws = AIEngine.workspace(this);
        final java.io.File[] files = ws.listFiles();
        int count = (files == null) ? 0 : files.length;
        final io.taskpro.md.MdDialog d = new io.taskpro.md.MdDialog(this);
        d.title("清空 AI 工作区");
        d.message(count == 0
                ? "工作区是空的, 无需清理。"
                : "确定删除工作区里的 " + count + " 个文件吗?\n\n"
                + "这些是 AI 生成/测试产生的临时文件, 已安装到脚本库的不受影响。");
        d.action("取消", new Runnable() { public void run() { d.dismiss(); } });
        if (count > 0) {
            d.actionPrimary("全部删除", new Runnable() {
                public void run() {
                    AIEngine.clearWorkspace(AIActivity.this);
                    snapshot = snapshotWorkspace();
                    generated.clear();
                    MdSnackbar.show(chatBox, "已清空工作区 (" + count + " 个文件)");
                    d.dismiss();
                }
            });
        } else {
            d.actionPrimary("知道了", new Runnable() { public void run() { d.dismiss(); } });
        }
        d.show();
    }

    /** 历史会话列表对话框 */
    private void showHistoryDialog() {
        saveSession();
        final java.util.List<JSONObject> list = AISessionStore.list(this);
        if (list.isEmpty()) {
            MdSnackbar.show(chatBox, "还没有历史对话");
            return;
        }
        final io.taskpro.md.MdDialog d = new io.taskpro.md.MdDialog(this);
        d.title("对话历史");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 搜索框
        final EditText searchInput = new EditText(this);
        searchInput.setHint("搜索对话…");
        searchInput.setTextSize(13);
        searchInput.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5);
        sg.setCornerRadius(dp(10));
        searchInput.setBackground(sg);
        box.addView(searchInput);
        // 列表容器
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);
        // 渲染列表
        Runnable renderList = new Runnable() {
            public void run() {
                listBox.removeAllViews();
                String q = searchInput.getText().toString().trim().toLowerCase(java.util.Locale.US);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
                for (final JSONObject o : list) {
                    String title = o.optString("title", "对话");
                    if (!q.isEmpty() && !title.toLowerCase(java.util.Locale.US).contains(q)) continue;
                    String t = title.length() > 14 ? title.substring(0, 14) + "…" : title;
                    String time = sdf.format(new java.util.Date(o.optLong("updatedAt")));
                    final String id = o.optString("id", "");
                    final String dispTitle = title;
                    LinearLayout row = new LinearLayout(AIActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(4), dp(8), dp(4));
                    // 会话图标 + 标题+时间
                    LinearLayout textCol = new LinearLayout(AIActivity.this);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    textCol.setPadding(dp(4), dp(4), dp(4), dp(4));
                    TextView label = new TextView(AIActivity.this);
                    label.setText(t);
                    label.setTextColor(MdTheme.onSurface(AIActivity.this));
                    label.setTextSize(14);
                    label.setTypeface(Typeface.DEFAULT_BOLD);
                    textCol.addView(label);
                    TextView timeLbl = new TextView(AIActivity.this);
                    timeLbl.setText(time);
                    timeLbl.setTextColor(MdTheme.onSurfaceVariant(AIActivity.this));
                    timeLbl.setTextSize(11);
                    textCol.addView(timeLbl);
                    row.addView(textCol, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    // 删除按钮
                    TextView del = new TextView(AIActivity.this);
                    del.setText("✕");
                    del.setTextColor(0xFFFF5252);
                    del.setTextSize(16);
                    del.setPadding(dp(12), dp(4), dp(12), dp(4));
                    del.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            final io.taskpro.md.MdDialog confirm = new io.taskpro.md.MdDialog(AIActivity.this);
                            confirm.title("删除对话");
                            confirm.message("确定删除「" + dispTitle + "」?");
                            confirm.action("取消", new Runnable() { public void run() { confirm.dismiss(); } });
                            confirm.actionPrimary("删除", new Runnable() {
                                public void run() {
                                    confirm.dismiss();
                                    AISessionStore.delete(AIActivity.this, id);
                                    if (id.equals(sessionId)) newConversation();
                                    else d.dismiss();
                                    MdSnackbar.show(chatBox, "已删除");
                                }
                            });
                            confirm.show();
                        }
                    });
                    row.addView(del);
                    row.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            d.dismiss();
                            if (!id.equals(sessionId)) switchSession(id);
                        }
                    });
                    listBox.addView(row);
                }
                if (listBox.getChildCount() == 0) {
                    TextView empty = new TextView(AIActivity.this);
                    empty.setText("未找到匹配的对话");
                    empty.setTextColor(MdTheme.onSurfaceVariant(AIActivity.this));
                    empty.setTextSize(13);
                    empty.setPadding(0, dp(12), 0, dp(12));
                    empty.setGravity(Gravity.CENTER);
                    listBox.addView(empty);
                }
            }
        };
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderList.run();
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        renderList.run();
        d.content(box);
        d.actionPrimary("＋ 新建对话", new Runnable() {
            public void run() { d.dismiss(); newConversation(); }
        });
        d.action("关闭", new Runnable() {
            public void run() { d.dismiss(); }
        });
        d.show();
    }

    /** 切换到指定历史会话 */
    private void switchSession(String id) {
        JSONObject o = AISessionStore.load(this, id);
        if (o == null) return;
        saveSession();
        sessionId = id;
        sessionTitle = o.optString("title", "对话");
        titleView.setText(sessionTitle);
        messagesTo(sessionMessages(o));
        renderMessages(o);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST, id).apply();
        snapshot = snapshotWorkspace();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession();   // 误触返回/切后台: 对话立即落盘
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    // ================= Markdown 渲染 =================
    /** 设置气泡背景 (圆角) */
    private android.graphics.drawable.Drawable setupBubbleBg(boolean mine) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(mine ? MdTheme.primary(this)
                : (MdTheme.isDark(this) ? 0xFF2A2A2E : 0xFFF1F3F5));
        float r = dp(14);
        float sr = dp(4);
        if (mine) {
            g.setCornerRadii(new float[]{r, r, r, r, sr, sr, r, r});
        } else {
            g.setCornerRadii(new float[]{r, r, r, r, r, r, sr, sr});
        }
        return g;
    }
    /** 递归地将 Markdown 文本渲染为 View (支持 **bold** *italic* `code` ``` ``` 等) */
    private View renderMarkdown(String text, boolean mine) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        // 按行分割
        String[] lines = text.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            // 代码块 ``` ... ```
            if (trimmed.startsWith("```")) {
                String lang = trimmed.substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) code.append("\n");
                    code.append(lines[i]);
                    i++;
                }
                i++; // 跳过结束 ```
                container.addView(makeCodeBlock(code.toString(), mine));
                continue;
            }
            // 标题 # ## ###
            if (trimmed.startsWith("### ") && trimmed.length() > 4) {
                container.addView(makeHeading(trimmed.substring(4), 13, mine));
                i++;
                continue;
            }
            if (trimmed.startsWith("## ") && trimmed.length() > 3) {
                container.addView(makeHeading(trimmed.substring(3), 15, mine));
                i++;
                continue;
            }
            if (trimmed.startsWith("# ") && trimmed.length() > 2) {
                container.addView(makeHeading(trimmed.substring(2), 17, mine));
                i++;
                continue;
            }
            // 分割线 ---
            if (trimmed.matches("^[-]{3,}$") || trimmed.matches("^[*]{3,}$")) {
                View sep = new View(this);
                sep.setLayoutParams(new LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                sep.setBackgroundColor(MdTheme.outlineVariant(this));
                sep.setPadding(0, dp(6), 0, dp(6));
                container.addView(sep);
                i++;
                continue;
            }
            // 列表 - 或 *
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(1), 0, dp(1));
                TextView bullet = new TextView(this);
                bullet.setText("  \u2022  ");
                bullet.setTextSize(14);
                bullet.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
                row.addView(bullet);
                CharSequence styled = parseInline(trimmed.substring(2).trim(), mine);
                TextView tv = new TextView(this);
                tv.setText(styled);
                tv.setTextSize(14);
                tv.setLineSpacing(dp(2), 1f);
                tv.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
                row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
                container.addView(row);
                i++;
                continue;
            }
            // 有序列表 1. 2. 3.
            if (trimmed.matches("^\\d+\\..*")) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(1), 0, dp(1));
                String num = trimmed.replaceAll("^(\\d+)\\..*", "$1");
                TextView bullet = new TextView(this);
                bullet.setText("  " + num + ".  ");
                bullet.setTextSize(14);
                bullet.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
                row.addView(bullet);
                CharSequence styled = parseInline(trimmed.replaceAll("^\\d+\\.\\s*", ""), mine);
                TextView tv = new TextView(this);
                tv.setText(styled);
                tv.setTextSize(14);
                tv.setLineSpacing(dp(2), 1f);
                tv.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
                row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
                container.addView(row);
                i++;
                continue;
            }
            // 表格 |col1|col2|col3|
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                String[] parts = trimmed.split("\\|");
                if (parts.length > 3) {
                    // 跳过对齐行 |---|---|
                    if (parts.length > 1 && parts[1].trim().matches("^[-:]+$")) {
                        i++;
                        continue;
                    }
                    LinearLayout tableRow = new LinearLayout(this);
                    tableRow.setOrientation(LinearLayout.HORIZONTAL);
                    tableRow.setPadding(0, dp(1), 0, dp(1));
                    for (int ci = 1; ci < parts.length - 1; ci++) {
                        String cellText = parts[ci].trim();
                        if (cellText.isEmpty()) continue;
                        CharSequence styled = parseInline(cellText, mine);
                        TextView cell = new TextView(this);
                        cell.setText(styled);
                        cell.setTextSize(12);
                        cell.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
                        cell.setPadding(dp(6), dp(3), dp(6), dp(3));
                        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
                        cbg.setColor(MdTheme.isDark(this) ? 0x1AFFFFFF : 0x08000000);
                        cbg.setCornerRadius(dp(2));
                        cell.setBackground(cbg);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                        clp.rightMargin = dp(2);
                        tableRow.addView(cell, clp);
                    }
                    container.addView(tableRow);
                    i++;
                    continue;
                }
            }
            // 普通行（含内联样式）
            CharSequence styled = parseInline(line, mine);
            TextView tv = new TextView(this);
            tv.setText(styled);
            tv.setTextSize(14);
            tv.setLineSpacing(dp(2), 1f);
            tv.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
            tv.setPadding(0, dp(1), 0, dp(1));
            container.addView(tv);
            i++;
        }
        return container;
    }
    /** 解析内联样式: **bold** *italic* `code` */
    private CharSequence parseInline(String text, boolean mine) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            // 内联代码 `code`
            int codeStart = text.indexOf('`', pos);
            if (codeStart >= 0 && codeStart < len - 1) {
                int codeEnd = text.indexOf('`', codeStart + 1);
                if (codeEnd > codeStart) {
                    sb.append(text.substring(pos, codeStart));
                    String code = text.substring(codeStart + 1, codeEnd);
                    int start = sb.length();
                    sb.append(code);
                    sb.setSpan(new android.text.style.TypefaceSpan("monospace"), start, sb.length(), 0);
                    sb.setSpan(new android.text.style.BackgroundColorSpan(mine ? 0x44FFFFFF : 0x22000000), start, sb.length(), 0);
                    pos = codeEnd + 1;
                    continue;
                }
            }
            // 加粗 **text**
            int b1 = text.indexOf("**", pos);
            if (b1 >= 0 && b1 < len - 2) {
                int b2 = text.indexOf("**", b1 + 2);
                if (b2 > b1) {
                    sb.append(text.substring(pos, b1));
                    String bold = text.substring(b1 + 2, b2);
                    int start = sb.length();
                    sb.append(bold);
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), 0);
                    pos = b2 + 2;
                    continue;
                }
            }
            // 斜体 *text*
            int i1 = text.indexOf('*', pos);
            if (i1 >= 0 && i1 < len - 1 && text.charAt(i1 + 1) != '*') {
                int i2 = text.indexOf('*', i1 + 1);
                if (i2 > i1) {
                    sb.append(text.substring(pos, i1));
                    String italic = text.substring(i1 + 1, i2);
                    int start = sb.length();
                    sb.append(italic);
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, sb.length(), 0);
                    pos = i2 + 1;
                    continue;
                }
            }
            // 链接 [text](url)
            int l1 = text.indexOf('[', pos);
            if (l1 >= 0) {
                int l2 = text.indexOf(']', l1);
                int l3 = l2 > 0 ? text.indexOf('(', l2) : -1;
                int l4 = l3 > 0 ? text.indexOf(')', l3) : -1;
                if (l2 > l1 && l3 == l2 + 1 && l4 > l3) {
                    sb.append(text.substring(pos, l1));
                    String linkText = text.substring(l1 + 1, l2);
                    String linkUrl = text.substring(l3 + 1, l4);
                    int start = sb.length();
                    sb.append(linkText);
                    sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF3B82F6), start, sb.length(), 0);
                    sb.setSpan(new android.text.style.UnderlineSpan(), start, sb.length(), 0);
                    pos = l4 + 1;
                    continue;
                }
            }
            // 普通字符
            sb.append(text.charAt(pos));
            pos++;
        }
        return sb;
    }
    /** 创建标题 */
    private View makeHeading(String text, int size, boolean mine) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(mine ? 0xFFFFFFFF : MdTheme.onSurface(this));
        tv.setPadding(0, dp(4), 0, dp(2));
        return tv;
    }
    /** 创建代码块 */
    private View makeCodeBlock(String code, boolean mine) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(8), dp(6), dp(8), dp(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(mine ? 0x33FFFFFF : 0x0A000000);
        bg.setCornerRadius(dp(6));
        block.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        block.setLayoutParams(lp);
        // 每一行代码单独 TextView
        String[] clines = code.split("\n");
        for (String cl : clines) {
            TextView tv = new TextView(this);
            tv.setText(cl);
            tv.setTextSize(11);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextColor(mine ? 0xCCFFFFFF : 0xFF1A1A2E);
            tv.setLineSpacing(dp(1), 1f);
            block.addView(tv);
        }
        // 复制代码按钮
        final String fCode = code;
        TextView copyBtn = new TextView(this);
        copyBtn.setText("复制代码");
        copyBtn.setTextSize(10);
        copyBtn.setTextColor(mine ? 0x88FFFFFF : MdTheme.primary(this));
        copyBtn.setPadding(0, dp(4), 0, 0);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("code", fCode));
                io.taskpro.md.MdSnackbar.show(chatBox, "代码已复制");
            }
        });
        block.addView(copyBtn);
        return block;
    }

}