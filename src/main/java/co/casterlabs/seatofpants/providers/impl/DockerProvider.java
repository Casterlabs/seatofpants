package co.casterlabs.seatofpants.providers.impl;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.Socket;
import java.util.Map;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.SeatOfPants;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import co.casterlabs.seatofpants.util.CommandBuilder;
import co.casterlabs.seatofpants.util.Environment;
import co.casterlabs.seatofpants.util.NetworkUtil;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class DockerProvider implements InstanceProvider {
    public static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("Docker Instance Provider");

    private Config config;

    @JsonClass(exposeAll = true)
    private static class Config {
        public String imageToUse = "hashicorp/http-echo";
        public Environment env = new Environment();
        public int portToMap = 5678;

        public double cpuLimit = -1; // -1 = no limit, 1 = 1 core, 1.5 = 1.5 cores, etc
        public int memoryLimitGb = -1; // -1 = no limit
        public int swapLimitMb = 0; // -1 = no limit, 0 = same as memory limit

        private String authRegistry = null; // Null to disable.
        private String authUsername = null; // Null to disable.
        private String authPassword = null; // Null to disable.

    }

    @Override
    public JsonObject getConfig() {
        return (JsonObject) Rson.DEFAULT.toJson(this.config);
    }

    @SneakyThrows
    @Override
    public void loadConfig(JsonObject providerConfig) {
        this.config = Rson.DEFAULT.fromJson(providerConfig, Config.class);

        if (this.config.authRegistry != null && this.config.authUsername != null && this.config.authPassword != null) {
            CommandBuilder command = new CommandBuilder()
                .add("docker", "login")
                .add(this.config.authRegistry)
                .add("--username", this.config.authUsername)
                .add("--password", this.config.authPassword);
            new ProcessBuilder(command.asList())
                .redirectError(Redirect.INHERIT)
                .redirectOutput(Redirect.INHERIT)
                .redirectInput(Redirect.PIPE)
                .start()
                .waitFor();
        }
    }

    @Override
    public Instance create(@NonNull String idToUse) throws InstanceCreationException {
        try {
            int port = NetworkUtil.randomPort();
            FastLogger logger = LOGGER.createChild("Instance " + idToUse);

            CommandBuilder command = new CommandBuilder()
                .add("docker", "run")
                .add("--rm")
                .add("--pull", "always")
                .add("--name", idToUse)
                .add("-p", String.format("127.0.0.1:%d:%d", port, this.config.portToMap));
            for (Map.Entry<String, String> entry : this.config.env.get().entrySet()) {
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

            if (this.config.cpuLimit > 0) {
                command.add("--cpus", String.valueOf(this.config.cpuLimit));
            }

            if (this.config.memoryLimitGb > 0) {
                command.add("--memory", String.valueOf(this.config.memoryLimitGb) + "g");
            }

            if (this.config.swapLimitMb == -1) {
                command.add("--memory-swap", "-1");
            } else if (this.config.swapLimitMb > 0) {
                command.add("--memory-swap", String.valueOf(this.config.swapLimitMb) + "m");
            }

            command.add(this.config.imageToUse);

            Process proc = new ProcessBuilder(command.asList())
                .redirectError(Redirect.DISCARD)
                .redirectOutput(Redirect.DISCARD)
                .redirectInput(Redirect.PIPE)
                .start();

            Thread
                .ofVirtual()
                .name("Docker process close handler")
                .start(() -> {
                    try {
                        proc.waitFor();
                    } catch (InterruptedException ignored) {
                        Thread.interrupted(); // Clear
                    }
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
                protected boolean isAlive0() {
                    return proc.isAlive();
                }

                @SneakyThrows
                @Override
                protected void close0() {
                    this.logger.trace("Closed.");
                    new ProcessBuilder(
                        "docker",
                        "stop",
                        idToUse
                    )
                        .redirectError(Redirect.PIPE)
                        .redirectOutput(Redirect.PIPE)
                        .redirectInput(Redirect.PIPE)
                        .start()
                        .waitFor();
                }
            };
        } catch (IOException e) {
            throw new InstanceCreationException(e);
        }
    }

}
