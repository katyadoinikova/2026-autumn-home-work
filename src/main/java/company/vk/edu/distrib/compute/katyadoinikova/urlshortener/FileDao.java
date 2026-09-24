package company.vk.edu.distrib.compute.katyadoinikova.urlshortener;

import company.vk.edu.distrib.compute.Dao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.NoSuchElementException;

public final class FileDao implements Dao<String> {
    private final Path directory;
    private boolean closed;

    public FileDao(Path directory) throws IOException {
        this.directory = directory;
        this.closed = false;
        Files.createDirectories(directory);
    }

    @Override
    public synchronized String get(String key) throws IOException {
        ensureOpen();
        try {
            return Files.readString(fileFor(key), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("Key is absent", e);
        }
    }

    @Override
    public synchronized void upsert(String key, String value) throws IOException {
        ensureOpen();
        Path temporary = Files.createTempFile(directory, "value-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, fileFor(key), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void delete(String key) throws IOException {
        ensureOpen();
        Files.deleteIfExists(fileFor(key));
    }

    @Override
    public synchronized void close() {
        this.closed = true;
    }

    public synchronized boolean isAvailable() {
        return !this.closed && Files.isDirectory(directory) && Files.isWritable(directory);
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("DAO is closed");
        }
    }

    private Path fileFor(String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Key must not be empty");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return directory.resolve(encoded);
    }
}
