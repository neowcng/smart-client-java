/*
 * Copyright (c) 2015, EMC Corporation.
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *     + Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *     + Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     + The name of EMC Corporation may not be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.emc.rest.smart.ecs;

import com.emc.rest.smart.Host;
import com.emc.rest.smart.SmartConfig;
import com.emc.util.TestConfig;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.client.apache4.ApacheHttpClient4;
import com.sun.jersey.client.apache4.config.ApacheHttpClient4Config;
import org.junit.Assert;
import org.junit.Test;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public class EcsHostListProviderTest {
    public static final String S3_ENDPOINT = "s3.endpoint";
    public static final String S3_ACCESS_KEY = "s3.access_key";
    public static final String S3_SECRET_KEY = "s3.secret_key";

    public static final String PROXY_URI = "http.proxyUri";

    @Test
    public void testEcsHostListProvider() throws Exception {
        Properties properties = TestConfig.getProperties();

        URI serverURI = new URI(TestConfig.getPropertyNotEmpty(properties, S3_ENDPOINT));
        String user = TestConfig.getPropertyNotEmpty(properties, S3_ACCESS_KEY);
        String secret = TestConfig.getPropertyNotEmpty(properties, S3_SECRET_KEY);
        String proxyUri = properties.getProperty(PROXY_URI);

        ClientConfig clientConfig = new DefaultClientConfig();
        if (proxyUri != null) clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_URI, proxyUri);
        Client client = ApacheHttpClient4.create(clientConfig);

        SmartConfig smartConfig = new SmartConfig(serverURI.getHost());

        EcsHostListProvider hostListProvider = new EcsHostListProvider(client, smartConfig.getLoadBalancer(), user, secret);
        hostListProvider.setProtocol(serverURI.getScheme());
        hostListProvider.setPort(serverURI.getPort());

        List<Host> hostList = hostListProvider.getHostList();

        Assert.assertTrue("server list is empty", hostList.size() > 0);
    }

    @Test
    public void testHealthCheck() throws Exception {
        Properties properties = TestConfig.getProperties();

        URI serverURI = new URI(TestConfig.getPropertyNotEmpty(properties, S3_ENDPOINT));
        String user = TestConfig.getPropertyNotEmpty(properties, S3_ACCESS_KEY);
        String secret = TestConfig.getPropertyNotEmpty(properties, S3_SECRET_KEY);
        String proxyUri = properties.getProperty(PROXY_URI);

        ClientConfig clientConfig = new DefaultClientConfig();
        if (proxyUri != null) clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_URI, proxyUri);
        Client client = ApacheHttpClient4.create(clientConfig);

        SmartConfig smartConfig = new SmartConfig(serverURI.getHost());

        EcsHostListProvider hostListProvider = new EcsHostListProvider(client, smartConfig.getLoadBalancer(), user, secret);
        hostListProvider.setProtocol(serverURI.getScheme());
        hostListProvider.setPort(serverURI.getPort());

        for (Host host : hostListProvider.getHostList()) {
            hostListProvider.runHealthCheck(host);
        }

        // test non-VDC host
        Host host = new Host(serverURI.getHost());
        hostListProvider.runHealthCheck(host);
        Assert.assertTrue(host.isHealthy());

        // test VDC host
        Vdc vdc = new Vdc(serverURI.getHost());
        VdcHost vdcHost = vdc.getHosts().get(0);
        hostListProvider.runHealthCheck(vdcHost);
        Assert.assertTrue(vdcHost.isHealthy());
        Assert.assertFalse(vdcHost.isMaintenanceMode());

        try {
            hostListProvider.runHealthCheck(new Host("localhost"));
            Assert.fail("health check against bad host should fail");
        } catch (Exception e) {
            // expected
        }
    }

    @Test
    public void testMaintenanceMode() {
        Vdc vdc = new Vdc("foo.com");
        VdcHost host = vdc.getHosts().get(0);

        // assert the host is healthy first
        Assert.assertTrue(host.isHealthy());

        // maintenance mode should make the host appear offline
        host.setMaintenanceMode(true);
        Assert.assertFalse(host.isHealthy());

        host.setMaintenanceMode(false);
        Assert.assertTrue(host.isHealthy());
    }

    @Test
    public void testPing() throws Exception {
        Properties properties = TestConfig.getProperties();

        URI serverURI = new URI(TestConfig.getPropertyNotEmpty(properties, S3_ENDPOINT));
        String proxyUri = properties.getProperty(PROXY_URI);

        ClientConfig clientConfig = new DefaultClientConfig();
        if (proxyUri != null) clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_URI, proxyUri);
        Client client = ApacheHttpClient4.create(clientConfig);

        String portStr = serverURI.getPort() > 0 ? ":" + serverURI.getPort() : "";

        PingResponse response = client.resource(
                String.format("%s://%s%s/?ping", serverURI.getScheme(), serverURI.getHost(), portStr))
                .header("x-emc-namespace", "foo").get(PingResponse.class);
        Assert.assertNotNull(response);
        Assert.assertEquals(PingItem.Status.OFF, response.getPingItemMap().get(PingItem.MAINTENANCE_MODE).getStatus());
    }

    @Test
    public void testVdcs() throws Exception {
        Properties properties = TestConfig.getProperties();

        URI serverURI = new URI(TestConfig.getPropertyNotEmpty(properties, S3_ENDPOINT));
        String user = TestConfig.getPropertyNotEmpty(properties, S3_ACCESS_KEY);
        String secret = TestConfig.getPropertyNotEmpty(properties, S3_SECRET_KEY);
        String proxyUri = properties.getProperty(PROXY_URI);

        ClientConfig clientConfig = new DefaultClientConfig();
        if (proxyUri != null) clientConfig.getProperties().put(ApacheHttpClient4Config.PROPERTY_PROXY_URI, proxyUri);
        Client client = ApacheHttpClient4.create(clientConfig);

        SmartConfig smartConfig = new SmartConfig(serverURI.getHost());

        EcsHostListProvider hostListProvider = new EcsHostListProvider(client, smartConfig.getLoadBalancer(), user, secret);
        hostListProvider.setProtocol(serverURI.getScheme());
        hostListProvider.setPort(serverURI.getPort());

        Vdc vdc1 = new Vdc(serverURI.getHost()).withName("vdc1");
        Vdc vdc2 = new Vdc(serverURI.getHost()).withName("vdc2");
        Vdc vdc3 = new Vdc(serverURI.getHost()).withName("vdc3");

        hostListProvider.withVdcs(vdc1, vdc2, vdc3);

        List<Host> hostList = hostListProvider.getHostList();

        Assert.assertTrue("server list should have at least 3 entries", hostList.size() >= 3);
        Assert.assertTrue("VDC1 server list is empty", vdc1.getHosts().size() > 0);
        Assert.assertTrue("VDC2 server list is empty", vdc2.getHosts().size() > 0);
        Assert.assertTrue("VDC3 server list is empty", vdc3.getHosts().size() > 0);
    }

    @Test
    public void testPingMarshalling() throws Exception {
        JAXBContext context = JAXBContext.newInstance(PingResponse.class);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<PingList xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                "<PingItem><Name>LOAD_FACTOR</Name><Value>1</Value></PingItem>" +
                "<PingItem><Name>MAINTENANCE_MODE</Name><Status>OFF</Status><Text>Data Node is Available</Text></PingItem>" +
                "</PingList>";

        PingResponse object = new PingResponse();
        Map<String, PingItem> map = new TreeMap<String, PingItem>();
        map.put("LOAD_FACTOR", new PingItem("LOAD_FACTOR", null, null, "1"));
        map.put("MAINTENANCE_MODE", new PingItem("MAINTENANCE_MODE", PingItem.Status.OFF, "Data Node is Available", null));
        object.setPingItemMap(map);

        // unmarshall and compare to object
        Unmarshaller unmarshaller = context.createUnmarshaller();
        PingResponse xObject = (PingResponse) unmarshaller.unmarshal(new StringReader(xml));

        Assert.assertEquals(object.getPingItemMap().keySet(), xObject.getPingItemMap().keySet());
        PingItem pingItem = object.getPingItems().get(0), xPingItem = xObject.getPingItems().get(0);
        Assert.assertEquals(pingItem.getName(), xPingItem.getName());
        Assert.assertEquals(pingItem.getStatus(), xPingItem.getStatus());
        Assert.assertEquals(pingItem.getText(), xPingItem.getText());
        Assert.assertEquals(pingItem.getValue(), xPingItem.getValue());
        pingItem = object.getPingItems().get(1);
        xPingItem = xObject.getPingItems().get(1);
        Assert.assertEquals(pingItem.getName(), xPingItem.getName());
        Assert.assertEquals(pingItem.getStatus(), xPingItem.getStatus());
        Assert.assertEquals(pingItem.getText(), xPingItem.getText());
        Assert.assertEquals(pingItem.getValue(), xPingItem.getValue());

        // marshall and compare XML
        Marshaller marshaller = context.createMarshaller();
        StringWriter writer = new StringWriter();
        marshaller.marshal(object, writer);

        Assert.assertEquals(xml, writer.toString());
    }
}
