package co.casterlabs.seatofpants;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import co.casterlabs.seatofpants.providers.Instance;
import co.casterlabs.seatofpants.providers.InstanceCreationException;
import lombok.SneakyThrows;

public class Daemon {

    @SneakyThrows
    public void start() {
        try (
            ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("::", SeatOfPants.config.port));

            while (serverSocket.isBound()) {
                Socket socket = serverSocket.accept();

                // TODO instance sharding.
                Thread
                    .ofVirtual()
                    .name(String.format("TCP #%d", socket.hashCode()))
                    .start(() -> {
                        try (Instance instance = SeatOfPants.provider.create(SeatOfPants.generateId())) {
                            instance.adopt(socket);
                        } catch (InstanceCreationException e) {
                            SeatOfPants.LOGGER.fatal("Unable to create instance! THIS IS BAD!\n%s", e);
                        } catch (IOException e) {
                            SeatOfPants.LOGGER.debug(e);
                        }
                    });
            }
        }
    }

}
