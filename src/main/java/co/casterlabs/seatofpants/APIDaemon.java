package co.casterlabs.seatofpants;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rhs.protocol.StandardHttpStatus;
import co.casterlabs.rhs.server.HttpListener;
import co.casterlabs.rhs.server.HttpResponse;
import co.casterlabs.rhs.server.HttpServer;
import co.casterlabs.rhs.server.HttpServerBuilder;
import co.casterlabs.rhs.session.HttpSession;
import co.casterlabs.rhs.session.WebsocketListener;
import co.casterlabs.rhs.session.WebsocketSession;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

public class APIDaemon {

    @SneakyThrows
    public void start() {
        if (SeatOfPants.config.apiPort == -1) return;

        HttpServer server = new HttpServerBuilder()
            .withPort(SeatOfPants.config.apiPort)
            .build(new HttpListener() {
                @Override
                public @Nullable HttpResponse serveHttpSession(@NonNull HttpSession session) {
                    try {
                        switch (session.getUri()) {
                            case "/instances/addresses":
                                return HttpResponse.newFixedLengthResponse(
                                    StandardHttpStatus.OK,
                                    String.join("\n", SeatOfPants.getInstanceAddresses())
                                );

                            case "/connections/count":
                                return HttpResponse.newFixedLengthResponse(
                                    StandardHttpStatus.OK,
                                    String.valueOf(SeatOfPants.getConnectionCount())
                                );

                            default:
                                return HttpResponse.newFixedLengthResponse(StandardHttpStatus.NOT_FOUND, "Not found.");
                        }
                    } catch (Throwable t) {
                        FastLogger.logStatic(LogLevel.SEVERE, "Unable to API request!\n%s", t);
                        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.INTERNAL_ERROR, "An internal error occurred.");
                    }
                }

                @Override
                public @Nullable WebsocketListener serveWebsocketSession(@NonNull WebsocketSession session) {
                    return null; // Drop.
                }
            });

        server.start();
    }

}
