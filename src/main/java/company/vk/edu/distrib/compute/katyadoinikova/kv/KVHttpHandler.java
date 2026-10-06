package company.vk.edu.distrib.compute.katyadoinikova.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import company.vk.edu.distrib.compute.Dao;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

final class KVHttpHandler implements HttpHandler {
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";
    private static final String STATUS_PATH = "/v0/status";
    private static final String ENTITY_PATH = "/v0/entity";

    private final Dao<byte[]> entities;
    private final ByteArrayFileDao storage;

    KVHttpHandler(Dao<byte[]> entities, ByteArrayFileDao storage) {
        this.entities = entities;
        this.storage = storage;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = route(exchange);
            } catch (NoSuchElementException e) {
                response = new Response(404);
            } catch (IllegalArgumentException e) {
                response = new Response(400);
            } catch (IOException e) {
                response = new Response(503);
            }
            byte[] body = response.body();
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length != 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private Response route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (STATUS_PATH.equals(path)) {
            return GET.equals(method) && storage.isAvailable()
                    ? new Response(200)
                    : new Response(503);
        }
        if (!ENTITY_PATH.equals(path)) {
            return new Response(404);
        }
        String key = extractKey(exchange.getRequestURI().getRawQuery());
        return accessEntity(exchange, method, key);
    }

    private Response accessEntity(HttpExchange exchange, String method, String key)
            throws IOException {
        return switch (method) {
            case GET -> new Response(200, entities.get(key));
            case PUT -> {
                entities.upsert(key, exchange.getRequestBody().readAllBytes());
                yield new Response(201);
            }
            case DELETE -> {
                entities.delete(key);
                yield new Response(202);
            }
            default -> new Response(405);
        };
    }

    private static String extractKey(@Nullable String query) {
        if (query == null || !query.startsWith("id=") || query.length() == 3
                || query.indexOf('&') >= 0) {
            throw new IllegalArgumentException("Expected a non-empty id query parameter");
        }
        String key = URLDecoder.decode(query.substring(3), StandardCharsets.UTF_8);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("ID must not be empty");
        }
        return key;
    }

    private record Response(int status, byte[] body) {
        private Response(int status) {
            this(status, new byte[0]);
        }
    }
}
