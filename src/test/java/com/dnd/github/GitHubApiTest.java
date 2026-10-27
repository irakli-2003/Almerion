package com.dnd.github;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitHubApiTest {

    @Test
    public void userIsReadWithTheBearerToken() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, "{\"id\":7,\"login\":\"gm\"}");
        GitHubAccount account = new GitHubApi(http, "tok").user();
        assertEquals("gm", account.login());
        assertEquals(7, account.id());
        assertEquals("https://api.github.com/user", http.requests.get(0).url());
        assertEquals("Bearer tok", http.requests.get(0).headers().get("Authorization"));
    }

    @Test
    public void rejectedTokensAreAuthFailures() throws Exception {
        try {
            new GitHubApi(new FakeHttp().reply(401, "{\"message\":\"Bad credentials\"}"), "tok").user();
            fail();
        } catch (GitHubException e) {
            assertTrue(e.isAuthFailure());
        }
    }

    @Test
    public void createsPrivateRepositories() throws Exception {
        FakeHttp http = new FakeHttp().reply(201, "{\"full_name\":\"gm/almerion-campaign-x\",\"name\":"
            + "\"almerion-campaign-x\",\"private\":true,\"clone_url\":\"https://github.com/gm/almerion-campaign-x.git\","
            + "\"size\":0}");
        GitHubApi.Repo repo = new GitHubApi(http, "tok").createPrivateRepo("almerion-campaign-x", "Campaign");
        assertTrue(repo.isPrivate());
        assertTrue(repo.empty());
        String body = http.requests.get(0).body();
        assertTrue(body.contains("\"private\":true"));
        assertTrue(body.contains("\"auto_init\":false"));
    }

    @Test
    public void repositoriesFollowPages() throws Exception {
        StringBuilder full = new StringBuilder("[");
        for (int i = 0; i < 100; i++) full.append(i == 0 ? "" : ",").append("{\"full_name\":\"gm/r").append(i).append("\"}");
        full.append("]");
        FakeHttp http = new FakeHttp().reply(200, full.toString()).reply(200, "[{\"full_name\":\"gm/last\"}]");
        assertEquals(101, new GitHubApi(http, "tok").repositories().size());
        assertTrue(http.requests.get(1).url().contains("page=2"));
    }
}
