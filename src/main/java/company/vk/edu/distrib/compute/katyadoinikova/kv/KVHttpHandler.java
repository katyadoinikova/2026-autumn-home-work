package company.vk.edu.distrib.compute.katyadoinikova.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

final class KVHttpHandler implements HttpHandler {
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String STATUS_PATH = "/v0/status";
    private static final String ENTITY_PATH = "/v0/entity";
    private static final String REPLICA_PATH = "/internal/replica";
    private static final String ID_PARAMETER = "id";
    private static final String ACK_PARAMETER = "ack";

    private final ByteArrayFileDao storage;
    private final ReplicaCoordinator coordinator;

    KVHttpHandler(ByteArrayFileDao storage, ReplicaCoordinator coordinator) {
        this.storage = storage;
        this.coordinator = coordinator;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            ReplicaCoordinator.Response response;
            try {
                response = route(exchange);
            } catch (IllegalArgumentException e) {
                response = new ReplicaCoordinator.Response(400);
            } catch (IOException e) {
                response = new ReplicaCoordinator.Response(503);
            }
            byte[] body = response.body();
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length != 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private ReplicaCoordinator.Response route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (STATUS_PATH.equals(path)) {
            int status = GET.equals(method) && storage.isAvailable() ? 200 : 503;
            return new ReplicaCoordinator.Response(status);
        }
        if (!ENTITY_PATH.equals(path) && !REPLICA_PATH.equals(path)) {
            return new ReplicaCoordinator.Response(404);
        }
        EntityQuery query = parseQuery(exchange.getRequestURI().getRawQuery());
        byte[] value = PUT.equals(method) ? exchange.getRequestBody().readAllBytes() : new byte[0];
        return coordinator.access(method, query.key(), query.ack(), value, REPLICA_PATH.equals(path));
    }

    private static EntityQuery parseQuery(@Nullable String rawQuery) {
        if (rawQuery == null) {
            throw new IllegalArgumentException("Expected id query parameter");
        }
        @Nullable String id = null;
        @Nullable String ack = null;
        for (String parameter : rawQuery.split("&", -1)) {
            int separator = parameter.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed query parameter");
            }
            String name = parameter.substring(0, separator);
            String value = URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
            switch (name) {
                case ID_PARAMETER -> id = uniqueValue(id, value);
                case ACK_PARAMETER -> ack = uniqueValue(ack, value);
                default -> throw new IllegalArgumentException("Unknown query parameter");
            }
        }
        return new EntityQuery(requireId(id), ack);
    }

    private static String uniqueValue(@Nullable String current, String value) {
        if (current != null) {
            throw new IllegalArgumentException("Repeated query parameter");
        }
        return value;
    }

    private static String requireId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("ID must not be empty");
        }
        return id;
    }

    private record EntityQuery(String key, @Nullable String ack) {
    }
}
