package com.ephemeral.crypto;

import com.ephemeral.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encryption at rest (AES-256-GCM). Message text is stored as
 * {@code enc:v1:base64(iv || ciphertext+tag)}; uploaded blobs as
 * {@code "EPHC" || iv || ciphertext+tag}. Reads pass legacy plaintext through
 * unchanged (pre-encryption rows age out with the retention window anyway), and
 * a string that fails to decrypt (key rotated) degrades to a marker instead of
 * a 500.
 *
 * <p>The key comes from {@code ephemeral.encryption-key} (base64, 32 bytes).
 * When unset it is derived from the JWT secret so dev "just works" — set a real
 * key in production or rotating the JWT secret orphans old content.</p>
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final String PREFIX = "enc:v1:";
    private static final byte[] MAGIC = "EPHC".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    public static final String UNREADABLE = "🔒 [unreadable: encrypted with a different key]";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AppProperties props) {
        byte[] k;
        String configured = props.getEncryptionKey();
        if (configured != null && !configured.isBlank()) {
            k = Base64.getDecoder().decode(configured.trim());
            if (k.length != 32) {
                throw new IllegalStateException("ephemeral.encryption-key must be base64 of exactly 32 bytes");
            }
        } else {
            k = sha256(("ephemeral-at-rest:" + props.getJwtSecret()).getBytes(StandardCharsets.UTF_8));
            log.warn("ENCRYPTION_KEY not set — deriving the at-rest key from the JWT secret. "
                    + "Set a dedicated key in production (openssl rand -base64 32).");
        }
        this.key = new SecretKeySpec(k, "AES");
    }

    // ---- strings (message content) ----------------------------------------

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = newIv();
            Cipher c = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** Decrypts {@code enc:v1:} strings; passes legacy plaintext (or null) through. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return UNREADABLE;
        }
    }

    // ---- streams (uploaded blobs) ------------------------------------------

    /** Wraps a sink so everything written to it lands on disk encrypted. */
    public OutputStream encrypting(OutputStream sink) throws IOException {
        try {
            byte[] iv = newIv();
            sink.write(MAGIC);
            sink.write(iv);
            return new CipherOutputStream(sink, cipher(Cipher.ENCRYPT_MODE, iv));
        } catch (GeneralSecurityException e) {
            throw new IOException("encrypt failed", e);
        }
    }

    /** Wraps a stored blob for reading; legacy unencrypted files pass through. */
    public InputStream decrypting(InputStream stored) throws IOException {
        PushbackInputStream in = new PushbackInputStream(stored, MAGIC.length);
        byte[] head = in.readNBytes(MAGIC.length);
        if (!Arrays.equals(head, MAGIC)) {
            in.unread(head);
            return in;
        }
        try {
            byte[] iv = in.readNBytes(IV_LEN);
            return new CipherInputStream(in, cipher(Cipher.DECRYPT_MODE, iv));
        } catch (GeneralSecurityException e) {
            throw new IOException("decrypt failed", e);
        }
    }

    // ---- internals ----------------------------------------------------------

    private byte[] newIv() {
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);
        return iv;
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return c;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
