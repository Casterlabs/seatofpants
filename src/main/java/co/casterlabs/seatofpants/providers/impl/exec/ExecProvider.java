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
import co.casterlabs.seatofpants.util.CommandBuilder;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class ExecProvider implements InstanceProvider {
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

            CommandBuilder command = new CommandBuilder();
            for (String part : this.applicationToExec) {
                command.add(part.replace("%port%", String.valueOf(port)));
            }

            Process proc = new ProcessBuilder(command.toString())
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
                    return new Socket("127.0.0.1", port);
                }
            };
        } catch (IOException e) {
            throw new InstanceCreationException(e);
        }
    }

}
