package co.casterlabs.seatofpants;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
        Thread
            .ofVirtual()
            .name("Background tick() thread.")
            .start(() -> {
                while (true) {
                    try {
                        TimeUnit.MINUTES.sleep(2);
                    } catch (InterruptedException ignored) {}
                    tick();
                }
            });
    }

    public static void handle(Socket socket) {
        try {
            LOGGER.info("Incoming connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());

            // Look for an existing instance that has capacity.
            synchronized (logicLock) {
                Optional<Instance> potentialInstance = new ArrayList<>(instances.values())
                    .stream()
                    .filter((i) -> i.isAlive())
                    .filter((i) -> !i.isExpired())
                    .filter((i) -> i.hasCapacity())
                    .sorted((i1, i2) -> Long.compare(i1.age(), i2.age()) * -1) // Prefer younger instances.
                    .findFirst();

                if (potentialInstance.isPresent()) {
                    Instance instance = potentialInstance.get();
                    LOGGER.info("Using instance for request: %s", instance.id);

                    Thread
                        .ofPlatform()
                        .name(String.format("TCP #%d", socket.hashCode()))
                        .start(() -> {
                            try (socket) {
                                instance.adopt(socket);
                            } catch (Exception ignored) {} finally {
                                LOGGER.info("Closed connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());
                                tick();
                            }
                        });
                    Thread.ofVirtual().start(SeatOfPants::tick); // Tick asynchronously.
                    return; // DO NOT execute the below logic.
                }
                // Fall through...
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
        long warmInstanceCount = 0;

        synchronized (logicLock) {
            // Prune any dead instances.
            new ArrayList<>(instances.values())
                .stream()
                .filter((i) -> !i.isAlive())
                .forEach((i) -> {
                    instances.remove(i.id);
                    LOGGER.info("Pruned instance: %s", i.id);
                });

            if (config.instanceMaxAgeMinutes > 0) {
                // Prune any expired instances.
                new ArrayList<>(instances.values())
                    .stream()
                    .filter((i) -> i.isExpired())
                    .forEach((instance) -> {
                        switch (config.expirationBehavior) {
                            case WAIT_FOR_LAST_CONNECTIONS:
                                if (instance.connections() > 0) {
                                    return; // Continue to let it live.
                                }
                                break; // Kill code below.

                            case KILL_INSTANTLY:
                                break; // Kill code below.
                        }

                        // We're ready to kill it. See the above lines to see when this happens or
                        // doesn't happen.
                        try {
                            instance.close();
                        } catch (IOException e) {
                            SeatOfPants.LOGGER.warn("Error whilst closing instance:\n%s", e);
                        } finally {
                            instances.remove(instance.id);
                        }
                        LOGGER.info("Killed expired instance: %s", instance.id);
                    });
            }

            if (config.instancesToKeepWarm > 0) {
                // Count the warm instances.
                warmInstanceCount = new ArrayList<>(instances.values())
                    .stream()
                    .filter((i) -> !i.isExpired())
                    .filter((i) -> !i.isAboutToExpire()) // Don't count instances that are about to expire.
                    .filter((i) -> i.hasCapacity())
                    .count();

                if (warmInstanceCount > config.instancesToKeepWarm) {
                    // Close any instances that we no longer need, keeping in mind the amount that
                    // we want to keep around.
                    long overProvisionedCount = warmInstanceCount - config.instancesToKeepWarm;
                    new ArrayList<>(instances.values())
                        .stream()
                        .filter((i) -> i.connections() == 0)
                        .sorted((i1, i2) -> Long.compare(i1.age(), i2.age())) // Kill the older instances first.
                        .limit(overProvisionedCount)
                        .forEach((instance) -> {
                            try {
                                instance.close();
                            } catch (IOException e) {
                                SeatOfPants.LOGGER.warn("Error whilst closing instance:\n%s", e);
                            } finally {
                                instances.remove(instance.id);
                            }
                            LOGGER.info("Closed instance: %s", instance.id);
                        });
                }
            }
        }

        synchronized (notifications) {
            notifications.notifyAll();
        }

        if (warmInstanceCount < config.instancesToKeepWarm) {
            int $warmInstanceCount_ptr = (int) warmInstanceCount;
            Thread.ofVirtual().start(() -> {
                // Spin up any instances we need to make the warm count be satisfied.
                for (int iter = $warmInstanceCount_ptr; iter < config.instancesToKeepWarm; iter++) {
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
