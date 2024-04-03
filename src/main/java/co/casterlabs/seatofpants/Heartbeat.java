package co.casterlabs.seatofpants;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

public class Heartbeat extends Thread implements Closeable {
    private static final HttpClient httpClient = HttpClient
        .newBuilder()
        .followRedirects(Redirect.ALWAYS)
        .build();

    private boolean shouldRun = true;

    protected Heartbeat() {
        this.setName("Heartbeat Thread");
        this.setPriority(Thread.MIN_PRIORITY);
        this.setDaemon(true);
    }

    @Override
    public void run() {
        while (this.shouldRun) {
            try {
                String response = httpClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(SeatOfPants.config.heartbeatUrl))
                        .header("Content-Type", "text/plain")
                        .GET()
//                        .POST(
//                            HttpRequest.BodyPublishers.ofString(
//                                String.valueOf(System.currentTimeMillis())
//                            )
//                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body();
                FastLogger.logStatic(LogLevel.DEBUG, "Sent heartbeat.\n%s", response);
            } catch (IOException | InterruptedException e) {
                FastLogger.logStatic(LogLevel.WARNING, "Unable to send heartbeat:\n%s", e);
            }

            try {
                TimeUnit.SECONDS.sleep(SeatOfPants.config.heartbeatIntervalSeconds);
            } catch (InterruptedException ignored) {}
        }
    }

    @Override
    public void close() throws IOException {
        this.shouldRun = false;
    }

}
