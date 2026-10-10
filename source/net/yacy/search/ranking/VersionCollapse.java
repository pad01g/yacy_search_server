// VersionCollapse.java
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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

/**
 * Keeps one page of a family of copies in a result list: the editions of a documentation page for other versions
 * (docs.docker.jp/v19.03/x and /v20.10/x, postgresql.org/docs/17/x and /docs/current/x) and the spellings of one URL
 * (with and without the trailing slash or index.html). They have the same text, so they fill the first page with
 * one answer. The best ranked copy stays, the others are dropped.
 */
public final class VersionCollapse {

    /** see SwitchboardConstants.SEARCH_RANKING_COLLAPSE_VERSIONS */
    public static volatile boolean enabled = true;

    /** a path segment that names a version: 3, 3.12, v20.10, 18, current, latest... */
    private static final Pattern VERSION_SEGMENT = Pattern.compile("v?\\d+(\\.\\d+)*|current|latest|stable|dev|devel|master|main|nightly");
    private static final Pattern INDEX_FILE = Pattern.compile("(index|default)\\.(html?|php|aspx?)$");

    private VersionCollapse() {
    }

    /**
     * @return the URL without its version directories, index file, trailing slash, fragment and scheme. The file
     *         name is kept (whatsnew/3.12.html and whatsnew/3.13.html are different pages), and so is the language.
     */
    public static String key(final String url) {
        if (url == null) return "";
        String u = url;
        final int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        final int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        String query = "";
        final int q = u.indexOf('?');
        if (q >= 0) {
            query = u.substring(q);
            u = u.substring(0, q);
        }
        final String[] parts = u.split("/", -1);
        final StringBuilder sb = new StringBuilder(u.length());
        sb.append(parts[0].toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""));
        // the last part is the file name (empty for a URL that ends with a slash)
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            final boolean last = i == parts.length - 1;
            if (last) {
                p = INDEX_FILE.matcher(p).replaceFirst("");
                if (p.isEmpty()) continue;
            } else if (p.isEmpty()) {
                continue;
            } else if (VERSION_SEGMENT.matcher(p.toLowerCase(Locale.ROOT)).matches()) {
                p = "*";
            }
            sb.append('/').append(p);
        }
        return sb.append(query).toString();
    }

    /** @return the URL of a result document (the sku field) */
    static String url(final SolrDocument d) {
        final Object sku = d.getFieldValue("sku");
        return sku == null ? null : sku.toString();
    }

    /**
     * @param docs results, best first
     * @return the documents without the copies of a better ranked one, at most limit
     */
    public static SolrDocumentList collapse(final SolrDocumentList docs, final int limit) {
        if (docs == null) return null;
        final SolrDocumentList out = new SolrDocumentList();
        out.setNumFound(docs.getNumFound());
        out.setStart(docs.getStart());
        out.setMaxScore(docs.getMaxScore());
        final Set<String> seen = new HashSet<>();
        for (final SolrDocument d : docs) {
            if (out.size() >= limit) break;
            final String url = url(d);
            if (url != null && !seen.add(key(url))) continue;
            out.add(d);
        }
        return out;
    }
}
