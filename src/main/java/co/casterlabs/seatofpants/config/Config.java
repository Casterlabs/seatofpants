package co.casterlabs.seatofpants.config;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonDeserializationMethod;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import lombok.ToString;

@ToString
@JsonClass(exposeAll = true)
public class Config {
    public @JsonField("_DO NOT CHANGE!") @Nullable String sopId = UUID.randomUUID().toString();

    public boolean debug = false;
    public int port = 10246;
    public int apiPort = -1; // -1 to disable.

    public @Nullable String heartbeatUrl = null;
    public long heartbeatIntervalSeconds = 15;

    public InstanceProvider.Type providerType;
    public JsonObject providerConfig;
    public int providerMaxRetries = 500;
    public long providerMaxCreationTimeSeconds = 120;
    public long providerInstanceWaitTimeSeconds = 5; // Time to wait for the container be ready. -1 to disable.

    public ScalingBehavior scalingBehavior = ScalingBehavior.DYNAMIC_POOL;
    public int maxInstancesLimit = -1;
    public int maxConnectionsPerInstance = 1;
    public double instanceWarmRatio = 1;

    public InstanceExpireBehavior expirationBehavior = InstanceExpireBehavior.WAIT_FOR_LAST_CONNECTIONS;
    public long instanceMaxAgeMinutes = -1; // -1 to disable.
    public long instanceAboutToExpireMinutes = 3;
    public long killAfterWaitingForLastMinutes = -1; // -1 to disable.

    public long instanceConnectionRateMilliseconds = -1; // -1 to disable.
    public long instanceDisconnectionRateMilliseconds = -1; // -1 to disable.

    @JsonDeserializationMethod("providerMaxCreationTime")
    private void $deserialize_providerMaxCreationTime(JsonElement e) {
        this.providerMaxCreationTimeSeconds = e.getAsNumber().longValue() / 1000;
    }

    @JsonDeserializationMethod("instanceConnectionRateSeconds")
    private void $deserialize_instanceConnectionRateSeconds(JsonElement e) {
        this.instanceConnectionRateMilliseconds = e.getAsNumber().longValue() * 1000;
    }

    @JsonDeserializationMethod("instanceDisconnectionRateSeconds")
    private void $deserialize_instanceDisconnectionRateSeconds(JsonElement e) {
        this.instanceDisconnectionRateMilliseconds = e.getAsNumber().longValue() * 1000;
    }

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
