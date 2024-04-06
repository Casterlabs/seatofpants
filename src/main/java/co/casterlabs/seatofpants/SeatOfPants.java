package co.casterlabs.seatofpants;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    public static final Set<Runnable> runOnClose = Collections.synchronizedSet(new HashSet<>());

    private static final Object logicLock = new Object();
    private static final Object creationLock = new Object();
    private static final Object notifications = new Object();
    private static volatile boolean isCreating = false;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Instance instance : new ArrayList<>(instances.values())) {
                try {
                    instance.close();
                } catch (Throwable ignored) {}
            }
        }));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Runnable toRun : new ArrayList<>(runOnClose)) {
                try {
                    toRun.run();
                } catch (Throwable ignored) {}
            }
        }));
    }

    public static void handle(Socket socket) {
        try {
            LOGGER.info("Incoming connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());

            // Look for an existing instance that has capacity.
            synchronized (logicLock) {
                for (Instance instance : instances.values()) {
                    if (!instance.isAlive() || instance.connections() >= config.maxConnectionsPerInstance) continue;

                    LOGGER.info("Using instance for request: %s", instance.id);

                    Instance $instance_ptr = instance;
                    Thread
                        .ofPlatform()
                        .name(String.format("TCP #%d", socket.hashCode()))
                        .start(() -> {
                            try (socket) {
                                $instance_ptr.adopt(socket);
                            } catch (Exception ignored) {} finally {
                                LOGGER.info("Closed connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());
                                tick();
                            }
                        });
                    Thread.ofVirtual().start(SeatOfPants::tick); // Tick asynchronously.
                    return; // DO NOT execute the below logic.
                }
            }

            // There was no instance ready...

            if (isCreating) {
                // Wait for another creation call to complete and then recurse.
                LOGGER.debug("Waiting for an existing instance creation operation to complete (or for another instance to have availability).");
                synchronized (notifications) {
                    notifications.wait();
                }
            } else {
                // Create a new instance since we didn't find one.
                LOGGER.debug("Creating a new instance...");
                createNewInstance();
            }

            // Recurse.
            handle(socket);
            return;
        } catch (InstanceCreationException | InterruptedException e) {
            LOGGER.info("Closed connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());
            try {
                socket.close(); // Make sure to close the socket since we failed.
            } catch (IOException ignored) {}
            SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
        }
    }

    private static void createNewInstance() throws InstanceCreationException {
        synchronized (creationLock) {
            try {
                isCreating = true;
                Instance instance = SeatOfPants.provider.create(String.format("SOP.%s.%s", config.sopId, UUID.randomUUID().toString()));
                instances.put(instance.id, instance);
                LOGGER.info("Created instance: %s", instance.id);
            } finally {
                isCreating = false;
                synchronized (notifications) {
                    notifications.notifyAll();
                }
            }
        }
    }

    public static void tick() {
        int warmInstanceCount = 0;

        synchronized (logicLock) {
            // Prune any dead instances.
            for (Instance existing : new ArrayList<>(instances.values())) {
                if (!existing.isAlive()) {
                    instances.remove(existing.id);
                    LOGGER.info("Pruned instance: %s", existing.id);
                }
            }

            // Count the warm instances.
            if (config.instancesToKeepWarm > 0) {
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
            }
        }

        synchronized (notifications) {
            notifications.notifyAll();
        }

        if (warmInstanceCount < config.instancesToKeepWarm) {
            int warmInstanceCountCopy = warmInstanceCount;
            Thread.ofVirtual().start(() -> {
                // Spin up any instances we need to make the warm count be true.
                for (int iter = warmInstanceCountCopy; iter < config.instancesToKeepWarm; iter++) {
                    try {
                        createNewInstance();
                    } catch (InstanceCreationException e) {
                        SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
                    }
                }
                // This may wind up creating more warm instances than necessary. But we won't
                // kill them until the next NATURAL tick()
            });
        }
    }

}
