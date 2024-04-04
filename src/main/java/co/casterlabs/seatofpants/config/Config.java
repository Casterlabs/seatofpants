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

}
