package com.ephemeral.file;

import com.ephemeral.config.AppProperties;
import com.ephemeral.crypto.CryptoService;
import com.ephemeral.web.ApiException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.UUID;

/**
 * Stores uploaded blobs on disk under a configurable directory, encrypted at
 * rest (AES-GCM via {@link CryptoService}). Key = attachment id.
 */
@Service
public class StorageService {

    private final Path root;
    private final CryptoService crypto;

    public StorageService(AppProperties props, CryptoService crypto) {
        this.root = Paths.get(props.getStorageDir()).toAbsolutePath().normalize();
        this.crypto = crypto;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create storage dir " + root, e);
        }
    }

    public String store(MultipartFile file, UUID id) {
        Path dest = root.resolve(id.toString());
        try (OutputStream out = crypto.encrypting(Files.newOutputStream(dest))) {
            file.getInputStream().transferTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store upload", e);
        }
        return id.toString();
    }

    /**
     * The decrypted blob as a one-shot stream. {@code plainSize} (from the
     * attachments row) is the pre-encryption length, so callers can set
     * Content-Length without consuming the stream.
     */
    public Resource loadResource(String storageKey, long plainSize) {
        Path p = root.resolve(storageKey);
        if (!Files.exists(p)) {
            throw ApiException.notFound("file not found");
        }
        try {
            return new InputStreamResource(crypto.decrypting(Files.newInputStream(p))) {
                @Override
                public long contentLength() {
                    return plainSize;
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read blob", e);
        }
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
