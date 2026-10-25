package company.vk.edu.distrib.compute.artyompeshkov.urlshortener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.urlshortener.UrlShortenerService;

public class UrlShortenerServiceImpl implements UrlShortenerService {
    private final int port;
    private final Dao<String> links;
    private final Dao<String> users;
    private final HttpServer server;

    public UrlShortenerServiceImpl(int port, Dao<String> links, Dao<String> users) throws IOException {
        this.port = port;
        this.links = links;
        this.users = users;
        this.server = HttpServer.create();
        String baseUrl = "http://localhost:" + port + "/";
        server.createContext(StatusHandler.PATH, new StatusHandler());
        server.createContext(LinksHandler.PATH, new LinksHandler(links, new SimpleAuth(users), baseUrl));
        server.createContext(UsersHandler.PATH, new UsersHandler(users));
        server.createContext(RedirectHandler.PATH, new RedirectHandler(links));
    }

    @Override
    public void start() {
        try {
            server.bind(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to bind to port " + port, e);
        }
        server.start();
    }

    @Override
    public void stop() {
        server.stop(0);
        try {
            users.close();
            links.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close the storage", e);
        }
    }
}
