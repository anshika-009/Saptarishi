package com.saptarishi.backend.api;

import com.saptarishi.backend.job.JobService;
import com.saptarishi.backend.job.JobStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final JobService jobs;

    public UploadController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobStatus> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        if (file.isEmpty() || original == null || !original.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Please upload a .zip file");
        }
        
        Path tmp = Files.createTempFile("saptarishi-upload-", ".zip");
        String sha;
        try {
            sha = copyAndHash(file, tmp);
            if (!looksLikeZip(tmp)) throw new IllegalArgumentException("The file is not a valid zip archive");
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        JobStatus status = jobs.submitUpload(displayName(original), sha, tmp);
        return status.state() == JobStatus.State.DONE
                ? ResponseEntity.ok(status)
                : ResponseEntity.accepted().body(status);
    }

    /** Cache key: first 40 hex chars of the content's SHA-256, same shape as a git commit SHA. */
    private static String copyAndHash(MultipartFile file, Path target) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(file.getInputStream(), md)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(md.digest()).substring(0, 40);
    }

    private static boolean looksLikeZip(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] h = in.readNBytes(4);
            return h.length == 4 && h[0] == 'P' && h[1] == 'K'
                    && ((h[2] == 3 && h[3] == 4) || (h[2] == 5 && h[3] == 6));
        }
    }

    private static String displayName(String original) {
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        name = UNSAFE.matcher(name).replaceAll("_");
        if (name.length() > 100) name = name.substring(0, 100);
        return name.isBlank() ? "archive" : name;
    }
}