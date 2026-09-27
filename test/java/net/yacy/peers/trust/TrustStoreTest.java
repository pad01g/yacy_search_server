package net.yacy.peers.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class TrustStoreTest {

    private KeyPair coordinator, operator, stranger, peerA, peerB;
    private List<String> coordinators;
    private TrustStore store;

    private static String pk(final KeyPair k) {
        return Ed25519.encode(Ed25519.rawPublicKey(k.getPublic()));
    }

    @Before
    public void setUp() {
        this.coordinator = Ed25519.generate();
        this.operator = Ed25519.generate();
        this.stranger = Ed25519.generate();
        this.peerA = Ed25519.generate();
        this.peerB = Ed25519.generate();
        this.coordinators = new ArrayList<>();
        this.coordinators.add(pk(this.coordinator));
        this.store = new TrustStore(null, () -> this.coordinators, () -> "lab");
    }

    private JSONObject delegation(final KeyPair by, final KeyPair op, final long version, final boolean revoked) throws Exception {
        final JSONObject p = new JSONObject();
        p.put("type", TrustEnvelope.TYPE_DELEGATION);
        p.put("network", "lab");
        p.put("operator", pk(op));
        p.put("version", version);
        p.put("revoked", revoked);
        return TrustEnvelope.sign(p, by);
    }

    private JSONObject list(final KeyPair by, final long version, final Object[][] peers) throws Exception {
        final JSONObject p = new JSONObject();
        p.put("type", TrustEnvelope.TYPE_PEERLIST);
        p.put("network", "lab");
        p.put("version", version);
        final JSONArray a = new JSONArray();
        for (final Object[] e : peers) {
            final JSONObject o = new JSONObject();
            o.put("pk", pk((KeyPair) e[0]));
            o.put("priority", (Integer) e[1]);
            final JSONArray tags = new JSONArray();
            for (int i = 2; i < e.length; i++) tags.put(e[i]);
            o.put("tags", tags);
            a.put(o);
        }
        p.put("peers", a);
        return TrustEnvelope.sign(p, by);
    }

    private void add(final JSONObject env) {
        this.store.importJSON(env.toString(), false);
    }

    @Test
    public void testDelegatedListIsTrusted() throws Exception {
        add(delegation(this.coordinator, this.operator, 1, false));
        add(list(this.operator, 1, new Object[][] {{this.peerA, 50, "ads"}, {this.peerB, 100}}));
        final Map<String, TrustStore.Entry> e = this.store.effective();
        assertEquals(2, e.size());
        final TrustStore.Entry a = e.get(PeerIdentity.peerHashOf(pk(this.peerA)));
        assertEquals(50, a.priority);
        assertTrue(a.tags.contains("ads"));
        assertEquals(0.75d, a.weight(), 1e-9);
    }

    @Test
    public void testListWithoutDelegationIsIgnored() throws Exception {
        add(list(this.operator, 1, new Object[][] {{this.peerA, 100}}));
        assertTrue(this.store.effective().isEmpty());
    }

    @Test
    public void testCoordinatorMaySignListItself() throws Exception {
        add(list(this.coordinator, 1, new Object[][] {{this.peerA, 100}}));
        assertEquals(1, this.store.effective().size());
    }

    @Test
    public void testNewerVersionReplacesOlder() throws Exception {
        add(delegation(this.coordinator, this.operator, 1, false));
        add(list(this.operator, 2, new Object[][] {{this.peerA, 100}, {this.peerB, 100}}));
        add(list(this.operator, 3, new Object[][] {{this.peerA, 100}}));
        assertEquals(1, this.store.effective().size());
        // an older version arriving later does not bring peerB back
        add(list(this.operator, 2, new Object[][] {{this.peerA, 100}, {this.peerB, 100}}));
        assertEquals(1, this.store.effective().size());
    }

    @Test
    public void testRevokedOperator() throws Exception {
        add(delegation(this.coordinator, this.operator, 1, false));
        add(list(this.operator, 1, new Object[][] {{this.peerA, 100}}));
        assertEquals(1, this.store.effective().size());
        add(delegation(this.coordinator, this.operator, 2, true));
        assertTrue(this.store.effective().isEmpty());
        // the operator cannot undo the revocation with a new list
        add(list(this.operator, 9, new Object[][] {{this.peerA, 100}}));
        assertTrue(this.store.effective().isEmpty());
    }

    @Test
    public void testStrangersAreNotStored() throws Exception {
        // a delegation signed by someone who is not a configured coordinator
        add(delegation(this.stranger, this.operator, 1, false));
        add(list(this.operator, 1, new Object[][] {{this.peerA, 100}}));
        assertTrue(this.store.effective().isEmpty());
        assertEquals("{\"envelopes\":[]}", this.store.exportJSON());
    }

    @Test
    public void testTamperedPayloadIsRejected() throws Exception {
        final JSONObject env = list(this.coordinator, 1, new Object[][] {{this.peerA, 100}});
        final JSONObject other = list(this.coordinator, 1, new Object[][] {{this.peerB, 100}});
        env.put("payload", other.getString("payload"));
        add(env);
        assertTrue(this.store.effective().isEmpty());
    }

    @Test
    public void testNoCoordinatorMeansSignedOnlyMode() throws Exception {
        this.coordinators.clear();
        assertNull(this.store.effective());
    }

    @Test
    public void testCoordinatorPriority() throws Exception {
        final KeyPair second = Ed25519.generate();
        this.coordinators.add(pk(second));
        add(list(second, 1, new Object[][] {{this.peerA, 10, "unfiltered"}}));
        add(list(this.coordinator, 1, new Object[][] {{this.peerA, 90, "curated"}}));
        final TrustStore.Entry a = this.store.effective().get(PeerIdentity.peerHashOf(pk(this.peerA)));
        assertEquals(90, a.priority);
        assertEquals(0, a.coordinator);
        assertTrue(a.tags.contains("curated"));
    }

    @Test
    public void testVersionSummaryAndExchange() throws Exception {
        final String before = this.store.versionSummary();
        add(delegation(this.coordinator, this.operator, 1, false));
        add(list(this.operator, 4, new Object[][] {{this.peerA, 100}}));
        final String after = this.store.versionSummary();
        assertFalse(before.equals(after));
        // a second store that knows nothing learns from the export
        final TrustStore other = new TrustStore(null, () -> this.coordinators, () -> "lab");
        assertTrue(other.isNewer(after));
        assertEquals(2, other.importJSON(this.store.exportJSON(), false));
        assertFalse(other.isNewer(after));
        assertEquals(1, other.effective().size());
    }

    @Test
    public void testRevocationRaisesTheVersionSummary() throws Exception {
        add(delegation(this.coordinator, this.operator, 1, false));
        add(list(this.operator, 2, new Object[][] {{this.peerA, 100}}));
        final String before = this.store.versionSummary();
        final TrustStore other = new TrustStore(null, () -> this.coordinators, () -> "lab");
        other.importJSON(this.store.exportJSON(), false);
        add(delegation(this.coordinator, this.operator, 2, true));
        // peers that still have the old state must see the revocation as newer
        assertFalse(before.equals(this.store.versionSummary()));
        assertTrue(other.isNewer(this.store.versionSummary()));
    }

    @Test
    public void testOtherNetworkIsIgnored() throws Exception {
        final TrustStore other = new TrustStore(null, () -> this.coordinators, () -> "freeworld");
        other.importJSON(list(this.coordinator, 1, new Object[][] {{this.peerA, 100}}).toString(), false);
        assertTrue(other.effective().isEmpty());
    }
}
