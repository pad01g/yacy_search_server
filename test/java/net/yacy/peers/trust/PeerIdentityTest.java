package net.yacy.peers.trust;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;

import org.junit.Test;

public class PeerIdentityTest {

    @Test
    public void testKeyFileRoundTrip() throws Exception {
        final File dir = Files.createTempDirectory("peerkey").toFile();
        final File f = new File(dir, "peer.key");
        final PeerIdentity a = PeerIdentity.init(f);
        final PeerIdentity b = PeerIdentity.init(f);
        assertEquals(a.peerHash(), b.peerHash());
        assertArrayEquals(a.publicKey(), b.publicKey());
        final String sig = b.sign("hello");
        assertTrue(Ed25519.verify(a.publicKeyB64(), "hello", sig));
        assertTrue(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).startsWith("-----BEGIN PRIVATE KEY-----"));
        PeerIdentity.setInstance(null);
    }

    @Test
    public void testPeerHashIsDerivedFromKey() {
        final KeyPair k = Ed25519.generate();
        final PeerIdentity id = PeerIdentity.forKeys(k);
        assertEquals(12, id.peerHash().length());
        assertEquals(id.peerHash(), PeerIdentity.peerHashOf(id.publicKeyB64()));
        assertNull(PeerIdentity.peerHashOf("not-a-key"));
    }

    @Test
    public void testLibp2pPeerIdFormat() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        // Ed25519 keys in an identity multihash always start with 12D3KooW in base58btc
        assertTrue(id.libp2pPeerId(), id.libp2pPeerId().startsWith("12D3KooW"));
        assertEquals(52, id.libp2pPeerId().length());
    }

    @Test
    public void testBase58() {
        assertEquals("1", PeerIdentity.base58(new byte[] {0}));
        assertEquals("2g", PeerIdentity.base58("a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("StV1DL6CwTryKyV", PeerIdentity.base58("hello world".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testVerifyRejectsGarbage() {
        final KeyPair k = Ed25519.generate();
        final byte[] pk = Ed25519.rawPublicKey(k.getPublic());
        final byte[] sig = Ed25519.sign(k.getPrivate(), new byte[] {1, 2, 3});
        assertTrue(Ed25519.verify(pk, new byte[] {1, 2, 3}, sig));
        assertFalse(Ed25519.verify(pk, new byte[] {1, 2, 4}, sig));
        assertFalse(Ed25519.verify(new byte[5], new byte[] {1, 2, 3}, sig));
        assertFalse(Ed25519.verify(pk, new byte[] {1, 2, 3}, new byte[3]));
        assertFalse(Ed25519.verify((String) null, "x", "y"));
    }
}
