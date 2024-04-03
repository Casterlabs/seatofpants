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
        try {
            try (socket; Socket instanceSocket = this.connect()) {
//                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SeatOfPants.SO_TIMEOUT);
//                instanceSocket.setTcpNoDelay(true);
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
            }
        } catch (InterruptedException | IOException e) {
            this.logger.severe("An error occurred whilst adopting:\n%s", e);
        }
    }

}
