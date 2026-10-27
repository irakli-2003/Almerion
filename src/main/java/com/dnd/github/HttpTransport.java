package com.dnd.github;

import java.io.IOException;
import java.util.Map;

/** The tiny slice of HTTP the GitHub client needs, so tests can swap in a fake. */
public interface HttpTransport {
    record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    Response send(String method, String url, Map<String, String> headers, String body) throws IOException;
}
