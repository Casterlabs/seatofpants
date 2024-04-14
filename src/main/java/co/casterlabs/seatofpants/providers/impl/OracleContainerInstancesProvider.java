package co.casterlabs.seatofpants.providers.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.ConfigFileReader.ConfigFile;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimplePrivateKeySupplier;
import com.oracle.bmc.containerinstances.ContainerInstanceClient;
import com.oracle.bmc.containerinstances.model.ContainerInstance;
import com.oracle.bmc.containerinstances.model.ContainerInstance.LifecycleState;
import com.oracle.bmc.containerinstances.model.CreateBasicImagePullSecretDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerInstanceDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerInstanceShapeConfigDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerVnicDetails;
import com.oracle.bmc.containerinstances.requests.CreateContainerInstanceRequest;
import com.oracle.bmc.containerinstances.requests.DeleteContainerInstanceRequest;
import com.oracle.bmc.containerinstances.requests.GetContainerInstanceRequest;
import com.oracle.bmc.containerinstances.responses.CreateContainerInstanceResponse;
import com.oracle.bmc.containerinstances.responses.GetContainerInstanceResponse;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.responses.GetVnicResponse;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.seatofpants.SeatOfPants;
import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import co.casterlabs.seatofpants.providers.InstanceProvider;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class OracleContainerInstancesProvider implements InstanceProvider {
    public static final FastLogger LOGGER = SeatOfPants.LOGGER.createChild("OCI Instance Provider");

    private ContainerInstanceClient containerClient;
    private VirtualNetworkClient vnicClient;

    private Config config;

    @JsonClass(exposeAll = true)
    private static class Config {
        // Endpoints
        public String containerInstanceEndpoint = "https://compute-containers.us-ashburn-1.oci.oraclecloud.com"; // https://docs.oracle.com/en-us/iaas/api/#/en/container-instances/20210415/
        public String coreApiEndpoint = "https://iaas.us-ashburn-1.oraclecloud.com"; // https://docs.oracle.com/en-us/iaas/api/#/en/iaas/20160918/

        // Availability
        public String compartmentId;
        public String availabilityDomain = "drsJ:US-ASHBURN-AD-1"; /* To get this name, use `oci iam availability-domain list --compartment-id ...` in the cloud shell. See this to get a compartment's id https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/contactingsupport_topic-Locating_Oracle_Cloud_Infrastructure_IDs.htm#Finding_the_OCID_of_a_Compartment */
        public String faultDomain = "FAULT-DOMAIN-1"; /* Use FAULT-DOMAIN-1, FAULT-DOMAIN-2, or FAULT-DOMAIN-3. Or, use `oci iam fault-domain list --availability-domain ... --compartment-id ...` in the cloud shell. Use the availability domain's name and the compartment id from above */

        // Shape
        public String shape = "CI.Standard.E4.Flex"; // https://docs.oracle.com/en-us/iaas/Content/container-instances/container-instance-shapes.htm.
                                                     // E3 = Intel, E4 = AMD, A1 = Ampere
        public float ocpuCount = 1;
        public float memoryGbs = 1;

        // Image
        public String imageUrl = "docker.io/crccheck/hello-world:latest";
        public Map<String, String> env = Collections.emptyMap();
        public long gracefulShutdownTimeoutSeconds = 30; // Seconds, max 300

        public String registryAuthEndpoint; // Usually docker.io or something like iad.ocir.io.
        public String registryAuthUsername; /* Optional, https://docs.oracle.com/en-us/iaas/api/#/en/container-instances/20210415/datatypes/CreateBasicImagePullSecretDetails */
        public String registryAuthPassword; // See above^

        // Network
        public String subnetId; // Open the VCN page, go to the desired subnet and copy the OCID.
        public int port = 8000;

    }

    @Override
    public JsonObject getConfig() {
        return (JsonObject) Rson.DEFAULT.toJson(this.config);
    }

    @SneakyThrows
    @Override
    public void loadConfig(JsonObject providerConfig) {
        this.config = Rson.DEFAULT.fromJson(providerConfig, Config.class);

        ConfigFile oConfig = ConfigFileReader.parse("./oci.config");
        Supplier<InputStream> oPK = new SimplePrivateKeySupplier(oConfig.get("key_file"));

        AuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
            .tenantId(oConfig.get("tenancy"))
            .userId(oConfig.get("user"))
            .fingerprint(oConfig.get("fingerprint"))
            .privateKeySupplier(oPK)
            .build();

        this.containerClient = ContainerInstanceClient
            .builder()
            .endpoint(this.config.containerInstanceEndpoint)
            .build(provider);
        this.vnicClient = VirtualNetworkClient
            .builder()
            .endpoint(this.config.coreApiEndpoint)
            .build(provider);
    }

    @Override
    public Instance create(@NonNull String idToUse) throws InstanceCreationException {
        try {
            FastLogger logger = LOGGER.createChild("Instance " + idToUse);

            String containerId;
            String vnicId;
            String privateIp;

            long startedCreatingAt = System.currentTimeMillis();

            {
                CreateContainerInstanceDetails.Builder createContainerInstanceDetails = CreateContainerInstanceDetails.builder()
                    .displayName(idToUse)
                    .compartmentId(this.config.compartmentId)
                    .availabilityDomain(this.config.availabilityDomain)
                    .faultDomain(this.config.faultDomain)
                    .shape(this.config.shape)
                    .shapeConfig(
                        CreateContainerInstanceShapeConfigDetails.builder()
                            .ocpus(this.config.ocpuCount)
                            .memoryInGBs(this.config.memoryGbs)
                            .build()
                    )
                    .containers(
                        Arrays.asList(
                            CreateContainerDetails.builder()
                                .displayName("container." + idToUse)
                                .imageUrl(this.config.imageUrl)
                                .environmentVariables(this.config.env)
//                                .volumeMounts(
//                                    new ArrayList<>(
//                                        Arrays.asList(
//                                            CreateVolumeMountDetails.builder()
//                                                .mountPath("EXAMPLE-mountPath-Value")
//                                                .volumeName("EXAMPLE-volumeName-Value")
//                                                .subPath("EXAMPLE-subPath-Value")
//                                                .isReadOnly(false)
//                                                .partition(32).build()
//                                        )
//                                    )
//                                )
                                .build()
                        )
                    )
                    .vnics(
                        Arrays.asList(
                            CreateContainerVnicDetails.builder()
                                .displayName("vnic." + idToUse)
                                .privateIp(idToUse)
                                .isPublicIpAssigned(false)
                                .skipSourceDestCheck(false)
                                .privateIp(null) // Auto allocate
                                .subnetId(this.config.subnetId)
                                .build()
                        )
                    )
                    .gracefulShutdownTimeoutInSeconds(this.config.gracefulShutdownTimeoutSeconds)
                    .containerRestartPolicy(ContainerInstance.ContainerRestartPolicy.Never);

                if (this.config.registryAuthEndpoint != null && this.config.registryAuthUsername != null && this.config.registryAuthPassword != null) {
                    createContainerInstanceDetails.imagePullSecrets(
                        new ArrayList<>(
                            Arrays.asList(
                                CreateBasicImagePullSecretDetails.builder()
                                    .registryEndpoint(this.config.registryAuthEndpoint)
                                    .username(Base64.getEncoder().encodeToString(this.config.registryAuthUsername.getBytes(StandardCharsets.UTF_8)))
                                    .password(Base64.getEncoder().encodeToString(this.config.registryAuthPassword.getBytes(StandardCharsets.UTF_8)))
                                    .build()
                            )
                        )
                    );
                }

                CreateContainerInstanceResponse containerCreationResponse = this.containerClient.createContainerInstance(
                    CreateContainerInstanceRequest.builder()
                        .createContainerInstanceDetails(createContainerInstanceDetails.build())
                        .build()
                );
                containerId = containerCreationResponse.getContainerInstance().getId();
                logger.debug("containerId=%s", containerId);

            }

            Runnable destroyInstance = () -> {
                this.containerClient.deleteContainerInstance(
                    DeleteContainerInstanceRequest.builder()
                        .containerInstanceId(containerId)
                        .build()
                );
            };
            SeatOfPants.runOnClose.add(destroyInstance); // In case the deletion needs to occur before SOP knows about the instance.

            {
                LifecycleState state;
                String stateMessage;
                ContainerInstance updated;
                int spinCount = 1;
                do {
                    updated = this.containerClient.getContainerInstance(
                        GetContainerInstanceRequest.builder()
                            .containerInstanceId(containerId)
                            .build()
                    )
                        .getContainerInstance();
                    state = updated
                        .getLifecycleState();
                    stateMessage = updated.getLifecycleDetails();
//                    logger.trace("Waiting for container: %s (%s)", state, stateMessage);

                    Thread.sleep(Math.min(500 * spinCount, 5000)); // A backoff, capped at 5s.
                    spinCount++;
                } while (state == LifecycleState.Creating);

                if (state != LifecycleState.Active) {
                    throw new IllegalStateException(String.format("Container is of state: %s (%s)", state, stateMessage));
                }

                vnicId = updated
                    .getVnics()
                    .get(0)
                    .getVnicId();
                logger.debug("vnicId=%s", vnicId);
            }
            {
                GetVnicResponse vnicResponse = this.vnicClient.getVnic(
                    GetVnicRequest.builder()
                        .vnicId(vnicId)
                        .build()
                );

                privateIp = vnicResponse.getVnic().getPrivateIp();

                logger.debug("privateIp=%s", privateIp);
            }

            SeatOfPants.runOnClose.remove(destroyInstance); // SOP will take it from here :)
            logger.debug("Created! Took %.2fs.", (System.currentTimeMillis() - startedCreatingAt) / 1000d);

            return new Instance(idToUse, logger) {
                private boolean hasBeenDestroyedAlready = false;

                @Override
                protected Socket connect() throws IOException {
                    return new Socket(privateIp, config.port);
                }

                @Override
                public boolean isAlive() {
                    if (this.hasBeenDestroyedAlready) {
                        return false;
                    }

                    GetContainerInstanceResponse current = containerClient.getContainerInstance(
                        GetContainerInstanceRequest.builder()
                            .containerInstanceId(containerId)
                            .build()
                    );

                    boolean alive = current.get__httpStatusCode__() == 200 && current
                        .getContainerInstance()
                        .getLifecycleState() == LifecycleState.Active;

                    if (!alive) {
                        // Do cleanup.
                        try {
                            this.close();
                        } catch (IOException ignored) {}
                    }

                    return alive;
                }

                @Override
                public void close() throws IOException {
                    if (this.hasBeenDestroyedAlready) {
                        return;
                    }

                    try {
                        destroyInstance.run();
                    } catch (Exception e) {
                        throw new IOException(e);
                    } finally {
                        this.hasBeenDestroyedAlready = true;
                    }
                }
            };
        } catch (Exception e) {
            throw new InstanceCreationException(e);
        }
    }

}
