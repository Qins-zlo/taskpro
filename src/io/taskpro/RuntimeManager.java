package io.taskpro;

import android.content.Context;

import java.io.File;
import java.io.InputStream;

/**
 * 运行时管理器 (nativeLibraryDir 方案)
 *
 * 设备限制背景: 部分 ROM 将 app 数据目录 (files/) 挂载为 noexec,
 * 导致 execve 二进制被拒。因此可执行文件与动态库全部打包进
 * APK 的 lib/arm64-v8a/, Android 安装时解压到 nativeLibraryDir
 * (/data/app/<pkg>/lib/arm64), 该目录系统保证可执行。
 *
 * 纯 Python 标准库 (.py) 与 site-packages 仍在 assets, 解压到 files/termux/usr。
 */
public class RuntimeManager {

    public static final String ASSET = "termux_lib.tar.gz";
    public static final String DIR = "termux";

    /** nativeLibraryDir: /data/app/<pkg>-xxx/lib/arm64 */
    public static File nativeDir(Context ctx) {
        try {
            return new File(ctx.getApplicationInfo().nativeLibraryDir);
        } catch (Exception e) {
            return new File("/data/app/" + ctx.getPackageName() + "/lib/arm64");
        }
    }

    /** assets 解压的 python 标准库根 (files/termux/usr) */
    public static File usrDir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR + "/usr");
    }

    /** 运行时是否已就绪 */
    public static boolean isReady(Context ctx) {
        File flag = new File(ctx.getFilesDir(), DIR + "/.ready");
        File py = new File(nativeDir(ctx), "libpython3_main.so");
        return flag.exists() && py.exists();
    }

    /**
     * 生成命令 wrapper (files/usr/bin/):
     * nativeLibraryDir 内的可执行文件名为 libpython3_main.so 等,
     * 无法用 python3 / pip / busybox 命令名在 PATH 中查到, 因此生成
     * sh 脚本 wrapper 指向真实二进制。sh 脚本由 /system/bin/sh 解释
     * 执行, 不受 files/ noexec 限制。
     */
    public static void ensureWrappers(Context ctx) {
        try {
            File bin = new File(usrDir(ctx), "bin");
            bin.mkdirs();
            String py = pythonBin(ctx);
            writeWrapper(bin, "python3", "exec " + py + " \"$@\"");
            writeWrapper(bin, "py3", "exec " + py + " \"$@\"");
            writeWrapper(bin, "pip", "exec " + py + " -m pip \"$@\"");
            writeWrapper(bin, "pip3", "exec " + py + " -m pip \"$@\"");
            writeWrapper(bin, "busybox", "exec " + busyboxBin(ctx) + " \"$@\"");
            writeWrapper(bin, "curl", "exec " + curlBin(ctx) + " \"$@\"");
            // npm 包装器: node 没有捆绑 npm, 第一次运行时自动下载 npm CLI
            writeNpmWrapper(bin, ctx);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    private static void writeNpmWrapper(File bin, Context ctx) throws Exception {
        String node = nodeBin(ctx);
        String npmCache = new File(ctx.getFilesDir(), "npm_cache").getAbsolutePath();
        String npmDir = npmCache + "/npm";
        String npmCli = npmDir + "/bin/npm-cli.js";
        String wrapper = "#!/system/bin/sh\n"
            + "export HOME=\"" + ctx.getFilesDir().getAbsolutePath() + "\"\n"
            + "export LD_LIBRARY_PATH=\"" + nativeDir(ctx).getAbsolutePath() + "\"\n"
            + "NPM_CACHE=\"" + npmCache + "\"\n"
            + "NPM_DIR=\"" + npmDir + "\"\n"
            + "NPM_CLI=\"$NPM_DIR/bin/npm-cli.js\"\n"
            + "if [ ! -f \"$NPM_CLI\" ]; then\n"
            + "  mkdir -p \"$NPM_CACHE\"\n"
            + "  echo \"[npm] 正在下载 npm CLI...\"\n"
            + "  " + curlBin(ctx) + " -sL -o \"$NPM_CACHE/npm.tgz\" \"https://registry.npmmirror.com/npm/-/npm-10.8.2.tgz\" 2>/dev/null\n"
            + "  if [ ! -f \"$NPM_CACHE/npm.tgz\" ]; then\n"
            + "    echo \"[npm] 下载失败, 请检查网络\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  mkdir -p \"$NPM_DIR\"\n"
            + "  cd \"$NPM_CACHE\" && " + busyboxBin(ctx) + " tar xzf npm.tgz -C \"$NPM_DIR\" --strip-components=1 2>/dev/null\n"
            + "  rm -f \"$NPM_CACHE/npm.tgz\"\n"
            + "  if [ ! -f \"$NPM_CLI\" ]; then\n"
            + "    echo \"[npm] 解压失败\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  chmod -R 755 \"$NPM_DIR\" 2>/dev/null\n"
            + "  echo \"[npm] npm CLI 准备就绪\"\n"
            + "fi\n"
            + "exec " + node + " \"$NPM_CLI\" \"$@\"\n";
        writeWrapper(bin, "npm", wrapper);
    }

    private static void writeWrapper(File bin, String name, String body) throws Exception {
        File f = new File(bin, name);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
        fo.write(("#!/system/bin/sh\n" + body + "\n").getBytes());
        fo.close();
        f.setExecutable(true, false);
    }

    /** CA 证书: 每次启动确保存在 (升级后 files/ 已存在, tar 不会重解压) */
    public static void ensureCert(Context ctx) {
        try {
            File cert = new File(usrDir(ctx), "etc/tls/cert.pem");
            if (!cert.exists()) {
                cert.getParentFile().mkdirs();
                InputStream in = ctx.getAssets().open("termux_ca.pem");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(cert);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                in.close();
                fo.close();
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 确保就绪: 解压 assets 标准库 (可执行部分已由系统解压到 nativeLibraryDir) */
    public static boolean ensureReady(Context ctx, TarExtractor.Progress progress) {
        ensureCert(ctx);
        if (isReady(ctx)) {
            ensureWrappers(ctx);
            ensurePkgs(ctx);
            ensureDynload(ctx);   // 补齐 C 扩展 (lib-dynload), 防止 import 报 ModuleNotFoundError
            return true;
        }
        try {
            File dest = new File(ctx.getFilesDir(), DIR);
            if (dest.exists()) {
                File flag = new File(dest, ".ready");
                if (!flag.exists()) {
                    deleteRecursive(dest);
                } else {
                    ensureWrappers(ctx);
                    ensurePkgs(ctx);
                    ensureDynload(ctx);
                    return true;
                }
            }
            dest.mkdirs();
            TarExtractor.extractAsset(ctx, ASSET, dest, progress);
            // 写完成标志
            File flag = new File(dest, ".ready");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(flag);
            fo.write("ok".getBytes());
            fo.close();
            ensureWrappers(ctx);
            ensurePkgs(ctx);
            ensureDynload(ctx);   // 解压后也要补 C 扩展
            return isReady(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /** 确保 lib-dynload 存在且包含全部 C 扩展 (nativeLibraryDir 优先, 失败自动回退 APK 直读) */
    public static void ensureDynload(Context ctx) {
        try {
            File dl = dynloadDir(ctx);
            if (!dl.exists()) dl.mkdirs();
            File nd = nativeDir(ctx);
            File[] sos = nd == null ? null : nd.listFiles();
            boolean needApk = true;
            if (sos != null) {
                copySos(sos, dl);
                // nativeDir 枚举不到任何 .so (部分设备 extractNativeLibs=false),
                // 或 lib-dynload 仍然没有 cpython-314 扩展 → 回退 APK 直读
                File[] after = dl.listFiles();
                boolean hasPyExt = false;
                if (after != null) for (File f : after) if (f.getName().contains("cpython-314")) { hasPyExt = true; break; }
                if (hasPyExt) needApk = false;
            }
            if (needApk) copySosFromApk(ctx, dl);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 把 nativeLibraryDir 里的 .so 复制到目标目录 (跳过已存在且大小一致的), 返回复制数 */
    private static int copySos(File[] sos, File dl) {
        int copied = 0;
        if (sos == null) return 0;
        for (File f : sos) {
            String n = f.getName();
            if (!n.endsWith(".so")) continue;
            File target = new File(dl, n);
            if (target.exists() && target.length() == f.length()) continue; // 已就位
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
                byte[] buf = new byte[65536];
                int r;
                while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
                fis.close();
                fos.close();
                target.setExecutable(true, false);
                copied++;
            } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
        }
        return copied;
    }

    /**
     * 从 APK 直读 .so 补进 lib-dynload (终极兜底, 不依赖 nativeLibraryDir)。
     * 适用: 部分设备/ROM extractNativeLibs=false, nativeLibraryDir 里没有真实文件,
     *       C 扩展 .so 只存在于 APK zip 内 lib/<abi>/。走 ZipFile 直接解压。
     * 返回实际写入的 .so 数量。
     */
    public static int copySosFromApk(Context ctx, File dl) {
        int copied = 0;
        try {
            if (!dl.exists()) dl.mkdirs();
            String apkPath = ctx.getApplicationInfo().sourceDir;
            if (apkPath == null) return 0;
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
            try {
                // 匹配优先级: 当前设备 ABI → arm64-v8a → armeabi-v7a
                java.util.List<String> abiPrefix = new java.util.ArrayList<String>();
                try {
                    String[] abis = android.os.Build.SUPPORTED_ABIS;
                    if (abis != null) for (String a : abis) abiPrefix.add("lib/" + a + "/");
                } catch (Exception __) {}
                abiPrefix.add("lib/arm64-v8a/");
                abiPrefix.add("lib/armeabi-v7a/");
                java.util.List<java.util.zip.ZipEntry> soEntries = new java.util.ArrayList<java.util.zip.ZipEntry>();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> es = zf.entries();
                while (es.hasMoreElements()) {
                    java.util.zip.ZipEntry e = es.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".so")) continue;
                    boolean match = false;
                    for (String p : abiPrefix) if (n.startsWith(p)) { match = true; break; }
                    if (!match) continue;
                    soEntries.add(e);
                }
                for (java.util.zip.ZipEntry e : soEntries) {
                    String name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                    File target = new File(dl, name);
                    if (target.exists() && target.length() == e.getSize()) continue;
                    try {
                        java.io.InputStream is = zf.getInputStream(e);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
                        is.close();
                        fos.close();
                        target.setExecutable(true, false);
                        copied++;
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            } finally {
                try { zf.close(); } catch (Exception __) {}
            }
            android.util.Log.w("TaskPro", "copySosFromApk copied " + copied + " .so");
        } catch (Exception e) {
            android.util.Log.w("TaskPro", "copySosFromApk error: " + e);
        }
        return copied;
    }

/** 确保常用第三方包完整 (requests/urllib3/certifi/pip 等)。
     *  策略: 增量合并 — 绝不删除整个 site-packages (防止误删 pip),
     *  只检查关键文件, 缺失时把 pkgs 包内容覆盖解压进去 (tar 按需覆盖)。
     *  注意: termux_pkgs.tar.gz 已包含完整 pip (见 merge_pip.py),
     *  即使旧版本曾误删 pip, 本方法也会自动补回。 */
    public static void ensurePkgs(Context ctx) {
        try {
            File sp = new File(usrDir(ctx), "lib/python3.14/site-packages");
            boolean needExtract = false;
            // 关键文件检查 (含 pip, 防止旧版本删除 pip 后无法恢复)
            if (!new File(sp, "requests/sessions.py").exists()) needExtract = true;
            if (!new File(sp, "requests/__init__.py").exists()) needExtract = true;
            if (!new File(sp, "urllib3/connectionpool.py").exists()) needExtract = true;
            if (!new File(sp, "pip/__init__.py").exists()) needExtract = true;
            if (!needExtract) return;  // 包已完整, 跳过
            // 增量合并: 直接覆盖解压, 不清空已有内容
            sp.mkdirs();
            TarExtractor.extractAsset(ctx, "termux_pkgs.tar.gz", new File(ctx.getFilesDir(), DIR), null);
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /** python3 解释器路径 (nativeLibraryDir) */
    public static String pythonBin(Context ctx) {
        return new File(nativeDir(ctx), "libpython3_main.so").getAbsolutePath();
    }

    /** busybox 路径 */
    public static String busyboxBin(Context ctx) {
        // busybox 二进制以 "busybox" 命名 (argv[0] 以 busybox 结尾时按子命令分发, 否则 applet not found)
        return new File(nativeDir(ctx), "busybox").getAbsolutePath();
    }

    /** curl 路径 (termux curl 8.21, 完整 HTTPS/JSON 支持) */
    public static String curlBin(Context ctx) {
        return new File(nativeDir(ctx), "libcurl_main.so").getAbsolutePath();
    }

    /**
     * node 解释器路径 (nativeLibraryDir 内的 node 可执行文件, 约 49.7MB)
     *
     * ⚠️⚠️⚠️ 血泪坑合集 (2026-08 真机排查记录) ⚠️⚠️⚠️
     *
     * 【坑1: 代码硬编码说"未捆绑 node"】
     *   根因: APK 明明打包了 lib/arm64-v8a/node, 但以下两处代码硬编码
     *   返回"本版未捆绑 node", 导致用户运行 JS 脚本直接报错:
     *     - AdvActivity.runAdvTask(): 对 js 类型任务 echo 提示后 return
     *     - SelfCheck.checkNode(): 固定返回"未捆绑"
     *   教训: 打包了运行时就必须在所有调用路径真实使用, 不要留 echo 假提示。
     *   自检必须是真实执行, 而不是猜。
     *
     * 【坑2: node 依赖动态库, 不设 LD_LIBRARY_PATH 直接 CANNOT LINK】
     *   现象: 直接执行 node 报 "CANNOT LINK EXECUTABLE: library libz.so.1 not found"
     *   原因: node 二进制链接了 libz.so.1 / libstdc++ 等, Android 系统不自带,
     *         必须通过 LD_LIBRARY_PATH 指向 nativeLibraryDir。
     *   修复: 所有调用 node 的地方必须经 RuntimeManager.buildCommand()
     *         (它会 export LD_LIBRARY_PATH=nativeDir)。
     *   验证: export LD_LIBRARY_PATH=<nativeDir>; node --version → v26.4.0 ✓
     *
     * 【坑3: node 读 OpenSSL 配置路径报错】
     *   现象: 设了 LD_LIBRARY_PATH 后, node 报
     *     "OpenSSL configuration error: ... fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf, rb)"
     *   原因: Node.js 内置 OpenSSL 默认按编译期路径找 openssl.cnf,
     *         该路径 (/data/data/com.termux/files/...) 在本 App 不存在。
     *   处理方向: 在 buildCommand 中追加 export OPENSSL_CONF=<usr>/etc/tls/openssl.cnf
     *     (若该文件存在), 或保证 termux 目录结构里提供此文件;
     *     详见 buildCommand 尾部注释。
     *
     * 【结论】node 可用的三要素: ① 文件在 nativeDir ② LD_LIBRARY_PATH 指对 ③ 环境变量前缀
     *   缺一不可; 任何"node 不能用"的问题请先按此排查。
     */
    public static String nodeBin(Context ctx) {
        File node = new File(nativeDir(ctx), "node");
        if (node.exists()) return node.getAbsolutePath();
        if (!isReady(ctx)) return "node";
        return node.getAbsolutePath();
    }

    /**
     * 命令重写: 把命令位置(行首或 ; && | 之后)的 python3/py3/pip/pip3/busybox
     * 替换为 nativeLibraryDir 完整路径。nativeLibraryDir 内文件名为
     * libpython3_main.so 等, PATH 查不到 python3, 且 files/ 可能为 noexec
     * (sh wrapper 也不可执行), 故在命令层直接替换。
     */
    public static String rewriteCommand(Context ctx, String cmd) {
        if (cmd == null) return cmd;
        String py = pythonBin(ctx);
        String out = cmd;
        // 规则0: busybox wget 不支持 https, 自动转为 curl (先于 busybox/curl 重写)
        //   wget -qO- URL  →  curl -s URL        (静默输出到 stdout)
        //   wget -qO F URL →  curl -s -o F URL
        //   wget -q URL    →  curl -s -O URL
        //   wget -O F URL  →  curl -o F URL
        //   wget URL       →  curl -O URL
        out = out.replaceAll("(?m)(^|[;&|(]\\s*)busybox[ \\t]+wget[ \\t]+-qO-", "$1curl -s ");
        out = out.replaceAll("(?m)(^|[;&|(]\\s*)busybox[ \\t]+wget[ \\t]+-qO([ \\t]+\\S+[ \\t]+\\S+.*)", "$1curl -s -o$2");
        out = out.replaceAll("(?m)(^|[;&|(]\\s*)busybox[ \\t]+wget[ \\t]+-q([ \\t]+\\S+.*)", "$1curl -s -O$2");
        out = out.replaceAll("(?m)(^|[;&|(]\\s*)busybox[ \\t]+wget[ \\t]+-O([ \\t]+\\S+[ \\t]+\\S+.*)", "$1curl -o$2");
        out = out.replaceAll("(?m)(^|[;&|(]\\s*)busybox[ \\t]+wget([ \\t]+\\S+.*)", "$1curl -O$2");
        // 规则1: 行首 (MULTILINE, 支持脚本换行分隔) — 行首空白后跟命令
        out = out.replaceAll("(?m)^[ \\t]*(python3|py3)(?=\\s|$)",
                java.util.regex.Matcher.quoteReplacement(py));
        out = out.replaceAll("(?m)^[ \\t]*(pip|pip3)(?=\\s|$)",
                java.util.regex.Matcher.quoteReplacement(py + " -m pip"));
        out = out.replaceAll("(?m)^[ \\t]*busybox(?=\\s|$)",
                java.util.regex.Matcher.quoteReplacement(busyboxBin(ctx)));
        out = out.replaceAll("(?m)^[ \\t]*node(?=\\s|$)",
                java.util.regex.Matcher.quoteReplacement(nodeBin(ctx)));
        out = out.replaceAll("(?m)^[ \\t]*curl(?=\\s|$)",
                java.util.regex.Matcher.quoteReplacement(curlBin(ctx)));
        // 规则1b: npm → sh npm wrapper (files/usr/bin/npm 位于 noexec 分区, 不能直接 exec, 用 sh 调用)
        String npmWrapper = new File(usrDir(ctx), "bin/npm").getAbsolutePath();
        out = out.replaceAll("(?m)^[ \\t]*npm(?=\\s|$)",
                java.util.regex.Matcher.quoteReplacement("/system/bin/sh " + npmWrapper));
        // 规则2: 行内分隔符后 (; && | () 后跟空白
        out = out.replaceAll("(^|[;&|(]\\s*)(python3|py3)(?=\\s|$)",
                "$1" + java.util.regex.Matcher.quoteReplacement(py));
        out = out.replaceAll("(^|[;&|(]\\s*)(pip|pip3)(?=\\s|$)",
                "$1" + java.util.regex.Matcher.quoteReplacement(py + " -m pip"));
        out = out.replaceAll("(^|[;&|(]\\s*)busybox(?=\\s|$)",
                "$1" + java.util.regex.Matcher.quoteReplacement(busyboxBin(ctx)));
        out = out.replaceAll("(^|[;&|(]\\s*)node(?=\\s|$)",
                "$1" + java.util.regex.Matcher.quoteReplacement(nodeBin(ctx)));
        out = out.replaceAll("(^|[;&|(]\\s*)curl(?=\\s|$)",
                "$1" + java.util.regex.Matcher.quoteReplacement(curlBin(ctx)));
        // 规则2b: 行内分隔符后的 npm → sh npm wrapper
        out = out.replaceAll("(^|[;&|(]\\s*)npm(?=\\s|$)",
                "$1" + java.util.regex.Matcher.quoteReplacement("/system/bin/sh " + npmWrapper));
        return out;
    }

    /**
     * 构建执行环境前缀:
     * - PATH 指向 nativeLibraryDir + assets 的 bin
     * - LD_LIBRARY_PATH 指向 nativeLibraryDir (所有 .so)
     * - PYTHONHOME 指向 nativeLibraryDir (python 找扩展模块)
     * - PYTHONPATH 指向 assets 标准库 (纯 .py)
     * - 注入用户环境变量
     */
    /** 重写脚本内容: /tmp 路径映射 + 命令重写 (供执行与诊断共用) */
    public static String rewriteScript(Context ctx, String script) {
        if (script == null) return null;
        String tmpDir = new File(ctx.getFilesDir(), "tmp").getAbsolutePath();
        String qt = java.util.regex.Matcher.quoteReplacement(tmpDir);
        String cmd = script.replaceAll("(^|[^a-zA-Z0-9])/tmp/(?=\\S|$)", "$1" + qt + "/");
        cmd = cmd.replaceAll("(^|[^a-zA-Z0-9])/tmp(?=\\s|$)", "$1" + qt);
        return rewriteCommand(ctx, cmd);
    }

    public static String buildCommand(Context ctx, String cmd) {
        // 已就绪设备不会走解压流程, 这里兜底确保证书与 wrapper 每次执行前就位
        ensureCert(ctx);
        ensureWrappers(ctx);
        ensurePkgs(ctx);   // 每次执行都检查第三方包完整性, 修复断解压导致的残缺文件
        ensureDynload(ctx); // 每次执行都检查 C 扩展完整性 (lib-dynload), 修复 _struct 等 ModuleNotFoundError
        File nd = nativeDir(ctx);
        File usr = usrDir(ctx);
        String nativeLib = nd.getAbsolutePath();
        String stdlib = new File(usr, "lib/python3.14").getAbsolutePath();
        String site = new File(stdlib, "site-packages").getAbsolutePath();
        String binPath = nativeLib + ":" + new File(usr, "bin").getAbsolutePath();

        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=\"").append(binPath).append(":$PATH\"; ");
        sb.append("export LD_LIBRARY_PATH=\"").append(nativeLib).append("\"; ");
        sb.append("export PYTHONHOME=\"").append(nativeLib).append("\"; ");
        sb.append("export PYTHONPATH=\"").append(nativeLib).append(":").append(stdlib).append(":")
                .append(site).append(":").append(dynloadDir(ctx).getAbsolutePath()).append("\"; ");
        // HOME 指向 App 数据目录 (node 的 os.homedir / npm 缓存等需要)
        sb.append("export HOME=\"").append(ctx.getFilesDir().getAbsolutePath()).append("\"; ");
        // NODE_PATH: Node.js 模块搜索路径, 指向 files/node_modules
        sb.append("export NODE_PATH=\"").append(new File(ctx.getFilesDir(), "node_modules").getAbsolutePath()).append("\"; ");
        // ╔══════════════════════════════════════════════════════════════════════════╗
// ║  CA 证书环境变量 — 血泪史 ║
// ║  ║
// ║  警告: SSL_CERT_FILE 会完全替换 (而非追加) OpenSSL 内置的 CA 根证书库! ║
// ║  ║
// ║  【错误历史 v1】无条件设置 SSL_CERT_FILE=xxx                           ║
// ║    → ensureCert 静默吞异常, 文件可能不存在                             ║
// ║    → OpenSSL 加载空 CA 列表 → 所有 HTTPS 请求直接挂掉                   ║
// ║    → Node.js fetch / Python requests / curl 全部 TypeError: fetch failed║
// ║  ║
// ║  【错误历史 v2】指向 certifi 的 cacert.pem (121 certs, 看似更全)        ║
// ║    → Node.js 绑定的 OpenSSL 不兼容某些格式                             ║
// ║    → 同一台设备上 Node.js fetch 失败而 Python requests 可能正常         ║
// ║  ║
// ║  【正确做法】                                                          ║
// ║  1. 仅当文件真实存在时才设置 SSL_CERT_FILE 和 CURL_CA_BUNDLE           ║
// ║  2. 优先使用 NODE_EXTRA_CA_CERTS (Node.js 专用, 追加而非替换)          ║
// ║  3. 文件不存在时啥也不设, 各运行时靠内置 CA 正常工作                   ║
// ║  4. 如果要改这里, 必须用真机 Python + Node.js 脚本做 HTTPS 测试        ║
// ╚══════════════════════════════════════════════════════════════════════════╝
        String certFile = new File(usr, "etc/tls/cert.pem").getAbsolutePath();
        if (new File(certFile).exists()) {
            sb.append("export SSL_CERT_FILE=\"").append(certFile).append("\"; ");
            sb.append("export CURL_CA_BUNDLE=\"").append(certFile).append("\"; ");
        }
        // NODE_EXTRA_CA_CERTS 是追加模式, 不覆盖 Node.js 内置 CA, 兼容性最好
        if (new File(certFile).exists()) {
            sb.append("export NODE_EXTRA_CA_CERTS=\"").append(certFile).append("\"; ");
        }
        // ⚠️ node 的 OpenSSL 配置坑 (详见 nodeBin 注释【坑3】):
        //   Node.js 默认按编译期路径找 openssl.cnf (/data/data/com.termux/files/usr/etc/tls/),
        //   本 App 无此目录 → node 启动报 "OpenSSL configuration error ... Permission denied"。
        //   解决: 若我们的证书目录存在同名文件则用 OPENSSL_CONF 显式指过去;
        //         若不存在, 不要硬设 (OPENSSL_CONF 指向不存在的文件同样会报错)。
        File opensslConf = new File(usr, "etc/tls/openssl.cnf");
        if (opensslConf.exists()) {
            sb.append("export OPENSSL_CONF=\"").append(opensslConf.getAbsolutePath()).append("\"; ");
        }
        sb.append("export HOME=\"").append(ctx.getFilesDir().getAbsolutePath()).append("\"; ");
        sb.append("export PREFIX=\"").append(nativeLib).append("\"; ");

        // /tmp 兼容: Android /tmp 无写权限, 创建私有目录并映射
        String tmpDir = new File(ctx.getFilesDir(), "tmp").getAbsolutePath();
        sb.append("mkdir -p \"").append(tmpDir).append("\"; ");
        sb.append("export TMPDIR=\"").append(tmpDir).append("\"; ");

        // 注入用户环境变量
        java.util.List<EnvStore.Env> envs = EnvStore.load(ctx);
        for (EnvStore.Env e : envs) {
            // B8: 单引号包裹 + 值内单引号转义, 防止 $() 等被 shell 二次解释; 变量名白名单校验
            String k = e.name == null ? "" : e.name.trim();
            if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            String v = e.value == null ? "" : e.value.replace("'", "'\\''");
            sb.append("export ").append(k).append("='").append(v).append("'; ");
        }
        // 脚本硬编码 /tmp 路径 → 映射到私有目录 + 命令重写
        sb.append(rewriteScript(ctx, cmd));
        return sb.toString();
    }

    // ================= Python 扩展模块自动修复 =================
    // 背景: Python 3.x 的 C 扩展模块 (_struct/_ssl/_sqlite3 等 .so)
    // 打包在 APK lib/arm64-v8a/ (nativeLibraryDir), 但 Python import 时
    // 会去 sys.path 的 lib-dynload 目录找。
    // 若 assets 解压的标准库缺少 lib-dynload (或其中的 .so 没带上),
    // 就会报 "ModuleNotFoundError: No module named '_struct'"。
    // 解决: 把 nativeLibraryDir 里的 *.so 补一份 symlink/复制 到
    //       <usr>/lib/python3.14/lib-dynload/, 兼容全部 C 扩展。

    /** Python 标准库目录 (assets 解压位置) */
    public static File pythonLibDir(Context ctx) {
        return new File(usrDir(ctx), "lib/python3.14");
    }

    /** lib-dynload: Python C 扩展模块目录 (缺失时自动补) */
    public static File dynloadDir(Context ctx) {
        File d = new File(pythonLibDir(ctx), "lib-dynload");
        if (!d.exists()) d.mkdirs();
        return d;
    }
/** 校验 Python C 扩展模块: 直接检查 lib-dynload 目录的 .so 文件是否齐全。
     *  返回缺失的关键扩展文件名列表 (如 _struct.cpython-314-aarch64-linux-android.so)。
     *  注意: 不能用 Python import 测试来判断, 因为 PYTHONPATH 里 nativeLib
     *  (含全部 .so) 在最前, import 永远成功 → 假阳性, 导致"修复成功"实际没修好。
     *  必须直接检查 lib-dynload 目录物理文件。 */
    public static java.util.List<String> verifyPythonExts(Context ctx) {
        java.util.List<String> missing = new java.util.ArrayList<String>();
        try {
            File dl = dynloadDir(ctx);
            if (!dl.exists()) dl.mkdirs();
            // 兜底先补一次 (nativeDir 优先, APK 直读兜底), 避免误报
            ensureDynload(ctx);
            File nd = nativeDir(ctx);
            File[] soFiles = nd == null ? null : nd.listFiles();
            if (soFiles == null || soFiles.length == 0) {
                // 设备 nativeLibraryDir 不可枚举 → 直接从 APK 声明为准
                String apkPath = ctx.getApplicationInfo().sourceDir;
                if (apkPath == null) { missing.add("_struct"); return missing; }
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
                try {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> es = zf.entries();
                    while (es.hasMoreElements()) {
                        java.util.zip.ZipEntry e = es.nextElement();
                        String n = e.getName();
                        if (!n.endsWith(".so") || !n.contains("cpython-314")) continue;
                        String name = n.substring(n.lastIndexOf('/') + 1);
                        File target = new File(dl, name);
                        if (target.exists() && target.length() > 0) continue;
                        missing.add(name);
                    }
                } finally {
                    try { zf.close(); } catch (Exception __) {}
                }
                if (missing.isEmpty()) missing.add("_struct.cpython-314-aarch64-linux-android.so");
                return missing;
            }
            int present = 0;
            for (File f : soFiles) {
                String n = f.getName();
                if (!n.endsWith(".so")) continue;
                // 只关心 Python C 扩展 (名字形如 _xxx.cpython-314-*.so 或 xxx.cpython-314-*.so)
                if (!n.contains("cpython-314")) continue;
                File target = new File(dl, n);
                if (target.exists() && target.length() > 0) {
                    present++;
                } else {
                    missing.add(n);
                }
            }
            // 如果 lib-dynload 里根本没有 cpython-314 扩展, 全算缺失
            if (present == 0 && missing.isEmpty()) missing.add("_struct.cpython-314-aarch64-linux-android.so");
        } catch (Exception e) {
            missing.add("_struct");
        }
        return missing;
    }

    /**
     * 修复 Python C 扩展: 把 nativeLibraryDir 里的 *.so 镜像到 lib-dynload。
     * 返回实际复制的文件数 (0 = 没有需要复制的)。
     */
    public static int fixPythonExts(Context ctx) {
        int copied = 0;
        try {
            File nd = nativeDir(ctx);
            File dl = dynloadDir(ctx);
            if (!dl.exists()) dl.mkdirs();
            File[] soFiles = nd.listFiles();
            if (soFiles == null) return 0;
            for (File f : soFiles) {
                String name = f.getName();
                if (!name.endsWith(".so")) continue;
                File target = new File(dl, name);
                // 已存在且大小一致 → 跳过; 存在但大小不同 → 覆盖 (可能损坏)
                if (target.exists() && target.length() == f.length()) continue;
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                    fis.close();
                    fos.close();
                    target.setExecutable(true, false);
                    copied++;
                } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
            }
            android.util.Log.w("TaskPro", "fixPythonExts copied " + copied + " .so to lib-dynload");
        } catch (Exception e) {
            android.util.Log.w("TaskPro", "fixPythonExts error: " + e);
        }
        return copied;
    }

    // ================= pip 依赖下载安装 =================
    // 背景: 当脚本 import 第三方库 (requests/bs4/httpx 等) 但运行时没有时,
    // 光复制本地 .so 没用 —— 需要真正的「下载安装」。
    // 本项目已内置 pip (pip-26.1.2), 走 `python -m pip install <pkg>` 即可。
    // 安装位置: site-packages (usr/lib/python3.14/site-packages), 持久生效。
    //
    // ⚠️⚠️⚠️ 血泪坑: pip 神秘消失 (2026-08 真机排查记录) ⚠️⚠️⚠️
    // 【坑4: termux_pkgs.tar.gz 没有 pip, ensurePkgs 删目录导致 pip 丢失】
    //   根因: 早期 assets 只有 termux_lib.tar.gz (含完整 pip site-packages),
    //   后来为了补 requests 等第三方包, 新建了 termux_pkgs.tar.gz (只含
    //   certifi/charset_normalizer/idna/requests/urllib3, 无 pip!)。而
    //   ensurePkgs() 检测到 requests/sessions.py 缺失时, 会 deleteRecursive
    //   删掉整个 site-packages 再用 pkgs 包重解压 → pip 被删 → 点「安装依赖」
    //   报 "No module named pip"!
    //   修复 (2026-08): ① merge_pip.py 把 lib 包里的 567 个 pip 文件合并进
    //   pkgs 包 → ensurePkgs 怎么删都能恢复 pip; ② ensurePkgs 改为增量合并
    //   (不删目录, 只覆盖缺的文件); ③ onCreate 后台自愈线程每次启动补包。
    //   教训: 任何「删目录重建」的修复逻辑都可能误删其他包, 优先用增量覆盖。

    /**
     * pip 安装依赖包 (可多个, 空格分隔)。返回安装日志全文。
     * 使用 buildCommand 构建环境 (PATH/LD_LIBRARY_PATH/PYTHONHOME 齐全)。
     * lineListener 可选: 每读到一行输出时回调 (用于 UI 实时显示下载进度)
     */
    public static String pipInstall(Context ctx, String packages, LineListener lineListener) {
        if (packages == null || packages.trim().isEmpty()) return "包名为空";
        try {
            // ⚠️ 必须 --target 指向可写 site-packages!
            // 原因: PYTHONHOME 指向 nativeLib (只读), pip 检测到 normal
            // site-packages 不可写 → 回退到 $HOME/.local 用户目录安装 →
            // 该目录不在 PYTHONPATH → 装了也 import 不到!
            // 修复: 显式 --target 到 usr/lib/python3.14/site-packages
            // (已在 PYTHONPATH 里, 且 usr 目录位于 app files 可写)。
            String site = new File(usrDir(ctx), "lib/python3.14/site-packages").getAbsolutePath();
            String cmd = buildCommand(ctx, pythonBin(ctx)
                    + " -m pip install --no-input --target " + site
                    + " --no-warn-script-location " + packages + " 2>&1");
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                out.append(l).append("\n");
                if (lineListener != null) lineListener.onLine(l);
                if (out.length() > 12000) { out.append("...(输出过长截断)\n"); p.destroy(); break; }
            }
            r.close();
            if (!p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                out.append("\n! pip 安装超时(180s), 已终止");
            }
            if (lineListener != null) lineListener.onDone(out.toString().trim().contains("Successfully installed")
                    || out.toString().trim().contains("already satisfied"));
            return out.toString().trim();
        } catch (Exception e) {
            if (lineListener != null) lineListener.onLine("pip 安装失败: " + e.toString());
            return "pip 安装失败: " + e.toString();
        }
    }

    public static String pipInstall(Context ctx, String packages) {
        return pipInstall(ctx, packages, null);
    }

    /** npm 安装依赖包 (自动创建 node_modules 目录并安装到其中) */
    public static String npmInstall(Context ctx, String packages, LineListener lineListener) {
        if (packages == null || packages.trim().isEmpty()) return "包名为空";
        try {
            String nodeModules = new File(ctx.getFilesDir(), "node_modules").getAbsolutePath();
            new File(nodeModules).mkdirs();
            String cmd = "mkdir -p \"" + nodeModules + "\" && cd \"" + ctx.getFilesDir().getAbsolutePath()
                    + "\" && export HOME=\"" + ctx.getFilesDir().getAbsolutePath()
                    + "\" && export LD_LIBRARY_PATH=\"" + nativeDir(ctx).getAbsolutePath()
                    + "\" && " + "/system/bin/sh " + new File(usrDir(ctx), "bin/npm").getAbsolutePath()
                    + " install --no-audit --no-fund --registry=https://registry.npmmirror.com"
                    + " " + packages + " 2>&1";
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                out.append(l).append("\n");
                if (lineListener != null) lineListener.onLine(l);
                if (out.length() > 12000) { out.append("...(输出过长截断)\n"); p.destroy(); break; }
            }
            r.close();
            if (!p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                out.append("\n! npm 安装超时(180s), 已终止");
            }
            boolean success = out.toString().contains("added ") || out.toString().contains("up to date");
            if (lineListener != null) lineListener.onDone(success);
            return out.toString().trim();
        } catch (Exception e) {
            if (lineListener != null) lineListener.onLine("npm 安装失败: " + e.toString());
            return "npm 安装失败: " + e.toString();
        }
    }

    public static String npmInstall(Context ctx, String packages) {
        return npmInstall(ctx, packages, null);
    }

    /** pip 安装进度行回调 */
    public interface LineListener {
        void onLine(String line);
        void onDone(boolean success);
    }

    /** 判断某个缺失模块是否可能是 pip 第三方包 (非内置) */
    public static boolean isThirdPartyModule(String modName) {
        if (modName == null || modName.isEmpty()) return false;
        String m = modName.toLowerCase();
        // 下划线开头的 C 扩展是内置的, 不是 pip 包
        if (m.startsWith("_")) return false;
        // 常见内置模块白名单
        String[] builtin = {"struct", "ssl", "sqlite3", "zlib", "hashlib", "json",
                "math", "time", "datetime", "random", "base64", "socket",
                "array", "binascii", "bz2", "lzma", "os", "sys", "re",
                "subprocess", "threading", "collections", "functools",
                "itertools", "operator", "pathlib", "shutil", "glob",
                "urllib", "http", "email", "xml", "html", "traceback",
                "logging", "queue", "tempfile", "io", "types", "typing",
                "enum", "dataclasses", "abc", "contextlib", "copy",
                "decimal", "fractions", "statistics", "weakref", "textwrap",
                "string", "unicodedata", "argparse", "getopt", "platform",
                "signal", "sysconfig", "venv", "zipfile", "gzip", "tarfile",
                "csv", "configparser", "json", "secrets", "uuid", "calendar",
                "sched", "select", "selectors", "asyncio", "concurrent",
                "ctypes", "curses", "dbm", "hashlib", "hmac", "importlib",
                "inspect", "linecache", "locale", "marshal", "mmap", "netrc",
                "nis", "nntplib", "numbers", "operator", "os", "parser",
                "pdb", "pickle", "pickletools", "pipes", "pkgutil", "plistlib",
                "poplib", "posix", "pprint", "profile", "pstats", "pty",
                "pwd", "py_compile", "pyclbr", "pydoc", "queue", "quopri",
                "random", "re", "readline", "reprlib", "resource", "rfc822",
                "rlcompleter", "runpy", "sched", "secrets", "select",
                "shelve", "shlex", "shutil", "signal", "site", "smtpd",
                "smtplib", "sndhdr", "socket", "socketserver", "spwd", "sqlite3",
                "ssl", "stat", "statistics", "string", "stringprep", "struct",
                "subprocess", "sunau", "symbol", "symtable", "sys", "sysconfig",
                "syslog", "tabnanny", "tarfile", "telnetlib", "tempfile",
                "termios", "test", "textwrap", "threading", "time", "timeit",
                "tkinter", "token", "tokenize", "trace", "traceback", "tty",
                "turtle", "types", "typing", "unicodedata", "unittest",
                "urllib", "uu", "uuid", "venv", "warnings", "wave", "weakref",
                "webbrowser", "winreg", "winsound", "wsgiref", "xdrlib",
                "xml", "xmlrpc", "zipfile", "zipimport", "zlib", "zoneinfo"};
        for (String b : builtin) {
            if (b.equals(m)) return false;
        }
        return true;
    }

    /**
     * 已知误报模块集合: 这些名字 import 会失败, 但运行时其实健康,
     * 不应触发修复弹窗, 否则会走"复制本地 .so" → 0 个 → 误导用户。
     *   _zlib     → CPython 3.14 没有 _zlib 模块, 只有 zlib (zlib.cpython-314-*.so)
     *   _itertools → builtin 模块, 编译进解释器核心, 永远没有独立 .so 文件
     */
    public static boolean isKnownFalsePositive(String modName) {
        if (modName == null) return false;
        String m = modName.trim().toLowerCase();
        return m.equals("_zlib") || m.equals("_itertools");
    }
}
