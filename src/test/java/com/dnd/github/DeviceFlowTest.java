package com.dnd.github;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeviceFlowTest {
    private final List<Long> sleeps = new ArrayList<>();
    private final FakeHttp http = new FakeHttp();
    private final DeviceFlow flow = new DeviceFlow("client-123", http, sleeps::add);

    private static final String CODE = "{\"device_code\":\"dev\",\"user_code\":\"ABCD-1234\","
        + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}";

    @Test
    public void startRequestsTheRepoScope() throws Exception {
        http.reply(200, CODE);
        DeviceFlow.Code code = flow.start();
        assertEquals("ABCD-1234", code.userCode());
        assertEquals(5, code.intervalSeconds());
        FakeHttp.Request request = http.requests.get(0);
        assertEquals(DeviceFlow.CODE_URL, request.url());
        assertTrue(request.body().contains("client_id=client-123"));
        assertTrue(request.body().contains("scope=repo"));
        assertEquals("application/json", request.headers().get("Accept"));
        assertFalse("the device code must not leak through toString", code.toString().contains("dev"));
    }

    @Test
    public void pollsUntilApprovedAndHonoursSlowDown() throws Exception {
        http.reply(200, CODE)
            .reply(200, "{\"error\":\"authorization_pending\"}")
            .reply(200, "{\"error\":\"slow_down\",\"interval\":10}")
            .reply(200, "{\"error\":\"authorization_pending\"}")
            .reply(200, "{\"access_token\":\"gho_secret\",\"token_type\":\"bearer\",\"scope\":\"repo\"}");
        String token = flow.awaitToken(flow.start(), () -> false);
        assertEquals("gho_secret", token);
        assertEquals(List.of(5000L, 5000L, 10000L, 10000L), sleeps);
        FakeHttp.Request poll = http.requests.get(1);
        assertEquals(DeviceFlow.TOKEN_URL, poll.url());
        assertTrue(poll.body().contains("device_code=dev"));
        assertTrue(poll.body().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
    }

    @Test
    public void slowDownWithoutIntervalAddsFiveSeconds() throws Exception {
        http.reply(200, CODE)
            .reply(200, "{\"error\":\"slow_down\"}")
            .reply(200, "{\"access_token\":\"t\"}");
        flow.awaitToken(flow.start(), () -> false);
        assertEquals(List.of(5000L, 10000L), sleeps);
    }

    @Test
    public void expiredAndDeniedCodesStopPolling() throws Exception {
        http.reply(200, CODE).reply(200, "{\"error\":\"expired_token\"}");
        assertFails(flow.start(), "expired");
        http.reply(200, CODE).reply(200, "{\"error\":\"access_denied\"}");
        assertFails(flow.start(), "declined");
    }

    @Test
    public void givesUpWhenTheCodeRunsOut() throws Exception {
        http.reply(200, "{\"device_code\":\"dev\",\"user_code\":\"X\",\"expires_in\":10,\"interval\":5}")
            .reply(200, "{\"error\":\"authorization_pending\"}")
            .reply(200, "{\"error\":\"authorization_pending\"}");
        assertFails(flow.start(), "expired");
        assertEquals(2, sleeps.size());
    }

    @Test(expected = CancellationException.class)
    public void cancellingStopsBeforeTheNextPoll() throws Exception {
        http.reply(200, CODE);
        flow.awaitToken(flow.start(), () -> true);
    }

    @Test
    public void disabledDeviceFlowIsExplained() throws Exception {
        http.reply(400, "{\"error\":\"device_flow_disabled\"}");
        try {
            flow.start();
            fail();
        } catch (GitHubException e) {
            assertTrue(e.getMessage().contains("Device sign-in isn't enabled"));
        }
    }

    private void assertFails(DeviceFlow.Code code, String messagePart) throws Exception {
        try {
            flow.awaitToken(code, () -> false);
            fail("expected failure");
        } catch (GitHubException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }
}
