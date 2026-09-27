// P2PRoute.java
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.peers.Seed;

/**
 * Peers behind a NAT (seed field Reach=relay) are reached through the local libp2p sidecar. The sidecar opens one
 * local tunnel port per remote peer ({@code GET <sidecar>/tunnel/<libp2p peer id>} returns {"port": n}); a plain
 * HTTP request to 127.0.0.1:n is carried over a libp2p stream, through a relay if needed, to the sidecar of the
 * target, which passes it to its YaCy. Because the tunnel is a normal host:port, every client in YaCy (the
 * protocol client, Solr) works unchanged.
 */
public final class P2PRoute {

    private static final Map<String, Integer> tunnels = new ConcurrentHashMap<>();
    private static final Set<Integer> tunnelPorts = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> failures = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_FAILURE = 30000;
    private static volatile HttpClient client = null;

    private P2PRoute() {
    }

    /**
     * @return the base URL (http://127.0.0.1:port, without trailing slash) to use instead of http://ip:port for
     *         the given peer, or null if the peer is reached directly or no tunnel is available
     */
    public static String baseURL(final Seed seed) {
        if (seed == null || !seed.isRelayed()) return null;
        if (PeerIdentity.isMine(seed.hash)) return null;
        final String sidecar = TrustPolicy.sidecarURL();
        if (sidecar == null) return null;
        final String id = seed.libp2pPeerId();
        if (id == null) return null;
        final Integer port = tunnelPort(sidecar, id);
        if (port == null) return null;
        return "http://127.0.0.1:" + port;
    }

    private static Integer tunnelPort(final String sidecar, final String peerId) {
        final Integer known = tunnels.get(peerId);
        if (known != null) return known;
        final Long failed = failures.get(peerId);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_FAILURE) return null;
        try {
            final HttpResponse<String> res = http().send(
                    HttpRequest.newBuilder(URI.create(sidecar + "/tunnel/" + peerId)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body());
            final int port = new JSONObject(res.body()).getInt("port");
            if (port <= 0 || port > 65535) throw new IllegalStateException("bad port " + port);
            tunnels.put(peerId, port);
            tunnelPorts.add(port);
            failures.remove(peerId);
            return port;
        } catch (final Exception e) {
            failures.put(peerId, System.currentTimeMillis());
            ConcurrentLog.info("P2PRoute", "no tunnel to " + peerId + " from sidecar " + sidecar + ": " + e.getMessage());
            return null;
        }
    }

    /** @return the number of tunnels this peer remembers */
    public static int size() {
        return tunnels.size();
    }

    /** forget the tunnels, e.g. after the sidecar restarted */
    public static void reset() {
        tunnels.clear();
        tunnelPorts.clear();
        failures.clear();
    }

    static HttpClient http() {
        HttpClient c = client;
        if (c == null) {
            synchronized (P2PRoute.class) {
                if (client == null) client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                c = client;
            }
        }
        return c;
    }

    /** @return true if the URL goes to a local sidecar tunnel; its host is then not the address of the target peer */
    public static boolean isRouted(final MultiProtocolURL url) {
        if (url == null) return false;
        final String host = url.getHost();
        return ("127.0.0.1".equals(host) || "localhost".equals(host)) && tunnelPorts.contains(url.getPort());
    }
}
