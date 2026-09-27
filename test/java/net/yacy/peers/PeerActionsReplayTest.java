package net.yacy.peers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.security.KeyPair;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import net.yacy.peers.trust.Ed25519;
import net.yacy.peers.trust.PeerIdentityTestAccess;
import net.yacy.peers.trust.SeedSignature;

public class PeerActionsReplayTest {

    private static Seed signed(final KeyPair key, final String hash, final String port, final String ip) {
        final ConcurrentHashMap<String, String> dna = new ConcurrentHashMap<>();
        dna.put(Seed.NAME, "peer");
        dna.put(Seed.PORT, port);
        dna.put(Seed.BDATE, "20260927000000");
        dna.put(Seed.IP, ip);
        dna.put(Seed.PEERTYPE, Seed.PEERTYPE_SENIOR);
        final Seed s = new Seed(hash, dna);
        if (key != null) SeedSignature.sign(s, PeerIdentityTestAccess.identity(key));
        return s;
    }

    @Test
    public void testOlderSignatureIsRejected() throws Exception {
        final KeyPair k = Ed25519.generate();
        final String hash = PeerIdentityTestAccess.identity(k).peerHash();
        final Seed old = signed(k, hash, "8090", "192.0.2.1");
        Thread.sleep(5);
        final Seed current = signed(k, hash, "9000", "192.0.2.1");
        assertNull(PeerActions.replayReason(current, old, true));
        assertNotNull(PeerActions.replayReason(old, current, true));
    }

    @Test
    public void testUnsignedCannotReplaceSigned() {
        final KeyPair k = Ed25519.generate();
        final String hash = PeerIdentityTestAccess.identity(k).peerHash();
        final Seed stored = signed(k, hash, "8090", "192.0.2.1");
        final Seed unsigned = signed(null, hash, "8090", "192.0.2.66");
        assertNotNull(PeerActions.replayReason(unsigned, stored, true));
    }

    @Test
    public void testRelayedSeedKeepsKnownAddress() {
        final KeyPair k = Ed25519.generate();
        final String hash = PeerIdentityTestAccess.identity(k).peerHash();
        final Seed stored = signed(k, hash, "8090", "192.0.2.1");
        final Seed relayed = new Seed(hash, new ConcurrentHashMap<>(stored.getMap()));
        relayed.setIP("198.51.100.7");
        assertNull(PeerActions.replayReason(relayed, stored, false));
        assertEquals(stored.getIPs(), relayed.getIPs());
    }

    @Test
    public void testStaleSignatureOfUnknownPeerIsRejected() {
        final KeyPair k = Ed25519.generate();
        final String hash = PeerIdentityTestAccess.identity(k).peerHash();
        final Seed s = signed(k, hash, "8090", "192.0.2.1");
        s.put(Seed.SIGT, Long.toString(System.currentTimeMillis() - 2 * PeerActions.MAX_SIGNATURE_AGE));
        assertNotNull(PeerActions.replayReason(s, null, true));
    }

    @Test
    public void testFutureSignatureIsRejected() {
        final KeyPair k = Ed25519.generate();
        final String hash = PeerIdentityTestAccess.identity(k).peerHash();
        final Seed s = signed(k, hash, "8090", "192.0.2.1");
        s.put(Seed.SIGT, Long.toString(System.currentTimeMillis() + 2 * PeerActions.MAX_SIGNATURE_SKEW));
        assertNotNull(PeerActions.replayReason(s, null, true));
    }
}
