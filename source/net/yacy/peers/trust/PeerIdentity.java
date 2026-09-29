// PeerIdentity.java
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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.yacy.cora.order.Base64Order;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.data.word.Word;

/**
 * The Ed25519 key of this peer. The peer hash (the 12 character id that also decides the DHT position) is derived
 * from the public key, so a peer cannot claim the hash of another peer without its private key.
 * The same key is the libp2p identity of the NAT traversal sidecar.
 */
public final class PeerIdentity {

    public static final String KEY_FILE = "DATA/SETTINGS/peer.key";

    private static volatile PeerIdentity instance = null;

    private final KeyPair keys;
    private final byte[] publicKey;
    private final String publicKeyB64;
    private final String peerHash;

    private PeerIdentity(final KeyPair keys) {
        this.keys = keys;
        this.publicKey = Ed25519.rawPublicKey(keys.getPublic());
        this.publicKeyB64 = Ed25519.encode(this.publicKey);
        this.peerHash = peerHashOf(this.publicKey);
    }

    /**
     * Load the key from the file or create it. Called once before the seed database is opened.
     * @param keyFile the PEM file
     * @return the identity
     */
    public static synchronized PeerIdentity init(final File keyFile) throws IOException {
        KeyPair pair;
        if (keyFile.exists()) {
            Ed25519.restrictToOwner(keyFile);
            pair = Ed25519.readPrivateKey(keyFile);
        } else {
            pair = Ed25519.generate();
            Ed25519.writePrivateKey(keyFile, pair);
            ConcurrentLog.info("PeerIdentity", "created a new peer key in " + keyFile);
        }
        instance = new PeerIdentity(pair);
        ConcurrentLog.info("PeerIdentity", "peer hash " + instance.peerHash + ", public key " + instance.publicKeyB64);
        return instance;
    }

    /** for tests */
    static PeerIdentity forKeys(final KeyPair pair) {
        return new PeerIdentity(pair);
    }

    /** for tests: make the given identity the one of this process */
    static void setInstance(final PeerIdentity identity) {
        instance = identity;
    }

    /** @return the identity of this peer, or null before {@link #init(File)} (e.g. in unit tests) */
    public static PeerIdentity get() {
        return instance;
    }

    public static boolean isMine(final String hash) {
        final PeerIdentity i = instance;
        return i != null && i.peerHash.equals(hash);
    }

    public String peerHash() {
        return this.peerHash;
    }

    public byte[] publicKey() {
        return this.publicKey.clone();
    }

    public String publicKeyB64() {
        return this.publicKeyB64;
    }

    public byte[] sign(final byte[] message) {
        return Ed25519.sign(this.keys.getPrivate(), message);
    }

    public String sign(final String message) {
        return Ed25519.encode(sign(message.getBytes(StandardCharsets.UTF_8)));
    }

    public String libp2pPeerId() {
        return libp2pPeerIdOf(this.publicKey);
    }

    /** @return the YaCy peer hash of a public key: the first 12 characters of the enhanced base64 SHA-256 */
    public static String peerHashOf(final byte[] publicKey) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
            return Base64Order.enhancedCoder.encode(digest).substring(0, Word.commonHashLength);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the peer hash of a base64url public key, or null if the key is malformed */
    public static String peerHashOf(final String publicKeyB64) {
        final byte[] pk = Ed25519.decode(publicKeyB64);
        if (pk == null || pk.length != Ed25519.PUBLIC_KEY_LENGTH) return null;
        return peerHashOf(pk);
    }

    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * The libp2p peer id of an Ed25519 key: the identity multihash (0x00, length 36) of the protobuf
     * PublicKey {Type: Ed25519 (1), Data: key}, in base58btc. This is the "12D3KooW..." form.
     */
    public static String libp2pPeerIdOf(final byte[] publicKey) {
        final byte[] mh = new byte[2 + 4 + Ed25519.PUBLIC_KEY_LENGTH];
        mh[0] = 0x00;
        mh[1] = 0x24;
        mh[2] = 0x08;
        mh[3] = 0x01;
        mh[4] = 0x12;
        mh[5] = 0x20;
        System.arraycopy(publicKey, 0, mh, 6, Ed25519.PUBLIC_KEY_LENGTH);
        return base58(mh);
    }

    static String base58(final byte[] input) {
        BigInteger n = new BigInteger(1, input);
        final StringBuilder sb = new StringBuilder();
        final BigInteger base = BigInteger.valueOf(58);
        while (n.signum() > 0) {
            final BigInteger[] qr = n.divideAndRemainder(base);
            sb.append(BASE58.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for (int i = 0; i < input.length && input[i] == 0; i++) sb.append('1');
        return sb.reverse().toString();
    }
}
