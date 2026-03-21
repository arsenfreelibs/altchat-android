package org.thoughtcrime.securesms.altplatform.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

/**
 * AES-256-GCM encryption for the OpenPGP private key.
 * Blob format: [16 bytes salt][12 bytes nonce][ciphertext + 16 bytes GCM tag]
 */
public class AltKeyCrypto {

    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String KEY_DERIVATION = "PBKDF2WithHmacSHA256";

    public static byte[] encrypt(byte[] plaintext, String password) throws AltCryptoException {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LENGTH];
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(salt);
            random.nextBytes(nonce);

            SecretKey key = deriveKey(password, salt);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[SALT_LENGTH + NONCE_LENGTH + ciphertext.length];
            System.arraycopy(salt, 0, result, 0, SALT_LENGTH);
            System.arraycopy(nonce, 0, result, SALT_LENGTH, NONCE_LENGTH);
            System.arraycopy(ciphertext, 0, result, SALT_LENGTH + NONCE_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new AltCryptoException("Encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] blob, String password) throws AltCryptoException {
        if (blob.length < SALT_LENGTH + NONCE_LENGTH + 16) {
            throw new AltCryptoException("Invalid blob size");
        }
        try {
            byte[] salt = new byte[SALT_LENGTH];
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(blob, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(blob, SALT_LENGTH, nonce, 0, NONCE_LENGTH);
            int ciphertextLen = blob.length - SALT_LENGTH - NONCE_LENGTH;
            byte[] ciphertext = new byte[ciphertextLen];
            System.arraycopy(blob, SALT_LENGTH + NONCE_LENGTH, ciphertext, 0, ciphertextLen);

            SecretKey key = deriveKey(password, salt);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return cipher.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new AltCryptoException("Wrong password — GCM tag mismatch", e);
        } catch (Exception e) {
            throw new AltCryptoException("Decryption failed", e);
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}
