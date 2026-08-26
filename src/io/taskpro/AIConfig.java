package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;

/** AI 功能配置: API 地址 / 密钥 / 模型 (OpenAI 兼容格式)
 *  支持预设提供商 + 自定义 (兼容任意协议/格式) */
public class AIConfig {
    private static final String PREFS = "ai_config";
    private static final String K_BASE = "base_url";
    private static final String K_KEY = "api_key";
    private static final String K_MODEL = "model";
    private static final String K_SEARCH = "search_enabled";

    /** 预设提供商列表: 名称 / 默认 Base URL / 默认模型 / 备注 */
    public static final String[][] PROVIDERS = {
        { "DeepSeek",            "https://api.deepseek.com/v1",         "deepseek-chat",    "OpenAI 兼容, 国内可用" },
        { "OpenAI",              "https://api.openai.com/v1",           "gpt-4o-mini",      "官方, 需可访问网络" },
        { "通义千问 Qwen",        "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "阿里云百炼, OpenAI 兼容" },
        { "Kimi 月之暗面",        "https://api.moonshot.cn/v1",          "moonshot-v1-8k",   "OpenAI 兼容" },
        { "智谱 GLM",            "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",      "OpenAI 兼容" },
        { "豆包 火山方舟",         "https://ark.cn-beijing.volces.com/api/v3", "doubao-1-5-pro-32k-250115", "火山引擎, OpenAI 兼容" },
        { "本地 Ollama",         "http://127.0.0.1:11434/v1",           "qwen2.5",          "本机 Ollama, OpenAI 兼容" },
        { "Groq",                "https://api.groq.com/openai/v1",      "llama-3.3-70b-versatile", "快速推理" },
        { "Gemini",              "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "Google, OpenAI 兼容端点" },
        { "自定义",              "",                                     "",                 "任意 OpenAI 兼容接口" },
    };

    /** 当前选中的提供商索引 (0-based, 最后一项=自定义) */
    public static int providerIndex(Context c) {
        String base = c.getSharedPreferences(PREFS, 0).getString(K_BASE, "");
        if (base.isEmpty()) return 0;
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i][1].equals(base)) return i;
        }
        return PROVIDERS.length - 1;   // 自定义
    }

    public static String baseUrl(Context c) {
        String v = c.getSharedPreferences(PREFS, 0).getString(K_BASE, "");
        return v.isEmpty() ? PROVIDERS[0][1] : v;
    }

    public static String apiKey(Context c) {
        return c.getSharedPreferences(PREFS, 0).getString(K_KEY, "");
    }

    public static String model(Context c) {
        String v = c.getSharedPreferences(PREFS, 0).getString(K_MODEL, "");
        return v.isEmpty() ? PROVIDERS[0][2] : v;
    }

    public static boolean isConfigured(Context c) {
        return !baseUrl(c).isEmpty();
    }

    /** 联网搜索开关 */
    public static boolean searchEnabled(Context c) {
        return c.getSharedPreferences(PREFS, 0).getBoolean(K_SEARCH, true);
    }

    public static void setSearchEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean(K_SEARCH, enabled).apply();
    }

    public static void save(Context c, String base, String key, String model) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, 0).edit();
        if (base != null && !base.trim().isEmpty()) e.putString(K_BASE, base.trim().replaceAll("/+$", ""));
        if (key != null) e.putString(K_KEY, key.trim());
        if (model != null && !model.trim().isEmpty()) e.putString(K_MODEL, model.trim());
        e.apply();
    }
}
