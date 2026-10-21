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

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String token;

    public GithubClient(@Value("${saptarishi.github.token:}") String token) {
        this.token = token;
    }

    /** Latest commit SHA of the default branch. This is our cache key. */
    public String resolveHeadSha(String owner, String repo) throws IOException {
        HttpRequest req = request("https://api.github.com/repos/" + owner + "/" + repo + "/commits/HEAD")
                .header("Accept", "application/vnd.github.sha") // returns just the SHA as plain text
                .GET().build();
        HttpResponse<String> res = send(req, HttpResponse.BodyHandlers.ofString());
        checkStatus(res.statusCode());
        String sha = res.body().trim();
        if (!SHA.matcher(sha).matches()) throw new IOException("Unexpected response from GitHub");
        return sha;
    }

    /** Streams the repo zip at a fixed commit to a file, with a size cap. */
    public void downloadZip(String owner, String repo, String sha, Path target) throws IOException {
        HttpRequest req = request("https://codeload.github.com/" + owner + "/" + repo + "/zip/" + sha).GET().build();
        HttpResponse<InputStream> res = send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            checkStatus(res.statusCode());
            try (OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Repository archive is too large");
                    out.write(buf, 0, n);
                }
            }
        }
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "saptarishi");
        if (!token.isBlank()) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private void checkStatus(int status) throws IOException {
        if (status == 200) return;
        if (status == 404) throw new IllegalArgumentException("Repository not found, or it is private");
        if (status == 403 || status == 429)
            throw new IOException("GitHub rate limit hit. Set GITHUB_TOKEN to raise the limit.");
        throw new IOException("GitHub returned HTTP " + status);
    }
}