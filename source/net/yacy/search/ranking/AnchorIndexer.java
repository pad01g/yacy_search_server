// AnchorIndexer.java
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

package net.yacy.search.ranking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;

import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import net.yacy.search.schema.CollectionSchema;

/**
 * Collects the anchor texts of the links that point to a page from the other pages of the local index and writes them
 * to the page as anchors_in_txt, which the keyword search reads (search.ranking.solr.collection.boostfields). Other
 * pages often name a page differently than its title does: an abbreviation ("HPA"), the term a reader looks for
 * ("Pod Disruption Budget" for "Specifying a Disruption Budget"), a translation. Runs in the background with the lowest
 * priority; a page is visited again after refreshHours, because pages crawled later add links to it.
 */
public final class AnchorIndexer extends Thread {

    public static final String FIELD_DATE = "anchors_in_date_dt";
    static final int BATCH = 32;
    /** linking pages read per page; enough to see the usual names of a page */
    static final int MAX_LINKING_PAGES = 50;
    /** different anchor texts kept per page, the most frequent first */
    static final int MAX_TEXTS = 20;
    static final int MAX_TEXT_CHARS = 120;
    private static final long IDLE_MS = 60000;
    private static final long RECENT_MS = 120000;

    private static volatile AnchorIndexer instance = null;
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    public final AtomicLong updated = new AtomicLong();

    private AnchorIndexer() {
        super("AnchorIndexer");
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY);
    }

    public static synchronized void start(final Switchboard sb) {
        if (instance != null) return;
        instance = new AnchorIndexer();
        instance.start();
    }

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

    @Override
    public void run() {
        while (!this.stopped) {
            long sleep = IDLE_MS;
            try {
                final Switchboard sb = Switchboard.getSwitchboard();
                if (sb != null && sb.index != null && sb.getConfigBool(SwitchboardConstants.SEARCH_RANKING_ANCHORS_ENABLED, false)) {
                    final int n = step(sb);
                    if (n > 0) sleep = 100; else if (n < 0) sleep = 3000;
                }
            } catch (final Throwable e) {
                ConcurrentLog.info("AnchorIndexer", "failed: " + e.getMessage() + "; trying again in a minute");
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

    /** @return the documents whose anchors were never collected or are older than refreshHours */
    static String pendingQuery(final int refreshHours) {
        return "*:* AND -" + FIELD_DATE + ":[NOW-" + Math.max(1, refreshHours) + "HOURS TO *]";
    }

    /** @return the URL as it is stored in the link fields: without the protocol */
    static String stub(final String url) {
        if (url == null) return null;
        final int p = url.indexOf("://");
        return p < 0 ? url : url.substring(p + 3);
    }

    /**
     * @param linkingPages documents with the parallel lists of link targets (urlstub) and anchor texts
     * @return the anchor texts of the links to the target, the most frequent first, at most MAX_TEXTS
     */
    static List<String> anchorTexts(final String targetStub, final Collection<SolrDocument> linkingPages) {
        final Map<String, String> spelling = new LinkedHashMap<>();
        final Map<String, Integer> count = new LinkedHashMap<>();
        for (final SolrDocument d : linkingPages) {
            for (final String[] fields : new String[][] {
                    {CollectionSchema.inboundlinks_urlstub_sxt.getSolrFieldName(), CollectionSchema.inboundlinks_anchortext_txt.getSolrFieldName()},
                    {CollectionSchema.outboundlinks_urlstub_sxt.getSolrFieldName(), CollectionSchema.outboundlinks_anchortext_txt.getSolrFieldName()}}) {
                final List<Object> stubs = values(d, fields[0]), texts = values(d, fields[1]);
                for (int i = 0; i < Math.min(stubs.size(), texts.size()); i++) {
                    final Object s = stubs.get(i), t = texts.get(i);
                    if (s == null || t == null) continue;
                    String stub = s.toString();
                    final int hash = stub.indexOf('#');
                    if (hash >= 0) stub = stub.substring(0, hash);
                    if (!stub.equals(targetStub)) continue;
                    String text = t.toString().replaceAll("\\s+", " ").trim();
                    if (text.length() < 2 || text.startsWith("http")) continue;
                    if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
                    final String key = text.toLowerCase(Locale.ROOT);
                    spelling.putIfAbsent(key, text);
                    count.merge(key, 1, Integer::sum);
                }
            }
        }
        final List<String> keys = new ArrayList<>(count.keySet());
        keys.sort((a, b) -> count.get(b) - count.get(a));
        final List<String> out = new ArrayList<>();
        for (final String k : keys) {
            if (out.size() >= MAX_TEXTS) break;
            out.add(spelling.get(k));
        }
        return out;
    }

    private static List<Object> values(final SolrDocument d, final String field) {
        final Collection<Object> v = d.getFieldValues(field);
        return v == null ? Collections.emptyList() : new ArrayList<>(v);
    }

    static String quote(final String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** @return the number of updated documents, -1 if only just updated documents are pending */
    int step(final Switchboard sb) throws Exception {
        final long now = System.currentTimeMillis();
        this.recent.values().removeIf(t -> now - t > RECENT_MS);
        final int refresh = sb.getConfigInt(SwitchboardConstants.SEARCH_RANKING_ANCHORS_REFRESH_HOURS, 168);
        final EmbeddedSolrConnector connector = sb.index.fulltext().getDefaultEmbeddedConnector();
        final SolrQuery query = AbstractSolrConnector.getSolrQuery(pendingQuery(refresh), null, 0, BATCH * 2, "id", "_version_", "sku");
        query.addFilterQuery(net.yacy.search.vector.VectorIndexer.NO_ERROR_DOCUMENTS);
        final String skip = net.yacy.search.vector.VectorIndexer.skipFilter(this.recent.keySet());
        if (skip != null) query.addFilterQuery(skip);
        final SolrDocumentList docs = connector.getDocumentListByParams(query);
        if (docs.isEmpty()) return this.recent.isEmpty() ? 0 : -1;
        final SolrClient solr = connector.getServer();
        final String in = CollectionSchema.inboundlinks_urlstub_sxt.getSolrFieldName(), out = CollectionSchema.outboundlinks_urlstub_sxt.getSolrFieldName();
        int done = 0;
        for (final SolrDocument d : docs) {
            if (done >= BATCH) break;
            final String id = (String) d.getFieldValue("id");
            final String stub = stub((String) d.getFieldValue("sku"));
            if (id == null || stub == null) continue;
            final SolrQuery linking = AbstractSolrConnector.getSolrQuery(in + ":" + quote(stub) + " OR " + out + ":" + quote(stub), null, 0, MAX_LINKING_PAGES,
                    "id", in, CollectionSchema.inboundlinks_anchortext_txt.getSolrFieldName(), out, CollectionSchema.outboundlinks_anchortext_txt.getSolrFieldName());
            linking.addFilterQuery("-id:" + quote(id)); // links of the page to itself
            final List<String> texts = anchorTexts(stub, connector.getDocumentListByParams(linking));
            final SolrInputDocument update = new SolrInputDocument();
            update.setField("id", id);
            final Object version = d.getFieldValue("_version_");
            update.setField("_version_", version == null ? 1L : version);
            update.setField(CollectionSchema.anchors_in_txt.getSolrFieldName(), Collections.singletonMap("set", texts.isEmpty() ? null : texts));
            update.setField(FIELD_DATE, Collections.singletonMap("set", new java.util.Date()));
            try {
                solr.add(update);
                done++;
            } catch (final Exception e) {
                // changed meanwhile (crawled again, embedded): it stays pending
            }
            this.recent.put(id, now);
        }
        this.updated.addAndGet(done);
        return done;
    }
}
