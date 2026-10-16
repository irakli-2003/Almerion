package com.dnd.cloud;

import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** {@link CloudStore} backed by the Firebase Realtime Database REST API. */
public class FirebaseCloudStore implements CloudStore {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final ObjectMapper mapper = JsonMappers.create();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;

    /**
     * @param databaseUrl e.g. {@code https://x-default-rtdb.europe-west1.firebasedatabase.app}
     * @param rootPath    node every path is relative to, e.g. {@code sessions/dnd-directory}
     */
    public FirebaseCloudStore(String databaseUrl, String rootPath) {
        String root = rootPath == null ? "" : rootPath.replaceAll("^/+|/+$", "");
        this.baseUrl = databaseUrl.replaceAll("/+$", "") + (root.isEmpty() ? "" : "/" + root);
    }

    private URI uri(String path) {
        String clean = path == null ? "" : path.replaceAll("^/+|/+$", "");
        return URI.create(baseUrl + (clean.isEmpty() ? "" : "/" + clean) + ".json");
    }

    @Override
    public JsonNode get(String path) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT).GET().build());
        try {
            JsonNode node = mapper.readTree(response.body());
            return node == null || node.isNull() ? null : node;
        } catch (IOException e) {
            throw new CloudException("Unreadable reply from the online directory", false, e);
        }
    }

    @Override
    public void put(String path, Object value) {
        String body;
        try {
            body = mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new CloudException("Could not serialise " + path, false, e);
        }
        send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    @Override
    public void delete(String path) {
        send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT).DELETE().build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CloudException("Can't reach the online directory (are you offline?)", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException("Request interrupted", true, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new CloudException("Online directory refused the request (HTTP "
                + response.statusCode() + "): " + response.body(), false);
        }
        return response;
    }
}
