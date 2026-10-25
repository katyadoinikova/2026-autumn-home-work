package company.vk.edu.distrib.compute.mrglaster.urlshortener.service;

import com.sun.net.httpserver.HttpServer;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.mrglaster.urlshortener.controller.manager.ControllerManager;
import company.vk.edu.distrib.compute.mrglaster.urlshortener.controller.route.external.LinkController;
import company.vk.edu.distrib.compute.mrglaster.urlshortener.controller.route.external.StatusController;
import company.vk.edu.distrib.compute.mrglaster.urlshortener.controller.route.internal.UserController;
import company.vk.edu.distrib.compute.mrglaster.urlshortener.dao.PersistentDao;
import company.vk.edu.distrib.compute.mrglaster.urlshortener.exception.DataSavingException;
import company.vk.edu.distrib.compute.urlshortener.UrlShortenerService;

import java.io.IOException;
import java.net.InetSocketAddress;

public class EPUrlShortenerService implements UrlShortenerService {

    private static final int SHUTDOWN_DELAY = 5;
    private static final String USERS_STORAGE_FILE = "/tmp/users";
    private static final String URL_STORAGE_FILE = "/tmp/urls";

    private final HttpServer httpServer;
    private final Dao<String> userDao;
    private Dao<String> urlDao;
    private boolean started;

    public EPUrlShortenerService(int port) throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);

        final String baseUrl = "http://127.0.0.1:" + port;

        this.urlDao = new PersistentDao<>(URL_STORAGE_FILE, PersistentDao.stringSerializer());
        this.userDao = new PersistentDao<>(USERS_STORAGE_FILE, PersistentDao.stringSerializer());
        this.started = false;
        AuthorizationService authService = new AuthorizationService(userDao);

        ControllerManager routingManager = new ControllerManager(authService);
        routingManager.addController(new StatusController());
        routingManager.addController(new LinkController(() -> this.urlDao, baseUrl));
        routingManager.addController(new UserController(userDao));
        routingManager.register(httpServer);
    }

    @Override
    public void start() {
        started = true;
        httpServer.start();
    }

    @Override
    public void stop() {
        try {
            userDao.close();
            urlDao.close();
        } catch (Exception e) {
            throw new DataSavingException("Unable to save data to files", e);
        } finally {
            httpServer.stop(SHUTDOWN_DELAY);
        }
    }

    @Override
    public void setLinksDao(Dao<String> urlDao) {
        if (started) {
            throw new IllegalStateException("Links storage can only be set before start");
        }
        this.urlDao = urlDao;
    }
}
