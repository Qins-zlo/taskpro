# 定时任务Pro (TaskPro)

> 一款运行在 Android 上的**定时任务 / 脚本执行引擎**，内置 Python 3.14 + Node.js + Shell 运行时，无需 root，无需额外安装解释器。

![Android](https://img.shields.io/badge/Platform-Android-3DDC84) ![Python](https://img.shields.io/badge/Runtime-Python%203.14-3776AB) ![Node](https://img.shields.io/badge/Runtime-Node.js-339933) ![Version](https://img.shields.io/badge/Version-7.65-blue)

---

## ✨ 功能特性

### 🗓️ 任务调度
- **cron 表达式**定时执行 HTTP 请求 / Shell 命令
- 支持「每隔几分钟/几小时/几天」的快捷定时
- 系统闹钟驱动（`AlarmManager`），App 被杀也能准点触发
- 执行结果推送通知（成功可静默 / 失败必通知）

### 📜 脚本执行
- **多语言运行时**：Python 3.14 + Node.js + Shell (BusyBox)
- 内置 **cURL**，支持 HTTPS / JSON / Cookie
- 脚本**变量系统**：注释声明变量 → 自动注入为环境变量
- 敏感信息自动遮罩（`TOKEN` / `PASSWORD` / `COOKIE` / `SECRET` / `APIKEY`）
- 脚本**市场**：从后端安装/更新脚本

### 🖥️ 沉浸式终端
- 交互式 Shell，Termux 风格 extra-keys 键盘
- 快捷命令 / 字体大小调节 / 中断运行

### 🤖 AI 助手
- OpenAI 兼容 API（支持 DeepSeek / Qwen / GPT 等多提供商）
- 可选联网搜索

### 📦 其他
- 环境变量库（独立管理，供脚本注入）
- 我的产物（下载/生成的文件管理）
- 数据备份/恢复、一键导入导出任务/脚本/日志
- 执行统计、深色/浅色主题、自定义主题色

---

## 🏗️ 项目结构

```
taskpro/
├── src/io/taskpro/          # Java 源码
│   ├── MainActivity.java    # 基础模式主界面
│   ├── AdvActivity.java     # 高级模式 (脚本库/产物/日志/终端/环境变量)
│   ├── AIActivity.java      # AI 助手会话
│   ├── TaskEngine.java      # 任务执行引擎
│   ├── RuntimeManager.java  # 运行时管理 (命令重写/PATH/LD_LIBRARY_PATH 注入)
│   ├── TerminalView.java    # 终端模拟器
│   ├── md/                  # Material 3 纯代码组件 (无 XML 依赖)
│   └── ... (TaskStore/AlarmScheduler/CronParser/ScriptStore 等)
├── lib/arm64-v8a/           # ★ APK 内置 native 运行时 (构建必需)
│   ├── node                 # Node.js (aarch64-android, ~49MB)
│   ├── libpython3.14.so     # Python 3.14 核心库
│   ├── *.cpython-314-aarch64-linux-android.so  # Python C 扩展
│   ├── busybox / libcurl    # Shell 工具集 + curl
│   └── libssl/libicu/libsqlite3/...  # 依赖动态库
├── assets/                  # ★ APK 资源 (构建必需)
│   ├── termux_lib.tar.gz    # Python 3.14 纯 Python 标准库 + pip
│   ├── termux_pkgs.tar.gz   # requests/certifi 等第三方包
│   ├── termux_ca.pem        # CA 根证书
│   └── icons.ttf            # Material 图标字体
├── res/                     # 资源 (浅色+深色)
├── AndroidManifest.xml
├── build.sh                 # 一键构建脚本
└── mkicon.py
```

---

## 🔧 构建

```sh
# 依赖: JDK 8+ / Android SDK 工具链
# (aapt2 / d8 / apksigner / zipalign / platform-34 android.jar)
sh build.sh
# 输出: out/taskpro.apk
```

> 全部运行时已随仓库打包，**无需联网下载任何依赖**即可构建出完整可运行的 APK。
>
> ⚠️ 构建时会生成/使用 `taskrun.keystore` 签名密钥，**该文件不会提交到仓库**（已加入 `.gitignore`）。请自行保管你的签名密钥。

---

## 🧠 运行时工作原理

Android ROM 常把 app 数据目录挂载为 `noexec`，导致 Python/Node 解释器**无法放在 `files/` 下执行**。

本项目把全部可执行文件打包进 APK 的 `lib/arm64-v8a/`，Android 安装时系统自动解压到 `nativeLibraryDir`（该目录保证可执行）：

1. `RuntimeManager.buildCommand()` 注入 `PATH` / `LD_LIBRARY_PATH` / `PYTHONHOME` / `PYTHONPATH`
2. `rewriteCommand()` 把脚本中的 `python3 / pip / node / busybox / curl` 重写为 nativeLibraryDir 完整路径
3. 纯 `.py` 标准库从 assets 解压到 `files/termux/usr`（可写、无执行需求）
4. 生成 shell wrapper 指向真实二进制

删除 `lib/` 或 `assets/` 后构建的 APK **将无法运行 python/js 脚本**。

---

## ⚖️ 免责声明

本项目仅供**学习、研究与合法自动化**用途。请勿用于任何违反法律法规、平台规则或侵犯他人权益的场景。使用本软件产生的一切后果由使用者自行承担。

---

## 📄 License

MIT License (请在使用/分发时保留原版权声明)

---

## 🌟 支持

如果你觉得这个项目有帮助，欢迎 Star / Fork。项目由纯 Java 程序化 UI 构建，运行在 Sandbox 环境，欢迎交流技术问题。
