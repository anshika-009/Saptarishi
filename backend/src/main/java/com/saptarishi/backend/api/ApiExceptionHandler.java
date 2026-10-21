package com.saptarishi.backend.api;

import com.saptarishi.backend.ingest.SafeUnzipper.UnsafeArchiveException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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

    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<Map<String, String>> busy(RejectedExecutionException e) {
        return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
    }
}