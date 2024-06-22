package co.casterlabs.seatofpants;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

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
                socket.setSoTimeout(SeatOfPants.SO_TIMEOUT);
                Thread.ofPlatform().start(() -> {
                    // Don't block the socket loop.
                    SeatOfPants.handle(socket);
                });
            }
        }
    }

}
