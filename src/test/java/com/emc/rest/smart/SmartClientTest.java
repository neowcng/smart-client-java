package com.emc.rest.smart;

import com.emc.vipr.services.lib.ViprConfig;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class SmartClientTest {
    private static final Logger l4j = Logger.getLogger(SmartClientTest.class);

    private static final String HEADER_FORMAT = "EEE, d MMM yyyy HH:mm:ss z";
    private static final ThreadLocal<DateFormat> headerFormat = new ThreadLocal<DateFormat>();

    @Test
    public void testAtmosOnViPR() throws Exception {
        Properties viprProperties = null;
        try {
            viprProperties = ViprConfig.getProperties();
        } catch (Exception e) {
            Assume.assumeTrue("vipr.properties missing", false);
        }
        String endpointStr = ViprConfig.getPropertyNotEmpty(viprProperties, ViprConfig.PROP_ATMOS_ENDPOINTS);
        final String uid = ViprConfig.getPropertyNotEmpty(viprProperties, ViprConfig.PROP_ATMOS_UID);
        final String secret = ViprConfig.getPropertyNotEmpty(viprProperties, ViprConfig.PROP_ATMOS_SECRET);

        String[] endpoints = endpointStr.split(",");
        final URI serverUri = new URI(endpointStr.split(",")[0]);

        List<String> initialHosts = new ArrayList<>();
        for (String endpoint : endpoints) {
            initialHosts.add(new URI(endpoint).getHost());
        }

        SmartConfig smartConfig = new SmartConfig(initialHosts);
        final Client client = SmartClientFactory.createSmartClient(smartConfig);

        ExecutorService service = Executors.newFixedThreadPool(10);

        final AtomicInteger successCount = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            futures.add(service.submit(new Runnable() {
                @Override
                public void run() {
                    getServiceInfo(client, serverUri, uid, secret);
                    successCount.incrementAndGet();
                }
            }));
        }

        // wait for all threads to complete
        for (Future<?> future : futures) {
            future.get();
        }

        l4j.info(Arrays.toString(smartConfig.getLoadBalancer().getHostStats()));

        Assert.assertEquals("at least one task failed", 100, successCount.intValue());
    }

    private void getServiceInfo(Client client, URI serverUri, String uid, String secretKey) {
        String path = "/rest/service";
        String date = getDateFormat().format(new Date());

        String signature = sign("GET\n\n\n" + date + "\n" + path + "\nx-emc-date:" + date + "\nx-emc-uid:" + uid, secretKey);

        Invocation.Builder request = client.target(serverUri).path(path).request();

        request.header("Date", date);
        request.header("x-emc-date", date);
        request.header("x-emc-uid", uid);
        request.header("x-emc-signature", signature);

        Response response = request.get();

        if (response.getStatus() > 299) throw new RuntimeException("error response: " + response.getStatus());

        String responseStr = response.readEntity(String.class);
        if (!responseStr.contains("Atmos")) throw new RuntimeException("unrecognized response string: " + responseStr);
    }

    private String sign(String canonicalString, String secretKey) {
        try {
            byte[] hashKey = Base64.decodeBase64(secretKey.getBytes("UTF-8"));
            byte[] input = canonicalString.getBytes("UTF-8");

            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec key = new SecretKeySpec(hashKey, "HmacSHA1");
            mac.init(key);

            byte[] hashBytes = mac.doFinal(input);

            return new String(Base64.encodeBase64(hashBytes), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Error signing string:\n" + canonicalString + "\n", e);
        }
    }

    private DateFormat getDateFormat() {
        DateFormat format = headerFormat.get();
        if (format == null) {
            format = new SimpleDateFormat(HEADER_FORMAT, Locale.ENGLISH);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            headerFormat.set(format);
        }
        return format;
    }
}