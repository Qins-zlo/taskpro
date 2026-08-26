package io.taskpro;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** AI 会话持久化: files/ai_sessions/<id>.json
 *  退出 App / 误触返回后对话不丢失, 可随时切换历史会话或新建对话 */
public class AISessionStore {

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "ai_sessions");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File file(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    public static void save(Context c, String id, String title, JSONArray messages,
                            JSONArray generatedNames) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title == null ? "对话" : title);
            o.put("updatedAt", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (int i = 0; i < messages.length(); i++) arr.put(messages.optJSONObject(i));
            o.put("messages", arr);
            if (generatedNames != null && generatedNames.length() > 0) {
                o.put("generatedFiles", generatedNames);
            }
            File f = file(c, id);
            File tmp = new File(f.getParentFile(), id + ".tmp");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            fo.write(o.toString().getBytes("UTF-8"));
            fo.close();
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    public static JSONObject load(Context c, String id) {
        try {
            File f = file(c, id);
            if (!f.exists()) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除指定会话 */
    public static void delete(Context c, String id) {
        File f = file(c, id);
        if (f.exists()) f.delete();
    }

    /** 会话列表, 按 updatedAt 倒序 */
    public static List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        File[] fs = dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) continue;
                JSONObject o = load(c, f.getName().replace(".json", ""));
                if (o != null) out.add(o);
            }
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return (int) (b.optLong("updatedAt") - a.optLong("updatedAt"));
            }
        });
        return out;
    }
}
