package co.casterlabs.seatofpants.config;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import lombok.ToString;

@ToString
@JsonClass(exposeAll = true)
public class Config {
    public @JsonField("_DO NOT CHANGE!") @Nullable String sopId = UUID.randomUUID().toString();

    public boolean debug = false;
    public int port = 10246;

    public @Nullable String heartbeatUrl = null;
    public long heartbeatIntervalSeconds = 15;

    public InstanceProvider.Type providerType;
    public JsonObject providerConfig;
    public int providerMaxRetries = 500;
    public long providerRetryTimeout = 100;

    public long providerMaxCreationTime = 120 * 1000;

    public int maxInstancesLimit = -1;
    public int maxConnectionsPerInstance = 1;
    public double instanceWarmRatio = 1;
    public long instanceMaxAgeMinutes = -1; // -1 to disable.
    public InstanceExpireBehavior expirationBehavior = InstanceExpireBehavior.WAIT_FOR_LAST_CONNECTIONS;
    public ScalingBehavior scalingBehavior = ScalingBehavior.DYNAMIC_POOL;
    public long instanceConnectionRateSeconds = -1; // -1 to disable.

    public int apiPort = -1; // -1 to disable.

    public static enum InstanceExpireBehavior {
        WAIT_FOR_LAST_CONNECTIONS,
        KILL_INSTANTLY,
        ;
    }

    public static enum ScalingBehavior {
        DYNAMIC_POOL,
        FIXED_POOL,
        ;
    }

}
