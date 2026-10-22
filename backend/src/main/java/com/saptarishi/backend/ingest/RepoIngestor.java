package com.saptarishi.backend.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

@Service
public class RepoIngestor {

    public record IngestResult(String owner, String repo, String sha, boolean cached,
                               Path sourceDir, List<IndexedFile> files) {}

    @FunctionalInterface
    private interface Fetcher {
        void fetch(Path zip) throws IOException;
    }

    private final GithubClient github;
    private final SourceFilter filter;
    private final SafeUnzipper unzipper;
    private final Path storageRoot;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public RepoIngestor(GithubClient github, SourceFilter filter,
                        @Value("${saptarishi.storage-dir}") String storageDir) {
        this.github = github;
        this.filter = filter;
        this.unzipper = new SafeUnzipper(SafeUnzipper.Limits.defaults(), filter);
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public String resolveSha(GithubUrl gh) throws IOException {
        return github.resolveHeadSha(gh.owner(), gh.repo());
    }

    public IngestResult ingest(GithubUrl gh, String sha) throws IOException {
        return ingest(gh.owner(), gh.repo(), sha,
                zip -> github.downloadZip(gh.owner(), gh.repo(), sha, zip), false);
    }

    /** sha is derived from the file content by the caller. May move the uploaded file, so callers delete it afterwards if it still exists. */
    public IngestResult ingestZip(String name, String sha, Path uploaded) throws IOException {
        try {
            return ingest("upload", name, sha,
                    zip -> Files.move(uploaded, zip, StandardCopyOption.REPLACE_EXISTING), true);
        } catch (ZipException e) {
            throw new IllegalArgumentException("The file is not a valid zip archive");
        }
    }

    private IngestResult ingest(String owner, String repo, String sha, Fetcher fetcher, boolean uploaded)
            throws IOException {
        Path repoDir = storageRoot.resolve("repos").resolve(sha);
        Path src = repoDir.resolve("src");
        Path done = repoDir.resolve(".done");

        // One ingest per SHA at a time, so two requests don't write the same folder.
        synchronized (locks.computeIfAbsent(sha, k -> new Object())) {
            if (Files.exists(done)) {
                return new IngestResult(owner, repo, sha, true, src, index(src));
            }
            Path zip = Files.createTempFile("saptarishi-", ".zip");
            try {
                FileSystemUtils.deleteRecursively(repoDir); // clear any half-finished earlier attempt
                fetcher.fetch(zip);
                unzipper.extract(zip, src, !uploaded || hasSingleRoot(zip));
                Files.createFile(done);                     // marker written last = extraction finished
            } catch (IOException | RuntimeException ex) {
                FileSystemUtils.deleteRecursively(repoDir);
                throw ex;
            } finally {
                Files.deleteIfExists(zip);
            }
            return new IngestResult(owner, repo, sha, false, src, index(src));
        }
    }

    /** True if every entry (ignoring macOS resource forks) sits under one shared top-level folder. */
    private static boolean hasSingleRoot(Path zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            if (zf.size() > SafeUnzipper.Limits.defaults().maxEntries()) {
                throw new SafeUnzipper.UnsafeArchiveException("Too many entries in archive");
            }
            String root = null;
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/")) continue;
                int slash = name.indexOf('/');
                if (slash < 0) return false;
                String top = name.substring(0, slash);
                if (root == null) root = top;
                else if (!root.equals(top)) return false;
            }
            return root != null;
        }
    }

    /** Walks the extracted folder and records path, language, size. */
    private List<IndexedFile> index(Path src) throws IOException {
        List<Path> paths;
        try (Stream<Path> s = Files.walk(src)) {
            paths = s.filter(Files::isRegularFile).toList();
        }
        List<IndexedFile> out = new ArrayList<>(paths.size());
        for (Path p : paths) {
            String rel = src.relativize(p).toString().replace('\\', '/');
            String lang = filter.classify(rel);
            out.add(new IndexedFile(rel, lang == null ? "text" : lang, Files.size(p)));
        }
        out.sort(Comparator.comparing(IndexedFile::path));
        return out;
    }
}