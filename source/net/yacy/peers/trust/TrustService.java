// TrustService.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.peers.Seed;
import net.yacy.search.Switchboard;

/**
 * Periodic work of the trust and NAT traversal layer, driven by the peer ping (see docs/trust-and-nat.md, sections 4
 * and 6): keep the own seed up to date (declared tags, trust list versions, how the peer can be reached), fetch trust
 * bundles from the configured URLs and from peers that announce newer versions, and read the sidecar status.
 */
public final class TrustService {

    static final long BUNDLE_URL_INTERVAL = 10 * 60 * 1000;
    static final int MAX_BUNDLE_BYTES = 4 * 1024 * 1024;
    /** peers asked for their bundle per round; each round is one peer ping */
    static final int MAX_GOSSIP_FETCHES = 3;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile long lastBundleFetch = 0;
    private static volatile String lastBundleUrls = null;
    private static volatile SidecarStatus sidecar = null;
    /** set once the peer switched to the relay because others could not reach it; kept until it is public */
    private static volatile boolean relayChosen = false;
    /** consecutive pings at which this peer was junior; the relay is chosen after JUNIOR_PINGS_FOR_RELAY */
    private static volatile int juniorPings = 0;
    static final int JUNIOR_PINGS_FOR_RELAY = 3;
    /** peers whose bundle brought nothing new are not asked again for this long */
    static final long GOSSIP_BACKOFF = 10 * 60 * 1000;
    private static final java.util.Map<String, Long> gossipBackoff = new java.util.concurrent.ConcurrentHashMap<>();

    /** what the sidecar reports on GET /status */
    public static final class SidecarStatus {
        public final String peerId;
        public final String reachability;
        public final List<String> relayAddrs;

        SidecarStatus(final String peerId, final String reachability, final List<String> relayAddrs) {
            this.peerId = peerId;
            this.reachability = reachability;
            this.relayAddrs = relayAddrs;
        }
    }

    private TrustService() {
    }

    public static SidecarStatus sidecarStatus() {
        return sidecar;
    }

    /** called at every peer ping, before the own seed is published */
    public static void tick(final Switchboard sb) {
        if (sb == null || sb.peers == null || !sb.peers.mySeedIsDefined()) return;
        updateMySeed(sb.peers.mySeed());
        if (running.compareAndSet(false, true)) {
            final Thread t = new Thread(() -> {
                try {
                    background(sb);
                } catch (final Throwable e) {
                    ConcurrentLog.warn("TrustService", "round failed: " + e.getMessage());
                } finally {
                    running.set(false);
                }
            }, "TrustService");
            t.setDaemon(true);
            t.start();
        }
    }

    /** set the fields of the own seed that describe tags, trust versions and reachability */
    static void updateMySeed(final Seed my) {
        final List<String> tags = new ArrayList<>(TrustPolicy.selfTags());
        setOrRemove(my, Seed.TAGS, tags.isEmpty() ? null : String.join("|", tags));
        final TrustStore store = TrustStore.get();
        final String tv = store == null ? "" : store.versionSummary();
        setOrRemove(my, Seed.TV, tv.isEmpty() ? null : tv);

        final String mode = TrustPolicy.p2pMode();
        final SidecarStatus s = TrustPolicy.sidecarURL() == null ? null : sidecar;
        if (TrustPolicy.P2P_MODE_LEECHER.equals(mode)) {
            relayChosen = false;
            my.put(Seed.REACH, Seed.REACH_NONE);
            setOrRemove(my, Seed.P2PA, null);
        } else if (TrustPolicy.P2P_MODE_AUTO.equals(mode) && useRelay(s, my)) {
            my.put(Seed.REACH, Seed.REACH_RELAY);
            my.put(Seed.P2PA, String.join("|", s.relayAddrs));
        } else {
            setOrRemove(my, Seed.REACH, null); // direct is the default
            setOrRemove(my, Seed.P2PA, null);
        }
        setOrRemove(my, Seed.RDS, Seed.REACH_RELAY.equals(my.getReach()) && TrustPolicy.relayDhtStorage() ? "1" : null);
    }

    /**
     * Use the relay when the sidecar has a reservation and either AutoNAT says the peer is private, or AutoNAT has no
     * answer (it only tests public addresses and needs time) and other peers reported this peer as junior, i.e. they
     * could not connect back. Once chosen, the relay is kept while the peer is senior through it, until AutoNAT
     * reports the peer as public; otherwise the decision would flip at every ping.
     */
    static boolean useRelay(final SidecarStatus s, final Seed my) {
        if (s == null || s.relayAddrs.isEmpty() || "public".equals(s.reachability)) {
            relayChosen = false;
            juniorPings = 0;
            return false;
        }
        // a single junior report (e.g. at startup, or one failed back-ping) is not enough
        juniorPings = my.isJunior() ? juniorPings + 1 : 0;
        if ("private".equals(s.reachability) || juniorPings >= JUNIOR_PINGS_FOR_RELAY) relayChosen = true;
        return relayChosen;
    }

    private static void setOrRemove(final Seed seed, final String key, final String value) {
        synchronized (seed.getMap()) { // Seed.genSeedStr signs and serializes under this lock
            if (value == null) {
                seed.getMap().remove(key);
            } else if (!value.equals(seed.get(key, null))) {
                seed.put(key, value);
            }
        }
    }

    private static void background(final Switchboard sb) {
        readSidecar();
        final TrustStore store = TrustStore.get();
        if (store == null) return;
        final long now = System.currentTimeMillis();
        final String urls = sb.getConfig(TrustPolicy.BUNDLE_URLS, "");
        if (now - lastBundleFetch > BUNDLE_URL_INTERVAL || !urls.equals(lastBundleUrls)) {
            lastBundleFetch = now;
            lastBundleUrls = urls;
            for (final String url : urls.split("[,\\s]+")) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                importFrom(store, url, "bundle url");
            }
        }
        // peers that announce newer trust lists than ours. TV is not signed: ask in random order, and do not ask a
        // peer again soon if its bundle brought nothing, so that peers announcing fake versions cannot starve us
        final List<Seed> candidates = new ArrayList<>();
        final Iterator<Seed> i = sb.peers.seedsConnected(true, false, null, 0.0f);
        int seen = 0;
        while (i.hasNext() && seen++ < 500) {
            final Seed s = i.next();
            if (s == null || !s.isSigned() || !store.isNewer(s.get(Seed.TV, null))) continue;
            final Long until = gossipBackoff.get(s.hash);
            if (until != null && until > now) continue;
            candidates.add(s);
        }
        java.util.Collections.shuffle(candidates);
        int fetched = 0;
        for (final Seed s : candidates) {
            if (fetched >= MAX_GOSSIP_FETCHES) break;
            final String ip = s.getIP();
            if (ip == null && !s.isRelayed()) continue;
            try {
                final int n = importFrom(store, s.getPublicURL(ip, false) + "/yacy/trust.json", "peer " + s.getName());
                if (n <= 0) gossipBackoff.put(s.hash, now + GOSSIP_BACKOFF);
                fetched++;
            } catch (final RuntimeException e) {
                gossipBackoff.put(s.hash, now + GOSSIP_BACKOFF);
            }
        }
        if (gossipBackoff.size() > 10000) gossipBackoff.clear();
    }

    /** @return the number of statements that changed the store, -1 on failure */
    private static int importFrom(final TrustStore store, final String url, final String source) {
        try {
            final byte[] body = fetch(url, MAX_BUNDLE_BYTES, Duration.ofSeconds(10), null);
            if (body == null) return -1;
            final int n = store.importJSON(new String(body, StandardCharsets.UTF_8), true);
            if (n > 0) ConcurrentLog.info("TrustService", "imported " + n + " trust statements from " + source + " (" + url + ")");
            return n;
        } catch (final Exception e) {
            ConcurrentLog.info("TrustService", "cannot load trust bundle from " + source + " (" + url + "): " + e.getMessage());
            return -1;
        }
    }

    private static final java.security.SecureRandom NONCES = new java.security.SecureRandom();

    private static void readSidecar() {
        final String base = TrustPolicy.sidecarURL();
        if (base == null) {
            sidecar = null;
            return;
        }
        try {
            // the sidecar must prove that it holds our key: it signs a fresh nonce. The peer id alone is public (it is in
            // the seed), so another local process that took the port could claim it. Only a proven sidecar gets the
            // token (with the tunnel requests), and only it is used for tunnels.
            final PeerIdentity me = PeerIdentity.get();
            if (me == null) throw new IOException("no peer identity");
            final byte[] n = new byte[16];
            NONCES.nextBytes(n);
            final String nonce = Ed25519.encode(n);
            final byte[] body = fetch(base + "/status?nonce=" + nonce, 64 * 1024, Duration.ofSeconds(3), null);
            if (body == null) throw new IOException("no answer");
            final JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (!me.libp2pPeerId().equals(o.optString("peerId", ""))) {
                throw new IOException("the sidecar reports peer id " + o.optString("peerId", "") + ", expected " + me.libp2pPeerId());
            }
            final String signed = "yacy-sidecar-status-v1|" + nonce + "|" + o.optString("peerId", "") + "|" + o.optString("boot", "");
            if (!Ed25519.verify(me.publicKeyB64(), signed, o.optString("sig", ""))) {
                throw new IOException("the process at " + base + " did not prove that it holds our peer key (old sidecar version, or not our sidecar)");
            }
            final List<String> relay = new ArrayList<>();
            final JSONArray a = o.optJSONArray("relayAddrs");
            if (a != null) for (int k = 0; k < a.length() && k < 8; k++) {
                final String addr = a.optString(k, "");
                // multiaddrs must not break the seed format (fields separated by ',' and '|')
                if (!addr.isEmpty() && addr.length() < 300 && addr.indexOf('|') < 0 && SeedSignature.isSafeFieldValue(addr) && addr.contains("/p2p-circuit/p2p/")) relay.add(addr);
            }
            // a restarted sidecar has new tunnel ports: forget the remembered ones
            P2PRoute.sidecarBooted(o.optString("boot", null));
            final SidecarStatus previous = sidecar;
            sidecar = new SidecarStatus(o.optString("peerId", ""), o.optString("reachability", "unknown"), relay);
            if (previous == null || !previous.reachability.equals(sidecar.reachability)) {
                ConcurrentLog.info("TrustService", "sidecar " + sidecar.peerId + ": reachability " + sidecar.reachability + ", relay addresses " + relay);
            }
        } catch (final Exception e) {
            if (sidecar != null) ConcurrentLog.info("TrustService", "sidecar at " + base + " not available: " + e.getMessage());
            sidecar = null;
            P2PRoute.reset();
        }
    }

    /** GET with a size limit; null for a non-200 answer */
    static byte[] fetch(final String url, final int maxBytes, final Duration timeout, final String sidecarToken) throws IOException, InterruptedException {
        final HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        if (sidecarToken != null) req.header(P2PRoute.TOKEN_HEADER, sidecarToken);
        final HttpResponse<InputStream> res = P2PRoute.http().send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) return null;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new IOException("answer larger than " + maxBytes + " bytes");
            }
            return out.toByteArray();
        }
    }
}
