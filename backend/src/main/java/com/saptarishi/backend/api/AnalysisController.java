package com.saptarishi.backend.api;

import com.saptarishi.backend.graph.GraphAnalysis;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.graph.HealthScore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class AnalysisController {

    private final GraphRepository repository;

    public AnalysisController(GraphRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{sha}/analysis")
    public ResponseEntity<GraphAnalysis.Result> get(@PathVariable String sha) {
        return repository.load(sha)
                .map(g -> GraphAnalysis.analyze(
                        g.nodes().stream().map(GraphNode::id).toList(), g.edges()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Health score (0-100) with its breakdown, and the repository's god files. Tests are excluded. */
    @GetMapping("/{sha}/health")
    public ResponseEntity<HealthScore.Result> health(@PathVariable String sha) {
        return repository.load(sha)
                .map(HealthScore::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
