package com.smartlib.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.smartlib.common.config.AppProperties;

/** Stores uploaded files under a private directory (never web-served directly). Keys are random. */
@Service
public class StorageService {

    private final Path root;

    public StorageService(AppProperties properties) {
        this.root = Paths.get(properties.storage().dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + root, e);
        }
    }

    public String store(String folder, InputStream in, String extension) {
        String key = folder + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file", e);
        }
        return key;
    }

    public Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }

    public boolean exists(String key) {
        return key != null && Files.isRegularFile(resolve(key));
    }

    public Resource resource(String key) {
        return new FileSystemResource(resolve(key));
    }

    public void delete(String key) {
        if (key == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
