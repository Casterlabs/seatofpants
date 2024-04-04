package co.casterlabs.seatofpants.providers;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;

import co.casterlabs.seatofpants.SeatOfPants;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@RequiredArgsConstructor
public abstract class Instance implements Closeable {
    public final @NonNull String id;
    protected final FastLogger logger;

    protected abstract Socket connect() throws IOException;

    /**
     * @implNote This method blocks until the connection is terminated. The provided
     *           Socket will be automatically closed for you.
     */
    public final void adopt(@NonNull Socket socket) {
        int retryCount = 0;
        while (socket.isConnected()) {
            try (Socket instanceSocket = this.connect()) {
                this.doProxy(socket, instanceSocket);
                return;
            } catch (IOException e) {
                if (retryCount == SeatOfPants.config.providerMaxRetries) {
                    this.logger.severe("Timed out whilst adopting:\n%s", e);
                    return;
                }
                retryCount++;
                try {
                    Thread.sleep(100); // Try to give the process enough time to start up.
                } catch (InterruptedException e1) {}
            }
        }
    }

    private final void doProxy(Socket socket, Socket instanceSocket) {
        try (socket; instanceSocket) {
            socket.setSoTimeout(SeatOfPants.SO_TIMEOUT);
            instanceSocket.setSoTimeout(SeatOfPants.SO_TIMEOUT);

            Thread socketToInstance = Thread
                .ofVirtual()
                .name(String.format("TCP #%d->%s", socket.hashCode(), this.id))
                .start(() -> {
                    try (socket; instanceSocket) {
                        socket.getInputStream().transferTo(instanceSocket.getOutputStream());
                    } catch (IOException ignored) {}
                });
            Thread instanceToSocket = Thread
                .ofVirtual()
                .name(String.format("TCP #%d<-%s", socket.hashCode(), this.id))
                .start(() -> {
                    try (socket; instanceSocket) {
                        instanceSocket.getInputStream().transferTo(socket.getOutputStream());
                    } catch (IOException ignored) {}
                });

            socketToInstance.join();
            instanceToSocket.join();
        } catch (InterruptedException | IOException e) {
            this.logger.severe("An error occurred whilst adopting:\n%s", e);
        }
    }

}
