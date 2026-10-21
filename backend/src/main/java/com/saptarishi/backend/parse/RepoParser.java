package com.saptarishi.backend.parse;

import com.saptarishi.backend.ingest.IndexedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.springframework.stereotype.Service;

@Service
public class RepoParser {

    private final ParserRegistry registry;

    public RepoParser(ParserRegistry registry) { this.registry = registry; }

    public List<ParsedFile> parseAll(Path srcDir, List<IndexedFile> files) {
        return parseAll(srcDir, files, done -> {});
    }

    public List<ParsedFile> parseAll(Path srcDir, List<IndexedFile> files, IntConsumer onFileDone) {
        AtomicInteger finished = new AtomicInteger();
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ParsedFile>> futures = new ArrayList<>(files.size());
            for (IndexedFile f : files) {
                futures.add(pool.submit(() -> {
                    try {
                        return parseOne(srcDir, f);
                    } finally {
                        onFileDone.accept(finished.incrementAndGet());
                    }
                }));
            }
            List<ParsedFile> out = new ArrayList<>(files.size());
            for (int i = 0; i < futures.size(); i++) {
                IndexedFile f = files.get(i);
                try {
                    out.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    out.add(ParsedFile.unparsed(f.path(), f.language(), 0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Parsing interrupted", e);
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private ParsedFile parseOne(Path srcDir, IndexedFile f) {
        LanguageSupport plugin = registry.forLanguage(f.language());
        if (plugin == null) {
            // No parser for this language: plain node, not "broken".
            return new ParsedFile(f.path(), f.language(), "", List.of(), List.of(), 0, 0, 0, true);
        }
        try {
            String content = Files.readString(srcDir.resolve(f.path()), StandardCharsets.UTF_8);
            return plugin.parse(f.path(), content);
        } catch (IOException e) { // e.g. not valid UTF-8
            return ParsedFile.unparsed(f.path(), f.language(), 0);
        }
    }
}