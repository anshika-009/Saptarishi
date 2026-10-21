package com.saptarishi.backend.ingest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SafeUnzipper {

    public record Limits(int maxEntries, long maxTotalBytes, long maxEntryBytes, long maxKeptFileBytes) {
        public static Limits defaults() {
            return new Limits(50_000, 500L << 20, 100L << 20, 1L << 20);
        }
    }

    public static class UnsafeArchiveException extends RuntimeException {
        public UnsafeArchiveException(String msg) { super(msg); }
    }

    private final Limits limits;
    private final SourceFilter filter;

    public SafeUnzipper(Limits limits, SourceFilter filter) {
        this.limits = limits;
        this.filter = filter;
    }

    /**
     * Extracts only the files we keep. Returns how many were written.
     * stripRoot=true removes GitHub's single top-level folder (repo-sha/).
     */
    public int extract(Path zip, Path targetDir, boolean stripRoot) throws IOException {
        Files.createDirectories(targetDir);
        Path root = targetDir.toAbsolutePath().normalize();
        byte[] buf = new byte[16 * 1024];
        int entries = 0, written = 0;
        long total = 0;

        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) throw new UnsafeArchiveException("Too many entries in archive");

                String name = e.getName().replace('\\', '/');
                String rel = name;
                if (stripRoot) {
                    int slash = name.indexOf('/');
                    if (slash < 0) continue;
                    rel = name.substring(slash + 1);
                }
                if (rel.isEmpty() || e.isDirectory()) continue;

                // Zip-slip: the resolved path must stay inside the target folder.
                if (rel.indexOf('\0') >= 0) throw new UnsafeArchiveException("Bad entry name");
                Path out = root.resolve(rel).normalize();
                if (!out.startsWith(root)) throw new UnsafeArchiveException("Unsafe path in archive: " + name);

                boolean keep = filter.classify(rel) != null;
                long entryBytes = 0;
                OutputStream os = null;
                try {
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        entryBytes += n;
                        total += n;
                        // Count real decompressed bytes. Sizes in zip headers can lie.
                        if (entryBytes > limits.maxEntryBytes()) throw new UnsafeArchiveException("Archive entry too large");
                        if (total > limits.maxTotalBytes()) throw new UnsafeArchiveException("Archive expands too large");

                        if (keep && entryBytes > limits.maxKeptFileBytes()) { // too big to keep: drop it, keep draining
                            keep = false;
                            if (os != null) { os.close(); os = null; Files.deleteIfExists(out); written--; }
                        }
                        if (keep) {
                            if (os == null) {
                                Files.createDirectories(out.getParent());
                                os = Files.newOutputStream(out);
                                written++;
                            }
                            os.write(buf, 0, n);
                        }
                    }
                    if (keep && os == null) { // empty file
                        Files.createDirectories(out.getParent());
                        Files.createFile(out);
                        written++;
                    }
                } finally {
                    if (os != null) os.close();
                }
            }
        }
        return written;
    }
}