package co.casterlabs.seatofpants.util;

import co.casterlabs.seatofpants.SeatOfPants;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class Watchdog implements AutoCloseable {
    private static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("Watchdog");

    private volatile boolean finished = false;

    public Watchdog(long timeout, String name) {
        Thread threadToInterrupt = Thread.currentThread();
        Thread
            .ofVirtual()
            .name("Watchdog Thread")
            .start(() -> {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException ignored) {}

                if (!this.finished) {
                    LOGGER.severe("'%s' task took longer than %dms, attempting to interrupt!", name, timeout);
                    threadToInterrupt.interrupt();
                }
            });

    }

    @Override
    public void close() {
        this.finished = true;
    }

}
