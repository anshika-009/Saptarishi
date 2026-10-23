package com.saptarishi.backend.pathfinder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Open, unassigned beginner-friendly issues of a public repo: one GitHub search call. */
@Component
public class IssueSource {

    public record Issue(int number, String title, String body, String url,
                        List<String> labels, int comments, String createdAt) {}

    private static final int MAX_BODY = 20_000;

    private final RestClient http;

    public IssueSource(@Value("${saptarishi.github.token:}") String token) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "saptarishi");
        if (!token.isBlank()) builder.defaultHeader("Authorization", "Bearer " + token);
        this.http = builder.build();
    }

    public List<Issue> fetch(String owner, String repo) throws IOException {
        String q = "repo:" + owner + "/" + repo
                + " is:issue is:open no:assignee label:\"good first issue\",\"good-first-issue\",\"help wanted\"";
        Map<String, Object> body;
        try {
            body = http.get()
                    .uri("/search/issues?q={q}&sort=updated&per_page=50", q)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 403 || status == 429) {
                throw new IOException("GitHub rate limit reached. Try again later, or set GITHUB_TOKEN on the server.");
            }
            throw new IOException("GitHub returned HTTP " + status + " while searching issues");
        } catch (RestClientException e) {
            throw new IOException("Could not reach GitHub", e);
        }

        List<Issue> out = new ArrayList<>();
        if (body == null || !(body.get("items") instanceof List<?> items)) return out;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            List<String> labels = new ArrayList<>();
            if (m.get("labels") instanceof List<?> ls) {
                for (Object l : ls) {
                    if (l instanceof Map<?, ?> lm && lm.get("name") != null) labels.add(lm.get("name").toString());
                }
            }
            String text = str(m.get("body"));
            out.add(new Issue(num(m.get("number")), str(m.get("title")),
                    text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text,
                    str(m.get("html_url")), labels, num(m.get("comments")), str(m.get("created_at"))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}