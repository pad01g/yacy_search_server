// trust.java
// -----------------------
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

package net.yacy.htroot.yacy;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.peers.trust.TrustStore;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/**
 * /yacy/trust.json: the signed delegations and peer lists this peer holds (see docs/trust-and-nat.md, section 4).
 * Everything in it is signed, so any peer may relay it; receivers verify each statement.
 */
public final class trust {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        final TrustStore store = TrustStore.get();
        prop.put("bundle", store == null ? "{\"envelopes\":[]}" : store.exportJSON());
        return prop;
    }
}
