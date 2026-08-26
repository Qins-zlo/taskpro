package io.taskpro;

import android.content.Context;
import android.graphics.Typeface;

/** 单色矢量图标字体 (Material Icons), 替代 emoji */
public class IconFont {
    private static Typeface tf;

    public static Typeface get(Context c) {
        if (tf == null) {
            try {
                tf = Typeface.createFromAsset(c.getAssets(), "icons.ttf");
            } catch (Exception e) {
                tf = Typeface.DEFAULT;
            }
        }
        return tf;
    }

    // Material Icons codepoints (classic)
    public static final String HISTORY = "\uE889";        // 历史
    public static final String ADD = "\uE145";            // 新建
    public static final String BUILD = "\uE869";          // 工具
    public static final String IMAGE = "\uE3F4";          // 图片
    public static final String MOVIE = "\uE404";          // 视频
    public static final String MUSIC = "\uE405";          // 音频
    public static final String DOC = "\uE873";            // 文档
    public static final String ARCHIVE = "\uE149";        // 压缩包
    public static final String FOLDER = "\uE2C7";         // 文件
    public static final String UPLOAD = "\uE226";         // 上传/选择文件
    public static final String CHECK = "\uE5CA";          // 勾选
    public static final String WARNING = "\uE002";        // 警告
    public static final String CHAT = "\uE0B7";           // 会话
    public static final String KEY = "\uE3C9";            // 环境变量 (settings_ethernet)
    public static final String SETTINGS = "\uE8B8";       // AI 配置
    public static final String HOME = "\uE88A";           // 基础模式
    public static final String CHECK_CIRCLE = "\uE86C";   // 完成
    public static final String TERMINAL_IC = "\uE8D3";    // 终端
    public static final String SHIELD = "\uE877";         // 盾牌 (后台常驻)
    public static final String BATTERY = "\uE19C";        // 电池 (电池优化)
    public static final String STAR = "\uE838";           // 星 (赞助)
    public static final String BUG = "\uE868";            // bug (错误日志)
    public static final String CHART = "\uE26B";          // 柱状图 (统计)
    public static final String SHARE = "\uE80D";          // 分享 (导出)
    public static final String IMPORT = "\uE2C6";         // 下载/导入 (导入)
    public static final String SEND = "\uE163";           // 发送 (paper plane)
    public static final String SMART_TOY = "\uE65F";      // AI 头像 (auto_awesome ✨) — 仅用于引用, 实际用 Icons.AI_AVATAR
    public static final String PERSON = "\uE7FD";         // 用户头像
    public static final String INFO = "\uE88E";           // info (关于)
    public static final String EXPAND_MORE = "\uE5CF";   // 展开 (expand_more)
    public static final String EXPAND_LESS = "\uE5CE";   // 收起 (expand_less)
    public static final String DELETE = "\uE872";         // 垃圾桶 (delete)
    public static final String CLEAR = "\uE14C";          // 清空 (clear/删除内容)
}
