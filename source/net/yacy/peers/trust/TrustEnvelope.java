// TrustEnvelope.java
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

import java.nio.charset.StandardCharsets;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A signed statement: {"payload": base64url(JSON bytes), "signer": base64url(key), "sig": base64url(signature)}.
 * The signature covers the payload bytes as they are, so no JSON canonicalization is needed.
 */
public final class TrustEnvelope {

    public static final String TYPE_DELEGATION = "yacy-delegation-v1";
    public static final String TYPE_PEERLIST = "yacy-peerlist-v1";
    /** upper bound for one envelope; a list of 10000 peers fits */
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    public final String signer;
    public final JSONObject payload;
    public final String type;
    public final long version;
    private final JSONObject json;

    private TrustEnvelope(final String signer, final JSONObject payload, final JSONObject json) throws JSONException {
        this.signer = signer;
        this.payload = payload;
        this.type = payload.getString("type");
        this.version = payload.getLong("version");
        if (this.version < 0) throw new JSONException("negative version");
        this.json = json;
    }

    /**
     * Parse and verify an envelope.
     * @return the envelope, or null if it is malformed or its signature does not verify
     */
    public static TrustEnvelope parse(final JSONObject json) {
        try {
            final String payloadB64 = json.getString("payload");
            final String signer = json.getString("signer");
            final String sig = json.getString("sig");
            final byte[] payloadBytes = Ed25519.decode(payloadB64);
            final byte[] signerKey = Ed25519.decode(signer);
            if (payloadBytes == null || payloadBytes.length > MAX_PAYLOAD_BYTES) return null;
            if (signerKey == null || signerKey.length != Ed25519.PUBLIC_KEY_LENGTH) return null;
            if (!Ed25519.verify(signerKey, payloadBytes, Ed25519.decode(sig))) return null;
            final JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            // store the signer in canonical form so that it compares equal to configured keys
            final TrustEnvelope e = new TrustEnvelope(Ed25519.encode(signerKey), payload, json);
            if (!TYPE_DELEGATION.equals(e.type) && !TYPE_PEERLIST.equals(e.type)) return null;
            return e;
        } catch (final JSONException | RuntimeException e) {
            return null;
        }
    }

    /** sign a payload (used by TrustTool and tests) */
    public static JSONObject sign(final JSONObject payload, final java.security.KeyPair key) {
        final byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try {
            final JSONObject env = new JSONObject();
            env.put("payload", Ed25519.encode(bytes));
            env.put("signer", Ed25519.encode(Ed25519.rawPublicKey(key.getPublic())));
            env.put("sig", Ed25519.encode(Ed25519.sign(key.getPrivate(), bytes)));
            return env;
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public String network() {
        return this.payload.optString("network", "*");
    }

    public boolean appliesTo(final String networkName) {
        final String n = network();
        return "*".equals(n) || n.equals(networkName);
    }

    public JSONObject toJSON() {
        return this.json;
    }
}
