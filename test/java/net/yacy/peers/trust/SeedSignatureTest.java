package net.yacy.peers.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Test;

import net.yacy.peers.Seed;

public class SeedSignatureTest {

    @After
    public void tearDown() {
        PeerIdentity.setInstance(null);
    }

    private static Seed seedOf(final PeerIdentity id) {
        final ConcurrentHashMap<String, String> dna = new ConcurrentHashMap<>();
        dna.put(Seed.NAME, "peer-a");
        dna.put(Seed.PORT, "8090");
        dna.put(Seed.BDATE, "20260927000000");
        dna.put(Seed.IP, "192.0.2.1");
        dna.put(Seed.PEERTYPE, Seed.PEERTYPE_SENIOR);
        return new Seed(id.peerHash(), dna);
    }

    @Test
    public void testSignedSeedVerifies() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(id);
        assertEquals(SeedSignature.Status.UNSIGNED, SeedSignature.verify(s));
        SeedSignature.sign(s, id);
        assertEquals(SeedSignature.Status.VALID, SeedSignature.verify(s));
        assertTrue(s.isSigned());
    }

    @Test
    public void testObservedFieldsMayChange() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(id);
        SeedSignature.sign(s, id);
        // receivers rewrite these before they relay the seed
        s.put(Seed.IP, "198.51.100.7");
        s.put(Seed.PEERTYPE, Seed.PEERTYPE_SENIOR);
        s.put(Seed.LASTSEEN, "20260927010101");
        assertEquals(SeedSignature.Status.VALID, s.signatureStatus());
    }

    @Test
    public void testSignedFieldsAreProtected() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(id);
        SeedSignature.sign(s, id);
        s.put(Seed.PORT, "9999");
        assertEquals(SeedSignature.Status.INVALID, s.signatureStatus());
        s.put(Seed.PORT, "8090");
        assertEquals(SeedSignature.Status.VALID, s.signatureStatus());
        s.put(Seed.TAGS, "curated");
        assertEquals(SeedSignature.Status.INVALID, s.signatureStatus());
    }

    @Test
    public void testHashMustBelongToKey() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final PeerIdentity other = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(id);
        SeedSignature.sign(s, id);
        // somebody copies PK and Sig of peer A into a seed with another hash
        final ConcurrentHashMap<String, String> dna = new ConcurrentHashMap<>(s.getMap());
        final Seed forged = new Seed(other.peerHash(), dna);
        assertEquals(SeedSignature.Status.INVALID, SeedSignature.verify(forged));
        // a key without signature is not enough
        final ConcurrentHashMap<String, String> dna2 = new ConcurrentHashMap<>(s.getMap());
        dna2.remove(Seed.SIG);
        assertEquals(SeedSignature.Status.INVALID, SeedSignature.verify(new Seed(id.peerHash(), dna2)));
    }

    @Test
    public void testResignOnlyWhenNeeded() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(id);
        SeedSignature.sign(s, id);
        final String t = s.get(Seed.SIGT, null);
        SeedSignature.sign(s, id);
        assertEquals(t, s.get(Seed.SIGT, null));
        s.put(Seed.NAME, "renamed");
        SeedSignature.sign(s, id);
        assertEquals(SeedSignature.Status.VALID, SeedSignature.verify(s));
    }

    @Test
    public void testSignOnlyOwnSeed() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final PeerIdentity other = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(other);
        SeedSignature.sign(s, id);
        assertEquals(SeedSignature.Status.UNSIGNED, SeedSignature.verify(s));
    }

    @Test
    public void testChallenge() {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        final PeerIdentity other = PeerIdentity.forKeys(Ed25519.generate());
        final Seed s = seedOf(id);
        SeedSignature.sign(s, id);
        final String answer = SeedSignature.answerChallenge(id, "nonce-0001", "192.0.2.9");
        assertTrue(SeedSignature.checkChallenge(s, "nonce-0001", "192.0.2.9", answer));
        assertFalse(SeedSignature.checkChallenge(s, "nonce-0002", "192.0.2.9", answer));
        // the answer covers the address the peer saw the request coming from: a forwarded challenge is detected
        assertFalse(SeedSignature.checkChallenge(s, "nonce-0001", "198.51.100.1", answer));
        // a peer at the address that does not own the key cannot answer
        assertFalse(SeedSignature.checkChallenge(s, "nonce-0001", "192.0.2.9", SeedSignature.answerChallenge(other, "nonce-0001", "192.0.2.9")));
        assertFalse(SeedSignature.checkChallenge(s, "nonce-0001", "192.0.2.9", null));
    }

    @Test
    public void testChallengeFormat() {
        assertTrue(SeedSignature.isValidChallenge("AbCd_-0123456789"));
        assertFalse(SeedSignature.isValidChallenge("short"));
        assertFalse(SeedSignature.isValidChallenge("with|separator"));
        assertTrue(SeedSignature.isValidObserved("192.0.2.9"));
        assertTrue(SeedSignature.isValidObserved("2001:db8::1"));
        assertTrue(SeedSignature.isValidObserved(SeedSignature.OBSERVED_SIDECAR));
        assertFalse(SeedSignature.isValidObserved("192.0.2.9|x"));
    }

    @Test
    public void testSafeFieldValues() {
        assertTrue(SeedSignature.isSafeFieldValue("/ip4/172.30.0.3/tcp/4001/p2p/12D3KooWX/p2p-circuit/p2p/12D3KooWY"));
        assertFalse(SeedSignature.isSafeFieldValue("a,b"));
        assertFalse(SeedSignature.isSafeFieldValue("a=b"));
        assertFalse(SeedSignature.isSafeFieldValue("a\nb"));
    }

    @Test
    public void testGenSeedStrSignsOwnSeedAndRoundTrips() throws Exception {
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.generate());
        PeerIdentity.setInstance(id);
        final Seed s = seedOf(id);
        final String str = s.genSeedStr(null);
        final Seed parsed = Seed.genRemoteSeed(str, false, null);
        assertEquals(SeedSignature.Status.VALID, parsed.signatureStatus());
        assertEquals(id.peerHash(), parsed.hash);
    }
}
