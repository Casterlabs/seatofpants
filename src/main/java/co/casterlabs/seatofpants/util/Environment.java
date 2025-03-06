package co.casterlabs.seatofpants.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonSerializer;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import lombok.NonNull;

@JsonClass(serializer = EnvironmentSerializer.class)
public class Environment {
    protected final Map<String, String> strings = new HashMap<>();
    protected final Map<String, FileReference> files = new HashMap<>();

    public Map<String, String> get() throws IOException {
        Map<String, String> result = new HashMap<>();
        result.putAll(this.strings);

        for (Entry<String, FileReference> entry : this.files.entrySet()) {
            String key = entry.getKey();
            FileReference value = entry.getValue();

            File reference = value.file();
            String contents = Files.readString(reference.toPath(), StandardCharsets.UTF_8);

            result.put(key, contents);
        }

        return result;
    }

}

record FileReference(String raw, File file) {
}

class EnvironmentSerializer implements JsonSerializer<Environment> {
    private static final String FILE_PREFIX = "file:";

    @Override
    public @Nullable Environment deserialize(@NonNull JsonElement e, @NonNull Class<?> type, @NonNull Rson rson) throws JsonParseException {
        Environment env = new Environment();
        JsonObject map = (JsonObject) e;

        for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().getAsString();

            if (value.startsWith(FILE_PREFIX)) {
                File file = new File(value.substring(FILE_PREFIX.length()));
                env.files.put(key, new FileReference(value, file));
            } else {
                env.strings.put(key, value);
            }
        }

        return env;
    }

    @Override
    public JsonElement serialize(@NonNull Object v, @NonNull Rson rson) {
        Environment env = (Environment) v;
        JsonObject map = new JsonObject();

        for (Map.Entry<String, String> entry : env.strings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            map.put(key, value);
        }

        for (Entry<String, FileReference> entry : env.files.entrySet()) {
            String key = entry.getKey();
            FileReference value = entry.getValue();

            map.put(key, value.raw());
        }

        return map;
    }

}
