// ClickStore.java
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;

/**
 * The results the owner of this peer opened for a query, counted per query and page (url hash). A later local search
 * with the same query ranks these pages a little higher (see {@link #weight}). Only clicks of the administrator are
 * recorded (api/click_p.json), so visitors of a public search page cannot push pages up. Nothing leaves the peer.
 */
public final class ClickStore {

    static final int MAX_QUERIES = 2000;
    static final int MAX_PAGES_PER_QUERY = 50;
    /** clicks at which the full weight is reached */
    static final int FULL_CLICKS = 8;
    private static final long SAVE_INTERVAL_MS = 10000;
    private static final Pattern URL_HASH = Pattern.compile("[A-Za-z0-9_-]{12}");

    private static volatile ClickStore instance = new ClickStore(null);

    /** query -> url hash -> clicks, the least recently used query first */
    private final LinkedHashMap<String, Map<String, Integer>> clicks = new LinkedHashMap<>(256, 0.75f, true);
    private final File file;
    private long savedAt = 0;
    private boolean dirty = false;
    private final Object writeLock = new Object();

    ClickStore(final File file) {
        this.file = file;
        if (file != null && file.isFile()) {
            try {
                final JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                final Iterator<String> queries = json.keys();
                while (queries.hasNext()) {
                    final String q = queries.next();
                    final JSONObject pages = json.getJSONObject(q);
                    final Map<String, Integer> m = new HashMap<>();
                    final Iterator<String> hashes = pages.keys();
                    while (hashes.hasNext()) {
                        final String h = hashes.next();
                        if (URL_HASH.matcher(h).matches()) m.put(h, Math.max(0, pages.getInt(h)));
                    }
                    if (!m.isEmpty() && this.clicks.size() < MAX_QUERIES) this.clicks.put(normalize(q), m);
                }
            } catch (final Exception e) {
                ConcurrentLog.warn("ClickStore", "cannot read " + file + ": " + e.getMessage());
            }
        }
    }

    /** use the clicks stored in this file (DATA/WORK/clicks.json) */
    public static void init(final File file) {
        instance = new ClickStore(file);
    }

    public static ClickStore get() {
        return instance;
    }

    /**
     * @return the query as clicks are stored: lower case, single spaces, without operators such as site:x or
     *         /language/ja (they filter, they do not change what is relevant)
     */
    public static String normalize(final String query) {
        if (query == null) return "";
        final StringBuilder sb = new StringBuilder();
        for (final String w : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (w.isEmpty() || w.startsWith("/") || (w.indexOf(':') > 0 && !w.startsWith("http"))) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
    }

    /** @return false if the input is not a query and a url hash */
    public boolean record(final String query, final String urlHash) {
        final String q = normalize(query);
        if (q.isEmpty() || urlHash == null || !URL_HASH.matcher(urlHash).matches()) return false;
        synchronized (this.clicks) {
            Map<String, Integer> pages = this.clicks.get(q);
            if (pages == null) {
                pages = new HashMap<>();
                this.clicks.put(q, pages);
                if (this.clicks.size() > MAX_QUERIES) {
                    final Iterator<String> eldest = this.clicks.keySet().iterator();
                    eldest.next();
                    eldest.remove();
                }
            }
            if (!pages.containsKey(urlHash) && pages.size() >= MAX_PAGES_PER_QUERY) {
                // forget the least clicked page
                String least = null;
                for (final Map.Entry<String, Integer> e : pages.entrySet()) if (least == null || e.getValue() < pages.get(least)) least = e.getKey();
                pages.remove(least);
            }
            pages.merge(urlHash, 1, Integer::sum);
            this.dirty = true;
        }
        this.saveSoon();
        return true;
    }

    /** @return the clicks of the pages for this query (empty if none) */
    public Map<String, Integer> clicks(final String query) {
        synchronized (this.clicks) {
            final Map<String, Integer> pages = this.clicks.get(normalize(query));
            return pages == null ? Collections.emptyMap() : new HashMap<>(pages);
        }
    }

    /** @return the factor for a page clicked this many times: 1 without clicks, at most 1 + maxBoost */
    public static double weight(final int clicks, final double maxBoost) {
        if (clicks <= 0 || !(maxBoost > 0)) return 1.0d;
        return 1.0d + maxBoost * Math.min(1.0d, Math.log1p(clicks) / Math.log1p(FULL_CLICKS));
    }

    private void saveSoon() {
        if (this.file == null || System.currentTimeMillis() - this.savedAt < SAVE_INTERVAL_MS) return;
        this.save();
    }

    public void save() {
        if (this.file == null) return;
        // one writer at a time, and the snapshot taken under the same lock: an older snapshot never overwrites a newer
        synchronized (this.writeLock) {
            final JSONObject json = new JSONObject();
            synchronized (this.clicks) {
                if (!this.dirty) return;
                try {
                    for (final Map.Entry<String, Map<String, Integer>> e : this.clicks.entrySet()) json.put(e.getKey(), new JSONObject(e.getValue()));
                } catch (final Exception e) {
                    return;
                }
                this.dirty = false;
                this.savedAt = System.currentTimeMillis();
            }
            try {
                final File tmp = new File(this.file.getPath() + ".tmp");
                Files.write(tmp.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                ConcurrentLog.warn("ClickStore", "cannot write " + this.file + ": " + e.getMessage());
            }
        }
    }
}
