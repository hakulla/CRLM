package org.mobileagent.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private encrypted storage for the optional installer password. Never log or expose it. */
final class DeviceSecretStore {
    private static final String FILE = "device_secret";
    private static final String KEY_BLOB = "blob";
    private static final String KEY_ALIAS = "mobileagent_install_secret";
    private static final int MAX_SECRET = 256;

    private final Context context;
    private final SharedPreferences prefs;
    private volatile String lastError = "";

    DeviceSecretStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean hasSecret() {
        return !prefs.getString(KEY_BLOB, "").isEmpty();
    }

    boolean save(String secret) {
        String value = secret == null ? "" : secret;
        if (value.isEmpty() || value.length() > MAX_SECRET) {
            return false;
        }
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(encrypted, 0, blob, iv.length, encrypted.length);
            prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
            lastError = "";
            return true;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        }
    }

    String get() {
        String encoded = prefs.getString(KEY_BLOB, "");
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            if (blob.length < 13) {
                return null;
            }
            byte[] iv = new byte[12];
            System.arraycopy(blob, 0, iv, 0, 12);
            byte[] encrypted = new byte[blob.length - 12];
            System.arraycopy(blob, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    void clear() {
        prefs.edit().remove(KEY_BLOB).apply();
        lastError = "";
    }

    String lastError() {
        return lastError;
    }

    static String readPasswordFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1 && total < 8192) {
                    output.write(buffer, 0, read);
                    total += read;
                }
            } finally {
                input.close();
            }
            String[] lines = output.toString("UTF-8").split("\n", -1);
            for (String line : lines) {
                String value = line.replace("\r", "").trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                return value.length() <= MAX_SECRET ? value : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    boolean importFile(File file) {
        String secret = readPasswordFile(file);
        if (secret == null || !save(secret)) {
            return false;
        }
        if (!file.delete()) {
            try {
                FileOutputStream output = new FileOutputStream(file, false);
                output.write(new byte[0]);
                output.close();
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    static boolean writeTemplate(File file) {
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            if (file.isFile() && file.length() > 0) {
                return true;
            }
            FileOutputStream output = new FileOutputStream(file, false);
            output.write(("# CRLM Installing Help Passwords\n"
                    + "# Write a line below the phone lock./(b) Installation of passwords;\n"
                    + "# Yes. App Settings → Devices and Permissions → Installing Help Passwords → Imports from a file.\n"
                    + "# After import succeeded App Can not encrypt this message: no such folder: %s\n"
                    + "# Do not send the password to a conversation, to a screenshot or to a log.\n").getBytes(StandardCharsets.UTF_8));
            output.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
