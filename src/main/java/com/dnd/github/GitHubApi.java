package com.dnd.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The few GitHub REST calls campaign sync needs: who am I, my repositories, create a repository. */
public final class GitHubApi {
    static final String API = "https://api.github.com";
    static final String USER_AGENT = "Almerion-Campaign-Manager";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    /** A repository the GM can sync with. {@code empty} is true for a repository with no commits yet. */
    public record Repo(String fullName, String name, boolean isPrivate, String cloneUrl, String description,
                       boolean empty) {
    }

    private final HttpTransport http;
    private final String token;

    public GitHubApi(HttpTransport http, String token) {
        this.http = http;
        this.token = token;
    }

    /** Checks the token and returns the account it belongs to. */
    public GitHubAccount user() throws IOException {
        JsonNode json = call("GET", "/user", null);
        return new GitHubAccount(json.path("id").asLong(), json.path("login").asText(), token);
    }

    /** The GM's own and shared repositories, most recently updated first. */
    public List<Repo> repositories() throws IOException {
        List<Repo> repos = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode json = call("GET", "/user/repos?per_page=" + PAGE_SIZE + "&sort=updated&page=" + page, null);
            for (JsonNode repo : json) repos.add(repo(repo));
            if (json.size() < PAGE_SIZE) break;
        }
        return repos;
    }

    public Repo createPrivateRepo(String name, String description) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        body.put("description", description);
        body.put("private", true);
        body.put("auto_init", false);
        return repo(call("POST", "/user/repos", body.toString()));
    }

    private static Repo repo(JsonNode json) {
        return new Repo(json.path("full_name").asText(), json.path("name").asText(),
            json.path("private").asBoolean(), json.path("clone_url").asText(),
            json.path("description").asText(""), json.path("size").asLong() == 0);
    }

    private JsonNode call(String method, String path, String body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", USER_AGENT);
        headers.put("Authorization", "Bearer " + token);
        if (body != null) headers.put("Content-Type", "application/json");
        HttpTransport.Response response = http.send(method, API + path, headers, body);
        JsonNode json;
        try {
            json = JSON.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
        } catch (IOException e) {
            throw new GitHubException("GitHub sent an unexpected reply (HTTP " + response.status() + ").");
        }
        if (response.ok()) return json;
        if (response.status() == 401) {
            throw new GitHubException("GitHub didn't accept the saved sign-in. Link your GitHub account again.", true);
        }
        if (response.status() == 422 && "POST".equals(method)) {
            throw new GitHubException("GitHub couldn't create the repository: a repository with that name"
                + " probably exists already. Pick another name or link the existing one.");
        }
        if (response.status() == 403 || response.status() == 404) {
            throw new GitHubException("GitHub refused the request (HTTP " + response.status() + "). If you pasted"
                + " a token, make sure it has access to your repositories with Contents and Administration"
                + " set to Read and write.");
        }
        String message = json.path("message").asText("");
        throw new GitHubException("GitHub request failed (HTTP " + response.status() + ")"
            + (message.isBlank() ? "." : ": " + message));
    }
}
