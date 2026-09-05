package com.notifybridge.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 使用 Android Keystore 中的 AES-GCM 密钥对敏感配置（如 WebDAV 密码）加解密。
 * 密钥不可导出，仅本应用可解密。
 */
public final class CryptoStore {
    private static final String TAG = "CryptoStore";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "notifybridge_key";
    private static final int GCM_TAG_BITS = 128;

    private CryptoStore() {}

    private static SecretKey getOrCreateKey(Context c) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (key != null) return key;

        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    /** 加密为 "ivBase64:cipherBase64" 字符串。 */
    public static String encrypt(Context c, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(c));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    /** 解密 encrypt 产生的字符串。 */
    public static String decrypt(Context c, String data) throws Exception {
        String[] parts = data.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("bad ciphertext");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(c), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }
}
