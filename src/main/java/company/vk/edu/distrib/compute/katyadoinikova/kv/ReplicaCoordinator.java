package company.vk.edu.distrib.compute.katyadoinikova.kv;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class ReplicaCoordinator {
    private static final String CLUSTER_PROPERTY = "CLUSTER";
    private static final String FACTOR_PROPERTY = "REPLICATION_FACTOR";
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";
    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNAVAILABLE = 503;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(800);
    private static final long QUORUM_WAIT_MILLIS = 1000;

    private final int localPort;
    private final ByteArrayFileDao storage;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(400))
            .build();

    ReplicaCoordinator(int localPort, ByteArrayFileDao storage) {
        this.localPort = localPort;
        this.storage = storage;
    }

    Response access(String method, String key, @Nullable String ackText, byte[] value, boolean internal) {
        if (!GET.equals(method) && !PUT.equals(method) && !DELETE.equals(method)) {
            return new Response(405);
        }
        if (internal) {
            return accessLocal(method, key, value);
        }
        ClusterConfig config = currentCluster();
        int ack = parseAck(ackText, config.replicationFactor());
        if (!config.configured()) {
            return accessLocal(method, key, value);
        }
        int[] owners = new ReplicaPlacement(config.ports()).owners(key, config.replicationFactor());
        return collectReplies(method, key, value, ack, owners);
    }

    void close() {
        client.close();
    }

    private Response collectReplies(String method, String key, byte[] value, int ack, int... owners) {
        BlockingQueue<Response> completed = dispatch(method, key, value, owners);
        int confirmed = 0;
        boolean found = false;
        byte[] foundValue = new byte[0];
        int remaining = owners.length;
        while (remaining-- > 0) {
            Response reply;
            try {
                reply = completed.poll(QUORUM_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Response(HTTP_UNAVAILABLE);
            }
            if (reply == null) {
                break;
            }
            if (acknowledged(method, reply.status())) {
                confirmed++;
                if (GET.equals(method) && reply.status() == HTTP_OK) {
                    found = true;
                    foundValue = reply.body();
                }
            }
            if (confirmed >= ack) {
                return quorumResponse(method, found, foundValue);
            }
        }
        return new Response(HTTP_UNAVAILABLE);
    }

    private BlockingQueue<Response> dispatch(String method, String key, byte[] value, int... owners) {
        BlockingQueue<Response> completed = new LinkedBlockingQueue<>();
        List<Integer> remotePorts = new ArrayList<>(owners.length);
        boolean storeLocally = false;
        for (int port : owners) {
            if (port == localPort) {
                storeLocally = true;
            } else {
                remotePorts.add(port);
            }
        }
        for (int port : remotePorts) {
            sendToReplica(port, method, key, value).thenAccept(completed::offer);
        }
        if (storeLocally) {
            completed.offer(accessLocal(method, key, value));
        }
        return completed;
    }

    private CompletableFuture<Response> sendToReplica(int port, String method, String key, byte[] value) {
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        URI uri = URI.create("http://localhost:" + port + "/internal/replica?id=" + encodedKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        switch (method) {
            case GET -> builder.GET();
            case PUT -> builder.PUT(HttpRequest.BodyPublishers.ofByteArray(value));
            case DELETE -> builder.DELETE();
            default -> throw new IllegalArgumentException("Unsupported method");
        }
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(reply -> new Response(reply.statusCode(), reply.body()))
                .exceptionally(error -> new Response(HTTP_UNAVAILABLE));
    }

    private Response accessLocal(String method, String key, byte[] value) {
        try {
            return switch (method) {
                case GET -> new Response(HTTP_OK, storage.get(key));
                case PUT -> {
                    storage.upsert(key, value);
                    yield new Response(HTTP_CREATED);
                }
                case DELETE -> {
                    storage.delete(key);
                    yield new Response(HTTP_ACCEPTED);
                }
                default -> new Response(405);
            };
        } catch (NoSuchElementException e) {
            return new Response(HTTP_NOT_FOUND);
        } catch (IOException e) {
            return new Response(HTTP_UNAVAILABLE);
        }
    }

    private static boolean acknowledged(String method, int status) {
        return switch (method) {
            case GET -> status == HTTP_OK || status == HTTP_NOT_FOUND;
            case PUT -> status == HTTP_CREATED;
            case DELETE -> status == HTTP_ACCEPTED;
            default -> false;
        };
    }

    private static Response quorumResponse(String method, boolean found, byte[] value) {
        return switch (method) {
            case GET -> found ? new Response(HTTP_OK, value) : new Response(HTTP_NOT_FOUND);
            case PUT -> new Response(HTTP_CREATED);
            case DELETE -> new Response(HTTP_OK);
            default -> new Response(405);
        };
    }

    private ClusterConfig currentCluster() {
        @Nullable String description = System.getProperty(CLUSTER_PROPERTY);
        if (description == null || description.isBlank()) {
            return new ClusterConfig(new int[]{localPort}, 1, false);
        }
        Set<Integer> ports = new TreeSet<>();
        for (String part : description.split(",")) {
            int port = Integer.parseInt(part.trim());
            if (port <= 0 || port > 65535 || !ports.add(port)) {
                throw new IllegalArgumentException("Invalid cluster port");
            }
        }
        int factor = ports.size();
        @Nullable String configuredFactor = System.getProperty(FACTOR_PROPERTY);
        if (configuredFactor != null) {
            factor = Integer.parseInt(configuredFactor);
            if (factor < 1 || factor > ports.size()) {
                throw new IllegalArgumentException("Invalid replication factor");
            }
        }
        return new ClusterConfig(ports.stream().mapToInt(Integer::intValue).toArray(), factor, true);
    }

    private static int parseAck(@Nullable String text, int factor) {
        int ack = text == null ? 1 : Integer.parseInt(text);
        if (ack < 1 || ack > factor) {
            throw new IllegalArgumentException("ack is outside replication factor");
        }
        return ack;
    }

    record Response(int status, byte[] body) {
        Response(int status) {
            this(status, new byte[0]);
        }
    }

    private record ClusterConfig(int[] ports, int replicationFactor, boolean configured) {
    }
}
