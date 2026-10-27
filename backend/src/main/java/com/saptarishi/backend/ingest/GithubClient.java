
package com.saptarishi.backend.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GithubClient {

    private static final Pattern SHA =
            Pattern.compile("^[0-9a-f]{40}$");

    private static final long MAX_DOWNLOAD_BYTES =
            200L * 1024 * 1024; // 200 MB

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String token;

    public GithubClient(
            @Value("${saptarishi.github.token:}") String token) {
        this.token = token == null ? "" : token.trim();

        // Never print the token itself.
        System.out.println(
                "GitHub token configured: " + !this.token.isBlank());
    }

    /**
     * Latest commit SHA of the repository's default branch.
     */
    public String resolveHeadSha(String owner, String repo)
            throws IOException {

        String url = "https://api.github.com/repos/"
                + owner + "/" + repo + "/commits/HEAD";

        HttpRequest req = request(url)
                .header("Accept", "application/vnd.github.sha")
                .GET()
                .build();

        HttpResponse<String> res =
                send(req, HttpResponse.BodyHandlers.ofString());

        checkStatus(res.statusCode(), res);

        String sha = res.body().trim();

        if (!SHA.matcher(sha).matches()) {
            throw new IOException(
                    "Unexpected response from GitHub while resolving commit SHA");
        }

        return sha;
    }

    /**
     * Downloads a repository ZIP at a fixed commit SHA.
     */
    public void downloadZip(
            String owner,
            String repo,
            String sha,
            Path target) throws IOException {

        if (!SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("Invalid commit SHA");
        }

        String url = "https://codeload.github.com/"
                + owner + "/" + repo + "/zip/" + sha;

        HttpRequest req = request(url)
                .GET()
                .build();

        HttpResponse<InputStream> res =
                send(req, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream in = res.body()) {
            checkStatus(res.statusCode(), res);

            try (OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int n;

                while ((n = in.read(buffer)) != -1) {
                    total += n;

                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException(
                                "Repository archive exceeds the 200 MB limit");
                    }

                    out.write(buffer, 0, n);
                }
            }
        }
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "saptarishi")
                .header("Accept", "application/vnd.github+json");

        // GitHub API calls use the configured token.
        // codeload.github.com downloads are public-repository archives.
        if (!token.isBlank()
                && url.startsWith("https://api.github.com/")) {
            builder.header("Authorization", "Bearer " + token);
        }

        return builder;
    }

    private <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler) throws IOException {

        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub request was interrupted", e);
        }
    }

    private void checkStatus(
            int status,
            HttpResponse<?> response) throws IOException {

        if (status == 200) {
            return;
        }

        if (status == 404) {
            throw new IllegalArgumentException(
                    "Repository not found, or it is private");
        }

        if (status == 401) {
            throw new IOException(
                    "GitHub rejected the token (HTTP 401). "
                    + "Check that the token is valid and Spring Boot received it.");
        }

        if (status == 403 || status == 429) {
            String remaining = response.headers()
                    .firstValue("X-RateLimit-Remaining")
                    .orElse("unknown");

            String limit = response.headers()
                    .firstValue("X-RateLimit-Limit")
                    .orElse("unknown");

            String reset = response.headers()
                    .firstValue("X-RateLimit-Reset")
                    .orElse("unknown");

            String message = "GitHub returned HTTP " + status
                    + ". Rate-limit remaining: " + remaining
                    + ", limit: " + limit
                    + ", reset (Unix timestamp): " + reset
                    + ". Check token permissions and GitHub's rate-limit response.";

            throw new IOException(message);
        }

        throw new IOException("GitHub returned HTTP " + status);
    }
}
