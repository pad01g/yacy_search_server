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
        if (post != null && "POST".equalsIgnoreCase(header.getMethod())
                && sb.getConfigFloat(SwitchboardConstants.SEARCH_RANKING_CLICKS_WEIGHT, SwitchboardConstants.SEARCH_RANKING_CLICKS_WEIGHT_DEFAULT) > 0) {
            recorded = ClickStore.get().record(post.get("query", ""), post.get("urlhash", ""));
        }
        prop.put("recorded", recorded ? 1 : 0);
        return prop;
    }
}
