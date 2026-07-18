package com.ephemeral.file;

import com.ephemeral.config.AppProperties;
import com.ephemeral.web.ApiException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.UUID;

/** Stores uploaded blobs on disk under a configurable directory. Key = attachment id. */
@Service
public class StorageService {

    private final Path root;

    public StorageService(AppProperties props) {
        this.root = Paths.get(props.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create storage dir " + root, e);
        }
    }

    public String store(MultipartFile file, UUID id) {
        Path dest = root.resolve(id.toString());
        try {
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store upload", e);
        }
        return id.toString();
    }

    public Resource loadResource(String storageKey) {
        Path p = root.resolve(storageKey);
        if (!Files.exists(p)) {
            throw ApiException.notFound("file not found");
        }
        return new FileSystemResource(p);
    }

    public void deleteAll(Collection<String> keys) {
        for (String key : keys) {
            try {
                Files.deleteIfExists(root.resolve(key));
            } catch (IOException ignored) {
                // best-effort; the orphan reconciliation sweep is the backstop
            }
        }
    }

    /** All blob keys (filenames) currently on disk. */
    public java.util.List<String> listStoredKeys() {
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list storage dir", e);
        }
    }

    public java.time.Instant lastModified(String key) {
        try {
            return Files.getLastModifiedTime(root.resolve(key)).toInstant();
        } catch (IOException e) {
            return java.time.Instant.EPOCH;
        }
    }

    public Path root() {
        return root;
    }
}
