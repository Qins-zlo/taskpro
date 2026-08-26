# 定时任务Pro (v7.32) — 完整源码包

本包包含 **全部源码 + 全部运行时**，解压后可直接 `sh build.sh` 构建 APK，无需联网下载任何运行时。

## 包内结构

```
taskpro/
├── src/io/taskpro/          # Java 源码 (24 个文件)
│   ├── MainActivity.java    # 主界面 (任务列表/搜索)
│   ├── AdvActivity.java     # 高级页 (脚本库/产物/日志/终端/环境变量)
│   ├── AIActivity.java      # AI 助手会话
│   ├── TaskEngine.java      # 任务执行引擎
│   ├── RuntimeManager.java  # 运行时管理 (命令重写/PATH注入)
│   ├── TerminalView.java    # 终端模拟器
│   ├── md/                  # Material 3 组件 (MdTheme/MdButton/...)
│   └── ... (TaskStore/AlarmScheduler/CronParser/ScriptStore 等)
├── lib/arm64-v8a/           # ★ APK 内置 native 运行时 (构建必需)
│   ├── node                 # Node.js v22 (aarch64-android, 49MB)
│   ├── libpython3_main.so   # Python 3.14 解释器主程序
│   ├── libpython3.14.so     # Python 核心库
│   ├── *.cpython-314-aarch64-linux-android.so  # Python C 扩展模块
│   ├── busybox              # BusyBox (shell 工具集)
│   ├── libcurl_main.so      # curl 8.x (HTTPS/JSON)
│   └── libssl/libicu/libsqlite3/...  # 依赖动态库
├── assets/                  # ★ APK 资源 (构建必需)
│   ├── termux_lib.tar.gz    # Python 3.14 纯 Python 标准库 + pip (6.5MB)
│   ├── termux_pkgs.tar.gz   # requests/certifi 等第三方包 (441KB)
│   ├── termux_ca.pem        # CA 根证书
│   └── icons.ttf            # Material 图标字体
├── res/                     # 布局/主题资源 (浅色+深色)
├── AndroidManifest.xml
├── build.sh                 # 一键构建脚本
├── taskrun.keystore         # APK 签名密钥 (存在则重用, 不存在自动生成)
└── mkicon.py
```

## 构建

```sh
# 需要: java 8 (javac/java/keytool) + qemu-x86_64 + Android 工具链
# (aapt2/zipalign/d8.jar/apksigner.jar 位于 /opt/android/bt30/android-11/,
#  platform-34 android.jar 位于 /opt/android/platform-34/android-34-ext12/)
sh build.sh
# 输出: out/定时任务.apk
```

## 运行时是如何工作的

安卓 ROM 常把 app 数据目录挂载为 noexec，因此 Python/Node 解释器
**不能**放在 `files/` 下执行。本项目把全部可执行文件打包进 APK 的
`lib/arm64-v8a/`，Android 安装时系统自动解压到 nativeLibraryDir
(`/data/app/<pkg>/lib/arm64`，系统保证可执行)：

- `RuntimeManager.buildCommand()` 注入 PATH/LD_LIBRARY_PATH/PYTHONHOME/PYTHONPATH
- `rewriteCommand()` 把脚本中的 `python3/pip/node/busybox/curl`
  重写为 nativeLibraryDir 完整路径
- 纯 .py 标准库从 assets 解压到 `files/termux/usr` (可写, 无执行需求)
- 生成 sh wrapper (`files/termux/usr/bin/python3` 等) 指向真实二进制

删除 lib/ 或 assets/ 后构建出的 APK **将无法运行 python/js 脚本**，
请勿精简这两个目录。