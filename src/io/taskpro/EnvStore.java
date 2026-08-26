package io.taskpro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 环境变量管理 (青龙面板 env 概念)
 * 存于 SharedPreferences, 支持增删改查, 脚本执行时可注入。
 */
public class EnvStore {
    private static final String PREFS = "env_store";
    private static final String KEY = "envs";

    public static class Env {
        public String name;
        public String value;
        public String remark = "";
        public Env(String name, String value) { this.name = name; this.value = value; }
    }

    /** 密文前缀标记: 开头带此标记说明是 AES 加密过的敏感变量 */
    private static final String SEC_PREFIX = "enc:";

    /**
     * 判断变量名是否属于敏感变量 (需要加密存储)。
     * 规则: 变量名大写后包含 COOKIE/TOKEN/PWD/PASS/SECRET/KEY 等关键字。
     * 纯界面显示用的常见变量名 (如 KEY 结尾的短名) 也会加密, 可接受;
     * 非敏感变量保持明文, 便于排查问题。
     */
    public static boolean isSensitive(String name) {
        if (name == null) return false;
        String up = name.toUpperCase();
        return up.contains("COOKIE") || up.contains("TOKEN")
                || up.contains("PWD") || up.contains("PASS")
                || up.contains("SECRET") || up.contains("APIKEY")
                || up.contains("AUTH") || up.contains("SESSION")
                || up.contains("_KEY") || up.endsWith("KEY");
    }

    /** 保存时加密敏感值: 密文加前缀, 便于 load 时识别 */
    private static String secure(String name, String value) {
        if (!isSensitive(name)) return value;
        if (value == null || value.isEmpty()) return value;
        String c = Crypto.enc(value);
        return c.isEmpty() ? value : SEC_PREFIX + c;
    }

    /** 加载时解密: 带前缀的解开, 不带前缀的按明文返回 (兼容旧数据) */
    private static String reveal(String name, String value) {
        if (value == null || !value.startsWith(SEC_PREFIX)) return value;
        String plain = Crypto.dec(value.substring(SEC_PREFIX.length()));
        return plain == null || plain.isEmpty() ? value : plain;
    }

    public static List<Env> load(Context ctx) {
        List<Env> list = new ArrayList<Env>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String n = o.optString("name", "");
                Env e = new Env(n, reveal(n, o.optString("value", "")));
                e.remark = o.optString("remark", "");
                list.add(e);
            }
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
        return list;
    }

    public static void save(Context ctx, List<Env> list) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (Env e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("value", secure(e.name, e.value));
                o.put("remark", e.remark);
                arr.put(o);
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    /** 读取单个变量(脚本内可用) */
    public static String get(Context ctx, String name) {
        for (Env e : load(ctx)) if (e.name.equals(name)) return e.value;
        return null;
    }
}
