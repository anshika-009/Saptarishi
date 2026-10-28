package com.saptarishi.backend.pathfinder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private static final int MAX_ISSUES = 50;

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

    /**
     * Label groups, searched one call each. GitHub allows at most five AND/OR/NOT operators in a query and
     * every comma in a label list counts as an OR, so one long list does not fit. Label matching is
     * case-insensitive.
     */
    private static final List<List<String>> LABEL_GROUPS = List.of(
            List.of("good first issue", "good-first-issue", "good first bug", "first-timers-only", "beginner"),
            List.of("help wanted", "help-wanted", "beginner-friendly", "easy", "up-for-grabs"));

    public List<Issue> fetch(String owner, String repo) throws IOException {
        Map<Integer, Issue> byNumber = new LinkedHashMap<>();
        IOException failure = null;
        int ok = 0;
        for (List<String> group : LABEL_GROUPS) {
            try {
                for (Issue i : search(owner, repo, group)) byNumber.putIfAbsent(i.number(), i);
                ok++;
            } catch (IOException e) {
                failure = e;
                if (e.getMessage() != null && e.getMessage().contains("rate limit")) break;
            }
        }
        if (ok == 0 && failure != null) throw failure; // every call failed: tell the user why
        return byNumber.values().stream().limit(MAX_ISSUES).toList();
    }

    private List<Issue> search(String owner, String repo, List<String> labels) throws IOException {
        String q = "repo:" + owner + "/" + repo + " is:issue is:open no:assignee label:"
                + labels.stream().map(l -> "\"" + l + "\"").collect(Collectors.joining(","));
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
            if (m.get("pull_request") != null) continue; // is:issue already excludes these; belt and braces
            List<String> names = new ArrayList<>();
            if (m.get("labels") instanceof List<?> ls) {
                for (Object l : ls) {
                    if (l instanceof Map<?, ?> lm && lm.get("name") != null) names.add(lm.get("name").toString());
                }
            }
            String text = str(m.get("body"));
            out.add(new Issue(num(m.get("number")), str(m.get("title")),
                    text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text,
                    str(m.get("html_url")), names, num(m.get("comments")), str(m.get("created_at"))));
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