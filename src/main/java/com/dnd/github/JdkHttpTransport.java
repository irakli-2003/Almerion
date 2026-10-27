package com.dnd.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** {@link HttpTransport} backed by the JDK's own HTTP client. */
public final class JdkHttpTransport implements HttpTransport {
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Override
    public Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        headers.forEach(request::header);
        request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
