package company.vk.edu.distrib.compute.katyadoinikova.kv;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

final class ReplicaPlacement {
    private final int[] ports;

    ReplicaPlacement(int... ports) {
        this.ports = ports.clone();
    }

    int[] owners(String key, int count) {
        List<NodeScore> ranked = new ArrayList<>(ports.length);
        for (int port : ports) {
            ranked.add(new NodeScore(port, score(key, port)));
        }
        ranked.sort((left, right) -> {
            int comparison = Long.compareUnsigned(right.score(), left.score());
            return comparison == 0 ? Integer.compare(left.port(), right.port()) : comparison;
        });
        int[] owners = new int[count];
        for (int i = 0; i < count; i++) {
            owners[i] = ranked.get(i).port();
        }
        return owners;
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

    private record NodeScore(int port, long score) {
    }
}
