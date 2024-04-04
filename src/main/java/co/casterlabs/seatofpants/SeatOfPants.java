package co.casterlabs.seatofpants;

import java.util.UUID;

import co.casterlabs.seatofpants.config.Config;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class SeatOfPants {
    public static final int SO_TIMEOUT = 2 * 60 * 1000; // 2 minutes
    public static final FastLogger LOGGER = new FastLogger();

    public static Config config;
    public static Daemon daemon;
    public static Heartbeat heartbeat;
    public static InstanceProvider provider;

    public static String generateId() {
        return "SOP." + config.sopId + "." + UUID.randomUUID().toString();
    }

}
