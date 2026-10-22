package com.saptarishi.backend.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FileService {

    public static final int MAX_BYTES = 512 * 1024;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    public record FileView(String path, String language, String content, boolean truncated,
                           int tokens, int complexity, int loc,
                           List<String> imports, List<String> importedBy) {}

    private record Meta(String language, int tokens, int complexity, int loc) {}

    private final JdbcTemplate jdbc;
    private final Path reposRoot;

    public FileService(JdbcTemplate jdbc, @Value("${saptarishi.storage-dir}") String storageDir) {
        this.jdbc = jdbc;
        this.reposRoot = Path.of(storageDir).toAbsolutePath().normalize().resolve("repos");
    }

    /**
     * Empty if the sha or path is unknown. Only paths stored in the database are ever read,
     * and the resolved file must stay inside the repo's source folder.
     * Throws NoSuchFileException if the database knows the file but the cached source is gone.
     */
    public Optional<FileView> read(String sha, String path) throws IOException {
        if (!SHA.matcher(sha).matches()) return Optional.empty();

        List<Meta> rows = jdbc.query(
                "select language, tokens, complexity, loc from source_file where sha = ? and path = ?",
                (rs, i) -> new Meta(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                sha, path);
        if (rows.isEmpty()) return Optional.empty();
        Meta m = rows.get(0);

        Path src = reposRoot.resolve(sha).resolve("src");
        Path file = src.resolve(path).normalize();
        if (!file.startsWith(src) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path);
        }

        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        boolean truncated = bytes.length > MAX_BYTES;
        String content = new String(bytes, 0, Math.min(bytes.length, MAX_BYTES), StandardCharsets.UTF_8);

        List<String> imports = jdbc.queryForList(
                "select target from edge where sha = ? and source = ? order by target", String.class, sha, path);
        List<String> importedBy = jdbc.queryForList(
                "select source from edge where sha = ? and target = ? order by source", String.class, sha, path);

        return Optional.of(new FileView(path, m.language(), content, truncated,
                m.tokens(), m.complexity(), m.loc(), imports, importedBy));
    }
}