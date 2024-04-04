package co.casterlabs.seatofpants.providers.impl.docker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.Socket;
import java.util.Map;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.TypeToken;
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

public class DockerProvider implements InstanceProvider {
    public static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("Docker Instance Provider");

    private String imageToUse;
    private int portToMap;
    private Map<String, String> env;

    @SneakyThrows
    @Override
    public void loadConfig(JsonObject providerConfig) {
        this.imageToUse = providerConfig.getString("imageToUse");
        this.portToMap = providerConfig.getNumber("portToMap").intValue();
        this.env = Rson.DEFAULT.fromJson(
            providerConfig.get("env"),
            new TypeToken<Map<String, String>>() {
            }
        );
    }

    @Override
    public Instance create(@NonNull String idToUse) throws InstanceCreationException {
        try {
            int port = NetworkUtil.randomPort();
            FastLogger logger = LOGGER.createChild("Instance " + idToUse);

            CommandBuilder command = new CommandBuilder()
                .add("docker", "run")
                .add("--rm")
                .add("--name", idToUse)
                .add("-p", String.format("%d:%d", port, this.portToMap));
            for (Map.Entry<String, String> entry : env.entrySet()) {
                command.add(
                    "-e",
                    String.format(
                        "%s=%s",
                        entry.getKey(),
                        entry.getValue()
                            .replace("%port%", String.valueOf(port))
                    )
                );
            }
            command.add(this.imageToUse);

            Process proc = new ProcessBuilder(command.asList())
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
