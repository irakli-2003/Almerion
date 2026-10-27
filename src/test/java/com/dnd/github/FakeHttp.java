package com.dnd.github;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Replays canned HTTP responses and records what was sent. */
class FakeHttp implements HttpTransport {
    record Request(String method, String url, Map<String, String> headers, String body) {
    }

    final List<Request> requests = new ArrayList<>();
    private final Deque<Response> responses = new ArrayDeque<>();

    FakeHttp reply(int status, String body) {
        responses.add(new Response(status, body));
        return this;
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, String body) {
        requests.add(new Request(method, url, headers, body));
        if (responses.isEmpty()) throw new AssertionError("Unexpected request: " + method + " " + url);
        return responses.poll();
    }
}
