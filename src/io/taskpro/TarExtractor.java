package io.taskpro;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java tar.gz 解压器 (无需第三方库)
 * 支持: 普通文件 / 目录 / 符号链接 / 长文件名 (GNU/PAX)
 */
public class TarExtractor {

    public interface Progress {
        void onProgress(String file, int done, int total);
    }

    /**
     * 从 assets 解压 tar.gz 到目标目录
     * @param assetName assets 中的文件名
     * @param destDir 目标目录
     * @param progress 进度回调 (可为 null)
     * @throws Exception
     */
    public static void extractAsset(Context ctx, String assetName, File destDir, Progress progress) throws Exception {
        InputStream in = ctx.getAssets().open(assetName);
        extract(in, destDir, progress);
    }

    /**
     * 从文件解压 tar.gz
     */
    public static void extractFile(File src, File destDir, Progress progress) throws Exception {
        InputStream in = new FileInputStream(src);
        extract(in, destDir, progress);
    }

    private static void extract(InputStream rawIn, File destDir, Progress progress) throws Exception {
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(rawIn, 64 * 1024));
        byte[] buf = new byte[64 * 1024];
        int done = 0, total = 0;

        while (true) {
            // 读 512 字节头
            byte[] hdr = new byte[512];
            int r = readFully(gz, hdr);
            if (r <= 0) break;
            // 全零块 = 结束
            boolean allZero = true;
            for (byte b : hdr) { if (b != 0) { allZero = false; break; } }
            if (allZero) break;

            String name = readString(hdr, 0, 100);
            String prefix = readString(hdr, 345, 155);
            long size = readOctal(hdr, 124, 12);
            int type = hdr[156] & 0xff;
            String linkName = readString(hdr, 157, 100);

            // GNU 长文件名 (type 'L')
            if (type == 'L') {
                byte[] lb = new byte[(int) size];
                readFully(gz, lb);
                name = new String(lb, "UTF-8").trim();
                // 跳到对齐
                skipPad(gz, size);
                // 继续读真正的头
                r = readFully(gz, hdr);
                if (r <= 0) break;
                name = readString(hdr, 0, 100);
                prefix = readString(hdr, 345, 155);
                size = readOctal(hdr, 124, 12);
                type = hdr[156] & 0xff;
                linkName = readString(hdr, 157, 100);
            }

            String full = (prefix.isEmpty() ? name : prefix + "/" + name);
            // 去掉 "data/data/com.termux/files/" 前缀 -> 直接落到 destDir/usr/...
            String rel = stripPrefix(full);
            if (rel == null || rel.isEmpty()) {
                skipData(gz, size);
                continue;
            }
            File target = new File(destDir, rel);
            done++;
            if (progress != null) progress.onProgress(rel, done, 0);

            if (type == 0 || type == '\0' || type == '0') { // 普通文件
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(
                        new FileOutputStream(target), 128 * 1024);
                long left = size;
                while (left > 0) {
                    int n = gz.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
                out.flush();
                out.close();
                skipPad(gz, size);
                // 设置权限 (tar 中的 mode 低 9 位)
                int mode = (int) readOctal(hdr, 100, 8);
                applyMode(target, mode);
            } else if (type == '5') { // 目录
                target.mkdirs();
            } else if (type == '2') { // 符号链接
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                if (target.exists()) target.delete();
                try {
                    // 用 Java 创建符号链接 (API 26+); 低版本 fallback: 拷贝目标(若存在)
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        java.nio.file.Files.createSymbolicLink(target.toPath(),
                                java.nio.file.Paths.get(linkName));
                    }
                } catch (Exception e) {
                    // fallback: 尝试拷贝指向的文件
                    try {
                        File srcF = new File(target.getParentFile(), linkName);
                        if (srcF.exists()) {
                            FileInputStream fi = new FileInputStream(srcF);
                            FileOutputStream fo = new FileOutputStream(target);
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = fi.read(b)) > 0) fo.write(b, 0, n);
                            fi.close(); fo.close();
                        }
                    } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
                }
            } else {
                // 其他类型 (设备等) 跳过
                skipData(gz, size);
            }
        }
        gz.close();
        rawIn.close();
    }

    /** 应用 tar 文件权限 (owner 读/写/执行), Java API 失败时用系统 chmod 兜底 */
    private static void applyMode(File f, int mode) {
        try {
            boolean exe = (mode & 0111) != 0;
            boolean write = (mode & 0200) != 0;
            boolean ok = f.setExecutable(exe, true);
            f.setWritable(write, true);
            f.setReadable(true, true);
            if (!ok && exe) {
                // Java API 失败 -> 系统 chmod 兜底
                chmodCmd(f, "700");
            }
        } catch (Exception ignored) { try { android.util.Log.w("TaskPro","catch: "+ignored.getMessage()); } catch(Exception __){} }
    }

    /** 用系统 chmod 命令设置权限 (Android toybox/toolbox) */
    public static boolean chmodCmd(File f, String mode) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/chmod", mode, f.getAbsolutePath()});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripPrefix(String path) {
        // 期望前缀: data/data/com.termux/files/
        String p = path;
        while (p.startsWith("./")) p = p.substring(2);
        int idx = p.indexOf("com.termux");
        if (idx >= 0) {
            int rest = p.indexOf("files", idx);
            if (rest >= 0) return p.substring(rest + 6);
        }
        // 无前缀的路径 (理论不该出现) 直接返回
        return p;
    }

    private static int readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off);
    }

    private static long readOctal(byte[] b, int off, int len) {
        long v = 0;
        int end = off + len;
        int i = off;
        // 跳过空格/零
        while (i < end && (b[i] == ' ' || b[i] == 0)) i++;
        if (i < end && b[i] == 0x80) { // GNU base-256
            v = b[i] & 0x7f;
            for (int j = i + 1; j < end; j++) v = (v << 8) | (b[j] & 0xff);
            return v;
        }
        while (i < end && b[i] >= '0' && b[i] <= '7') {
            v = v * 8 + (b[i] - '0');
            i++;
        }
        return v;
    }

    private static void skipPad(InputStream in, long size) throws Exception {
        long pad = (512 - (size % 512)) % 512;
        skipFully(in, pad);
    }

    private static void skipData(InputStream in, long size) throws Exception {
        skipFully(in, size);
        skipPad(in, size);
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        byte[] b = new byte[8192];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) break;
            n -= r;
        }
    }
}
