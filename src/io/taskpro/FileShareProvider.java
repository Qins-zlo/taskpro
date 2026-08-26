package io.taskpro;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
/** 脚本文件分享 Provider (无 androidx 依赖, 手写实现 content:// 访问) */
public class FileShareProvider extends ContentProvider {
    private static final String AUTHORITY = "io.taskpro.fileshare";
    private static final UriMatcher M = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        M.addURI(AUTHORITY, "files/*", 1);
        M.addURI(AUTHORITY, "cache/*", 2);  // 导出 zip 等临时文件
    }
    /** 生成脚本文件的 content:// URI */
    public static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/files/" + Uri.encode(fileName));
    }
    /** 生成缓存文件 (zip 备份等) 的 content:// URI */
    public static Uri cacheUriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/cache/" + Uri.encode(fileName));
    }
    @Override
    public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        if (n.endsWith(".py")) return "text/x-python";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".sh")) return "text/x-shellscript";
        if (n.endsWith(".zip")) return "application/zip";
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String n = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        File base;
        if (M.match(uri) == 2) {
            base = getContext().getCacheDir();
        } else {
            base = new File(getContext().getFilesDir(), "scripts");
        }
        File f = new File(base, n);
        if (!f.exists()) throw new FileNotFoundException(n);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] p1, String p2, String[] p3, String p4) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String p1, String[] p2) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String p1, String[] p2) { return 0; }
}