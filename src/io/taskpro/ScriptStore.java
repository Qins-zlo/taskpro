package io.taskpro;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 脚本库管理: 在 app 私有目录 scripts/ 下保存真实脚本文件
 * 支持 JS / Python / Shell / 其他文本脚本
 */
public class ScriptStore {
    private static final String DIR = "scripts";

    public static class Script {
        public String name;   // 文件名(含扩展名)
        public String content;
        public String type;   // js/py/sh/other
        public long mtime;
        public Script(String name, String content, String type, long mtime) {
            this.name = name; this.content = content; this.type = type; this.mtime = mtime;
        }
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String typeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "js";
        if (n.endsWith(".py")) return "py";
        if (n.endsWith(".sh")) return "sh";
        return "other";
    }

    /** 是否脚本配置文件 (内部数据, 不应出现在脚本列表) */
    public static boolean isMetaFile(String name) {
        if (name == null) return true;
        String n = name.toLowerCase();
        return n.endsWith(".conf.json") || n.endsWith(".conf")
                || n.endsWith(".bak") || n.endsWith(".tmp")
                || n.startsWith(".");
    }

    public static List<Script> list(Context ctx) {
        List<Script> list = new ArrayList<Script>();
        File[] files = dir(ctx).listFiles();
        if (files == null) return list;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return b.lastModified() > a.lastModified() ? 1 : -1; }
        });
        for (File f : files) {
            if (f.isFile() && !isMetaFile(f.getName())) {
                list.add(new Script(f.getName(), "", typeOf(f.getName()), f.lastModified()));
            }
        }
        return list;
    }

    public static String read(Context ctx, String name) {
        try {
            File f = new File(dir(ctx), name);
            if (!f.exists()) return "";
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;          // EOF
                off += r;
            }
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Exception e) { return ""; }
    }

    public static void write(Context ctx, String name, String content) {
        try {
            File f = new File(dir(ctx), name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
            out.close();
        } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
    }

    public static void delete(Context ctx, String name) {
        File f = new File(dir(ctx), name);
        if (f.exists()) f.delete();
    }

    public static boolean exists(Context ctx, String name) {
        return new File(dir(ctx), name).exists();
    }

    /** 脚本版本记录文件 (市场安装时保存, 用于"已是最新"标记) */
    private static File verFile(Context ctx) {
        return new File(ctx.getFilesDir(), "script_versions.json");
    }

    public static String verOf(Context ctx, String name) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(verFile(ctx).toPath()), "UTF-8");
            org.json.JSONObject o = new org.json.JSONObject(s);
            return o.optString(name, "");
        } catch (Exception e) { return ""; }
    }

    public static void saveVer(Context ctx, String name, String ver) {        try {
            org.json.JSONObject o = new org.json.JSONObject();
            String s = "";
            if (verFile(ctx).exists()) {
                s = new String(java.nio.file.Files.readAllBytes(verFile(ctx).toPath()), "UTF-8");
                o = new org.json.JSONObject(s);
            }
            o.put(name, ver == null ? "" : ver);
            java.io.FileOutputStream out = new java.io.FileOutputStream(verFile(ctx));
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 脚本参数配置 (scripts/<name>.conf.json): 读为 Map, 无配置返回 null */
    public static java.util.Map<String, String> confOf(Context ctx, String name) {
        try {
            java.io.File f = new java.io.File(dir(ctx), name + ".conf.json");
            if (!f.exists()) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            org.json.JSONObject o = new org.json.JSONObject(s);
            java.util.Map<String, String> m = new java.util.HashMap<String, String>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("__timeout__".equals(k)) continue; // 超时是内部字段, 不注入环境变量
                m.put(k, o.optString(k, ""));
            }
            return m;
        } catch (Exception e) { return null; }
    }

    /** 脚本参数配置原始读 (含 __timeout__ 等内部字段, 用于合并保留), 无配置返回 null */
    public static java.util.Map<String, String> confOfRaw(Context ctx, String name) {
        try {
            java.io.File f = new java.io.File(dir(ctx), name + ".conf.json");
            if (!f.exists()) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            org.json.JSONObject o = new org.json.JSONObject(s);
            java.util.Map<String, String> m = new java.util.HashMap<String, String>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optString(k, ""));
            }
            return m;
        } catch (Exception e) { return null; }
    }

    public static void saveConf(Context ctx, String name, org.json.JSONObject o) {
        try {
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new java.io.File(dir(ctx), name + ".conf.json"));
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 脚本执行超时(秒): 存于 .conf.json 的 __timeout__ 字段, 未设置返回默认 180 */
    public static long getTimeout(Context ctx, String name) {
        try {
            java.io.File f = new java.io.File(dir(ctx), name + ".conf.json");
            if (!f.exists()) return 180L;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            org.json.JSONObject o = new org.json.JSONObject(s);
            long t = o.optLong("__timeout__", 0);
            if (t < 1) return 180L;
            if (t > 86400L) t = 86400L; // 上限 24h
            return t;
        } catch (Exception e) { return 180L; }
    }

    /** 保存脚本超时(秒); sec<=0 时删除超时字段, 回退默认 180s */
    public static void saveTimeout(Context ctx, String name, long sec) {
        try {
            java.io.File f = new java.io.File(dir(ctx), name + ".conf.json");
            org.json.JSONObject o = new org.json.JSONObject();
            if (f.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                o = new org.json.JSONObject(s);
            }
            if (sec > 0) o.put("__timeout__", sec);
            else o.remove("__timeout__");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    public static boolean hasConf(Context ctx, String name) {
        return new java.io.File(dir(ctx), name + ".conf.json").exists();
    }

    /** 解析脚本头部注释中的变量声明: "// 变量: A=说明, B=说明2" 或 "# 变量: ..." */
    public static org.json.JSONArray parseVars(String content) {
        org.json.JSONArray out = new org.json.JSONArray();
        try {
            if (content == null) return out;
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (t.startsWith("//") || t.startsWith("#")) {
                    String c = t.replaceFirst("^[/#]+", "").trim();
                    int idx = c.indexOf("变量:");
                    if (idx < 0) idx = c.indexOf("变量：");
                    if (idx >= 0) {
                        String vars = c.substring(idx + 3).trim();
                        for (String item : vars.split("[,，]")) {
                            item = item.trim();
                            if (item.isEmpty()) continue;
                            String[] kv = item.split("=", 2);
                            String key = kv[0].trim();
                            if (key.isEmpty()) continue;
                            String label = kv.length > 1 ? kv[1].trim() : key;
                            org.json.JSONObject o = new org.json.JSONObject();
                            o.put("key", key);
                            o.put("label", label);
                            String up = key.toUpperCase();
                            o.put("password", up.contains("PWD") || up.contains("PASS")
                                    || up.contains("TOKEN") || up.contains("SECRET")
                                    || up.contains("APIKEY"));
                            out.put(o);
                        }
                    }
                }
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        return out;
    }
}
