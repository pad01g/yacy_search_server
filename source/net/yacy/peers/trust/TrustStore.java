// TrustStore.java
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;

/**
 * The signed delegations and peer lists this peer knows, and the effective set of trusted peers derived from them
 * for the configured coordinator keys (see docs/trust-and-nat.md, sections 3 and 4).
 *
 * Rules: a delegation is signed by a coordinator and names an operator; per (coordinator, operator) the highest
 * version wins and a revoked delegation disables the operator. A peer list is signed by an operator (or by a
 * coordinator for itself); per signer the highest version wins. Versions only grow, there are no expiry dates.
 */
public final class TrustStore {

    public static final String BUNDLE_FILE = "DATA/SETTINGS/trust-bundle.json";
    /** stop storing envelopes beyond this number (protection against flooding) */
    static final int MAX_ENVELOPES = 512;
    static final int MAX_PEERS_PER_LIST = 10000;

    public static final class Entry {
        public final String publicKey;
        public final String peerHash;
        public final int priority;
        public final Set<String> tags;
        /** index of the coordinator in the configured list that made this peer trusted, 0 = first */
        public final int coordinator;

        Entry(final String publicKey, final String peerHash, final int priority, final Set<String> tags, final int coordinator) {
            this.publicKey = publicKey;
            this.peerHash = peerHash;
            this.priority = priority;
            this.tags = Collections.unmodifiableSet(tags);
            this.coordinator = coordinator;
        }

        /** the factor applied to the scores of documents by this author: 0.5 .. 1.0 */
        public double weight() {
            return 0.5d + 0.5d * this.priority / 100.0d;
        }
    }

    private static volatile TrustStore instance = null;

    private final File file;
    /** the configured coordinator keys (priority order) and the network name; read on every use */
    private final Supplier<List<String>> coordinators;
    private final Supplier<String> network;
    /** latest delegation per coordinator and operator: coordinator -> operator -> envelope */
    private final Map<String, Map<String, TrustEnvelope>> delegations = new TreeMap<>();
    /** latest peer list per signer */
    private final Map<String, TrustEnvelope> lists = new TreeMap<>();

    // cache of the effective trust set for a configuration
    private String effectiveFor = null;
    private Map<String, Entry> effective = null;

    TrustStore(final File file, final Supplier<List<String>> coordinators, final Supplier<String> network) {
        this.file = file;
        this.coordinators = coordinators;
        this.network = network;
    }

    public static synchronized TrustStore init(final File file, final Supplier<List<String>> coordinators, final Supplier<String> network) {
        final TrustStore store = new TrustStore(file, coordinators, network);
        if (file != null && file.exists()) {
            try {
                final int n = store.importJSON(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), false);
                ConcurrentLog.info("TrustStore", "loaded " + n + " trust statements from " + file);
            } catch (final IOException e) {
                ConcurrentLog.warn("TrustStore", "cannot read " + file + ": " + e.getMessage());
            }
        }
        instance = store;
        return store;
    }

    /** @return the store of this peer, or null before init (unit tests, tools) */
    public static TrustStore get() {
        return instance;
    }

    /**
     * Import envelopes from a bundle {"envelopes": [...]}, an array of envelopes or a single envelope.
     * Envelopes that do not verify are ignored.
     * @param save write the bundle file if something changed
     * @return the number of envelopes that changed the store
     */
    public int importJSON(final String text, final boolean save) {
        final List<JSONObject> candidates = new ArrayList<>();
        try {
            final String t = text.trim();
            if (t.startsWith("[")) {
                final JSONArray a = new JSONArray(t);
                for (int i = 0; i < a.length(); i++) candidates.add(a.getJSONObject(i));
            } else {
                final JSONObject o = new JSONObject(t);
                final JSONArray a = o.optJSONArray("envelopes");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) candidates.add(a.getJSONObject(i));
                } else {
                    candidates.add(o);
                }
            }
        } catch (final JSONException e) {
            ConcurrentLog.info("TrustStore", "ignoring malformed trust bundle: " + e.getMessage());
            return 0;
        }
        // delegations first, so that the lists of newly delegated operators are accepted in the same import
        final List<TrustEnvelope> parsed = new ArrayList<>();
        for (final JSONObject c : candidates) {
            final TrustEnvelope e = TrustEnvelope.parse(c);
            if (e != null) parsed.add(e);
        }
        parsed.sort((a, b) -> Boolean.compare(TrustEnvelope.TYPE_PEERLIST.equals(a.type), TrustEnvelope.TYPE_PEERLIST.equals(b.type)));
        int changed = 0;
        for (final TrustEnvelope e : parsed) {
            if (add(e)) changed++;
        }
        if (changed > 0 && save) save();
        return changed;
    }

    /**
     * Only statements of the configured coordinators and of the operators they delegate to are stored; anybody can
     * make a signed envelope, so storing others would let strangers fill the store.
     * @return true if the envelope was new and replaced an older version
     */
    public synchronized boolean add(final TrustEnvelope e) {
        if (size() >= MAX_ENVELOPES) return false;
        final List<String> coords = this.coordinators.get();
        if (TrustEnvelope.TYPE_DELEGATION.equals(e.type)) {
            if (!coords.contains(e.signer)) return false;
            final String operator = canonicalKey(e.payload.optString("operator", null));
            if (operator == null) return false;
            final Map<String, TrustEnvelope> byOperator = this.delegations.computeIfAbsent(e.signer, k -> new TreeMap<>());
            final TrustEnvelope old = byOperator.get(operator);
            if (old != null && old.version >= e.version) return false;
            byOperator.put(operator, e);
        } else if (TrustEnvelope.TYPE_PEERLIST.equals(e.type)) {
            if (!isOperator(e.signer, coords)) return false;
            final TrustEnvelope old = this.lists.get(e.signer);
            if (old != null && old.version >= e.version) return false;
            if (e.payload.optJSONArray("peers") == null) return false;
            this.lists.put(e.signer, e);
        } else {
            return false;
        }
        this.effectiveFor = null;
        return true;
    }

    /** @return true if the key is a configured coordinator or has a delegation from one (revoked or not) */
    private boolean isOperator(final String key, final List<String> coords) {
        if (coords.contains(key)) return true;
        for (final String c : coords) {
            final Map<String, TrustEnvelope> byOperator = this.delegations.get(c);
            if (byOperator != null && byOperator.containsKey(key)) return true;
        }
        return false;
    }

    private synchronized int size() {
        int n = this.lists.size();
        for (final Map<String, TrustEnvelope> m : this.delegations.values()) n += m.size();
        return n;
    }

    /** @return the key in canonical base64url form, or null if it is not an Ed25519 public key */
    static String canonicalKey(final String b64) {
        final byte[] k = Ed25519.decode(b64);
        if (k == null || k.length != Ed25519.PUBLIC_KEY_LENGTH) return null;
        return Ed25519.encode(k);
    }

    /** @return the configured coordinator keys in priority order; malformed keys are skipped */
    public static List<String> parseCoordinators(final String config) {
        final List<String> keys = new ArrayList<>();
        if (config == null) return keys;
        for (final String k : config.split("[,\\s]+")) {
            final String c = canonicalKey(k);
            if (c != null && !keys.contains(c)) keys.add(c);
        }
        return keys;
    }

    /**
     * The trusted peers for the given coordinators and network: peer hash -> entry. Coordinators earlier in the list
     * decide priority and tags of a peer that several coordinators trust.
     * @return the map, or null if no coordinator is configured (then every signed peer counts as trusted)
     */
    public Map<String, Entry> effective() {
        return effective(this.coordinators.get(), this.network.get());
    }

    public synchronized Map<String, Entry> effective(final List<String> coordinators, final String network) {
        if (coordinators.isEmpty()) return null;
        final String key = coordinators.toString() + '|' + network;
        if (key.equals(this.effectiveFor)) return this.effective;
        final Map<String, Entry> result = new LinkedHashMap<>();
        for (int ci = 0; ci < coordinators.size(); ci++) {
            final String coordinator = coordinators.get(ci);
            for (final String operator : operators(coordinator, network)) {
                final TrustEnvelope list = this.lists.get(operator);
                if (list == null || !list.appliesTo(network)) continue;
                addPeers(result, list, ci);
            }
        }
        this.effective = Collections.unmodifiableMap(result);
        this.effectiveFor = key;
        return this.effective;
    }

    /** @return the operators a coordinator currently delegates to for the network; the coordinator itself first */
    private List<String> operators(final String coordinator, final String network) {
        final List<String> ops = new ArrayList<>();
        ops.add(coordinator);
        final Map<String, TrustEnvelope> byOperator = this.delegations.get(coordinator);
        if (byOperator == null) return ops;
        for (final Map.Entry<String, TrustEnvelope> d : byOperator.entrySet()) {
            final TrustEnvelope e = d.getValue();
            if (e.payload.optBoolean("revoked", false)) {
                ops.remove(d.getKey()); // a coordinator can also revoke itself as operator
                continue;
            }
            if (!e.appliesTo(network)) continue;
            if (!ops.contains(d.getKey())) ops.add(d.getKey());
        }
        return ops;
    }

    private static void addPeers(final Map<String, Entry> result, final TrustEnvelope list, final int coordinatorIndex) {
        final JSONArray peers = list.payload.optJSONArray("peers");
        if (peers == null) return;
        for (int i = 0; i < peers.length() && i < MAX_PEERS_PER_LIST; i++) {
            final JSONObject p = peers.optJSONObject(i);
            if (p == null) continue;
            final String pk = canonicalKey(p.optString("pk", null));
            if (pk == null) continue;
            final String hash = PeerIdentity.peerHashOf(pk);
            if (result.containsKey(hash)) continue;
            final int priority = Math.max(0, Math.min(100, p.optInt("priority", 100)));
            final Set<String> tags = new LinkedHashSet<>();
            final JSONArray t = p.optJSONArray("tags");
            if (t != null) for (int j = 0; j < t.length() && j < 32; j++) {
                final String tag = t.optString(j, "").trim().toLowerCase(java.util.Locale.ROOT);
                if (TrustPolicy.isValidTag(tag)) tags.add(tag);
            }
            result.put(hash, new Entry(pk, hash, priority, tags, coordinatorIndex));
        }
    }

    /**
     * The versions this peer holds, per coordinator: the sum of the versions of the coordinator's delegations and of
     * the lists of its operators. The sum grows whenever one of them is replaced by a newer version.
     * @return "c8.v|c8.v" with c8 the first 8 characters of the coordinator's peer hash
     */
    public String versionSummary() {
        return versionSummary(this.coordinators.get(), this.network.get());
    }

    public synchronized String versionSummary(final List<String> coordinators, final String network) {
        final StringBuilder sb = new StringBuilder();
        for (final String c : coordinators) {
            if (sb.length() > 0) sb.append('|');
            sb.append(PeerIdentity.peerHashOf(c).substring(0, 8)).append('.').append(versionSum(c, network));
        }
        return sb.toString();
    }

    /**
     * The sum must only grow: count the lists of every operator the coordinator ever delegated to, also revoked ones,
     * otherwise a revocation would lower the sum and peers would not fetch it.
     */
    private long versionSum(final String coordinator, final String network) {
        long sum = 0;
        final List<String> signers = new ArrayList<>();
        signers.add(coordinator);
        final Map<String, TrustEnvelope> byOperator = this.delegations.get(coordinator);
        if (byOperator != null) {
            for (final Map.Entry<String, TrustEnvelope> d : byOperator.entrySet()) {
                sum += d.getValue().version;
                if (!signers.contains(d.getKey())) signers.add(d.getKey());
            }
        }
        for (final String signer : signers) {
            final TrustEnvelope l = this.lists.get(signer);
            if (l != null) sum += l.version;
        }
        return sum;
    }

    /** @return true if the remote summary announces a newer state for one of our coordinators */
    public boolean isNewer(final String remoteSummary) {
        if (remoteSummary == null || remoteSummary.isEmpty()) return false;
        final Map<String, Long> remote = parseSummary(remoteSummary);
        final Map<String, Long> local = parseSummary(versionSummary());
        for (final Map.Entry<String, Long> r : remote.entrySet()) {
            final Long l = local.get(r.getKey());
            if (l != null && r.getValue() > l) return true;
        }
        return false;
    }

    static Map<String, Long> parseSummary(final String s) {
        final Map<String, Long> m = new HashMap<>();
        for (final String part : s.split("\\|")) {
            final int p = part.indexOf('.');
            if (p <= 0) continue;
            try {
                m.put(part.substring(0, p), Long.parseLong(part.substring(p + 1)));
            } catch (final NumberFormatException e) {
                // ignore
            }
        }
        return m;
    }

    /** @return all stored envelopes as {"envelopes": [...]} */
    public synchronized String exportJSON() {
        final JSONArray a = new JSONArray();
        for (final Map<String, TrustEnvelope> m : this.delegations.values()) for (final TrustEnvelope e : m.values()) a.put(e.toJSON());
        for (final TrustEnvelope e : this.lists.values()) a.put(e.toJSON());
        try {
            return new JSONObject().put("envelopes", a).toString();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void save() {
        if (this.file == null) return;
        try {
            final File dir = this.file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            final File tmp = new File(dir, this.file.getName() + ".tmp");
            Files.write(tmp.toPath(), exportJSON().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            ConcurrentLog.warn("TrustStore", "cannot write " + this.file + ": " + e.getMessage());
        }
    }
}
