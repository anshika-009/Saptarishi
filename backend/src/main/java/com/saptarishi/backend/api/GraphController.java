package com.saptarishi.backend.api;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/graphs")
public class GraphController {

    private final GraphRepository repository;

    public GraphController(GraphRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{sha}")
    public ResponseEntity<Graph> get(@PathVariable String sha) {
        return repository.load(sha)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}