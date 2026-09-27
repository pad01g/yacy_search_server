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
            return false;
        }
        if ("private".equals(s.reachability) || my.isJunior()) relayChosen = true;
        return relayChosen;
    }

    private static void setOrRemove(final Seed seed, final String key, final String value) {
        if (value == null) {
            seed.getMap().remove(key);
        } else if (!value.equals(seed.get(key, null))) {
            seed.put(key, value);
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
        // peers that announce newer trust lists than ours
        int fetched = 0;
        final Iterator<Seed> i = sb.peers.seedsConnected(true, false, null, 0.0f);
        int seen = 0;
        while (i.hasNext() && fetched < MAX_GOSSIP_FETCHES && seen++ < 200) {
            final Seed s = i.next();
            if (s == null || !s.isSigned() || !store.isNewer(s.get(Seed.TV, null))) continue;
            final String ip = s.getIP();
            if (ip == null && !s.isRelayed()) continue;
            try {
                importFrom(store, s.getPublicURL(ip, false) + "/yacy/trust.json", "peer " + s.getName());
                fetched++;
            } catch (final RuntimeException e) {
                // bad address
            }
        }
    }

    private static void importFrom(final TrustStore store, final String url, final String source) {
        try {
            final byte[] body = fetch(url, MAX_BUNDLE_BYTES, Duration.ofSeconds(10));
            if (body == null) return;
            final int n = store.importJSON(new String(body, StandardCharsets.UTF_8), true);
            if (n > 0) ConcurrentLog.info("TrustService", "imported " + n + " trust statements from " + source + " (" + url + ")");
        } catch (final Exception e) {
            ConcurrentLog.info("TrustService", "cannot load trust bundle from " + source + " (" + url + "): " + e.getMessage());
        }
    }

    private static void readSidecar() {
        final String base = TrustPolicy.sidecarURL();
        if (base == null) {
            sidecar = null;
            return;
        }
        try {
            final byte[] body = fetch(base + "/status", 64 * 1024, Duration.ofSeconds(3));
            if (body == null) throw new IOException("no answer");
            final JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            final List<String> relay = new ArrayList<>();
            final JSONArray a = o.optJSONArray("relayAddrs");
            if (a != null) for (int k = 0; k < a.length() && k < 8; k++) {
                final String addr = a.optString(k, "");
                // multiaddrs must not break the seed format (fields separated by ',' and '|')
                if (!addr.isEmpty() && addr.length() < 300 && addr.indexOf(',') < 0 && addr.indexOf('|') < 0 && addr.indexOf('=') < 0) relay.add(addr);
            }
            // a restarted sidecar has new tunnel ports: forget the remembered ones
            if (P2PRoute.size() > o.optInt("tunnels", 0)) P2PRoute.reset();
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
    static byte[] fetch(final String url, final int maxBytes, final Duration timeout) throws IOException, InterruptedException {
        final HttpResponse<InputStream> res = P2PRoute.http().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
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
