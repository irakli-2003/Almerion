package com.dnd.auth;

import com.dnd.data.JsonMappers;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class FirebaseEmailLinkAuthTest {
    @Test public void oldAccountEnrollmentCreatesProviderAccountAndEmailsVerification() throws Exception {
        checkEnrollment(false);
    }

    @Test public void existingProviderAccountSignsInBeforeResendingVerification() throws Exception {
        checkEnrollment(true);
    }

    private void checkEnrollment(boolean exists) throws Exception {
        List<String> operations = new ArrayList<>();
        List<com.fasterxml.jackson.databind.JsonNode> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestURI().getPath().substring(1);
            operations.add(method);
            bodies.add(JsonMappers.create().readTree(exchange.getRequestBody()));
            String reply;
            int status = 200;
            if (method.equals("accounts:signUp") && exists) {
                status = 400;
                reply = "{\"error\":{\"message\":\"EMAIL_EXISTS\"}}";
            } else if (method.equals("accounts:sendOobCode")) {
                reply = "{}";
            } else {
                reply = "{\"idToken\":\"synthetic-verification-token\"}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var provider = new FirebaseEmailLinkAuth("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-api-key");
            provider.sendVerification("existing@example.test", "existing-password");
            assertEquals(exists ? List.of("accounts:signUp", "accounts:signInWithPassword", "accounts:sendOobCode")
                : List.of("accounts:signUp", "accounts:sendOobCode"), operations);
            assertEquals("existing@example.test", bodies.getFirst().path("email").asText());
            assertEquals("existing-password", bodies.getFirst().path("password").asText());
            var verification = bodies.getLast();
            assertEquals("VERIFY_EMAIL", verification.path("requestType").asText());
            assertEquals("synthetic-verification-token", verification.path("idToken").asText());
            assertFalse(verification.has("password"));
        } finally {
            server.stop(0);
        }
    }
}
