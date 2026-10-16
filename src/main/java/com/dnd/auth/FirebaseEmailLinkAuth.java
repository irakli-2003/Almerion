package com.dnd.auth;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** {@link EmailLinkAuth} over the Firebase Authentication (Identity Toolkit) REST API. */
public class FirebaseEmailLinkAuth implements EmailLinkAuth {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper mapper = JsonMappers.create();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final String apiKey;

    /** @param baseUrl normally {@code https://identitytoolkit.googleapis.com/v1} */
    public FirebaseEmailLinkAuth(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
    }

    @Override
    public void sendVerification(String email, String password) {
        String idToken;
        try {
            idToken = call("accounts:signUp", Map.of("email", email, "password", password,
                "returnSecureToken", true)).path("idToken").asText();
        } catch (EmailLinkException e) {
            if (!"EMAIL_EXISTS".equals(e.code())) throw e;
            idToken = signIn(email, password);
        }
        call("accounts:sendOobCode", Map.of("requestType", "VERIFY_EMAIL", "idToken", idToken));
    }

    @Override
    public boolean passwordMatches(String email, String password) {
        try {
            signIn(email, password);
            return true;
        } catch (EmailLinkException e) {
            if ("NETWORK".equals(e.code())) throw e;
            return false;
        }
    }

    @Override
    public boolean isEmailVerified(String email, String password) {
        String idToken = signIn(email, password);
        JsonNode users = call("accounts:lookup", Map.of("idToken", idToken)).path("users");
        return users.isArray() && users.size() > 0 && users.get(0).path("emailVerified").asBoolean(false);
    }

    @Override
    public void sendPasswordReset(String email) {
        call("accounts:sendOobCode", Map.of("requestType", "PASSWORD_RESET", "email", email));
    }

    private String signIn(String email, String password) {
        return call("accounts:signInWithPassword", Map.of("email", email, "password", password,
            "returnSecureToken", true)).path("idToken").asText();
    }

    private JsonNode call(String method, Map<String, Object> body) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + method
                    + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EmailLinkException("NETWORK", "Can't reach the email service: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailLinkException("NETWORK", "Interrupted", e);
        }
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EmailLinkException("BAD_REPLY", "Unreadable reply from the email service", e);
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) return json;
        // Firebase reports e.g. "EMAIL_EXISTS" or "TOO_MANY_ATTEMPTS_TRY_LATER : ..."
        String message = json.path("error").path("message").asText("HTTP " + response.statusCode());
        String code = message.split("[ :]", 2)[0];
        throw new EmailLinkException(code, message, null);
    }
}
