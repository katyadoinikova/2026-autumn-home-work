package company.vk.edu.distrib.compute.katyadoinikova.kv;

import company.vk.edu.distrib.compute.Dao;
import company.vk.edu.distrib.compute.kv.ClusterDaoFactoryTest;
import company.vk.edu.distrib.compute.kv.RemoteDaoFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ClusterDaoFactoryTest
public final class ClusterRemoteStringDaoFactory implements RemoteDaoFactory<String> {
    private static final int MINIMUM_NODE_COUNT = 1;

    @Override
    public Dao<String> create(int... ports) {
        if (ports.length < MINIMUM_NODE_COUNT) {
            throw new IllegalArgumentException("At least one KV service port is required");
        }
        int[] nodes = ports.clone();
        Arrays.sort(nodes);
        for (int i = 1; i < nodes.length; i++) {
            if (nodes[i] == nodes[i - 1]) {
                throw new IllegalArgumentException("Cluster ports must be distinct");
            }
        }
        RemoteStringDaoFactory singleNodeFactory = new RemoteStringDaoFactory();
        List<Dao<String>> nodeDaos = new ArrayList<>(nodes.length);
        for (int nodePort : nodes) {
            nodeDaos.add(singleNodeFactory.create(nodePort));
        }
        return new ClusterRemoteStringDao(nodes, nodeDaos);
    }

    private static final class ClusterRemoteStringDao implements Dao<String> {
        private static final int EQUAL_SCORE = 0;

        private final int[] ports;
        private final List<Dao<String>> nodes;

        private ClusterRemoteStringDao(int[] ports, List<Dao<String>> nodes) {
            this.ports = ports;
            this.nodes = nodes;
        }

        @Override
        public String get(String key) throws IOException {
            return owner(key).get(key);
        }

        @Override
        public void upsert(String key, String value) throws IOException {
            owner(key).upsert(key, value);
        }

        @Override
        public void delete(String key) throws IOException {
            owner(key).delete(key);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (Dao<String> node : nodes) {
                try {
                    node.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private Dao<String> owner(String key) {
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Key must not be empty");
            }
            int bestIndex = 0;
            long bestScore = score(key, ports[0]);
            for (int i = 1; i < ports.length; i++) {
                long candidate = score(key, ports[i]);
                if (Long.compareUnsigned(candidate, bestScore) > EQUAL_SCORE) {
                    bestScore = candidate;
                    bestIndex = i;
                }
            }
            return nodes.get(bestIndex);
        }

        private static long score(String key, int port) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(port).array());
                return ByteBuffer.wrap(digest.digest()).getLong();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }
}
