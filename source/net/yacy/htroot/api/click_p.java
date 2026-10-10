// click_p.java
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

package net.yacy.htroot.api;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import net.yacy.search.ranking.ClickStore;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/**
 * api/click_p.json?query=...&urlhash=...: the search page reports a result the administrator opened (see
 * {@link ClickStore}). Protected (_p): only the administrator's clicks count.
 */
public class click_p {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        final Switchboard sb = (Switchboard) env;
        boolean recorded = false;
        // a click is reported with POST (navigator.sendBeacon); a GET from a link or an image does not count
        if (post != null && "POST".equalsIgnoreCase(header.getMethod()) && sameOrigin(header)
                && sb.getConfigFloat(SwitchboardConstants.SEARCH_RANKING_CLICKS_WEIGHT, SwitchboardConstants.SEARCH_RANKING_CLICKS_WEIGHT_DEFAULT) > 0) {
            recorded = ClickStore.get().record(post.get("query", ""), post.get("urlhash", ""));
        }
        prop.put("recorded", recorded ? 1 : 0);
        return prop;
    }

    /**
     * Another site cannot report clicks through the administrator's browser: with adminAccountForLocalhost a request
     * from a browser on the peer's host passes without a password. Browsers say where a request comes from
     * (Sec-Fetch-Site, Origin); requests without these headers (curl with the password) are not from a web page.
     */
    static boolean sameOrigin(final RequestHeader header) {
        final String site = header.get("Sec-Fetch-Site");
        if (site != null) return "same-origin".equalsIgnoreCase(site.trim()); // also behind a proxy that changes Host
        final String origin = header.get("Origin");
        if (origin != null) return refersToHost(origin, header);
        final String referer = header.get("Referer");
        return referer == null || refersToHost(referer, header);
    }

    private static boolean refersToHost(final String url, final RequestHeader header) {
        final String host = header.get("Host");
        if (host == null || url == null) return false;
        final int scheme = url.indexOf("://");
        if (scheme < 0) return false;
        String h = url.substring(scheme + 3);
        final int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.equalsIgnoreCase(host.trim());
    }
}
