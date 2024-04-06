package co.casterlabs.seatofpants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.seatofpants.config.Config;
import co.casterlabs.seatofpants.util.FileWatcher;
import xyz.e3ndr.fastloggingframework.FastLoggingFramework;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

public class Bootstrap {
    private static final File CONFIG_FILE = new File("config.json");

    public static void main(String[] args) throws IOException {
        System.setProperty("fastloggingframework.wrapsystem", "true");
        FastLoggingFramework.setColorEnabled(false);

        reload();

        // Update the file with any new defaults.
        SeatOfPants.config.providerConfig = SeatOfPants.provider.getConfig();
        Files.writeString(
            CONFIG_FILE.toPath(),
            Rson.DEFAULT
                .toJson(SeatOfPants.config)
                .toString(true)
        );

        new FileWatcher(CONFIG_FILE) {
            @Override
            public void onChange() {
                try {
                    reload();
                    SeatOfPants.LOGGER.info("Reloaded config file successfully!");
                } catch (Throwable t) {
                    SeatOfPants.LOGGER.severe("Unable to reload config file:\n%s", t);
                }
            }
        }
            .start();
    }

    private static void reload() throws IOException {
        if (!CONFIG_FILE.exists()) {
            SeatOfPants.LOGGER.info("Config file doesn't exist, creating a new file. Modify it and re-start SOP.");
            Files.writeString(
                CONFIG_FILE.toPath(),
                Rson.DEFAULT
                    .toJson(new Config())
                    .toString(true)
            );
            System.exit(0);
        }

        Config config;
        try {
            config = Rson.DEFAULT.fromJson(Files.readString(CONFIG_FILE.toPath()), Config.class);
        } catch (JsonParseException e) {
            SeatOfPants.LOGGER.severe("Unable to parse config file, is it malformed?\n%s", e);
            return;
        }

        boolean isNew = SeatOfPants.config == null;
        SeatOfPants.config = config;

        // Logging
        FastLoggingFramework.setDefaultLevel(config.debug ? LogLevel.ALL : LogLevel.INFO);
        SeatOfPants.LOGGER.setCurrentLevel(FastLoggingFramework.getDefaultLevel());

        // Reconfigure heartbeats.
        if (SeatOfPants.heartbeat != null) {
            SeatOfPants.heartbeat.close();
            SeatOfPants.heartbeat = null;
        }

        if (config.heartbeatUrl != null && config.heartbeatIntervalSeconds > 0) {
            SeatOfPants.heartbeat = new Heartbeat();
            SeatOfPants.heartbeat.start();
        }

        if (isNew) {
            // Init the provider.
            SeatOfPants.provider = SeatOfPants.config.providerType.newInstance();
            SeatOfPants.provider.loadConfig(SeatOfPants.config.providerConfig);

            SeatOfPants.tick();

            // Start the daemon
            SeatOfPants.daemon = new Daemon();
            Thread
                .ofPlatform()
                .name("Daemon")
                .start(SeatOfPants.daemon::start);
        } else {
            SeatOfPants.provider.loadConfig(SeatOfPants.config.providerConfig); // We can reload the config safely tho.
            SeatOfPants.LOGGER.warn("SeatOfPants does not support changing the server port or the provider type while running. You will need to fully restart for any changes to take effect.");
        }
    }

}
