package io.taskpro;

import java.util.HashMap;
import java.util.Map;

/**
 * 轻量拼音工具: 无第三方库, 内置常用汉字 → 拼音 映射表。
 * 用于脚本搜索支持拼音首字母/全拼 (如输入 dds 搜「度大师」)。
 * 覆盖常用字 + 脚本/任务常见命名用字, 未收录的字原样返回(仍可被中文匹配兜底)。
 */
public class PinyinUtil {
    private static final Map<Character, String> MAP = new HashMap<Character, String>();
    private static volatile String cacheKey = null;
    private static volatile String cachePinyin = null;

    static {
        // 常用汉字 → 拼音 (首字母小写全拼)
        String[][] pairs = {
            {"度", "du"}, {"大", "da"}, {"师", "shi"},
            {"签", "qian"}, {"到", "dao"}, {"东", "dong"}, {"京", "jing"},
            {"小", "xiao"}, {"米", "mi"}, {"红", "hong"}, {"包", "bao"},
            {"天", "tian"}, {"气", "qi"}, {"预", "yu"}, {"报", "bao"},
            {"青", "qing"}, {"龙", "long"}, {"任", "ren"}, {"务", "wu"},
            {"脚", "jiao"}, {"本", "ben"}, {"库", "ku"}, {"市", "shi"}, {"场", "chang"},
            {"变", "bian"}, {"量", "liang"}, {"环", "huan"}, {"境", "jing"},
            {"超", "chao"}, {"时", "shi"}, {"定", "ding"}, {"时", "shi"},
            {"执", "zhi"}, {"行", "xing"}, {"日", "ri"}, {"志", "zhi"},
            {"产", "chan"}, {"物", "wu"}, {"文", "wen"}, {"件", "jian"},
            {"下", "xia"}, {"载", "zai"}, {"上", "shang"}, {"传", "chuan"},
            {"任", "ren"}, {"务", "wu"}, {"列", "lie"}, {"表", "biao"},
            {"成", "cheng"}, {"功", "gong"}, {"失", "shi"}, {"败", "bai"},
            {"新", "xin"}, {"建", "jian"}, {"删", "shan"}, {"除", "chu"},
            {"复", "fu"}, {"制", "zhi"}, {"粘", "zhan"}, {"贴", "tie"},
            {"运", "yun"}, {"行", "xing"}, {"终", "zhong"}, {"端", "duan"},
            {"历", "li"}, {"史", "shi"}, {"清", "qing"}, {"屏", "ping"},
            {"搜", "sou"}, {"索", "suo"}, {"筛", "shai"}, {"选", "xuan"},
            {"备", "bei"}, {"份", "fen"}, {"导", "dao"}, {"出", "chu"},
            {"入", "ru"}, {"更", "geng"}, {"新", "xin"}, {"版", "ban"},
            {"本", "ben"}, {"安", "an"}, {"装", "zhuang"}, {"卸", "xie"},
            {"启", "qi"}, {"动", "dong"}, {"停", "ting"}, {"止", "zhi"},
            {"重", "chong"}, {"启", "qi"}, {"关", "guan"}, {"开", "kai"},
            {"测", "ce"}, {"试", "shi"}, {"调", "tiao"}, {"试", "shi"},
            {"错", "cuo"}, {"报", "bao"}, {"错", "cuo"}, {"提", "ti"}, {"示", "shi"},
            {"信", "xin"}, {"息", "xi"}, {"数", "shu"}, {"据", "ju"},
            {"用", "yong"}, {"户", "hu"}, {"密", "mi"}, {"码", "ma"},
            {"账", "zhang"}, {"号", "hao"}, {"登", "deng"}, {"录", "lu"},
            {"签", "qian"}, {"退", "tui"}, {"注", "zhu"}, {"销", "xiao"},
            {"购", "gou"}, {"买", "mai"}, {"订", "ding"}, {"单", "dan"},
            {"快", "kuai"}, {"递", "di"}, {"物", "wu"}, {"流", "liu"},
            {"电", "dian"}, {"话", "hua"}, {"邮", "you"}, {"箱", "xiang"},
            {"验", "yan"}, {"证", "zheng"}, {"码", "ma"}, {"短", "duan"},
            {"信", "xin"}, {"通", "tong"}, {"知", "zhi"}, {"推", "tui"}, {"送", "song"},
            {"微", "wei"}, {"博", "bo"}, {"抖", "dou"}, {"音", "yin"},
            {"京", "jing"}, {"东", "dong"}, {"淘", "tao"}, {"宝", "bao"},
            {"拼", "pin"}, {"多", "duo"}, {"多", "duo"}, {"美", "mei"}, {"团", "tuan"},
            {"饿", "e"}, {"了", "le"}, {"么", "me"}, {"外", "wai"}, {"卖", "mai"},
            {"支", "zhi"}, {"付", "fu"}, {"宝", "bao"}, {"微", "wei"}, {"信", "xin"},
            {"苹", "ping"}, {"果", "guo"}, {"华", "hua"}, {"为", "wei"},
            {"小", "xiao"}, {"爱", "ai"}, {"同", "tong"}, {"学", "xue"},
            {"校", "xiao"}, {"园", "yuan"}, {"宿", "su"}, {"舍", "she"},
            {"工", "gong"}, {"作", "zuo"}, {"日", "ri"}, {"记", "ji"},
            {"备", "bei"}, {"忘", "wang"}, {"录", "lu"}, {"随", "sui"}, {"笔", "bi"},
            {"个", "ge"}, {"人", "ren"}, {"中", "zhong"}, {"心", "xin"},
            {"设", "she"}, {"置", "zhi"}, {"帮", "bang"}, {"助", "zhu"},
            {"关", "guan"}, {"于", "yu"}, {"版", "ban"}, {"权", "quan"},
            {"隐", "yin"}, {"私", "si"}, {"政", "zheng"}, {"策", "ce"},
            {"图", "tu"}, {"片", "pian"}, {"视", "shi"}, {"频", "pin"},
            {"音", "yin"}, {"乐", "le"}, {"文", "wen"}, {"档", "dang"},
            {"压", "ya"}, {"缩", "suo"}, {"包", "bao"}, {"文", "wen"}, {"件", "jian"},
            {"日", "ri"}, {"历", "li"}, {"月", "yue"}, {"周", "zhou"}, {"年", "nian"},
            {"今", "jin"}, {"明", "ming"}, {"昨", "zuo"}, {"后", "hou"}, {"天", "tian"},
            {"早", "zao"}, {"上", "shang"}, {"中", "zhong"}, {"午", "wu"}, {"晚", "wan"},
            {"睡", "shui"}, {"觉", "jiao"}, {"起", "qi"}, {"床", "chuang"},
            {"刷", "shua"}, {"卡", "ka"}, {"签", "qian"}, {"到", "dao"}, {"打", "da"}, {"卡", "ka"},
            {"抢", "qiang"}, {"购", "gou"}, {"秒", "miao"}, {"杀", "sha"},
            {"监", "jian"}, {"控", "kong"}, {"爬", "pa"}, {"虫", "chong"},
            {"网", "wang"}, {"络", "luo"}, {"请", "qing"}, {"求", "qiu"},
            {"响", "xiang"}, {"应", "ying"}, {"超", "chao"}, {"时", "shi"},
            {"记", "ji"}, {"录", "lu"}, {"文", "wen"}, {"本", "ben"},
            {"格", "ge"}, {"式", "shi"}, {"化", "hua"}, {"解", "jie"}, {"析", "xi"},
        };
        for (String[] p : pairs) MAP.put(p[0].charAt(0), p[1]);
    }

    /** 取单个汉字拼音 (未收录返回 null) */
    public static String of(char c) {
        return MAP.get(c);
    }

    /** 全拼: 中文转拼音 (未收录的字返回原文) */
    public static String full(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String p = MAP.get(c);
            if (p != null) sb.append(p);
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 拼音首字母: 每个汉字取拼音首字母, 非汉字原样 */
    public static String initials(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String p = MAP.get(c);
            if (p != null && !p.isEmpty()) sb.append(p.charAt(0));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 搜索匹配: 支持 拼音首字母 / 全拼 / 中文 混合 (如 "dds" 可匹配 "度大师签到" 的 拼音首字母 d d s) */
    public static boolean matches(String hayName, String query) {
        if (hayName == null || query == null || query.trim().isEmpty()) return true;
        String q = query.trim().toLowerCase();
        if (hayName.toLowerCase().contains(q)) return true; // 直接中文/英文包含
        String full = full(hayName).toLowerCase();
        if (full.contains(q)) return true;                   // 全拼
        String in = initials(hayName).toLowerCase();
        if (in.contains(q)) return true;                     // 首字母
        return false;
    }
}