// Ed25519.java
// -------------------------------------
// part of YaCy
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

package net.yacy.peers.trust;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Small helpers around the JDK's Ed25519 implementation. Public keys travel as the raw 32 byte key in
 * base64url without padding, the form libp2p and most other Ed25519 users exchange.
 */
public final class Ed25519 {

    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519; the raw key follows */
    private static final byte[] X509_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    public static final int PUBLIC_KEY_LENGTH = 32;
    public static final int SIGNATURE_LENGTH = 64;

    private Ed25519() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JVM", e);
        }
    }

    public static byte[] rawPublicKey(final PublicKey key) {
        final byte[] der = key.getEncoded();
        if (der.length != X509_PREFIX.length + PUBLIC_KEY_LENGTH) throw new IllegalArgumentException("not an Ed25519 public key");
        return Arrays.copyOfRange(der, X509_PREFIX.length, der.length);
    }

    public static PublicKey publicKey(final byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != PUBLIC_KEY_LENGTH) throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        final byte[] der = new byte[X509_PREFIX.length + PUBLIC_KEY_LENGTH];
        System.arraycopy(X509_PREFIX, 0, der, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, der, X509_PREFIX.length, PUBLIC_KEY_LENGTH);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    public static byte[] sign(final PrivateKey key, final byte[] message) {
        try {
            final Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message);
            return s.sign();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return true only if the signature is a valid Ed25519 signature of the message by the key; any malformed input gives false
     */
    public static boolean verify(final byte[] rawPublicKey, final byte[] message, final byte[] signature) {
        if (rawPublicKey == null || message == null || signature == null || signature.length != SIGNATURE_LENGTH) return false;
        try {
            final Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey(rawPublicKey));
            s.update(message);
            return s.verify(signature);
        } catch (final GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    public static boolean verify(final String publicKeyB64, final String message, final String signatureB64) {
        final byte[] pk = decode(publicKeyB64);
        final byte[] sig = decode(signatureB64);
        return verify(pk, message.getBytes(StandardCharsets.UTF_8), sig);
    }

    public static String encode(final byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** @return the decoded bytes, or null if the string is not base64url */
    public static byte[] decode(final String s) {
        if (s == null) return null;
        try {
            return Base64.getUrlDecoder().decode(s.trim());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** store a key pair as PKCS#8 PEM, readable only by the owner where the file system supports it */
    public static void writePrivateKey(final File file, final KeyPair pair) throws IOException {
        final String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        final File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        final File tmp = new File(dir, file.getName() + ".tmp");
        Files.write(tmp.toPath(), pem.getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (final UnsupportedOperationException | IOException e) {
            // not a POSIX file system; keep the default permissions
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** read a PKCS#8 PEM Ed25519 private key and derive its public key */
    public static KeyPair readPrivateKey(final File file) throws IOException {
        final String pem = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
        final String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            final byte[] der = Base64.getDecoder().decode(body);
            final PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            return new KeyPair(publicKeyOf(der), priv);
        } catch (final GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("cannot read Ed25519 private key from " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The JDK cannot derive the public key from a PKCS#8 Ed25519 private key. The RFC 8410 structure holds the
     * 32 byte seed, so run the JDK key generation again with exactly that seed and check that it reproduces the
     * stored private key.
     */
    private static PublicKey publicKeyOf(final byte[] pkcs8) throws GeneralSecurityException {
        // RFC 8410 PKCS#8 v1: 30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20 <32 byte seed>
        if (pkcs8.length < 48) throw new GeneralSecurityException("unexpected PKCS#8 length " + pkcs8.length);
        final byte[] seed = Arrays.copyOfRange(pkcs8, 16, 48);
        final KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        g.initialize(255, new FixedSecureRandom(seed));
        final KeyPair derived = g.generateKeyPair();
        if (!Arrays.equals(derived.getPrivate().getEncoded(), Arrays.copyOf(pkcs8, derived.getPrivate().getEncoded().length))) {
            throw new GeneralSecurityException("could not derive the public key of the stored private key");
        }
        return derived.getPublic();
    }

    /** a SecureRandom that returns the given bytes once; used to re-create a key pair from its seed */
    private static final class FixedSecureRandom extends java.security.SecureRandom {
        private static final long serialVersionUID = 1L;
        private final byte[] bytes;
        private int pos = 0;

        FixedSecureRandom(final byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public void nextBytes(final byte[] out) {
            for (int i = 0; i < out.length; i++) {
                if (this.pos >= this.bytes.length) throw new IllegalStateException("seed exhausted");
                out[i] = this.bytes[this.pos++];
            }
        }
    }
}
