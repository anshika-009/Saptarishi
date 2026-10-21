package com.saptarishi.backend.api;

import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.RepoParser;
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
import com.saptarishi.backend.graph.GraphBuilder;

/** Temporary: lets us test ingestion in the browser. Replaced by POST /api/repos + jobs in step 5. */
@RestController
@RequestMapping("/api/dev")
public class IngestDebugController {

    public record Summary(String owner, String repo, String sha, boolean cached, int fileCount,
                          Map<String, Long> byLanguage, List<IndexedFile> firstFiles) {}
    private final RepoIngestor ingestor;
    private final RepoParser parser;
    private final GraphBuilder graphBuilder;

    public IngestDebugController(RepoIngestor ingestor, RepoParser parser, GraphBuilder graphBuilder) {
        this.ingestor = ingestor;
        this.parser = parser;
        this.graphBuilder = graphBuilder;
    }
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

    public record ParseSummary(String sha, int files, int parsedJava, int unparsed,
                           long totalTokens, int totalComplexity, long millis,
                           List<ParsedFile> topComplex) {}

    @GetMapping("/parse")
    public ParseSummary parse(@RequestParam String url) throws IOException {
        var r = ingestor.ingest(url);
        long t0 = System.currentTimeMillis();
        List<ParsedFile> parsed = parser.parseAll(r.sourceDir(), r.files());
        long ms = System.currentTimeMillis() - t0;

        var java = parsed.stream().filter(p -> p.language().equals("java")).toList();
        return new ParseSummary(
            r.sha(), parsed.size(),
            (int) java.stream().filter(ParsedFile::parsed).count(),
            (int) parsed.stream().filter(p -> !p.parsed()).count(),
            java.stream().mapToLong(ParsedFile::tokens).sum(),
            java.stream().mapToInt(ParsedFile::complexity).sum(),
            ms,
            java.stream().sorted((a, b) -> Integer.compare(b.complexity(), a.complexity())).limit(5).toList()
        );
    }

    @GetMapping("/graph")
    public GraphBuilder.Graph graph(@RequestParam String url,
                                @RequestParam(defaultValue = "false") boolean includeText) throws IOException {
        var r = ingestor.ingest(url);
        List<ParsedFile> parsed = parser.parseAll(r.sourceDir(), r.files());
        return graphBuilder.build(parsed, includeText);
    }
}

