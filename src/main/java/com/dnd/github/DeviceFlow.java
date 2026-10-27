package com.dnd.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * GitHub's OAuth device flow: the app shows a short code, the GM enters it on
 * github.com/login/device, and the app polls until GitHub hands out a token.
 * See https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
 */
public final class DeviceFlow {
    static final String CODE_URL = "https://github.com/login/device/code";
    static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    /** Full access to the GM's repositories, including private ones (GitHub has no narrower OAuth scope). */
    public static final String SCOPE = "repo";

    /** What GitHub returns when the flow starts; {@code userCode} is what the GM types in. */
    public record Code(String deviceCode, String userCode, String verificationUri, int expiresInSeconds,
                       int intervalSeconds) {
        @Override
        public String toString() {
            return "Code[" + userCode + "]";
        }
    }

    /** Waits between polls; tests replace it so they don't actually sleep. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String clientId;
    private final HttpTransport http;
    private final Sleeper sleeper;

    public DeviceFlow(String clientId, HttpTransport http) {
        this(clientId, http, Thread::sleep);
    }

    public DeviceFlow(String clientId, HttpTransport http, Sleeper sleeper) {
        this.clientId = clientId;
        this.http = http;
        this.sleeper = sleeper;
    }

    public Code start() throws IOException {
        JsonNode json = post(CODE_URL, Map.of("client_id", clientId, "scope", SCOPE));
        if (!json.path("device_code").isTextual()) throw failure(json, "GitHub didn't start the sign-in.");
        return new Code(json.path("device_code").asText(), json.path("user_code").asText(),
            json.path("verification_uri").asText("https://github.com/login/device"),
            json.path("expires_in").asInt(900), Math.max(1, json.path("interval").asInt(5)));
    }

    /**
     * Polls until the GM approves (returns the token), declines, or the code expires (both throw
     * {@link GitHubException}). {@code cancelled} is checked before every poll.
     */
    public String awaitToken(Code code, BooleanSupplier cancelled) throws IOException, InterruptedException {
        long interval = code.intervalSeconds();
        long waited = 0;
        while (true) {
            sleeper.sleep(interval * 1000);
            waited += interval;
            if (cancelled.getAsBoolean()) throw new CancellationException("Sign-in cancelled");
            JsonNode json = post(TOKEN_URL, Map.of("client_id", clientId, "device_code", code.deviceCode(),
                "grant_type", GRANT_TYPE));
            String token = json.path("access_token").asText("");
            if (!token.isBlank()) return token;
            switch (json.path("error").asText("")) {
                case "authorization_pending" -> { }
                case "slow_down" -> interval = json.has("interval") ? json.path("interval").asInt() : interval + 5;
                case "expired_token" -> throw new GitHubException("The code expired before it was entered. Try again.");
                case "access_denied" -> throw new GitHubException("Access was declined on GitHub.");
                default -> throw failure(json, "GitHub sign-in failed.");
            }
            if (waited >= code.expiresInSeconds()) {
                throw new GitHubException("The code expired before it was entered. Try again.");
            }
        }
    }

    private JsonNode post(String url, Map<String, String> form) throws IOException {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&")
            .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpTransport.Response response = http.send("POST", url, Map.of(
            "Accept", "application/json",
            "Content-Type", "application/x-www-form-urlencoded",
            "User-Agent", GitHubApi.USER_AGENT), body.toString());
        try {
            return JSON.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
        } catch (IOException e) {
            throw new GitHubException("GitHub sent an unexpected reply (HTTP " + response.status() + ").");
        }
    }

    private static GitHubException failure(JsonNode json, String fallback) {
        String error = json.path("error").asText("");
        if ("device_flow_disabled".equals(error)) {
            return new GitHubException("Device sign-in isn't enabled for this app's GitHub OAuth App.");
        }
        String description = json.path("error_description").asText("");
        return new GitHubException(description.isBlank() ? fallback : description);
    }
}
