package co.casterlabs.seatofpants.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class NetworkUtil {

    public static int randomPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(false);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            return serverSocket.getLocalPort();
        }
    }

}
