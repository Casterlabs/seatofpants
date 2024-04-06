package co.casterlabs.seatofpants.providers;

import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.providers.impl.DockerProvider;
import co.casterlabs.seatofpants.providers.impl.ExecProvider;
import co.casterlabs.seatofpants.providers.impl.OracleContainerInstancesProvider;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

public interface InstanceProvider {

    public JsonObject getConfig();

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
        ORACLE(OracleContainerInstancesProvider.class),
        ;

        private final Class<? extends InstanceProvider> clazz;

        @SneakyThrows
        public InstanceProvider newInstance() {
            return this.clazz.getDeclaredConstructor().newInstance();
        }

    }

}
