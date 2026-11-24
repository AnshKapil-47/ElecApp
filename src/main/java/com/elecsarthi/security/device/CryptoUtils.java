package com.elecsarthi.security.device;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class CryptoUtils {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private CryptoUtils() {}

    public static String sha256Hex(String input) {
        if (input == null) return null;
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String hmacSha256Hex(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    public static boolean verifyEcdsaP256Signature(String base64PublicKey, byte[] data, String base64Signature) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey publicKey = kf.generatePublic(spec);

            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] aesGcmEncrypt(byte[] plaintext, byte[] key) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(128, iv);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, "AES");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] cipherText = cipher.doFinal(plaintext);
            byte[] output = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(cipherText, 0, output, iv.length, cipherText.length);
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] encryptedBytes, byte[] key) {
        try {
            if (encryptedBytes == null || encryptedBytes.length < 12 + 16) {
                throw new IllegalArgumentException("Invalid encrypted payload");
            }
            byte[] iv = new byte[12];
            System.arraycopy(encryptedBytes, 0, iv, 0, 12);
            int cipherTextLen = encryptedBytes.length - 12;
            byte[] cipherText = new byte[cipherTextLen];
            System.arraycopy(encryptedBytes, 12, cipherText, 0, cipherTextLen);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(128, iv);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, "AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, spec);
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    public static byte[] signEd25519(byte[] message, byte[] privateKeyBytes) {
        try {
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters privKey =
                    new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKeyBytes, 0);
            org.bouncycastle.crypto.signers.Ed25519Signer signer = new org.bouncycastle.crypto.signers.Ed25519Signer();
            signer.init(true, privKey);
            signer.update(message, 0, message.length);
            return signer.generateSignature();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public static boolean verifyEd25519(byte[] message, byte[] signature, byte[] publicKeyBytes) {
        try {
            org.bouncycastle.crypto.params.Ed25519PublicKeyParameters pubKey =
                    new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKeyBytes, 0);
            org.bouncycastle.crypto.signers.Ed25519Signer signer = new org.bouncycastle.crypto.signers.Ed25519Signer();
            signer.init(false, pubKey);
            signer.update(message, 0, message.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
