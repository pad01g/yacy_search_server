// VectorSearch.java
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * Vector search (docs/vector-search.md): settings, the query embedding, the k-nearest-neighbour Solr query and the
 * fusion of its result list with the keyword result list (reciprocal rank fusion).
 */
public final class VectorSearch {

    /** Solr fields; the dimension of embedding_knn is fixed in defaults/solr/schema.xml */
    public static final String FIELD_VECTOR = "embedding_knn";
    public static final String FIELD_MODEL = "embedding_model_s";
    public static final int DIMENSION = 384;
    /** reciprocal rank fusion constant: rank r adds 1 / (RRF_K + r) */
    static final int RRF_K = 60;
    /** set on fused result documents: the rank in the vector list (0: not in it) and whether the keyword query matched */
    public static final String FIELD_VECTOR_RANK = "vector_rank_i";
    public static final String FIELD_KEYWORD_MATCH = "keyword_match_b";

    private static final Map<String, float[]> queryCache = Collections.synchronizedMap(new LinkedHashMap<String, float[]>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, float[]> e) {
            return size() > 512;
        }
    });
    private static volatile long failedAt = 0;

    private VectorSearch() {
    }

    public static boolean enabled() {
        final Switchboard sb = Switchboard.getSwitchboard();
        return sb != null && sb.getConfigBool(SwitchboardConstants.SEARCH_VECTOR_ENABLED, false)
                && !sb.getConfig(SwitchboardConstants.SEARCH_VECTOR_URL, "").isEmpty();
    }

    public static String model() {
        final Switchboard sb = Switchboard.getSwitchboard();
        return sb == null ? SwitchboardConstants.SEARCH_VECTOR_MODEL_DEFAULT : sb.getConfig(SwitchboardConstants.SEARCH_VECTOR_MODEL, SwitchboardConstants.SEARCH_VECTOR_MODEL_DEFAULT);
    }

    static EmbeddingClient client() {
        final Switchboard sb = Switchboard.getSwitchboard();
        return new EmbeddingClient(sb.getConfig(SwitchboardConstants.SEARCH_VECTOR_URL, ""), model(), DIMENSION);
    }

    /** "query:" or "passage:" from the configuration, followed by one space (the configuration file trims values) */
    static String prefix(final String key) {
        final Switchboard sb = Switchboard.getSwitchboard();
        final String p = sb == null ? "" : sb.getConfig(key, "").trim();
        return p.isEmpty() ? "" : p + " ";
    }

    /** the text that represents a document: title, description and the start of the text */
    static String passage(final String title, final String description, final String text, final int chars) {
        final StringBuilder sb = new StringBuilder();
        for (final String part : new String[] {title, description, text}) {
            if (part == null || part.isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(part.replaceAll("\\s+", " ").trim());
            if (sb.length() >= chars) break;
        }
        return sb.length() > chars ? sb.substring(0, chars) : sb.toString();
    }

    /**
     * @return the embedding of a query, or null if the service is not available (then the search uses keywords only;
     *         after a failure the service is not asked again for 30 seconds, so searches do not wait for it)
     */
    public static float[] queryVector(final String query) {
        if (query == null || query.isBlank()) return null;
        final float[] cached = queryCache.get(query);
        if (cached != null) return cached;
        if (System.currentTimeMillis() - failedAt < 30000) return null;
        try {
            final float[] v = client().embed(List.of(prefix(SwitchboardConstants.SEARCH_VECTOR_QUERY_PREFIX) + query), Duration.ofSeconds(3))[0];
            queryCache.put(query, v);
            return v;
        } catch (final IOException | RuntimeException e) {
            failedAt = System.currentTimeMillis();
            ConcurrentLog.info("VectorSearch", "no query embedding (" + e.getMessage() + "); keyword search only");
            return null;
        }
    }

    /** the knn variant of a keyword query: the same filters and fields, the nearest topK documents, no highlighting */
    public static SolrQuery knnQuery(final SolrQuery keywordQuery, final float[] vector, final int topK) {
        final SolrQuery q = keywordQuery.getCopy();
        for (final String p : new String[] {"defType", "qf", "pf", "pf2", "pf3", "ps", "mm", "bq", "bf", "boost", "tie", "q.op", "sort", "facet", "facet.field", "hl", "hl.fl", "partitions"}) {
            q.remove(p);
        }
        q.setQuery("{!knn f=" + FIELD_VECTOR + " topK=" + topK + "}" + vectorString(vector));
        q.setStart(0);
        q.setRows(topK);
        q.set("hl", false);
        q.set("facet", false);
        return q;
    }

    static String vectorString(final float[] v) {
        final StringBuilder sb = new StringBuilder(v.length * 10);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", v[i]));
        }
        return sb.append(']').toString();
    }

    /**
     * Score fusion (the default, search.vector.fusion=score): the keyword score as a share of the best keyword score,
     * plus vectorWeight times the similarity scaled to [0, 1] within the nearest documents (the nearest gets 1, the
     * last of the topK 0). A clear keyword match keeps its lead (for example the title "Python 3.12" for a query
     * with "3.12", which a whole-page embedding hardly tells from "3.11"), and a document the keyword query missed
     * enters with up to vectorWeight. Reciprocal rank fusion keeps only the ranks and flattens such leads, so that
     * small later weights (term coverage, thin pages) decide the order.
     */
    public static SolrDocumentList fuseScores(final SolrDocumentList keyword, final SolrDocumentList nearest, final float vectorWeight, final int limit) {
        final Map<String, SolrDocument> docs = new LinkedHashMap<>();
        final Map<String, Double> score = new LinkedHashMap<>();
        double maxKeyword = 0;
        for (final SolrDocument d : keyword) maxKeyword = Math.max(maxKeyword, scoreOf(d));
        for (final SolrDocument d : keyword) {
            final String id = (String) d.getFieldValue("id");
            if (id == null || docs.containsKey(id)) continue;
            d.setField(FIELD_KEYWORD_MATCH, Boolean.TRUE);
            d.setField(FIELD_VECTOR_RANK, 0);
            docs.put(id, d);
            score.put(id, maxKeyword > 0 ? scoreOf(d) / maxKeyword : 0);
        }
        double top = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
        for (final SolrDocument d : nearest) {
            top = Math.max(top, scoreOf(d));
            low = Math.min(low, scoreOf(d));
        }
        int rank = 0;
        for (final SolrDocument d : nearest) {
            final String id = (String) d.getFieldValue("id");
            if (id == null) continue;
            rank++;
            final double similarity = top > low ? (scoreOf(d) - low) / (top - low) : 1.0;
            final SolrDocument known = docs.get(id);
            if (known != null) {
                known.setField(FIELD_VECTOR_RANK, rank);
            } else {
                d.setField(FIELD_KEYWORD_MATCH, Boolean.FALSE);
                d.setField(FIELD_VECTOR_RANK, rank);
                docs.put(id, d);
            }
            score.merge(id, vectorWeight * similarity, Double::sum);
        }
        return ordered(docs, score, keyword.getNumFound(), limit, 1.0);
    }

    private static double scoreOf(final SolrDocument d) {
        final Object s = d.getFieldValue("score");
        return s instanceof Number ? ((Number) s).doubleValue() : 0.0;
    }

    private static SolrDocumentList ordered(final Map<String, SolrDocument> docs, final Map<String, Double> score, final long keywordFound, final int limit, final double scale) {
        final List<String> order = new ArrayList<>(docs.keySet());
        order.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        final SolrDocumentList out = new SolrDocumentList();
        for (final String id : order) {
            if (out.size() >= limit) break;
            final SolrDocument d = docs.get(id);
            d.setField("score", (float) (score.get(id) * scale));
            out.add(d);
        }
        out.setNumFound(Math.max(keywordFound, docs.size()));
        out.setStart(0);
        out.setMaxScore(out.isEmpty() ? 0f : (Float) out.get(0).getFieldValue("score"));
        return out;
    }

    /**
     * Reciprocal rank fusion of the keyword results and the nearest documents (search.vector.fusion=rrf): a document at rank r of a list gets
     * 1 / (60 + r), the vector list weighted by vectorWeight; documents in both lists add up. The fused value
     * replaces the Solr score (YaCy normalizes it per peer anyway). Documents only the vector list found are marked
     * with keyword_match_b = false.
     */
    public static SolrDocumentList fuse(final SolrDocumentList keyword, final SolrDocumentList nearest, final float vectorWeight, final int limit) {
        final Map<String, SolrDocument> docs = new LinkedHashMap<>();
        final Map<String, Double> score = new LinkedHashMap<>();
        int rank = 0;
        for (final SolrDocument d : keyword) {
            final String id = (String) d.getFieldValue("id");
            if (id == null || docs.containsKey(id)) continue;
            rank++;
            d.setField(FIELD_KEYWORD_MATCH, Boolean.TRUE);
            d.setField(FIELD_VECTOR_RANK, 0);
            docs.put(id, d);
            score.put(id, 1.0 / (RRF_K + rank));
        }
        rank = 0;
        for (final SolrDocument d : nearest) {
            final String id = (String) d.getFieldValue("id");
            if (id == null) continue;
            rank++;
            final SolrDocument known = docs.get(id);
            if (known != null) {
                known.setField(FIELD_VECTOR_RANK, rank);
            } else {
                d.setField(FIELD_KEYWORD_MATCH, Boolean.FALSE);
                d.setField(FIELD_VECTOR_RANK, rank);
                docs.put(id, d);
            }
            score.merge(id, (double) vectorWeight / (RRF_K + rank), Double::sum);
        }
        return ordered(docs, score, keyword.getNumFound(), limit, 1000.0);
    }

    /** the fusion of search.vector.fusion: "score" (default) or "rrf" */
    public static SolrDocumentList fuse(final String mode, final SolrDocumentList keyword, final SolrDocumentList nearest, final float vectorWeight, final int limit) {
        return "rrf".equals(mode) ? fuse(keyword, nearest, vectorWeight, limit) : fuseScores(keyword, nearest, vectorWeight, limit);
    }
}
