package com.saptarishi.backend.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

@Service
public class RepoIngestor {

    public record IngestResult(String owner, String repo, String sha, boolean cached,
                               Path sourceDir, List<IndexedFile> files) {}

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
        Path repoDir = storageRoot.resolve("repos").resolve(sha);
        Path src = repoDir.resolve("src");
        Path done = repoDir.resolve(".done");

        // One ingest per SHA at a time, so two requests don't write the same folder.
        synchronized (locks.computeIfAbsent(sha, k -> new Object())) {
            if (Files.exists(done)) {
                return new IngestResult(gh.owner(), gh.repo(), sha, true, src, index(src));
            }
            Path zip = Files.createTempFile("saptarishi-", ".zip");
            try {
                FileSystemUtils.deleteRecursively(repoDir); // clear any half-finished earlier attempt
                github.downloadZip(gh.owner(), gh.repo(), sha, zip);
                unzipper.extract(zip, src, true);
                Files.createFile(done);                     // marker written last = extraction finished
            } catch (IOException | RuntimeException ex) {
                FileSystemUtils.deleteRecursively(repoDir);
                throw ex;
            } finally {
                Files.deleteIfExists(zip);
            }
            return new IngestResult(gh.owner(), gh.repo(), sha, false, src, index(src));
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