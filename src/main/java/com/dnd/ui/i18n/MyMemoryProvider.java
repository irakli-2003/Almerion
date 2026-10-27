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
 * MyMemory's free API, used when Google can't be reached. It accepts at most ~500 bytes per
 * request, so multi-line text is translated one line at a time.
 */
public final class MyMemoryProvider implements TranslationProvider {
    private static final String ENDPOINT = "https://api.mymemory.translated.net/get";
    private static final int MAX_BYTES = 480;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public String translate(String text, String targetLanguage) throws IOException {
        if (text.indexOf('\n') < 0) return translateLine(text, targetLanguage);
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i].isBlank() ? lines[i] : translateLine(lines[i], targetLanguage));
        }
        return out.toString();
    }

    private String translateLine(String text, String targetLanguage) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Text too long for MyMemory");
        String url = ENDPOINT + "?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
            + "&langpair=" + URLEncoder.encode("en|" + code(targetLanguage), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() != 200) throw new IOException("MyMemory answered HTTP " + response.statusCode());
        JsonNode root = JSON.readTree(response.body());
        JsonNode status = root.get("responseStatus");
        JsonNode translated = root.path("responseData").get("translatedText");
        if (status == null || status.asInt() != 200 || translated == null || !translated.isTextual()) {
            throw new IOException("MyMemory could not translate: " + root.path("responseDetails").asText(""));
        }
        return translated.asText();
    }

    private static String code(String googleCode) {
        return switch (googleCode) {
            case "iw" -> "he";
            case "jw" -> "jv";
            case "mni-Mtei" -> "mni";
            default -> googleCode;
        };
    }
}
