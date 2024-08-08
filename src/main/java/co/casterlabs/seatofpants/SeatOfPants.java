package co.casterlabs.seatofpants;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import co.casterlabs.seatofpants.config.Config;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import co.casterlabs.seatofpants.util.Watchdog;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class SeatOfPants {
    public static final int SO_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(3);
    public static final FastLogger LOGGER = new FastLogger();

    public static Config config;
    public static Daemon daemon;
    public static APIDaemon apiDaemon;
    public static Heartbeat heartbeat;
    public static InstanceProvider provider;

    private static Map<String, Instance> instances = new HashMap<>();
    public static final Set<Runnable> runOnClose = Collections.synchronizedSet(new HashSet<>());

    private static final Object searchLock = new Object();
    private static final Object tickLock = new Object();
    private static final Object notifications = new Object();

    private static boolean isShuttingDown = false;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            isShuttingDown = true;

            for (Instance instance : new ArrayList<>(instances.values())) {
                try {
                    instance.close();
                } catch (Throwable ignored) {}
            }

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
                while (!isShuttingDown) {
                    try {
                        TimeUnit.SECONDS.sleep(15);
                    } catch (InterruptedException ignored) {}
                    tick();
                }
            });
    }

    public static void handle(Socket socket) {
        try {
            LOGGER.info("Incoming connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());

            Optional<Instance> potentialInstance;
            synchronized (searchLock) {
                LOGGER.info("Processing connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());

                // Look for an existing instance that has capacity.
                potentialInstance = new ArrayList<>(instances.values())
                    .parallelStream()
                    .filter((i) -> !i.isExpired())
                    .filter((i) -> !i.isAboutToExpire())
                    .filter((i) -> i.hasCapacity())
                    .filter((i) -> i.isAlive())
                    .sorted((i1, i2) -> Long.compare(i1.age(), i2.age()) * -1) // Prefer newer instances.
                    .sorted((i1, i2) -> Long.compare(i1.connectionsCount(), i2.connectionsCount())) // Instances with the least connections
                    .findFirst();

                if (potentialInstance.isPresent()) {
                    Instance instance = potentialInstance.get();
                    LOGGER.info("Using instance for request: %s", instance.id);
                    instance.adopt(socket);
//                    Thread.ofVirtual().start(SeatOfPants::tick); // Tick asynchronously.
                    return; // DO NOT execute the below logic.
                }
            }

            // There was no instance ready...

            // Wait for another creation call to complete and then recurse.
            LOGGER.debug("Waiting for an existing instance creation operation to complete (or for another instance to have availability).");
            try (Watchdog wd = new Watchdog(config.providerMaxCreationTime * 2)) {
                synchronized (notifications) {
                    notifications.wait();
                }
            }

            // Recurse.
            handle(socket);
            return;
        } catch (InterruptedException e) {
            LOGGER.info("Closed connection: #%d %s", socket.hashCode(), socket.getRemoteSocketAddress());
            try {
                socket.close(); // Make sure to close the socket since we failed.
            } catch (IOException ignored) {}
            SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
        }
    }

    public static List<String> getInstanceAddresses() {
        return new ArrayList<>(instances.values())
            .parallelStream()
            .filter((i) -> i.isAlive())
            .map((i) -> i.getAddress())
            .toList();
    }

    public static long getConnectionCount() {
        return new ArrayList<>(instances.values())
            .parallelStream()
            .collect(Collectors.summingLong((i) -> i.connectionsCount()));
    }

    public static void notifyDisconnect() {
        synchronized (notifications) {
            notifications.notify();
        }
    }

    private static void createNewInstance() throws InstanceCreationException {
        try (Watchdog wd = new Watchdog(config.providerMaxCreationTime)) {
            if (config.maxInstancesLimit != -1 && instances.size() >= config.maxInstancesLimit) {
                return; // Don't create another.
            }

            String id = String.format("SOP.%s.%s", config.sopId, UUID.randomUUID().toString());
            LOGGER.info("Creating instance... (will be %s)", id);

            Instance instance = SeatOfPants.provider.create(id);
            instances.put(id, instance);
            LOGGER.info("Created instance: %s", id);
        } finally {
            synchronized (notifications) {
                notifications.notifyAll();
            }
        }
    }

    public static void tick() {
        if (isShuttingDown) return;

        synchronized (tickLock) {
            // Prune any dead instances.
            new ArrayList<>(instances.values())
                .parallelStream()
                .filter((i) -> !i.isAlive())
                .forEach((i) -> {
                    instances.remove(i.id);
                    LOGGER.info("Pruned instance: %s", i.id);
                });

            switch (config.scalingBehavior) {
                case DYNAMIC_POOL: {
                    if (config.instanceWarmRatio > 0) {
                        long requiredFreeConnections = (long) (config.maxConnectionsPerInstance * config.instanceWarmRatio);

                        // Count the warm instances.
                        long availableConnectionCount = new ArrayList<>(instances.values())
                            .stream()
                            .filter((i) -> i.isAlive())
                            .filter((i) -> !i.isExpired())
                            .filter((i) -> !i.isAboutToExpire()) // Don't count instances that are about to expire.
                            .mapToLong((i) -> Math.max(0, config.maxConnectionsPerInstance - i.connectionsCount())) // max() to avoid a negative value.
                            .sum();

                        if (availableConnectionCount < requiredFreeConnections) {
                            // Spin up more instances.
                            long amountToCreate = (long) Math.ceil((requiredFreeConnections - availableConnectionCount) / (double) config.maxConnectionsPerInstance);

                            // Parallelize.
                            List<Thread> waitFor = new LinkedList<>();
                            for (int i = 0; i < amountToCreate; i++) {
                                waitFor.add(
                                    Thread.ofPlatform().start(() -> {
                                        try {
                                            createNewInstance();
                                        } catch (InstanceCreationException e) {
                                            SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
                                        }
                                    })
                                );
                            }
                            waitFor.forEach((t) -> {
                                try {
                                    t.join();
                                } catch (InterruptedException ignored) {}
                            });
                        } else if (availableConnectionCount > requiredFreeConnections) {
                            // See if we can save money by closing instances with 0 connections.
                            // We don't want to kill anything that has >0 because we don't know how the app
                            // handles itself.
                            List<Instance> canBeDeleted = new ArrayList<>(instances.values())
                                .parallelStream()
                                .filter((i) -> i.isAlive())
                                .filter((i) -> i.connectionsCount() == 0)
                                .sorted((i1, i2) -> Long.compare(i1.age(), i2.age())) // Prefer older instances.
                                .collect(Collectors.toList());
                            long availableConnectionCountCopy = availableConnectionCount;

                            for (Instance instance : canBeDeleted) {
                                availableConnectionCountCopy -= config.maxConnectionsPerInstance;

                                if (availableConnectionCountCopy < requiredFreeConnections) {
                                    // Let's not kill our reserve capacity.
                                    break;
                                }

                                Thread.ofPlatform().start(() -> {
                                    try {
                                        instance.close();
                                    } catch (Throwable t) {
                                        SeatOfPants.LOGGER.warn("Error whilst closing instance:\n%s", t);
                                    } finally {
                                        instances.remove(instance.id);
                                    }
                                    LOGGER.info("Killed unused instance: %s", instance.id);
                                });
                            }
                        }
                    }
                    break;
                }

                case FIXED_POOL: {
                    long amountAlive = new ArrayList<>(instances.values())
                        .stream()
                        .filter((i) -> i.isAlive())
                        .filter((i) -> !i.isExpired())
                        .filter((i) -> !i.isAboutToExpire()) // Don't count instances that are about to expire.
                        .count();

                    long amountToCreate = config.maxInstancesLimit - amountAlive;
                    if (amountToCreate <= 0) break;

                    // Parallelize.
                    List<Thread> waitFor = new LinkedList<>();
                    for (int i = 0; i < amountToCreate; i++) {
                        waitFor.add(
                            Thread.ofPlatform().start(() -> {
                                try {
                                    createNewInstance();
                                } catch (InstanceCreationException e) {
                                    SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
                                }
                            })
                        );
                    }
                    waitFor.forEach((t) -> {
                        try {
                            t.join();
                        } catch (InterruptedException ignored) {}
                    });
                    break;
                }
            }

            if (config.instanceMaxAgeMinutes > 0) {
                // Prune any expired instances.
                new ArrayList<>(instances.values())
                    .parallelStream()
                    .filter((i) -> i.isExpired())
                    .forEach((instance) -> {
                        switch (config.expirationBehavior) {
                            case WAIT_FOR_LAST_CONNECTIONS:
                                if (instance.connectionsCount() > 0) {
                                    if (config.killAfterWaitingForLastMinutes > 0) {
                                        // We need to determine if the instance should be forcibly killed.
                                        long killAfterAge = TimeUnit.MINUTES.toMillis(config.instanceMaxAgeMinutes + config.killAfterWaitingForLastMinutes);

                                        if (instance.age() >= killAfterAge) {
                                            break; // Yup, needs to be killed.
                                        }
                                    }

                                    return; // Continue to let it live.
                                }

                                break; // No connections, trigger the kill code below.

                            case KILL_INSTANTLY:
                                break; // Kill code below.
                        }

                        // We're ready to kill it. See the above lines to see when this happens or
                        // doesn't happen.
                        Thread.ofVirtual().start(() -> {
                            try {
                                instance.close();
                            } catch (Throwable t) {
                                SeatOfPants.LOGGER.warn("Error whilst closing instance:\n%s", t);
                            } finally {
                                instances.remove(instance.id);
                            }
                            LOGGER.info("Killed expired instance: %s", instance.id);
                        });
                    });
            }
        }
    }

}
