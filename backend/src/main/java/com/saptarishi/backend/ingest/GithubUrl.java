package com.saptarishi.backend.ingest;

import java.net.URI;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Parsed, validated GitHub repo reference.
 *  We never fetch the user's URL, we only build our own from owner/repo. */
public record GithubUrl(String owner, String repo) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    public static GithubUrl parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("URL is empty");
        String s = raw.trim();
        if (!s.contains("://")) s = "https://" + s;

        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed URL");
        }

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
            throw new IllegalArgumentException("Only http(s) URLs are allowed");

        String host = uri.getHost();
        if (host == null || !(host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("www.github.com")))
            throw new IllegalArgumentException("Only github.com repositories are supported");

        String[] parts = Arrays.stream(uri.getPath() == null ? new String[0] : uri.getPath().split("/"))
                .filter(p -> !p.isEmpty()).toArray(String[]::new);
        if (parts.length < 2) throw new IllegalArgumentException("URL must look like https://github.com/owner/repo");

        String owner = parts[0];
        String repo = parts[1].endsWith(".git") ? parts[1].substring(0, parts[1].length() - 4) : parts[1];

        for (String n : new String[] {owner, repo}) {
            if (!NAME.matcher(n).matches() || n.equals(".") || n.equals(".."))
                throw new IllegalArgumentException("Invalid owner or repository name");
        }
        return new GithubUrl(owner, repo);
    }
}