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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;

import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
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
        while (!this.stopped) {
            long sleep = IDLE_MS;
            try {
                final Switchboard sb = Switchboard.getSwitchboard();
                if (sb != null && sb.index != null && VectorSearch.enabled()) {
                    final int n = step(sb);
                    // n < 0: only documents that were just updated are left; Solr shows the update within seconds
                    if (n > 0) sleep = 200; else if (n < 0) sleep = 3000;
                }
            } catch (final Throwable e) {
                this.errors.incrementAndGet();
                this.lastError = String.valueOf(e.getMessage());
                ConcurrentLog.info("VectorIndexer", "embedding failed: " + e.getMessage() + "; trying again in a minute");
                sleep = ERROR_MS;
            }
            try {
                synchronized (this.pause) {
                    if (!this.stopped) this.pause.wait(sleep);
                }
            } catch (final InterruptedException e) {
                return;
            }
        }
    }

    /** load failures are stored as documents too: they have no text to embed */
    public static final String NO_ERROR_DOCUMENTS = "-failreason_s:[* TO *]";

    /** stop the thread (YaCy shuts down) */
    public static synchronized void shutdown() {
        if (instance == null) return;
        // no interrupt: an interrupt during a Solr update can close the files of the index
        instance.stopped = true;
        synchronized (instance.pause) {
            instance.pause.notifyAll();
        }
        try {
            instance.join(30000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private volatile boolean stopped = false;
    private final Object pause = new Object();

    /** the query for documents that still need an embedding of this model */
    static String pendingQuery(final String model) {
        return "*:* AND -" + VectorSearch.FIELD_MODEL + ":\"" + model.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** the filter that leaves out documents updated just now and documents that failed too often */
    public static String skipFilter(final Collection<String> ids) {
        if (ids.isEmpty()) return null;
        // the terms parser has no clause limit; document ids are url hashes (letters, digits, - and _)
        // not cached: it changes with every step and would push the filters of the searches out of the filter cache
        return "{!lucene cache=false}*:* AND NOT _query_:\"{!terms f=id}" + String.join(",", ids) + "\"";
    }

    /** @return the number of documents embedded in this step, or -1 when only just updated documents are pending */
    int step(final Switchboard sb) throws Exception {
        final long now = System.currentTimeMillis();
        this.recent.values().removeIf(t -> now - t > RECENT_MS);
        final String signature = VectorSearch.signature();
        final int sections = VectorSearch.sections();
        final List<String> skip = new ArrayList<>(this.recent.keySet());
        this.failures.forEach((id, n) -> { if (n >= 3) skip.add(id); });
        final SolrQuery query = AbstractSolrConnector.getSolrQuery(pendingQuery(signature), null, 0, BATCH * 2, "id", "_version_", "title", "description_txt", "text_t");
        query.addFilterQuery(NO_ERROR_DOCUMENTS);
        final String fq = skipFilter(skip);
        if (fq != null) query.addFilterQuery(fq);
        final SolrDocumentList docs = sb.index.fulltext().getDefaultEmbeddedConnector().getDocumentListByParams(query);
        final int chars = sb.getConfigInt(SwitchboardConstants.SEARCH_VECTOR_PASSAGE_CHARS, SwitchboardConstants.SEARCH_VECTOR_PASSAGE_CHARS_DEFAULT);
        final String prefix = VectorSearch.prefix(SwitchboardConstants.SEARCH_VECTOR_PASSAGE_PREFIX);
        // the start of each document and its sections, as many documents as fit into one request
        final List<SolrDocument> todo = new ArrayList<>();
        final List<Integer> first = new ArrayList<>();
        final List<String> passages = new ArrayList<>();
        for (final SolrDocument d : docs) {
            final String id = (String) d.getFieldValue("id");
            if (id == null || this.recent.containsKey(id) || this.failures.getOrDefault(id, 0) >= 3) continue;
            final String title = join(d.getFieldValue("title")), text = join(d.getFieldValue("text_t"));
            final List<String> mine = new ArrayList<>();
            mine.add(prefix + VectorSearch.passage(title, join(d.getFieldValue("description_txt")), text, chars));
            for (final String section : VectorSearch.sections(title, text, chars, sections)) mine.add(prefix + section);
            if (!todo.isEmpty() && passages.size() + mine.size() > EmbeddingClient.MAX_BATCH) break;
            todo.add(d);
            first.add(passages.size());
            passages.addAll(mine);
            if (todo.size() >= BATCH) break;
        }
        if (todo.isEmpty()) return docs.isEmpty() && this.recent.isEmpty() ? 0 : -1;
        final float[][] vectors = VectorSearch.client().embed(passages, Duration.ofSeconds(120));
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
            final int from = first.get(i), to = i + 1 < first.size() ? first.get(i + 1) : passages.size();
            update.setField(VectorSearch.FIELD_VECTOR, Map.of("set", asList(vectors[from])));
            for (int s = 0; s < VectorSearch.FIELD_SECTION_VECTORS.length; s++) {
                // a section the document no longer has (or sections switched off): remove the old vector
                update.setField(VectorSearch.FIELD_SECTION_VECTORS[s], Collections.singletonMap("set", from + 1 + s < to ? asList(vectors[from + 1 + s]) : null));
            }
            update.setField(VectorSearch.FIELD_MODEL, Map.of("set", signature));
            try {
                solr.add(update);
                done++;
                this.failures.remove(id);
            } catch (final Exception e) {
                // a version conflict (crawled again, or the AnchorIndexer updated it meanwhile): try again later;
                // another error (a document Solr cannot update): at most 3 times
                if (!isVersionConflict(e)) this.failures.merge(id, 1, Integer::sum);
            }
            this.recent.put(id, now);
        }
        this.embedded.addAndGet(done);
        if (this.failures.size() > 10000) this.failures.clear();
        return done;
    }

    static boolean isVersionConflict(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SolrException && ((SolrException) t).code() == SolrException.ErrorCode.CONFLICT.code) return true;
        }
        return false;
    }

    private static List<Float> asList(final float[] v) {
        final List<Float> l = new ArrayList<>(v.length);
        for (final float f : v) l.add(f);
        return l;
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
