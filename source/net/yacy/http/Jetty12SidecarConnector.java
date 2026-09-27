// Jetty12SidecarConnector.java
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

package net.yacy.http;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import net.yacy.peers.trust.P2PRoute;

/**
 * The connector the libp2p sidecar uses to pass requests of other peers to YaCy (see docs/trust-and-nat.md, §6).
 *
 * Those requests arrive from the loopback address, and YaCy grants loopback clients administrator rights, exempts
 * them from rate limits and trusts their X-Real-IP header. On this connector the remote address is therefore
 * replaced by an address that stands for the remote peer: an IPv6 documentation address (2001:db8::/32) derived
 * from its libp2p peer id. It is never local, so the request is handled like one from any other remote peer, and
 * rate limits apply per remote peer.
 */
public final class Jetty12SidecarConnector {

    public static final String NAME = "p2pd";

    private Jetty12SidecarConnector() {
    }

    /** add the connector, bound to the loopback interface only */
    public static void add(final Server server, final int port, final int acceptors, final int requestHeaderSize) {
        final HttpConfiguration config = new HttpConfiguration();
        config.setRequestHeaderSize(requestHeaderSize);
        config.setSendServerVersion(false);
        config.addCustomizer(new RemotePeerCustomizer());
        final ServerConnector connector = new ServerConnector(server, acceptors, -1, new HttpConnectionFactory(config));
        connector.setHost("127.0.0.1");
        connector.setPort(port);
        connector.setName(NAME);
        connector.setIdleTimeout(60000);
        server.addConnector(connector);
    }

    static final class RemotePeerCustomizer implements HttpConfiguration.Customizer {
        @Override
        public Request customize(final Request request, final HttpFields.Mutable responseHeaders) {
            final String peer = request.getHeaders().get(P2PRoute.SIDECAR_HEADER);
            final SocketAddress remote = new InetSocketAddress(P2PRoute.sidecarClientAddress(peer), 0);
            return new Request.Wrapper(request) {
                @Override
                public ConnectionMetaData getConnectionMetaData() {
                    return new ConnectionMetaData.Wrapper(super.getConnectionMetaData()) {
                        @Override
                        public SocketAddress getRemoteSocketAddress() {
                            return remote;
                        }
                    };
                }
            };
        }
    }
}
