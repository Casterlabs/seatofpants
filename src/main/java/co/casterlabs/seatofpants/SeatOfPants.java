package co.casterlabs.seatofpants;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import co.casterlabs.seatofpants.config.Config;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class SeatOfPants {
    public static final int SO_TIMEOUT = 2 * 60 * 1000; // 2 minutes
    public static final FastLogger LOGGER = new FastLogger();

    public static Config config;
    public static Daemon daemon;
    public static Heartbeat heartbeat;
    public static InstanceProvider provider;

    private static Map<String, Instance> instances = new HashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Instance existing : new ArrayList<>(instances.values())) {
                try {
                    existing.close();
                } catch (IOException ignored) {}
            }
        }));
    }

    public static synchronized void handle(Socket socket) {
        LOGGER.info("Incoming connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());

        try {
            Instance instance = null;

            // Look for an existing instance.
            for (Instance existing : instances.values()) {
                if (existing.isAlive() && existing.connections() < config.maxConnectionsPerInstance) {
                    instance = existing;
                    break;
                }
            }

            // Create a new instance if we didn't find one.
            if (instance == null) {
                instance = createNewInstance();
            }

            LOGGER.info("Using instance for request: %s", instance.id);

            Instance $instance_ptr = instance;
            Thread
                .ofVirtual()
                .name(String.format("TCP #%d", socket.hashCode()))
                .start(() -> {
                    try (socket) {
                        $instance_ptr.adopt(socket);
                    } catch (Exception ignored) {} finally {
                        LOGGER.info("Closed connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());
                        tick();
                    }
                });
        } catch (InstanceCreationException e) {
            LOGGER.info("Closed connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());
            try {
                socket.close(); // Make sure to close the socket since we failed.
            } catch (IOException ignored) {}
            SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
        } finally {
            tick();
        }
    }

    private static synchronized Instance createNewInstance() throws InstanceCreationException {
        Instance instance = SeatOfPants.provider.create(String.format("SOP.%s.%s", config.sopId, UUID.randomUUID().toString()));
        instances.put(instance.id, instance);
        LOGGER.info("Created instance: %s", instance.id);
        return instance;
    }

    public static synchronized void tick() {
        // Prune any dead instances.
        for (Instance existing : new ArrayList<>(instances.values())) {
            if (!existing.isAlive()) {
                instances.remove(existing.id);
                LOGGER.info("Pruned instance: %s", existing.id);
            }
        }

        int warmInstanceCount = 0;
        for (Instance existing : new ArrayList<>(instances.values())) {
            boolean hasCapacity = existing.connections() < config.maxConnectionsPerInstance;
            if (!hasCapacity) continue;

            // Register this one as warm.
            warmInstanceCount++;

            // Close any instances that we no longer need, keeping in mind the amount that
            // we want to keep around.
            if (warmInstanceCount > config.instancesToKeepWarm) {
                instances.remove(existing.id);
                try {
                    existing.close();
                } catch (IOException e) {
                    SeatOfPants.LOGGER.warn("Error whilst closing instance:\n%s", e);
                }
                LOGGER.info("Closed instance: %s", existing.id);
            }
        }

        // Spin up any instances we need to make the warm count be true.
        for (; warmInstanceCount < config.instancesToKeepWarm; warmInstanceCount++) {
            try {
                createNewInstance();
            } catch (InstanceCreationException e) {
                SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
            }
        }
    }

}
