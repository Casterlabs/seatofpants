package co.casterlabs.seatofpants.providers.impl.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.SeatOfPants;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class IPExec implements InstanceProvider {
    public static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("Exec Instance Provider");

    private String[] applicationToExec;

    @SneakyThrows
    @Override
    public void loadConfig(JsonObject providerConfig) {
        this.applicationToExec = Rson.DEFAULT.fromJson(providerConfig.get("exec"), String[].class);
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

            String[] command = this.applicationToExec.clone();
            for (int idx = 0; idx < command.length; idx++) {
                command[idx] = command[idx].replace("%port%", String.valueOf(port));
            }

            Process proc = new ProcessBuilder(command)
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
                @Override
                public void close() throws IOException {
                    this.logger.trace("Closed.");
                    proc.destroyForcibly();
                }

                @Override
                protected Socket connect() throws IOException {
                    this.logger.trace("Creating a connection!");
                    int retryCount = 0;
                    while (true) {
                        try {
                            return new Socket("127.0.0.1", port);
                        } catch (IOException e) {
                            if (retryCount == 10) throw e;
                            retryCount++;
                            try {
                                Thread.sleep(100); // Try to give the process enough time to start up.
                            } catch (InterruptedException e1) {}
                        }
                    }
                }
            };
        } catch (IOException e) {
            throw new InstanceCreationException(e);
        }
    }

}
