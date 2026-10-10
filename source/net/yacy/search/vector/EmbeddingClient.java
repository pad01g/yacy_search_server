// EmbeddingClient.java
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

package net.yacy.search.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Client of an OpenAI-compatible embedding service ({@code POST /v1/embeddings} with {@code {"model", "input": [...]}}),
 * such as the yacy-embeddings image, Ollama or text-embeddings-inference.
 */
public final class EmbeddingClient {

    /** the service answers at most this many texts per request (yacy-embeddings: 64) */
    public static final int MAX_BATCH = 32;
    private static final int MAX_RESPONSE = 16 << 20;

    private final URI url;
    private final String model;
    private final int dimension;
    private final HttpClient http;

    public EmbeddingClient(final String url, final String model, final int dimension) {
        this.url = URI.create(url);
        if (!"http".equals(this.url.getScheme()) && !"https".equals(this.url.getScheme())) throw new IllegalArgumentException("embedding service must be http(s): " + url);
        this.model = model;
        this.dimension = dimension;
        // HTTP/1.1: no h2c upgrade attempt, which simple servers (and proxies) do not understand
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    boolean isFor(final String url, final String model) {
        return this.url.toString().equals(url) && this.model.equals(model);
    }

    /** @return one unit-length vector per text, in the order of the texts */
    public float[][] embed(final List<String> texts, final Duration timeout) throws IOException {
        if (texts.isEmpty()) return new float[0][];
        if (texts.size() > MAX_BATCH) throw new IllegalArgumentException("at most " + MAX_BATCH + " texts per request");
        final JSONObject body = new JSONObject();
        try {
            body.put("model", this.model);
            body.put("input", new JSONArray(texts));
        } catch (final Exception e) {
            throw new IOException(e);
        }
        final HttpResponse<byte[]> res;
        try {
            res = this.http.send(HttpRequest.newBuilder(this.url).timeout(timeout).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), limited(MAX_RESPONSE));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (res.statusCode() != 200) throw new IOException("embedding service answered HTTP " + res.statusCode());
        if (res.body() == null) throw new IOException("embedding answer too large");
        return parse(new String(res.body(), StandardCharsets.UTF_8), texts.size(), this.dimension);
    }

    /** a body handler that stops reading after max bytes (the body is then null) */
    private static HttpResponse.BodyHandler<byte[]> limited(final int max) {
        return info -> HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(), in -> {
            try (java.io.InputStream i = in) {
                final byte[] b = i.readNBytes(max + 1);
                return b.length > max ? null : b;
            } catch (final IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    static float[][] parse(final String json, final int expected, final int dimension) throws IOException {
        try {
            final JSONArray data = new JSONObject(json).getJSONArray("data");
            if (data.length() != expected) throw new IOException("expected " + expected + " embeddings, got " + data.length());
            final float[][] out = new float[expected][];
            for (int i = 0; i < data.length(); i++) {
                final JSONObject d = data.getJSONObject(i);
                final int index = d.optInt("index", i);
                if (index < 0 || index >= expected || out[index] != null) throw new IOException("bad embedding index " + index);
                final JSONArray e = d.getJSONArray("embedding");
                if (e.length() != dimension) throw new IOException("embedding has " + e.length() + " dimensions, the schema has " + dimension);
                final float[] v = new float[dimension];
                double norm = 0;
                for (int k = 0; k < dimension; k++) {
                    v[k] = (float) e.getDouble(k);
                    if (!Float.isFinite(v[k])) throw new IOException("embedding with a non-finite value");
                    norm += v[k] * v[k];
                }
                if (norm == 0) throw new IOException("zero embedding");
                // cosine similarity in Solr expects unit vectors
                final float inv = (float) (1.0 / Math.sqrt(norm));
                for (int k = 0; k < dimension; k++) v[k] *= inv;
                out[index] = v;
            }
            return out;
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("cannot read the embedding answer: " + e.getMessage(), e);
        }
    }
}
