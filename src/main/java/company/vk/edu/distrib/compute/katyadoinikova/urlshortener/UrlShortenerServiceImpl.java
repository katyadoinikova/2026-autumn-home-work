package company.vk.edu.distrib.compute.katyadoinikova.urlshortener;

import com.sun.net.httpserver.HttpServer;

import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.urlshortener.UrlShortenerService;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

public final class UrlShortenerServiceImpl implements UrlShortenerService {
    private static final Path STORAGE_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "katyadoinikova-url-shortener");

    private final int port;
    private final Lock lifecycleLock = new ReentrantLock();
    @Nullable private HttpServer server;
    @Nullable private ExecutorService executor;
    @Nullable private Dao<String> links;
    @Nullable private FileDao users;
    private boolean started;
    private boolean stopped;

    public UrlShortenerServiceImpl(int port) {
        this.port = port;
    }

    @Override
    public void setLinksDao(Dao<String> dao) {
        lifecycleLock.lock();
        try {
            if (started || stopped) {
                throw new IllegalStateException("Links DAO can only be set before service start");
            }
            links = dao;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void start() {
        lifecycleLock.lock();
        try {
            if (started) {
                throw new IllegalStateException("Service has already been started");
            }
            started = true;
            try {
                Path storage = STORAGE_ROOT.resolve(Integer.toString(port));
                Dao<String> activeLinks;
                BooleanSupplier linksAvailable;
                Dao<String> configuredLinks = links;
                if (configuredLinks == null) {
                    FileDao localLinks = new FileDao(storage.resolve("links"));
                    activeLinks = localLinks;
                    links = localLinks;
                    linksAvailable = localLinks::isAvailable;
                } else {
                    activeLinks = configuredLinks;
                    linksAvailable = () -> true;
                }
                FileDao userDao = new FileDao(storage.resolve("users"));
                users = userDao;
                HttpServer httpServer =
                        HttpServer.create(new InetSocketAddress("localhost", port), 0);
                server = httpServer;
                ExecutorService serviceExecutor = Executors.newVirtualThreadPerTaskExecutor();
                executor = serviceExecutor;
                httpServer.setExecutor(serviceExecutor);
                BooleanSupplier finalLinksAvailable = linksAvailable;
                httpServer.createContext("/", new UrlShortenerHandler(
                        port, activeLinks, userDao,
                        () -> finalLinksAvailable.getAsBoolean() && userDao.isAvailable()));
                httpServer.start();
            } catch (IOException | RuntimeException e) {
                closeResources();
                stopped = true;
                throw new IllegalStateException("Unable to start service", e);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void stop() {
        lifecycleLock.lock();
        try {
            if (!started || stopped) {
                return;
            }
            stopped = true;
            closeResources();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void closeResources() {
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.stop(0);
        }
        ExecutorService serviceExecutor = executor;
        if (serviceExecutor != null) {
            serviceExecutor.close();
        }
        Dao<String> linkDao = links;
        if (linkDao != null) {
            try {
                linkDao.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to close links DAO", e);
            }
        }
        FileDao userDao = users;
        if (userDao != null) {
            userDao.close();
        }
    }
}
