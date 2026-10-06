package company.vk.edu.distrib.compute.katyadoinikova.kv;

import company.vk.edu.distrib.compute.Dao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ByteArrayFileDao implements Dao<byte[]> {
    private final Path directory;
    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock readLock = readWriteLock.readLock();
    private final Lock writeLock = readWriteLock.writeLock();
    private boolean closed;

    public ByteArrayFileDao(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    @Override
    public byte[] get(String key) throws IOException {
        readLock.lock();
        try {
            ensureOpen();
            try {
                return Files.readAllBytes(fileFor(key));
            } catch (NoSuchFileException e) {
                throw new NoSuchElementException("Key is absent", e);
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void upsert(String key, byte[] value) throws IOException {
        writeLock.lock();
        try {
            ensureOpen();
            Path temporary = Files.createTempFile(directory, "value-", ".tmp");
            try {
                Files.write(temporary, value);
                Files.move(temporary, fileFor(key), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void delete(String key) throws IOException {
        writeLock.lock();
        try {
            ensureOpen();
            Files.deleteIfExists(fileFor(key));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            closed = true;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isAvailable() {
        readLock.lock();
        try {
            return !closed && Files.isDirectory(directory) && Files.isWritable(directory);
        } finally {
            readLock.unlock();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
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
