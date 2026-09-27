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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
 * local tunnel port per remote peer ({@code GET <sidecar>/tunnel/<libp2p peer id>?addrs=...} returns {"port": n});
 * a plain HTTP request to 127.0.0.1:n is carried over a libp2p stream, through a relay if needed, to the sidecar of
 * the target, which passes it to its YaCy. Because the tunnel is a normal host:port, every client in YaCy (the
 * protocol client, Solr) works unchanged.
 *
 * The control API of the sidecar requires a token that YaCy writes next to the peer key
 * ({@value #TOKEN_FILE}), so that other local processes and web pages cannot drive it.
 */
public final class P2PRoute {

    /** set by the sidecar on requests it carries from other peers: their libp2p peer id */
    public static final String SIDECAR_HEADER = "X-YaCy-Libp2p-Peer";
    public static final String TOKEN_HEADER = "X-YaCy-Sidecar-Token";
    public static final String TOKEN_FILE = "DATA/SETTINGS/sidecar.token";
    /** tunnels are asked for again after this time; the sidecar closes tunnels that were idle much longer */
    static final long TUNNEL_TTL = 5 * 60 * 1000;
    private static final long RETRY_AFTER_FAILURE = 30000;

    private static final class Tunnel {
        final int port;
        final long time;

        Tunnel(final int port) {
            this.port = port;
            this.time = System.currentTimeMillis();
        }
    }

    private static final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();
    private static final Set<Integer> tunnelPorts = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> failures = new ConcurrentHashMap<>();
    private static volatile HttpClient client = null;
    private static volatile String token = null;
    private static volatile String sidecarBoot = null;

    private P2PRoute() {
    }

    /** read or create the token of the sidecar control API */
    public static void initToken(final File file) throws IOException {
        if (file.exists()) {
            token = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).trim();
            if (!token.isEmpty()) return;
        }
        final byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        token = Ed25519.encode(b);
        final File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        Files.write(file.toPath(), token.getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (final UnsupportedOperationException | IOException e) {
            // not a POSIX file system
        }
    }

    static String token() {
        return token == null ? "" : token;
    }

    /** @return true if the value is the sidecar token (constant time) */
    public static boolean isSidecarToken(final String value) {
        final String t = token;
        if (t == null || t.isEmpty() || value == null) return false;
        return java.security.MessageDigest.isEqual(t.getBytes(StandardCharsets.US_ASCII), value.getBytes(StandardCharsets.US_ASCII));
    }

    /** @return true for a base58 libp2p peer id of an Ed25519 key (12D3KooW...) */
    public static boolean isLibp2pPeerId(final String id) {
        return id != null && id.length() == 52 && id.startsWith("12D3KooW") && id.matches("[1-9A-HJ-NP-Za-km-z]+");
    }

    /**
     * @return the base URL (http://127.0.0.1:port, without trailing slash) to use instead of http://ip:port for
     *         the given peer, or null if the peer is reached directly or no tunnel is available
     */
    public static String baseURL(final Seed seed) {
        if (seed == null || !seed.isRelayed()) return null;
        if (PeerIdentity.isMine(seed.hash)) return null;
        if (!seed.isSigned()) return null; // the tunnel authenticates the key of the seed; unsigned seeds have none
        final String sidecar = TrustPolicy.sidecarURL();
        if (sidecar == null || TrustService.sidecarStatus() == null) return null;
        final String id = seed.libp2pPeerId();
        if (id == null) return null;
        final Integer port = tunnelPort(sidecar, id, seed.get(Seed.P2PA, ""));
        if (port == null) return null;
        return "http://127.0.0.1:" + port;
    }

    private static Integer tunnelPort(final String sidecar, final String peerId, final String addrs) {
        final Tunnel known = tunnels.get(peerId);
        if (known != null && System.currentTimeMillis() - known.time < TUNNEL_TTL) return known.port;
        final Long failed = failures.get(peerId);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_FAILURE) return null;
        try {
            final String url = sidecar + "/tunnel/" + peerId + "?addrs=" + URLEncoder.encode(addrs, StandardCharsets.UTF_8);
            final HttpResponse<String> res = http().send(
                    HttpRequest.newBuilder(URI.create(url)).header(TOKEN_HEADER, token()).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body().trim());
            final int port = new JSONObject(res.body()).getInt("port");
            if (port <= 1024 || port > 65535) throw new IllegalStateException("bad port " + port);
            final Tunnel old = tunnels.put(peerId, new Tunnel(port));
            if (old != null && old.port != port) tunnelPorts.remove(old.port);
            tunnelPorts.add(port);
            failures.remove(peerId);
            return port;
        } catch (final Exception e) {
            failures.put(peerId, System.currentTimeMillis());
            ConcurrentLog.info("P2PRoute", "no tunnel to " + peerId + " from sidecar " + sidecar + ": " + e.getMessage());
            return null;
        }
    }

    /** forget the tunnels, e.g. after the sidecar restarted */
    public static void reset() {
        tunnels.clear();
        tunnelPorts.clear();
        failures.clear();
    }

    /** called with the boot id the sidecar reports; a new id means new tunnel ports */
    static void sidecarBooted(final String boot) {
        if (boot == null) return;
        if (sidecarBoot != null && !sidecarBoot.equals(boot)) reset();
        sidecarBoot = boot;
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

    /**
     * The address that stands for a remote peer whose request the sidecar carries: 2001:db8::/32 (documentation
     * range, never routed and never local) followed by 96 bits of SHA-256 of the libp2p peer id.
     */
    public static InetAddress sidecarClientAddress(final String libp2pPeerId) {
        final byte[] a = new byte[16];
        a[0] = 0x20;
        a[1] = 0x01;
        a[2] = 0x0d;
        a[3] = (byte) 0xb8;
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest((libp2pPeerId == null ? "" : libp2pPeerId).getBytes(StandardCharsets.UTF_8));
            System.arraycopy(d, 0, a, 4, 12);
            return InetAddress.getByAddress(a);
        } catch (final NoSuchAlgorithmException | UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return true if the client address is the one the sidecar connector assigns to this libp2p peer */
    public static boolean isSidecarClient(final String clientIp, final String libp2pPeerId) {
        if (clientIp == null || libp2pPeerId == null) return false;
        try {
            return InetAddress.getByName(clientIp.replace("[", "").replace("]", "")).equals(sidecarClientAddress(libp2pPeerId));
        } catch (final UnknownHostException e) {
            return false;
        }
    }
}
