package com.saptarishi.backend.api;

import com.saptarishi.backend.search.SearchService;
import com.saptarishi.backend.search.SearchService.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/{sha}/search")
    public Results get(@PathVariable String sha, @RequestParam String q) {
        return search.search(sha, q);
    }
}