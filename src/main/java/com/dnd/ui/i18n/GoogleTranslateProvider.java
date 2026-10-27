package com.dnd.ui.i18n;

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

/**
 * Google Translate's free, keyless web endpoint (the one used by the browser widget). The text
 * is POSTed as a form field so long batches don't hit URL length limits.
 */
public final class GoogleTranslateProvider implements TranslationProvider {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&dt=t&tl=";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;

    public GoogleTranslateProvider() {
        // Over HTTP/2 the endpoint answers every request with 429 (rate limited); HTTP/1.1 works.
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    GoogleTranslateProvider(HttpClient http) {
        this.http = http;
    }

    @Override
    public String translate(String text, String targetLanguage) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + URLEncoder.encode(targetLanguage, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("User-Agent", "Mozilla/5.0")
            .POST(HttpRequest.BodyPublishers.ofString("q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)))
            .build();
        HttpResponse<String> response = null;
        try {
            // The endpoint sporadically answers 429 to an otherwise fine request; a retry succeeds.
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) Thread.sleep(400L * attempt);
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 429 && response.statusCode() < 500) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Google Translate answered HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /** The response is {@code [[["translated","source",...],...],...]}: one entry per sentence. */
    static String parse(String body) throws IOException {
        JsonNode root = JSON.readTree(body);
        JsonNode segments = root == null ? null : root.get(0);
        if (segments == null || !segments.isArray()) throw new IOException("Unexpected Google Translate response");
        StringBuilder out = new StringBuilder();
        for (JsonNode segment : segments) {
            JsonNode part = segment.get(0);
            if (part != null && part.isTextual()) out.append(part.asText());
        }
        return out.toString();
    }
}
