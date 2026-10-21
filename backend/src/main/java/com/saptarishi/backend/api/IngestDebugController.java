package com.saptarishi.backend.api;

import com.saptarishi.backend.ingest.IndexedFile;
import com.saptarishi.backend.ingest.RepoIngestor;
import com.saptarishi.backend.ingest.SafeUnzipper.UnsafeArchiveException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Temporary: lets us test ingestion in the browser. Replaced by POST /api/repos + jobs in step 5. */
@RestController
@RequestMapping("/api/dev")
public class IngestDebugController {

    public record Summary(String owner, String repo, String sha, boolean cached, int fileCount,
                          Map<String, Long> byLanguage, List<IndexedFile> firstFiles) {}

    private final RepoIngestor ingestor;

    public IngestDebugController(RepoIngestor ingestor) { this.ingestor = ingestor; }

    @GetMapping("/ingest")
    public Summary ingest(@RequestParam String url) throws IOException {
        var r = ingestor.ingest(url);
        Map<String, Long> byLang = r.files().stream()
                .collect(Collectors.groupingBy(IndexedFile::language, TreeMap::new, Collectors.counting()));
        return new Summary(r.owner(), r.repo(), r.sha(), r.cached(), r.files().size(), byLang,
                r.files().stream().limit(40).toList());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(UnsafeArchiveException.class)
    ResponseEntity<Map<String, String>> unsafe(UnsafeArchiveException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> upstream(IOException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}