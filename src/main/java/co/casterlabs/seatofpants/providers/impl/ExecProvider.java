package co.casterlabs.seatofpants.providers.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.Socket;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.SeatOfPants;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import co.casterlabs.seatofpants.util.CommandBuilder;
import co.casterlabs.seatofpants.util.NetworkUtil;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class ExecProvider implements InstanceProvider {
    public static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("Exec Instance Provider");

    private Config config;

    @JsonClass(exposeAll = true)
    private static class Config {
        private String[] applicationToExec = {};

    }

    @Override
    public JsonObject getConfig() {
        return (JsonObject) Rson.DEFAULT.toJson(this.config);
    }

    @SneakyThrows
    @Override
    public void loadConfig(JsonObject providerConfig) {
        this.config = Rson.DEFAULT.fromJson(providerConfig, Config.class);
    }

    @Override
    public Instance create(@NonNull String idToUse) throws InstanceCreationException {
        try {
            int port = NetworkUtil.randomPort();
            FastLogger logger = LOGGER.createChild("Instance " + idToUse);

            CommandBuilder command = new CommandBuilder();
            for (String part : this.config.applicationToExec) {
                command.add(
                    part
                        .replace("%port%", String.valueOf(port))
                );
            }

            Process proc = new ProcessBuilder(command.asList())
                .redirectError(Redirect.PIPE)
                .redirectOutput(Redirect.PIPE)
                .redirectInput(Redirect.PIPE)
                .start();

            Thread
                .ofVirtual()
                .name("A log thread")
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
                .name("A log thread")
                .start(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            logger.trace(line);
                        }
                    } catch (IOException ignored) {}
                });

            Thread
                .ofVirtual()
                .name("A watchdog thread")
                .start(() -> {
                    try {
                        proc.waitFor();
                    } catch (InterruptedException ignored) {}
                    SeatOfPants.tick();
                });

            return new Instance(idToUse, logger) {
                @Override
                protected Socket connect() throws IOException {
                    return new Socket("127.0.0.1", port);
                }

                @Override
                public String getAddress() {
                    return String.format("127.0.0.1:%d", port);
                }

                @Override
                public boolean isAlive() {
                    return proc.isAlive();
                }

                @Override
                public void close() throws IOException {
                    this.logger.trace("Closed.");
                    proc.destroyForcibly();
                }
            };
        } catch (IOException e) {
            throw new InstanceCreationException(e);
        }
    }

}
