// VectorIndexer.java
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * Gives every document of the local index an embedding: it repeatedly asks Solr for documents without an embedding
 * of the configured model (new crawls as well as an existing index, or an index embedded with another model), embeds
 * title, description and the start of the text, and adds the vector to the stored document with an atomic update.
 * The update carries the document version it read, so a document that was deleted or crawled again meanwhile is not
 * changed (it is picked up again later); nothing else of the document is touched.
 */
public final class VectorIndexer extends Thread {

    static final int BATCH = 16;
    private static final long IDLE_MS = 30000;
    private static final long ERROR_MS = 60000;
    /** documents updated recently: Solr makes them visible within its soft commit time (5 s), skip them meanwhile */
    private static final long RECENT_MS = 120000;

    private static volatile VectorIndexer instance = null;
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    public final AtomicLong embedded = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    private volatile String lastError = "";

    private VectorIndexer() {
        super("VectorIndexer");
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
    }

    public static synchronized void start(final Switchboard sb) {
        if (instance != null) return;
        instance = new VectorIndexer();
        instance.start();
    }

    public static VectorIndexer get() {
        return instance;
    }

    public String lastError() {
        return this.lastError;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            long sleep = IDLE_MS;
            try {
                final Switchboard sb = Switchboard.getSwitchboard();
                if (sb != null && sb.index != null && VectorSearch.enabled()) {
                    final int n = step(sb);
                    if (n > 0) sleep = 200;
                }
            } catch (final Throwable e) {
                this.errors.incrementAndGet();
                this.lastError = String.valueOf(e.getMessage());
                ConcurrentLog.info("VectorIndexer", "embedding failed: " + e.getMessage() + "; trying again in a minute");
                sleep = ERROR_MS;
            }
            try {
                Thread.sleep(sleep);
            } catch (final InterruptedException e) {
                return;
            }
        }
    }

    /** the query for documents that still need an embedding of this model */
    static String pendingQuery(final String model) {
        return "*:* AND -" + VectorSearch.FIELD_MODEL + ":\"" + model.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** @return the number of documents embedded in this step */
    int step(final Switchboard sb) throws Exception {
        final long now = System.currentTimeMillis();
        this.recent.values().removeIf(t -> now - t > RECENT_MS);
        final String model = VectorSearch.model();
        final SolrDocumentList docs = sb.index.fulltext().getDefaultEmbeddedConnector().getDocumentListByQuery(
                pendingQuery(model), null, 0, BATCH * 4, "id", "_version_", "title", "description_txt", "text_t");
        final List<SolrDocument> todo = new ArrayList<>();
        for (final SolrDocument d : docs) {
            final String id = (String) d.getFieldValue("id");
            if (id == null || this.recent.containsKey(id) || this.failures.getOrDefault(id, 0) >= 3) continue;
            todo.add(d);
            if (todo.size() >= BATCH) break;
        }
        if (todo.isEmpty()) return 0;
        final int chars = sb.getConfigInt(SwitchboardConstants.SEARCH_VECTOR_PASSAGE_CHARS, SwitchboardConstants.SEARCH_VECTOR_PASSAGE_CHARS_DEFAULT);
        final String prefix = VectorSearch.prefix(SwitchboardConstants.SEARCH_VECTOR_PASSAGE_PREFIX);
        final List<String> passages = new ArrayList<>(todo.size());
        for (final SolrDocument d : todo) {
            passages.add(prefix + VectorSearch.passage(join(d.getFieldValue("title")), join(d.getFieldValue("description_txt")), join(d.getFieldValue("text_t")), chars));
        }
        final float[][] vectors = VectorSearch.client().embed(passages, Duration.ofSeconds(60));
        final SolrClient solr = sb.index.fulltext().getDefaultEmbeddedConnector().getServer();
        int done = 0;
        for (int i = 0; i < todo.size(); i++) {
            final SolrDocument d = todo.get(i);
            final String id = (String) d.getFieldValue("id");
            final SolrInputDocument update = new SolrInputDocument();
            update.setField("id", id);
            final Object version = d.getFieldValue("_version_");
            // the exact version read above: Solr refuses the update if the document changed or is gone
            update.setField("_version_", version == null ? 1L : version);
            final List<Float> v = new ArrayList<>(vectors[i].length);
            for (final float f : vectors[i]) v.add(f);
            update.setField(VectorSearch.FIELD_VECTOR, Map.of("set", v));
            update.setField(VectorSearch.FIELD_MODEL, Map.of("set", model));
            try {
                solr.add(update);
                done++;
                this.failures.remove(id);
            } catch (final Exception e) {
                // version conflict (changed or deleted meanwhile) or a document Solr cannot update: try later, at most 3 times
                this.failures.merge(id, 1, Integer::sum);
            }
            this.recent.put(id, now);
        }
        this.embedded.addAndGet(done);
        if (this.failures.size() > 100000) this.failures.clear();
        return done;
    }

    static String join(final Object value) {
        if (value == null) return "";
        if (value instanceof Collection) {
            final StringBuilder sb = new StringBuilder();
            for (final Object o : (Collection<?>) value) {
                if (o == null) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(o);
            }
            return sb.toString();
        }
        return value.toString();
    }
}
