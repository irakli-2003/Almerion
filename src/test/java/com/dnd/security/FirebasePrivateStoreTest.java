package com.dnd.security;

import com.dnd.auth.User;
import com.dnd.cloud.CloudConfig;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.junit.Assert.*;

public class FirebasePrivateStoreTest {
    private FirebasePrivateStore connect(Fixture http) {
        User user = new User();
        user.setEmail("gm@example.test");
        return FirebasePrivateStore.connect(new CloudConfig(true, "https://database.example.test",
            "ignored-legacy-root", "synthetic-api-key", "https://auth.example.test/v1"), user, "synthetic-password", http);
    }

    @Test
    public void scopedRequestsRefreshCredentialsAndClosingRevokesEveryScope() {
        Fixture http = new Fixture();
        try (FirebasePrivateStore connection = connect(http)) {
            var directory = connection.scope("almerion-cloud");
            directory.update(Map.of("campaigns/one/name", "Updated"));
            HttpRequest last = http.requests.getLast();
            assertEquals("PATCH", last.method());
            assertEquals("/almerion-cloud.json", last.uri().getPath());
            assertEquals("auth=refreshed-token", last.uri().getQuery());
            assertEquals(1, http.refreshes);
            connection.scope("almerion-live").put("session", Map.of("snapshot", "encrypted"));
            connection.scope("almerion-chat").put("session/publicKey", "ephemeral-key");
            assertEquals("/almerion-chat/session/publicKey.json", http.requests.getLast().uri().getPath());
            assertEquals(1, http.refreshes);
            assertThrows(IllegalArgumentException.class, () -> directory.get("../users"));
            assertThrows(IllegalArgumentException.class, () -> connection.scope("sessions"));
            connection.close();
            int calls = http.requests.size();
            assertThrows(IllegalStateException.class, () -> directory.get("profiles/gm"));
            assertEquals(calls, http.requests.size());
        }
    }

    @Test
    public void unverifiedIdentityAndRefreshIdentityChangesFailClosed() {
        Fixture unverified = new Fixture();
        unverified.verified = false;
        assertThrows(IllegalStateException.class, () -> connect(unverified));
        Fixture changed = new Fixture();
        changed.refreshUid = "another-user";
        try (FirebasePrivateStore connection = connect(changed)) {
            assertThrows(IllegalStateException.class, () -> connection.scope("almerion-cloud").get("profiles/gm"));
            int calls = changed.requests.size();
            assertThrows(IllegalStateException.class, () -> connection.get("accounts/gm"));
            assertEquals(calls, changed.requests.size());
        }
    }

    private static final class Fixture extends HttpClient {
        final List<HttpRequest> requests = new ArrayList<>();
        boolean verified = true;
        String refreshUid = "gm";
        int refreshes;

        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            String path = request.uri().getPath();
            String body = "{}";
            int status = 200;
            if (path.endsWith("accounts:signInWithPassword")) {
                body = "{\"localId\":\"gm\",\"idToken\":\"initial-token\",\"refreshToken\":\"refresh\",\"expiresIn\":\"0\"}";
            } else if (path.endsWith("accounts:lookup")) {
                body = "{\"users\":[{\"localId\":\"gm\",\"email\":\"gm@example.test\",\"emailVerified\":" + verified + "}]}";
            } else if (path.endsWith("/token")) {
                refreshes++;
                body = "{\"user_id\":\"" + refreshUid + "\",\"id_token\":\"refreshed-token\",\"refresh_token\":\"new-refresh\",\"expires_in\":\"3600\"}";
            } else if (request.uri().getQuery() == null) {
                status = 401;
                body = "{\"error\":\"Permission denied\"}";
            }
            int code = status;
            HttpHeaders headers = HttpHeaders.of(Map.of(), (a, b) -> true);
            var info = new HttpResponse.ResponseInfo() {
                @Override public int statusCode() { return code; }
                @Override public HttpHeaders headers() { return headers; }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            T value = subscriber.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
                @Override public int statusCode() { return code; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return headers; }
                @Override public T body() { return value; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); }
            catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            return CompletableFuture.completedFuture(send(r, h));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                        HttpResponse.PushPromiseHandler<T> p) {
            return sendAsync(r, h);
        }
    }
}
