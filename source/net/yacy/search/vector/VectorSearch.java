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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import net.yacy.search.ranking.VersionCollapse;

/**
 * Vector search (docs/vector-search.md): settings, the query embedding, the k-nearest-neighbour Solr query and the
 * fusion of its result list with the keyword result list (reciprocal rank fusion).
 */
public final class VectorSearch {

    /** Solr fields; the dimension of embedding_knn is fixed in defaults/solr/schema.xml */
    public static final String FIELD_VECTOR = "embedding_knn";
    public static final String FIELD_MODEL = "embedding_model_s";
    /** embeddings of later sections of a long page, see {@link #sections()} */
    public static final String[] FIELD_SECTION_VECTORS = {"embedding_knn_1", "embedding_knn_2", "embedding_knn_3"};
    /** a section shorter than this is not embedded on its own */
    static final int MIN_SECTION_CHARS = 200;
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

    /** @return the number of section embeddings per document besides the start of the page (0..3) */
    public static int sections() {
        final Switchboard sb = Switchboard.getSwitchboard();
        final int n = sb == null ? SwitchboardConstants.SEARCH_VECTOR_SECTIONS_DEFAULT : sb.getConfigInt(SwitchboardConstants.SEARCH_VECTOR_SECTIONS, SwitchboardConstants.SEARCH_VECTOR_SECTIONS_DEFAULT);
        return Math.max(0, Math.min(FIELD_SECTION_VECTORS.length, n));
    }

    /**
     * @return what the embeddings of a document are made with, stored in embedding_model_s: the model and the number
     *         of sections. Documents with another value are embedded again.
     */
    public static String signature() {
        final int s = sections();
        return s == 0 ? model() : model() + " sections=" + s;
    }

    private static volatile EmbeddingClient cachedClient = null;

    /** one client (and its connection pool) per configured service and model */
    static EmbeddingClient client() {
        final Switchboard sb = Switchboard.getSwitchboard();
        final String url = sb.getConfig(SwitchboardConstants.SEARCH_VECTOR_URL, ""), model = model();
        final EmbeddingClient c = cachedClient;
        if (c != null && c.isFor(url, model)) return c;
        final EmbeddingClient n = new EmbeddingClient(url, model, DIMENSION);
        cachedClient = n;
        return n;
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
     * The later sections of a page: the text after the start (which {@link #passage} covers), cut at headings
     * (the parser writes them as markdown "# ") into pieces of at most chars characters, each with the title in
     * front. A long page is represented by its start and its first n sections after it.
     */
    static List<String> sections(final String title, final String text, final int chars, final int n) {
        final List<String> out = new ArrayList<>();
        if (n <= 0 || text == null || text.length() <= chars) return out;
        final String head = title == null || title.isBlank() ? "" : title.replaceAll("\\s+", " ").trim() + "\n";
        final int room = Math.max(MIN_SECTION_CHARS, chars - head.length());
        final StringBuilder chunk = new StringBuilder();
        for (final String piece : text.substring(chars).split("(?=\\n#{1,6} )")) {
            String p = piece.replaceAll("\\s+", " ").trim();
            while (!p.isEmpty()) {
                if (chunk.length() > 0 && chunk.length() + 1 + p.length() > room) {
                    if (chunk.length() >= MIN_SECTION_CHARS) out.add(head + chunk);
                    chunk.setLength(0);
                    if (out.size() >= n) return out;
                }
                final int take = Math.min(p.length(), room - chunk.length() - (chunk.length() > 0 ? 1 : 0));
                if (chunk.length() > 0) chunk.append(' ');
                chunk.append(p, 0, take);
                p = p.substring(take).trim();
            }
        }
        if (chunk.length() >= MIN_SECTION_CHARS && out.size() < n) out.add(head + chunk);
        return out;
    }

    /** @return the documents of several nearest-neighbour lists by their best similarity, at most topK */
    public static SolrDocumentList bestOf(final List<SolrDocumentList> lists, final int topK) {
        final Map<String, SolrDocument> best = new LinkedHashMap<>();
        long found = 0;
        for (final SolrDocumentList l : lists) {
            found = Math.max(found, l.getNumFound());
            for (final SolrDocument d : l) {
                final String id = (String) d.getFieldValue("id");
                if (id == null) continue;
                final SolrDocument known = best.get(id);
                if (known == null || scoreOf(d) > scoreOf(known)) best.put(id, d);
            }
        }
        final List<SolrDocument> order = new ArrayList<>(best.values());
        order.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
        final SolrDocumentList out = new SolrDocumentList();
        for (final SolrDocument d : order) {
            if (out.size() >= topK) break;
            out.add(d);
        }
        out.setNumFound(Math.max(found, out.size()));
        return out;
    }

    /**
     * @return the embedding of a query, or null if the service is not available or does not answer within
     *         {@link #QUERY_TIMEOUT_MS} (then the search uses keywords only; after a failure the service is not asked
     *         again for 30 seconds, so searches do not wait for it)
     */
    /**
     * Wait for a query embedding at most this long. The keyword results are merged with the vector results before
     * they are ranked, and a local search ranks what arrived within SearchEvent.LOCAL_RANKING_WAIT_MS (2 s): a slower
     * answer (the service is busy embedding documents) would leave the keyword results out of the ranking.
     */
    static final long QUERY_TIMEOUT_MS = 1000;

    public static float[] queryVector(final String query) {
        if (query == null || query.isBlank()) return null;
        final float[] cached = queryCache.get(query);
        if (cached != null) return cached;
        if (System.currentTimeMillis() - failedAt < 30000) return null;
        try {
            final float[] v = client().embed(List.of(prefix(SwitchboardConstants.SEARCH_VECTOR_QUERY_PREFIX) + query), Duration.ofMillis(QUERY_TIMEOUT_MS))[0];
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
        return knnQuery(keywordQuery, vector, topK, FIELD_VECTOR);
    }

    public static SolrQuery knnQuery(final SolrQuery keywordQuery, final float[] vector, final int topK, final String field) {
        final SolrQuery q = keywordQuery.getCopy();
        for (final String p : new String[] {"defType", "qf", "pf", "pf2", "pf3", "ps", "mm", "bq", "bf", "boost", "tie", "q.op", "sort", "facet", "facet.field", "hl", "hl.fl", "partitions"}) {
            q.remove(p);
        }
        q.setQuery("{!knn f=" + field + " topK=" + topK + "}" + vectorString(vector));
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
        return fuseScores(keyword, nearest, vectorWeight, limit, Double.NaN, Double.NaN);
    }

    /**
     * Score fusion with the similarity scaled between fixed bounds (search.vector.fusion=abs): a cosine similarity of
     * low or less adds nothing, high or more the full vectorWeight. Unlike the scaling within the nearest documents,
     * the best of a list of poor matches does not get the full weight.
     */
    public static SolrDocumentList fuseScoresAbsolute(final SolrDocumentList keyword, final SolrDocumentList nearest, final float vectorWeight, final int limit, final double low, final double high) {
        return fuseScores(keyword, nearest, vectorWeight, limit, low, high);
    }

    /** Solr's knn score of the cosine similarity c is (1 + c) / 2 */
    static double cosine(final double knnScore) {
        return 2.0 * knnScore - 1.0;
    }

    private static SolrDocumentList fuseScores(final SolrDocumentList keyword, final SolrDocumentList nearest, final float vectorWeight, final int limit, final double low, final double high) {
        final boolean absolute = !Double.isNaN(low) && !Double.isNaN(high) && high > low;
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
        double top = Double.NEGATIVE_INFINITY, bottom = Double.POSITIVE_INFINITY;
        for (final SolrDocument d : nearest) {
            top = Math.max(top, scoreOf(d));
            bottom = Math.min(bottom, scoreOf(d));
        }
        int rank = 0;
        for (final SolrDocument d : nearest) {
            final String id = (String) d.getFieldValue("id");
            if (id == null) continue;
            rank++;
            final double similarity = absolute ? Math.max(0.0, Math.min(1.0, (cosine(scoreOf(d)) - low) / (high - low)))
                    : top > bottom ? (scoreOf(d) - bottom) / (top - bottom) : 1.0;
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
        final Set<String> seen = new HashSet<>();
        final boolean collapse = VersionCollapse.enabled();
        for (final String id : order) {
            if (out.size() >= limit) break;
            final SolrDocument d = docs.get(id);
            // another version or spelling of a better ranked page: the fused list is longer than the page, so the
            // page is still filled
            final String key = collapse ? VersionCollapse.key(d) : null;
            if (key != null && !seen.add(key)) continue;
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
        if ("rrf".equals(mode)) return fuse(keyword, nearest, vectorWeight, limit);
        if ("abs".equals(mode)) {
            final Switchboard sb = Switchboard.getSwitchboard();
            final double low = sb == null ? SwitchboardConstants.SEARCH_VECTOR_SIM_LOW_DEFAULT : sb.getConfigFloat(SwitchboardConstants.SEARCH_VECTOR_SIM_LOW, SwitchboardConstants.SEARCH_VECTOR_SIM_LOW_DEFAULT);
            final double high = sb == null ? SwitchboardConstants.SEARCH_VECTOR_SIM_HIGH_DEFAULT : sb.getConfigFloat(SwitchboardConstants.SEARCH_VECTOR_SIM_HIGH, SwitchboardConstants.SEARCH_VECTOR_SIM_HIGH_DEFAULT);
            return fuseScoresAbsolute(keyword, nearest, vectorWeight, limit, low, high);
        }
        return fuseScores(keyword, nearest, vectorWeight, limit);
    }
}
