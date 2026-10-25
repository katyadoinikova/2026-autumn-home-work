package company.vk.edu.distrib.compute.nosorozhek.urlshortener;

import com.sun.net.httpserver.HttpServer;
import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.nosorozhek.urlshortener.authentication.AuthenticationMiddleware;
import company.vk.edu.distrib.compute.nosorozhek.urlshortener.authentication.UserService;
import company.vk.edu.distrib.compute.nosorozhek.urlshortener.handlers.*;
import company.vk.edu.distrib.compute.urlshortener.UrlShortenerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

public class UrlShortenerServiceImpl implements UrlShortenerService {
    private static final Logger log = LoggerFactory.getLogger(UrlShortenerServiceImpl.class);

    private final HttpServer server;

    private final UrlShortener urlShortener;
    private final UserService userService;
    private final AuthenticationMiddleware auth;

    private final Dao<String> urlDao;
    private final Dao<String> userDao;

    public UrlShortenerServiceImpl(Dao<String> urlDao, Dao<String> userDao, int port) throws IOException {
        this.urlDao = urlDao;
        urlShortener = new UrlShortener(urlDao);
        this.userDao = userDao;
        userService = new UserService(userDao);
        auth = new AuthenticationMiddleware(userService);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", createRouter(port));
    }

    private Router createRouter(int port) {
        return new Router()
                .add(HttpMethod.GET, "/v0/status", new GetStatusHandler())
                .add(HttpMethod.GET, "/v0/links/(?<ID>[^/]+)", auth.wrap(new GetLinkHandler(urlShortener)))
                .add(HttpMethod.POST, "/v0/links", auth.wrap(new PostLinkHandler(urlShortener, port)))
                .add(HttpMethod.PUT, "/v0/links/(?<ID>[^/]+)", auth.wrap(new PutLinkHandler(urlShortener)))
                .add(HttpMethod.DELETE, "/v0/links/(?<ID>[^/]+)", auth.wrap(new DeleteLinkHandler(urlShortener)))
                .add(HttpMethod.GET, "/(?<ID>[^/]+)", new GetRedirectHandler(urlShortener))
                .add(HttpMethod.POST, "/internal/users", new PostUserHandler(userService));
    }

    @Override
    public void start() {
        log.info("Started");
        server.start();
    }

    @Override
    public void stop() {
        log.info("Stopping");
        server.stop(0);

        try {
            urlDao.close();
        } catch (IOException e) {
            log.error("Failed to close urlDao", e);
        }

        try {
            userDao.close();
        } catch (IOException e) {
            log.error("Failed to close authDao", e);
        }
    }
}
