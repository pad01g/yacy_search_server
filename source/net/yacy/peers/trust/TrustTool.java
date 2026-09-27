// TrustTool.java
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
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command line tool for coordinators and operators (see docs/trust-and-nat.md, section 3).
 *
 * <pre>
 * java -cp 'lib/*' net.yacy.peers.trust.TrustTool keygen coordinator.key
 * java -cp 'lib/*' net.yacy.peers.trust.TrustTool pubkey coordinator.key
 * java -cp 'lib/*' net.yacy.peers.trust.TrustTool delegate coordinator.key &lt;operator key&gt; &lt;network&gt; &lt;version&gt; [--revoke] &gt; delegation.json
 * java -cp 'lib/*' net.yacy.peers.trust.TrustTool peerlist operator.key &lt;network&gt; &lt;version&gt; peers.json &gt; list.json
 * java -cp 'lib/*' net.yacy.peers.trust.TrustTool bundle delegation.json list.json &gt; bundle.json
 * java -cp 'lib/*' net.yacy.peers.trust.TrustTool verify bundle.json &lt;network&gt; &lt;coordinator key&gt;...
 * </pre>
 *
 * peers.json is an array of {"pk": "&lt;peer key&gt;", "priority": 0..100, "tags": ["ads", ...]}. The key of a peer is the
 * PK field of its seed (/yacy/seedlist.json?my=), or the output of pubkey for its DATA/SETTINGS/peer.key.
 */
public final class TrustTool {

    private TrustTool() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) usage();
        final String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "keygen": keygen(rest); break;
            case "pubkey": pubkey(rest); break;
            case "delegate": delegate(rest); break;
            case "peerlist": peerlist(rest); break;
            case "bundle": bundle(rest); break;
            case "verify": verify(rest); break;
            default: usage();
        }
    }

    private static void usage() {
        System.err.println("usage: TrustTool keygen <key file> | pubkey <key file>\n"
                + "       | delegate <coordinator key file> <operator public key> <network> <version> [--revoke]\n"
                + "       | peerlist <operator key file> <network> <version> <peers.json>\n"
                + "       | bundle <envelope.json>... | verify <bundle.json> <network> <coordinator public key>...");
        System.exit(2);
    }

    private static void keygen(final String[] a) throws IOException {
        if (a.length != 1) usage();
        final File f = new File(a[0]);
        if (f.exists()) {
            System.err.println(f + " exists; not overwritten");
            System.exit(1);
        }
        Ed25519.writePrivateKey(f, Ed25519.generate());
        pubkey(a);
    }

    private static void pubkey(final String[] a) throws IOException {
        if (a.length != 1) usage();
        final PeerIdentity id = PeerIdentity.forKeys(Ed25519.readPrivateKey(new File(a[0])));
        System.out.println("public key:   " + id.publicKeyB64());
        System.out.println("peer hash:    " + id.peerHash());
        System.out.println("libp2p id:    " + id.libp2pPeerId());
    }

    private static long version(final String v) {
        final long n = Long.parseLong(v);
        if (n < 1 || n > TrustStore.MAX_VERSION) throw new IllegalArgumentException("version must be 1.." + TrustStore.MAX_VERSION);
        return n;
    }

    private static String key(final String b64) {
        final String k = TrustStore.canonicalKey(b64);
        if (k == null) throw new IllegalArgumentException("not an Ed25519 public key: " + b64);
        return k;
    }

    private static void delegate(final String[] a) throws IOException, JSONException {
        if (a.length < 4 || a.length > 5) usage();
        final KeyPair coordinator = Ed25519.readPrivateKey(new File(a[0]));
        final JSONObject p = new JSONObject();
        p.put("type", TrustEnvelope.TYPE_DELEGATION);
        p.put("network", a[2]);
        p.put("operator", key(a[1]));
        p.put("version", version(a[3]));
        p.put("revoked", a.length == 5 && "--revoke".equals(a[4]));
        System.out.println(TrustEnvelope.sign(p, coordinator).toString());
    }

    private static void peerlist(final String[] a) throws IOException, JSONException {
        if (a.length != 4) usage();
        final KeyPair operator = Ed25519.readPrivateKey(new File(a[0]));
        final JSONArray in = new JSONArray(new String(Files.readAllBytes(new File(a[3]).toPath()), StandardCharsets.UTF_8));
        final JSONArray peers = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            final JSONObject e = in.getJSONObject(i);
            final JSONObject o = new JSONObject();
            o.put("pk", key(e.getString("pk")));
            final int priority = e.optInt("priority", 100);
            if (priority < 0 || priority > 100) throw new IllegalArgumentException("priority must be 0..100");
            o.put("priority", priority);
            final JSONArray tags = new JSONArray();
            final JSONArray t = e.optJSONArray("tags");
            if (t != null) for (int j = 0; j < t.length(); j++) {
                final String tag = t.getString(j).trim().toLowerCase(java.util.Locale.ROOT);
                if (!TrustPolicy.isValidTag(tag)) throw new IllegalArgumentException("bad tag: " + tag);
                tags.put(tag);
            }
            o.put("tags", tags);
            peers.put(o);
        }
        final JSONObject p = new JSONObject();
        p.put("type", TrustEnvelope.TYPE_PEERLIST);
        p.put("network", a[1]);
        p.put("version", version(a[2]));
        p.put("peers", peers);
        System.out.println(TrustEnvelope.sign(p, operator).toString());
    }

    private static void bundle(final String[] a) throws IOException, JSONException {
        if (a.length == 0) usage();
        final JSONArray envelopes = new JSONArray();
        for (final String f : a) {
            final JSONObject e = new JSONObject(new String(Files.readAllBytes(new File(f).toPath()), StandardCharsets.UTF_8).trim());
            if (TrustEnvelope.parse(e) == null) throw new IllegalArgumentException(f + " is not a valid signed envelope");
            envelopes.put(e);
        }
        System.out.println(new JSONObject().put("envelopes", envelopes).toString());
    }

    private static void verify(final String[] a) throws IOException {
        if (a.length < 3) usage();
        final List<String> coordinators = new ArrayList<>();
        for (int i = 2; i < a.length; i++) coordinators.add(key(a[i]));
        final String network = a[1];
        final TrustStore store = new TrustStore(null, () -> coordinators, () -> network);
        final int n = store.importJSON(new String(Files.readAllBytes(new File(a[0]).toPath()), StandardCharsets.UTF_8), false);
        System.out.println(n + " statements accepted for network " + network);
        for (final Map.Entry<String, TrustStore.Entry> e : store.effective().entrySet()) {
            System.out.println(e.getKey() + "  priority " + e.getValue().priority + "  tags " + e.getValue().tags + "  key " + e.getValue().publicKey);
        }
    }
}
