package co.casterlabs.seatofpants.providers;

import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.providers.impl.docker.DockerProvider;
import co.casterlabs.seatofpants.providers.impl.exec.ExecProvider;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

public interface InstanceProvider {

    public void loadConfig(JsonObject providerConfig);

    /**
     * @throws InstanceCreationException only when all reasonable attempts have
     *                                   failed. Use this to indicate I/O,
     *                                   authentication, storage, or permission
     *                                   errors.
     */
    public Instance create(@NonNull String idToUse) throws InstanceCreationException;

    @AllArgsConstructor
    public static enum Type {
        EXEC(ExecProvider.class),
        DOCKER(DockerProvider.class),
        ;

        private final Class<? extends InstanceProvider> clazz;

        @SneakyThrows
        public InstanceProvider newInstance() {
            return this.clazz.getDeclaredConstructor().newInstance();
        }

    }

}
