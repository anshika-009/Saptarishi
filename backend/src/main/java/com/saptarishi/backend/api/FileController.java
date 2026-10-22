package com.saptarishi.backend.api;

import com.saptarishi.backend.file.FileService;
import com.saptarishi.backend.file.FileService.FileView;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @GetMapping("/{sha}/file")
    public ResponseEntity<FileView> get(@PathVariable String sha, @RequestParam String path) throws IOException {
        return files.read(sha, path)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(NoSuchFileException.class)
    ResponseEntity<Map<String, String>> gone(NoSuchFileException e) {
        return ResponseEntity.status(410)
                .body(Map.of("error", "Source is no longer cached. Analyse the repository again."));
    }
}