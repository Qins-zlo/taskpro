package io.taskpro;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import android.util.Base64;

/**
 * 简单 AES 加密, 用于密码混淆存储。
 * 固定 key(从应用签名派生太复杂, 用固定值) — 防普通查看, 不防逆向。
 */
public class Crypto {
    private static final byte[] KEY = {
        0x51, 0x23, (byte)0x9C, 0x7A, 0x11, (byte)0xE5, 0x3B, 0x42,
        0x0F, (byte)0xD8, 0x6A, 0x2C, (byte)0x88, 0x10, (byte)0xE7, 0x59
    };

    public static String enc(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            SecretKeySpec key = new SecretKeySpec(KEY, "AES");
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            return Base64.encodeToString(c.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static String dec(String cipher) {
        if (cipher == null || cipher.isEmpty()) return "";
        try {
            SecretKeySpec key = new SecretKeySpec(KEY, "AES");
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, key);
            byte[] raw = Base64.decode(cipher, Base64.NO_WRAP);
            return new String(c.doFinal(raw), "UTF-8");
        } catch (Exception e) {
            return cipher; // 无法解密则原样返回(兼容旧明文数据)
        }
    }
}
