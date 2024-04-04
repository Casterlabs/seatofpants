package co.casterlabs.seatofpants.providers.impl.docker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.SeatOfPants;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class DockerProvider implements InstanceProvider {
    public static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("Docker Instance Provider");

    private String imageToUse;
    private int portToMap;

    @SneakyThrows
    @Override
    public void loadConfig(JsonObject providerConfig) {
        this.imageToUse = providerConfig.getString("imageToUse");
        this.portToMap = providerConfig.getNumber("portToMap").intValue();
    }

    @Override
    public Instance create(@NonNull String idToUse) throws InstanceCreationException {
        try {
            int port;
            try (ServerSocket serverSocket = new ServerSocket()) {
                serverSocket.setReuseAddress(false);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", 0), 1);
                port = serverSocket.getLocalPort();
            }

            FastLogger logger = LOGGER.createChild("Instance " + idToUse);

            Process proc = new ProcessBuilder(
                "docker",
                "run",
                "--rm",
                "--name", idToUse,
                "-p", String.format("%d:%d", port, this.portToMap),
                this.imageToUse
            )
                .redirectError(Redirect.PIPE)
                .redirectOutput(Redirect.PIPE)
                .redirectInput(Redirect.PIPE)
                .start();

            Thread
                .ofVirtual()
                .name("A Log thread")
                .start(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            logger.trace(line);
                        }
                    } catch (IOException ignored) {}
                });
            Thread
                .ofVirtual()
                .name("A Log thread")
                .start(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            logger.trace(line);
                        }
                    } catch (IOException ignored) {}
                });

            return new Instance(idToUse, logger) {
                @SneakyThrows
                @Override
                public void close() throws IOException {
                    this.logger.trace("Closed.");
                    new ProcessBuilder(
                        "docker",
                        "kill",
                        idToUse
                    )
                        .redirectError(Redirect.PIPE)
                        .redirectOutput(Redirect.PIPE)
                        .redirectInput(Redirect.PIPE)
                        .start()
                        .waitFor();
                }

                @Override
                protected Socket connect() throws IOException {
                    return new Socket("127.0.0.1", port);
                }
            };
        } catch (IOException e) {
            throw new InstanceCreationException(e);
        }
    }

}
