package io.taskpro;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 青龙式 cron 解析器 (5段: 分 时 日 月 周)
 * 支持: * / 数字 / 范围 a-b / 步长 /n / 逗号列表
 * 用于匹配"当前时间是否命中", 配合每分钟闹钟检查。
 */
public class CronParser {

    public static class Field {
        public boolean[] bits = new boolean[64]; // 下标即值
        public boolean all = false;

        public boolean matches(int v) {
            return v >= 0 && v < bits.length && bits[v];
        }
    }

    /** 解析一个字段: 支持 星号 数字 范围 a-b 步长 n 逗号列表 */
    private static Field parseField(String expr, int min, int max) {
        Field f = new Field();
        String[] parts = expr.split(",");
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            int step = 1;
            String range = p;
            int slash = p.indexOf('/');
            if (slash >= 0) {
                range = p.substring(0, slash);
                try { step = Integer.parseInt(p.substring(slash + 1)); } catch (Exception e) { step = 1; }
                if (step < 1) step = 1;
            }
            if (range.equals("*")) {
                for (int i = min; i <= max; i += step) f.bits[i] = true;
                if (step == 1) f.all = true;
            } else if (range.contains("-")) {
                String[] r = range.split("-");
                int a, b;
                try {
                    a = Integer.parseInt(r[0].trim());
                    b = Integer.parseInt(r[1].trim());
                } catch (Exception e) { continue; }
                for (int i = a; i <= b; i += step) {
                    if (i >= min && i <= max) f.bits[i] = true;
                }
            } else {
                try {
                    int v = Integer.parseInt(range);
                    if (v >= min && v <= max) f.bits[v] = true;
                } catch (Exception e) { try { android.util.Log.w("TaskPro","catch: "+e.getMessage()); } catch(Exception __){} }
            }
        }
        return f;
    }

    /** 解析完整 cron, 返回 Field[5]; 失败返回 null */
    public static Field[] parse(String cron) {
        if (cron == null) return null;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 5) return null;
        try {
            Field[] fields = new Field[5];
            fields[0] = parseField(parts[0], 0, 59); // 分
            fields[1] = parseField(parts[1], 0, 23); // 时
            fields[2] = parseField(parts[2], 1, 31); // 日
            fields[3] = parseField(parts[3], 1, 12); // 月
            fields[4] = parseField(parts[4], 0, 6);  // 周(0=周日)
            return fields;
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前时间是否命中 cron */
    public static boolean matches(Field[] fields, Calendar cal) {
        if (fields == null) return false;
        int min = cal.get(Calendar.MINUTE);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int month = cal.get(Calendar.MONTH) + 1;
        // cron 0=Sun..6=Sat, cal.get(DAY_OF_WEEK): 1=Sun,2=Mon...7=Sat => dowCron = calDow-1
        int dowCron = cal.get(Calendar.DAY_OF_WEEK) - 1;
        if (!fields[0].matches(min)) return false;
        if (!fields[1].matches(hour)) return false;
        if (!fields[2].matches(day)) return false;
        if (!fields[3].matches(month)) return false;
        // 日/周同时指定时(青龙cron兼容): 任一满足即算(近似)
        if (!fields[4].all && !fields[2].all) {
            // 都指定: 满足任一
            if (!fields[4].matches(dowCron) && !fields[2].matches(day)) return false;
        } else if (!fields[4].all && fields[2].all) {
            if (!fields[4].matches(dowCron)) return false;
        } else if (fields[4].all && !fields[2].all) {
            if (!fields[2].matches(day)) return false;
        }
        return true;
    }

    /** 便捷: 完整 cron 字符串匹配当前时间 */
    public static boolean matches(String cron, Calendar cal) {
        return matches(parse(cron), cal);
    }

    /** 校验 cron 是否合法 */
    public static boolean isValid(String cron) {
        return parse(cron) != null;
    }
}
