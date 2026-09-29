// BootstrapPeers.java
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

package net.yacy.peers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;

/**
 * Peers to say hello to directly, by URL ({@code p2p.bootstrap.peers}, comma separated, e.g.
 * {@code http://100.101.102.103:8090}). Seed lists only carry senior peers, and a new peer is not senior before
 * another peer has reached it: the first peers of a new network (a group of agents on a Tailscale network, a
 * private cluster) could not find each other. A peer that knows one other peer's URL now joins by itself.
 *
 * The seed read from {@code /yacy/seedlist.json?my=} only gives the hash to address; the hello that follows proves
 * the key and the address with the usual challenge, and the other peer's back-ping proves ours.
 */
public final class BootstrapPeers {

    static final String CONFIG = "p2p.bootstrap.peers";
    /** below this many connected peers, the configured peers are contacted on every peer ping */
    static final int MIN_CONNECTED = 3;
    static final int MAX_URLS = 8;
    private static final Pattern HASH = Pattern.compile("[A-Za-z0-9_-]{12}");

    /** a URL that did not answer is tried again after this long, so that the peer ping is not held up every time */
    static final long RETRY_AFTER_FAILURE = 2L * 60L * 1000L;
    private static final java.util.Map<String, Long> failures = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile HttpClient client = null;

    private BootstrapPeers() {
    }

    /** the configured URLs, normalized to scheme://host:port, at most {@link #MAX_URLS} */
    static List<String> urls(final String config) {
        final List<String> out = new ArrayList<>();
        if (config == null) return out;
        for (final String raw : config.split(",")) {
            final String u = raw.trim();
            if (u.isEmpty() || out.size() >= MAX_URLS) continue;
            try {
                final URI uri = new URI(u);
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) continue;
                if (uri.getHost() == null || uri.getRawUserInfo() != null) continue;
                final int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
                final String host = uri.getHost().contains(":") && !uri.getHost().startsWith("[") ? "[" + uri.getHost() + "]" : uri.getHost();
                final String base = uri.getScheme() + "://" + host + ":" + port;
                if (!out.contains(base)) out.add(base);
            } catch (final Exception e) {
                ConcurrentLog.info("BootstrapPeers", "ignoring " + CONFIG + " entry " + u + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** @return the hash in the answer of {@code /yacy/seedlist.json?my=}, or null */
    static String hashOf(final String json) {
        try {
            final JSONArray peers = new JSONObject(json).optJSONArray("peers");
            if (peers == null || peers.length() == 0) return null;
            final String hash = peers.getJSONObject(0).optString("Hash", "");
            return HASH.matcher(hash).matches() ? hash : null;
        } catch (final Exception e) {
            return null;
        }
    }

    /** say hello to the configured peers while this peer has few connections */
    public static void contact(final Switchboard sb) {
        if (sb == null || sb.peers == null || !sb.peers.mySeedIsDefined()) return;
        if (sb.peers.sizeConnected() >= MIN_CONNECTED) return;
        final Seed my = sb.peers.mySeed();
        for (final String base : urls(sb.getConfig(CONFIG, ""))) {
            final Long failed = failures.get(base);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_FAILURE) continue;
            failures.put(base, System.currentTimeMillis()); // removed below on success
            try {
                final String json = fetch(base + "/yacy/seedlist.json?my=");
                final String hash = json == null ? null : hashOf(json);
                if (hash == null) {
                    ConcurrentLog.info("BootstrapPeers", base + " did not tell its peer hash");
                    continue;
                }
                if (hash.equals(my.hash) || sb.peers.hasConnected(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    failures.remove(base);
                    continue;
                }
                final java.util.Map<String, String> result = Protocol.hello(my, sb.peers.peerActions, new MultiProtocolURL(base), hash);
                ConcurrentLog.info("BootstrapPeers", "hello to " + base + " (" + hash + "): " + (result == null ? "failed" : "ok"));
                if (result != null) failures.remove(base);
            } catch (final Exception e) {
                ConcurrentLog.info("BootstrapPeers", "hello to " + base + " failed: " + e.getMessage());
            }
        }
    }

    private static String fetch(final String url) throws Exception {
        HttpClient c = client;
        if (c == null) {
            synchronized (BootstrapPeers.class) {
                if (client == null) client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
                c = client;
            }
        }
        final HttpResponse<byte[]> res = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length > 256 * 1024) return null;
        return new String(res.body(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
