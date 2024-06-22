package co.casterlabs.seatofpants.providers;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import co.casterlabs.seatofpants.SeatOfPants;
import co.casterlabs.seatofpants.util.Watchdog;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@RequiredArgsConstructor
public abstract class Instance implements Closeable {
    private static final long CACHE_ALIVE_CHECK_FOR = TimeUnit.SECONDS.toMillis(30);

    private final long createdAt = System.currentTimeMillis();

    private boolean isAlive = true;
    private long lastAliveCheck = 0;

    public final @NonNull String id;
    protected final FastLogger logger;

    private AtomicInteger connectionsCount = new AtomicInteger(0);

    private volatile boolean hasBeenDestroyed = false;

    protected abstract Socket connect() throws IOException;

    public abstract String getAddress();

    protected abstract boolean isAlive0();

    protected abstract void close0();

    public final synchronized boolean isAlive() {
        if (this.hasBeenDestroyed) return false;

        if (System.currentTimeMillis() - this.lastAliveCheck > CACHE_ALIVE_CHECK_FOR) {
            try (Watchdog wd = new Watchdog(8000)) {
                this.isAlive = this.isAlive0();
            } catch (Exception e) {
                this.logger.trace(e);
                // Assume the last state.
            }
            this.lastAliveCheck = System.currentTimeMillis();
        }

        return this.isAlive;
    }

    @Override
    public final void close() {
        if (this.hasBeenDestroyed) return;
        this.hasBeenDestroyed = true;
        this.close0();
    }

    /**
     * @implNote This method blocks until the connection is terminated. The provided
     *           Socket will be automatically closed for you.
     */
    public final void adopt(@NonNull Socket clientSocket) {
        this.connectionsCount.incrementAndGet();

        Thread
            .ofPlatform()
            .name(String.format("TCP #%d--%s", clientSocket.hashCode(), this.id))
            .start(() -> {
                try {
                    int retryCount = 0;
                    while (!clientSocket.isClosed()) {
                        try (Socket instanceSocket = this.connect()) {
                            instanceSocket.setSoTimeout(SeatOfPants.SO_TIMEOUT);
                            this.doProxy(clientSocket, instanceSocket);
                        } catch (IOException e) {
                            if (retryCount >= SeatOfPants.config.providerMaxRetries) {
                                try {
                                    clientSocket.close();
                                } catch (IOException ignored) {} finally {
                                    SeatOfPants.notifyDisconnect();
                                }
                                this.logger.severe("Timed out whilst adopting:\n%s", e);
                                return;
                            }

                            retryCount++;

                            try {
                                Thread.sleep(100); // Try to give the process enough time to start up.
                            } catch (InterruptedException e1) {}
                            continue;
                        }
                    }
                } catch (Throwable t) {
                    this.logger.fatal(t);
                } finally {
                    SeatOfPants.LOGGER.info("Closed connection: #%d %s", clientSocket.hashCode(), clientSocket.getRemoteSocketAddress());
                    this.connectionsCount.decrementAndGet();
                    SeatOfPants.notifyDisconnect();
                }
            });
    }

    private final void doProxy(Socket clientSocket, Socket instanceSocket) throws IOException, InterruptedException {
        Thread clientToInstance = Thread
            .ofPlatform()
            .name(String.format("TCP #%d->%s", clientSocket.hashCode(), this.id))
            .start(() -> {
                try {
                    clientSocket.getInputStream().transferTo(instanceSocket.getOutputStream());
                } catch (IOException ignored) {}
            });
        Thread instanceToClient = Thread
            .ofPlatform()
            .name(String.format("TCP #%d<-%s", clientSocket.hashCode(), this.id))
            .start(() -> {
                try {
                    instanceSocket.getInputStream().transferTo(clientSocket.getOutputStream());
                } catch (IOException ignored) {}
            });

        try (clientSocket; instanceSocket) {
            clientToInstance.join();
            instanceToClient.join();
        }
    }

    public final int connectionsCount() {
        return this.connectionsCount.get();
    }

    public final long age() {
        return System.currentTimeMillis() - this.createdAt;
    }

    public final boolean isExpired() {
        if (SeatOfPants.config.instanceMaxAgeMinutes == -1) return false;

        long maxAgeMs = TimeUnit.MINUTES.toMillis(SeatOfPants.config.instanceMaxAgeMinutes);
        long myAge = this.age();

        return myAge >= maxAgeMs;
    }

    public final boolean isAboutToExpire() {
        final int aboutToMinutes = 3;

        if (SeatOfPants.config.instanceMaxAgeMinutes == -1) return false;
        if (SeatOfPants.config.instanceMaxAgeMinutes <= aboutToMinutes) return false; // Our "about to" logic won't work. So we won't do it.

        long maxAgeMs = TimeUnit.MINUTES.toMillis(SeatOfPants.config.instanceMaxAgeMinutes - aboutToMinutes);
        long myAge = this.age();

        return myAge >= maxAgeMs;
    }

    public final boolean hasCapacity() {
        return this.connectionsCount() < SeatOfPants.config.maxConnectionsPerInstance;
    }

}
