package net.yacy.peers.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.kelondro.data.word.Word;

public class ProvenanceTest {

    private PeerIdentity author;
    private static final String URL = "http://example.org/doc.html";
    private static final List<String> WORDS = Arrays.asList("postgres", "autovacuum", "tuning", "暗号", "資産");

    @Before
    public void setUp() {
        this.author = PeerIdentity.forKeys(Ed25519.generate());
        PeerIdentity.setInstance(this.author);
    }

    @After
    public void tearDown() {
        PeerIdentity.setInstance(null);
    }

    private String signed() {
        return Provenance.sign(URL, "Autovacuum tuning", WORDS);
    }

    /** verify as another peer, with the author in or out of the trust set */
    private Provenance.Verdict verifyAsOther(final String value, final String url, final String title, final boolean authorTrusted) {
        PeerIdentity.setInstance(PeerIdentity.forKeys(Ed25519.generate()));
        final Map<String, TrustStore.Entry> set = new HashMap<>();
        if (authorTrusted) {
            set.put(this.author.peerHash(), new TrustStore.Entry(this.author.publicKeyB64(), this.author.peerHash(), 60,
                    new LinkedHashSet<>(Collections.singletonList("ads")), 0));
        }
        return Provenance.verify(value, url, title, set);
    }

    @Test
    public void testOwnDocument() {
        assertEquals(Provenance.Status.SELF, Provenance.verify(signed(), URL, "Autovacuum tuning", new HashMap<>()).status);
    }

    @Test
    public void testTrustedAndUntrustedAuthor() {
        final String v = signed();
        final Provenance.Verdict t = verifyAsOther(v, URL, "Autovacuum tuning", true);
        assertEquals(Provenance.Status.TRUSTED, t.status);
        assertTrue(t.tags().contains("ads"));
        assertEquals(0.8d, t.weight(), 1e-9);
        assertEquals(Provenance.Status.SIGNED, verifyAsOther(v, URL, "Autovacuum tuning", false).status);
    }

    @Test
    public void testSignedOnlyModeTrustsEveryValidAuthor() {
        final String v = signed();
        PeerIdentity.setInstance(PeerIdentity.forKeys(Ed25519.generate()));
        assertEquals(Provenance.Status.TRUSTED, Provenance.verify(v, URL, "Autovacuum tuning", null).status);
    }

    @Test
    public void testTamperingIsDetected() {
        final String v = signed();
        assertEquals(Provenance.Status.INVALID, verifyAsOther(v, URL, "Buy cheap pills", true).status);
        assertEquals(Provenance.Status.INVALID, verifyAsOther(v, "http://evil.example/", "Autovacuum tuning", true).status);
        // somebody else's key with the author's signature
        final String[] p = v.split("\\|");
        final String other = p[0] + "|" + PeerIdentity.forKeys(Ed25519.generate()).publicKeyB64() + "|" + p[2] + "|" + p[3];
        assertEquals(Provenance.Status.INVALID, verifyAsOther(other, URL, "Autovacuum tuning", true).status);
        assertEquals(Provenance.Status.INVALID, verifyAsOther("garbage", URL, "Autovacuum tuning", true).status);
        assertEquals(Provenance.Status.UNSIGNED, verifyAsOther(null, URL, "Autovacuum tuning", true).status);
    }

    @Test
    public void testUrlIsNormalized() {
        final String v = Provenance.sign("http://EXAMPLE.org:80/doc.html", "t", WORDS);
        assertTrue(verifyAsOther(v, "http://example.org/doc.html", "t", true).isTrusted());
    }

    @Test
    public void testWordFilter() {
        final Provenance.Verdict t = verifyAsOther(signed(), URL, "Autovacuum tuning", true);
        assertTrue(t.containsAll(Arrays.asList(Word.word2hash("postgres"), Word.word2hash("tuning"), Word.word2hash("暗号"))));
        int misses = 0;
        for (int i = 0; i < 50; i++) if (!t.containsAll(Collections.singletonList(Word.word2hash("absent" + i)))) misses++;
        assertTrue("bloom filter should reject most absent words, rejected " + misses, misses >= 45);
    }

    @Test
    public void testBloomSize() {
        assertEquals(Provenance.BLOOM_MIN_BITS / 8, Provenance.bloom(WORDS).length);
        final List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 100000; i++) many.add("w" + i);
        assertEquals(Provenance.BLOOM_MAX_BITS / 8, Provenance.bloom(many).length);
        assertTrue(Provenance.sign(URL, "t", many).length() <= Provenance.MAX_LENGTH);
    }

    @Test
    public void testAcceptNeverTakesInvalid() {
        assertFalse(Provenance.accept(verifyAsOther("1|x|y|z", URL, "t", true)));
        assertTrue(Provenance.accept(verifyAsOther(signed(), URL, "Autovacuum tuning", true)));
    }

    @Test
    public void testNoCoordinatorMeansOnlyOwnDocuments() {
        final TrustStore store = TrustStore.init(null, java.util.Collections::emptyList, () -> "lab");
        try {
            // without coordinators (and without trust.signedOnly) the trust set is empty: fail closed
            assertTrue(Provenance.currentTrustSet().isEmpty());
            final String v = signed();
            assertEquals(Provenance.Status.SELF, Provenance.verify(v, URL, "Autovacuum tuning", Provenance.currentTrustSet()).status);
            PeerIdentity.setInstance(PeerIdentity.forKeys(Ed25519.generate()));
            assertEquals(Provenance.Status.SIGNED, Provenance.verify(v, URL, "Autovacuum tuning", Provenance.currentTrustSet()).status);
        } finally {
            TrustStore.setInstance(null);
        }
        assertTrue(store != null);
    }

    @Test
    public void testTrustedEntryMustHaveTheSameKey() {
        final String v = signed();
        PeerIdentity.setInstance(PeerIdentity.forKeys(Ed25519.generate()));
        final Map<String, TrustStore.Entry> set = new HashMap<>();
        // an entry with the author's hash but another key (a hash collision) does not make the author trusted
        set.put(this.author.peerHash(), new TrustStore.Entry(PeerIdentity.forKeys(Ed25519.generate()).publicKeyB64(), this.author.peerHash(), 100,
                new LinkedHashSet<String>(), 0));
        assertEquals(Provenance.Status.SIGNED, Provenance.verify(v, URL, "Autovacuum tuning", set).status);
    }

    @Test
    public void testSidecarClientAddress() {
        final String a = P2PRoute.sidecarClientAddress("12D3KooWPeerA").getHostAddress();
        assertTrue(a, a.startsWith("2001:db8:"));
        assertTrue(P2PRoute.isSidecarClient(a, "12D3KooWPeerA"));
        assertFalse(P2PRoute.isSidecarClient(a, "12D3KooWPeerB"));
        assertFalse(P2PRoute.isSidecarClient("127.0.0.1", "12D3KooWPeerA"));
    }

    @Test
    public void testSplitHashes() {
        assertEquals(2, Provenance.splitHashes("AAAAAAAAAAAABBBBBBBBBBBB").size());
        assertEquals(0, Provenance.splitHashes(null).size());
    }
}
