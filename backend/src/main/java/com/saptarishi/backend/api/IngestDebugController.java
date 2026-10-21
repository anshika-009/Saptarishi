package com.saptarishi.backend.api;

import com.saptarishi.backend.graph.GraphBuilder;
import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.graph.GraphService;
import com.saptarishi.backend.ingest.GithubUrl;
import com.saptarishi.backend.ingest.IndexedFile;
import com.saptarishi.backend.ingest.RepoIngestor;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.RepoParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev")
public class IngestDebugController {

    public record Summary(String owner, String repo, String sha, boolean cached, int fileCount,
                          Map<String, Long> byLanguage, List<IndexedFile> firstFiles) {}

    public record ParseSummary(String sha, int files, int parsedJava, int unparsed,
                               long totalTokens, int totalComplexity, long millis,
                               List<ParsedFile> topComplex) {}

    private final RepoIngestor ingestor;
    private final RepoParser parser;
    private final GraphService graphService;
    private final GraphRepository repository;

    public IngestDebugController(RepoIngestor ingestor, RepoParser parser,
                                 GraphService graphService, GraphRepository repository) {
        this.ingestor = ingestor;
        this.parser = parser;
        this.graphService = graphService;
        this.repository = repository;
    }

    @GetMapping("/ingest")
    public Summary ingest(@RequestParam String url) throws IOException {
        GithubUrl gh = GithubUrl.parse(url);
        var r = ingestor.ingest(gh, ingestor.resolveSha(gh));
        Map<String, Long> byLang = r.files().stream()
                .collect(Collectors.groupingBy(IndexedFile::language, TreeMap::new, Collectors.counting()));
        return new Summary(r.owner(), r.repo(), r.sha(), r.cached(), r.files().size(), byLang,
                r.files().stream().limit(40).toList());
    }

    @GetMapping("/parse")
    public ParseSummary parse(@RequestParam String url) throws IOException {
        GithubUrl gh = GithubUrl.parse(url);
        var r = ingestor.ingest(gh, ingestor.resolveSha(gh));
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
    public ResponseEntity<GraphBuilder.Graph> graph(@RequestParam String url) throws IOException {
        GithubUrl gh = GithubUrl.parse(url);
        String sha = ingestor.resolveSha(gh);
        if (!repository.exists(sha)) {
            graphService.analyze(gh, sha, (stage, percent) -> {});
        }
        return repository.load(sha)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}