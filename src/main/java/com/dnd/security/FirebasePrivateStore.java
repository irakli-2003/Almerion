package com.dnd.security;

import com.dnd.auth.User;
import com.dnd.cloud.CloudConfig;
import com.dnd.cloud.CloudStore;
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

/** Verified Firebase account access, deliberately outside the shared session-token namespace. */
public final class FirebasePrivateStore implements CloudStore, AutoCloseable {
    public static final String ROOT = "almerion-private";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private final ObjectMapper mapper = JsonMappers.create();
    private final HttpClient http;
    private final String databaseUrl;
    private final String apiKey;
    private String uid;
    private String email;
    private String idToken;
    private String refreshToken;
    private long expiresAt;

    private FirebasePrivateStore(CloudConfig config, HttpClient http) {
        requireConfiguration(config);
        this.http = http;
        databaseUrl = secureUrl(config.databaseUrl());
        apiKey = config.apiKey();
    }

    private static void requireConfiguration(CloudConfig config) {
        if (!config.enabled() || !config.emailLinksEnabled()) {
            throw new IllegalStateException("Online access requires Firebase Authentication and a configured database.");
        }
    }

    public static void sendVerification(CloudConfig config, User user, String password) {
        requireConfiguration(config);
        if (user == null || user.getEmail() == null || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Sign in with your account password to unlock private files.");
        }
        new com.dnd.auth.FirebaseEmailLinkAuth(secureUrl(config.authUrl()), config.apiKey())
            .sendVerification(user.getEmail(), password);
    }

    public static FirebasePrivateStore connect(CloudConfig config, User user, String password) {
        return connect(config, user, password, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    static FirebasePrivateStore connect(CloudConfig config, User user, String password, HttpClient http) {
        if (user == null || user.getEmail() == null || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Sign in with your account password to unlock private files.");
        }
        FirebasePrivateStore store = new FirebasePrivateStore(config, http);
        JsonNode login;
        try {
            login = store.post(secureUrl(config.authUrl()) + "/accounts:signInWithPassword?key="
                + encode(config.apiKey()), Map.of("email", user.getEmail(), "password", password, "returnSecureToken", true));
        } catch (CloudException e) {
            throw new SignInException(e);
        }
        store.uid = required(login, "localId");
        validateKey(store.uid);
        store.idToken = required(login, "idToken");
        store.refreshToken = required(login, "refreshToken");
        store.expiresAt = System.currentTimeMillis() + login.path("expiresIn").asLong(3600) * 1000;
        JsonNode users = store.post(secureUrl(config.authUrl()) + "/accounts:lookup?key=" + encode(config.apiKey()),
            Map.of("idToken", store.idToken)).path("users");
        if (!users.isArray() || users.size() != 1 || !users.get(0).path("emailVerified").asBoolean()
            || !store.uid.equals(users.get(0).path("localId").asText())
            || !user.getEmail().equalsIgnoreCase(users.get(0).path("email").asText())) {
            store.close();
            throw new IllegalStateException("Online access requires a verified Firebase account matching your email.");
        }
        store.email = users.get(0).path("email").asText();
        store.requirePrivateRules();
        return store;
    }

    public static final class SignInException extends CloudException {
        private SignInException(CloudException cause) {
            super(cause.getMessage(), cause.isOffline(), cause);
        }
    }

    public void requireRestrictedNamespace(String root, String collection) {
        validateKey(root);
        validateKey(collection);
        String path = databaseUrl + "/" + root + "/" + collection + "/probe-" + java.util.UUID.randomUUID() + ".json";
        for (String method : java.util.List.of("GET", "PUT")) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(path)).timeout(TIMEOUT);
            if ("PUT".equals(method)) {
                request.header("If-Match", "null_etag").header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("null"));
            } else {
                request.GET();
            }
            int status = exchange(request.build()).statusCode();
            if (status != 401 && status != 403) {
                throw new CloudException("Authenticated cloud rules are not deployed safely. Anonymous account access must be denied.", false);
            }
        }
    }

    public String uid() { return uid; }
    public String email() { return email; }

    /** Shares this verified, refreshable login without exposing its bearer tokens. */
    public CloudStore scope(String root) {
        if (!java.util.Set.of(ROOT, "almerion-cloud", "almerion-live", "almerion-chat").contains(root)) {
            throw new IllegalArgumentException("Unsupported authenticated cloud namespace.");
        }
        return new CloudStore() {
            @Override
            public JsonNode get(String path) {
                JsonNode node = parse(send(scopedRequest(root, path).GET().build()));
                return node == null || node.isNull() ? null : node;
            }

            @Override
            public void put(String path, Object value) {
                write(root, path, "PUT", value);
            }

            @Override
            public void delete(String path) {
                send(scopedRequest(root, path).DELETE().build());
            }

            @Override
            public void update(Map<String, Object> changes) {
                changes.keySet().forEach(FirebasePrivateStore::validatePath);
                write(root, "", "PATCH", changes);
            }
        };
    }

    private void write(String root, String path, String method, Object value) {
        try {
            send(scopedRequest(root, path).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value),
                    StandardCharsets.UTF_8)).build());
        } catch (IOException e) {
            throw new CloudException("Could not encode cloud data.", false, e);
        }
    }

    @Override
    public JsonNode get(String path) {
        JsonNode node = parse(send(request(path).GET().build()));
        return node == null || node.isNull() ? null : node;
    }

    @Override
    public void put(String path, Object value) {
        try {
            send(request(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value), StandardCharsets.UTF_8)).build());
        } catch (IOException e) {
            throw new CloudException("Could not encode private data.", false, e);
        }
    }

    @Override
    public void delete(String path) {
        send(request(path).DELETE().build());
    }

    private HttpRequest.Builder request(String path) {
        return scopedRequest(ROOT, path);
    }

    private static void validatePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("A private-cloud path is required.");
        for (String part : path.split("/", -1)) validateKey(part);
    }

    private HttpRequest.Builder scopedRequest(String root, String path) {
        if (!path.isEmpty()) validatePath(path);
        return HttpRequest.newBuilder(URI.create(databaseUrl + "/" + root + (path.isEmpty() ? "" : "/" + path) + ".json?auth="
            + encode(token()))).timeout(TIMEOUT);
    }

    private synchronized String token() {
        if (idToken == null) throw new IllegalStateException("Private-file account is locked. Sign in again.");
        if (System.currentTimeMillis() >= expiresAt - 60_000) {
            String body = "grant_type=refresh_token&refresh_token=" + encode(refreshToken);
            JsonNode refreshed = parse(send(HttpRequest.newBuilder(URI.create(
                "https://securetoken.googleapis.com/v1/token?key=" + encode(apiKey))).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()));
            if (!uid.equals(required(refreshed, "user_id"))) {
                close();
                throw new IllegalStateException("Firebase account changed. Sign in again.");
            }
            idToken = required(refreshed, "id_token");
            refreshToken = required(refreshed, "refresh_token");
            expiresAt = System.currentTimeMillis() + refreshed.path("expires_in").asLong(3600) * 1000;
        }
        return idToken;
    }

    private void requirePrivateRules() {
        // A permissive ancestor rule cannot be narrowed by a child rule in Realtime Database.
        HttpResponse<String> response = exchange(HttpRequest.newBuilder(URI.create(databaseUrl + "/" + ROOT
            + "/accounts/" + uid + ".json")).timeout(TIMEOUT).GET().build());
        if (response.statusCode() != 401 && response.statusCode() != 403) {
            close();
            throw new IllegalStateException("Private-cloud rules are not deployed safely. Anonymous account reads must be denied.");
        }
        for (String collection : java.util.List.of("accounts", "identities")) {
            String probe = databaseUrl + "/" + ROOT + "/" + collection + "/probe-" + java.util.UUID.randomUUID() + ".json";
            // Conditional deletion of an absent nonce tests public writes without changing any account.
            HttpResponse<String> write = exchange(HttpRequest.newBuilder(URI.create(probe)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("If-Match", "null_etag")
                .PUT(HttpRequest.BodyPublishers.ofString("null")).build());
            if (write.statusCode() != 401 && write.statusCode() != 403) {
                close();
                throw new IllegalStateException("Private-cloud rules are not deployed safely. Anonymous key writes must be denied.");
            }
        }
    }

    private JsonNode post(String url, Object value) {
        try {
            return parse(send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value))).build()));
        } catch (IOException e) {
            throw new CloudException("Could not encode authentication request.", false, e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response = exchange(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Never include provider bodies or request URLs: either can contain credentials.
            throw new CloudException("Private cloud refused the request (HTTP " + response.statusCode()
                + "). Check your account password, verification, and deployed Firebase rules.", false);
        }
        return response;
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CloudException("Cannot reach private cloud. No private data was sent to shared storage.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException("Private-cloud request interrupted.", true);
        }
    }

    private JsonNode parse(HttpResponse<String> response) {
        try {
            JsonNode node = mapper.readTree(response.body());
            if (node == null) throw new IOException("Empty response");
            return node;
        } catch (IOException e) {
            throw new CloudException("Unreadable private-cloud response.", false);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Incomplete Firebase authentication response.");
        return value;
    }

    public static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid private-cloud identifier.");
        }
    }

    private static String secureUrl(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Private cloud requires an HTTPS service URL.");
        }
        return value.replaceAll("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        idToken = null;
        refreshToken = null;
    }
}
