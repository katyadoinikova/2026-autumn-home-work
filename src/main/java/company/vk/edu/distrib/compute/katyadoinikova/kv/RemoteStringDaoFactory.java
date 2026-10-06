package company.vk.edu.distrib.compute.katyadoinikova.kv;

import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.kv.RemoteDaoFactory;
import company.vk.edu.distrib.compute.kv.RemoteDaoFactoryTest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

@RemoteDaoFactoryTest
public final class RemoteStringDaoFactory implements RemoteDaoFactory<String> {
    private static final int REQUIRED_PORT_COUNT = 1;

    @Override
    public Dao<String> create(int... ports) {
        if (ports.length != REQUIRED_PORT_COUNT) {
            throw new IllegalArgumentException("Exactly one KV service port is required");
        }
        return new RemoteStringDao(ports[0]);
    }

    private static final class RemoteStringDao implements Dao<String> {
        private static final String ENTITY_PATH = "/v0/entity?id=";
        private final HttpClient client = HttpClient.newHttpClient();
        private final String baseUrl;

        private RemoteStringDao(int port) {
            baseUrl = "http://localhost:" + port + ENTITY_PATH;
        }

        @Override
        public String get(String key) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uriFor(key)).GET().build();
            HttpResponse<byte[]> response = send(request);
            if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NoSuchElementException("Key is absent");
            }
            requireStatus(response.statusCode(), HttpURLConnection.HTTP_OK);
            return new String(response.body(), StandardCharsets.UTF_8);
        }

        @Override
        public void upsert(String key, String value) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uriFor(key))
                    .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
                    .build();
            requireStatus(send(request).statusCode(), HttpURLConnection.HTTP_CREATED);
        }

        @Override
        public void delete(String key) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uriFor(key)).DELETE().build();
            requireStatus(send(request).statusCode(), HttpURLConnection.HTTP_ACCEPTED);
        }

        @Override
        public void close() {
            client.close();
        }

        private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while accessing KV service", e);
            }
        }

        private URI uriFor(String key) {
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Key must not be empty");
            }
            String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
            return URI.create(baseUrl + encoded);
        }

        private static void requireStatus(int actual, int expected) throws IOException {
            if (actual != expected) {
                throw new IOException("Unexpected KV service status: " + actual);
            }
        }
    }
}
