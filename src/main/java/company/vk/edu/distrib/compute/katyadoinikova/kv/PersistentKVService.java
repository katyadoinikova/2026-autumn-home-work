package company.vk.edu.distrib.compute.katyadoinikova.kv;

import com.sun.net.httpserver.HttpServer;

import company.vk.edu.distrib.compute.kv.KVService;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class PersistentKVService implements KVService {
    private static final Path SERVICE_DATA_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "katyadoinikova-kv");

    private final int listenPort;
    private final Lock stateLock = new ReentrantLock();
    @Nullable private HttpServer endpoint;
    @Nullable private ExecutorService requestWorkers;
    @Nullable private ByteArrayFileDao entityStore;
    private ServiceState state = ServiceState.CREATED;

    public PersistentKVService(int listenPort) {
        this.listenPort = listenPort;
    }

    @Override
    public void start() {
        stateLock.lock();
        try {
            if (state != ServiceState.CREATED) {
                throw new IllegalStateException("KV service can only be started once");
            }
            try {
                openStorageEndpoint();
                state = ServiceState.RUNNING;
            } catch (IOException | RuntimeException exception) {
                state = ServiceState.STOPPED;
                shutdownStorageEndpoint();
                throw new IllegalStateException("Unable to start KV service", exception);
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void stop() {
        stateLock.lock();
        try {
            if (state != ServiceState.RUNNING) {
                return;
            }
            state = ServiceState.STOPPED;
            shutdownStorageEndpoint();
        } finally {
            stateLock.unlock();
        }
    }

    private void openStorageEndpoint() throws IOException {
        ByteArrayFileDao storage = new ByteArrayFileDao(
                SERVICE_DATA_PATH.resolve(Integer.toString(listenPort))
        );
        entityStore = storage;

        HttpServer httpEndpoint = HttpServer.create(new InetSocketAddress("localhost", listenPort), 0);
        endpoint = httpEndpoint;

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        requestWorkers = workers;
        httpEndpoint.setExecutor(workers);

        httpEndpoint.createContext("/", new KVHttpHandler(storage, storage));
        httpEndpoint.start();
    }

    private void shutdownStorageEndpoint() {
        HttpServer currentEndpoint = endpoint;
        if (currentEndpoint != null) {
            currentEndpoint.stop(0);
        }
        ExecutorService currentWorkers = requestWorkers;
        if (currentWorkers != null) {
            currentWorkers.close();
        }
        ByteArrayFileDao currentStore = entityStore;
        if (currentStore != null) {
            currentStore.close();
        }
    }

    private enum ServiceState {
        CREATED,
        RUNNING,
        STOPPED
    }
}
