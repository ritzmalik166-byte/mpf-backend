package com.mypropertyfact.estate.integration.claude.storage;

import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Private working directory for job files. Uploads are copied here while the request is alive
 * (multipart temp files disappear afterwards) with a hard size cap and a SHA-256 computed on the
 * fly. Only directories created under the configured base are ever deleted.
 */
@Slf4j
@Service
public class ClaudeTemporaryStorageService {

    public static final String EXCEL_FILE = "workbook.bin";
    public static final String ZIP_FILE = "images.zip";

    private final Path base;

    public ClaudeTemporaryStorageService(ClaudeIntegrationProperties props) {
        String configured = props.getTempDir();
        this.base = (configured == null || configured.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "mpf-claude")
                : Paths.get(configured)).toAbsolutePath().normalize();
    }

    public record StoredFile(Path path, String sha256, long size, String originalFilename) {
    }

    public Path base() {
        return base;
    }

    public Path createDir(String name) {
        try {
            Files.createDirectories(base);
            Path dir = base.resolve(name).normalize();
            ensureInside(dir);
            Files.createDirectories(dir);
            restrict(dir);
            return dir;
        } catch (IOException e) {
            throw new ClaudeIntegrationException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Temporary storage is not available");
        }
    }

    public Path jobDir(String jobId) {
        Path dir = base.resolve(jobId).normalize();
        ensureInside(dir);
        return dir;
    }

    public StoredFile store(MultipartFile file, Path dir, String targetName, long maxBytes, String label) {
        Path target = dir.resolve(targetName).normalize();
        ensureInside(target);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = new DigestInputStream(file.getInputStream(), md);
                 OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new ClaudeIntegrationException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                                label + " is larger than the limit of " + maxBytes + " bytes");
                    }
                    out.write(buf, 0, n);
                }
            }
            return new StoredFile(target, HexFormat.of().formatHex(md.digest()), total, file.getOriginalFilename());
        } catch (ClaudeIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "UPLOAD_READ_FAILED", label + " could not be read");
        }
    }

    public void move(Path from, Path toDir) {
        ensureInside(from);
        ensureInside(toDir);
        try {
            Files.createDirectories(toDir.getParent());
            Files.move(from, toDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(from, toDir);
            } catch (IOException e2) {
                throw new ClaudeIntegrationException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Could not stage job files");
            }
        }
    }

    public void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        Path d = dir.toAbsolutePath().normalize();
        if (!d.startsWith(base) || d.equals(base) || !Files.exists(d)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(d)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete temporary file {}", p.getFileName());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean temporary directory {}", d.getFileName());
        }
    }

    private void ensureInside(Path p) {
        if (!p.toAbsolutePath().normalize().startsWith(base)) {
            throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Invalid storage path");
        }
    }

    private static void restrict(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: rely on the per-user temp directory ACLs.
        }
    }
}
