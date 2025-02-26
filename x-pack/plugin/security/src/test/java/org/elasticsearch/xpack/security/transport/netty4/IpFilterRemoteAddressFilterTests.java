/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.transport.netty4;

import io.netty.channel.ChannelHandlerContext;

import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.license.MockLicenseState;
import org.elasticsearch.license.TestUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.RemoteClusterPortSettings;
import org.elasticsearch.transport.TcpTransport;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.transport.filter.IPFilter;
import org.junit.Before;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IpFilterRemoteAddressFilterTests extends ESTestCase {
    private IpFilterRemoteAddressFilter handler;
    private IPFilter ipFilter;

    @Before
    public void init() throws Exception {
        Settings settings = Settings.builder()
            .put("xpack.security.transport.filter.allow", "127.0.0.1")
            .put("xpack.security.transport.filter.deny", "10.0.0.0/8")
            .put("remote_cluster.enabled", true)
            .build();

        boolean isHttpEnabled = randomBoolean();

        Transport transport = mock(Transport.class);
        TransportAddress address = new TransportAddress(InetAddress.getLoopbackAddress(), 9300);
        when(transport.boundAddress()).thenReturn(new BoundTransportAddress(new TransportAddress[] { address }, address));
        TransportAddress remoteClusterIngressPortAddress = new TransportAddress(InetAddress.getLoopbackAddress(), 9443);
        when(transport.profileBoundAddresses()).thenReturn(
            Map.of(
                RemoteClusterPortSettings.REMOTE_CLUSTER_PROFILE,
                new BoundTransportAddress(new TransportAddress[] { remoteClusterIngressPortAddress }, remoteClusterIngressPortAddress)
            )
        );
        when(transport.lifecycleState()).thenReturn(Lifecycle.State.STARTED);
        ClusterSettings clusterSettings = new ClusterSettings(
            Settings.EMPTY,
            new HashSet<>(
                Arrays.asList(
                    IPFilter.HTTP_FILTER_ALLOW_SETTING,
                    IPFilter.HTTP_FILTER_DENY_SETTING,
                    IPFilter.IP_FILTER_ENABLED_HTTP_SETTING,
                    IPFilter.IP_FILTER_ENABLED_SETTING,
                    IPFilter.TRANSPORT_FILTER_ALLOW_SETTING,
                    IPFilter.TRANSPORT_FILTER_DENY_SETTING,
                    TcpTransport.isUntrustedRemoteClusterEnabled() ? IPFilter.REMOTE_CLUSTER_FILTER_ALLOW_SETTING : null,
                    TcpTransport.isUntrustedRemoteClusterEnabled() ? IPFilter.REMOTE_CLUSTER_FILTER_DENY_SETTING : null,
                    IPFilter.PROFILE_FILTER_ALLOW_SETTING,
                    IPFilter.PROFILE_FILTER_DENY_SETTING
                )
            )
        );
        MockLicenseState licenseState = TestUtils.newMockLicenceState();
        when(licenseState.isAllowed(Security.IP_FILTERING_FEATURE)).thenReturn(true);
        AuditTrailService auditTrailService = new AuditTrailService(Collections.emptyList(), licenseState);
        ipFilter = new IPFilter(settings, auditTrailService, clusterSettings, licenseState);
        ipFilter.setBoundTransportAddress(transport.boundAddress(), transport.profileBoundAddresses());
        if (isHttpEnabled) {
            HttpServerTransport httpTransport = mock(HttpServerTransport.class);
            TransportAddress httpAddress = new TransportAddress(InetAddress.getLoopbackAddress(), 9200);
            when(httpTransport.boundAddress()).thenReturn(new BoundTransportAddress(new TransportAddress[] { httpAddress }, httpAddress));
            when(httpTransport.lifecycleState()).thenReturn(Lifecycle.State.STARTED);
            ipFilter.setBoundHttpTransportAddress(httpTransport.boundAddress());
        }

        if (isHttpEnabled) {
            handler = new IpFilterRemoteAddressFilter(ipFilter, IPFilter.HTTP_PROFILE_NAME);
        } else {
            handler = new IpFilterRemoteAddressFilter(ipFilter, "default");
        }
    }

    public void testThatFilteringWorksByIp() throws Exception {
        InetSocketAddress localhostAddr = new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 12345);
        assertThat(handler.accept(mock(ChannelHandlerContext.class), localhostAddr), is(true));

        InetSocketAddress remoteAddr = new InetSocketAddress(InetAddresses.forString("10.0.0.8"), 12345);
        assertThat(handler.accept(mock(ChannelHandlerContext.class), remoteAddr), is(false));
    }

    public void testFilteringWorksForRemoteClusterPort() throws Exception {
        handler = new IpFilterRemoteAddressFilter(ipFilter, RemoteClusterPortSettings.REMOTE_CLUSTER_PROFILE);
        InetSocketAddress localhostAddr = new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 12345);
        assertThat(handler.accept(mock(ChannelHandlerContext.class), localhostAddr), is(true));

        InetSocketAddress remoteAddr = new InetSocketAddress(InetAddresses.forString("10.0.0.8"), 12345);
        assertThat(handler.accept(mock(ChannelHandlerContext.class), remoteAddr), is(false));
    }
}
