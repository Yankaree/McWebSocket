package me.ngcsonsplash.mcwebsocket;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WebSocketClientManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketClientManager.class);
    private WebSocketClient wsClient;
    private final URI serverUri;
    private ScheduledExecutorService scheduler;

    private int maxReconnectionAttempts;
    private int reconnectionAttempts = 0;
    private boolean reconnectionStopped = false;
    private final MessageListener messageListener;

    public interface MessageListener {
        void onMessageReceived(String message);
    }

    public enum ConnectionStatus {
        CONNECTED("Đã kết nối"),
        DISCONNECTED("Đã ngắt kết nối"),
        RECONNECTING("Đang kết nối lại"),
        RECONNECTION_STOPPED("Đã dừng kết nối lại sau nhiều lần thất bại");

        private final String vietnameseStatus;

        ConnectionStatus(String vietnameseStatus) {
            this.vietnameseStatus = vietnameseStatus;
        }

        public String getVietnameseStatus() {
            return vietnameseStatus;
        }
    }

    public WebSocketClientManager(String uri, int maxReconnectionAttempts, MessageListener messageListener) throws URISyntaxException {
        this.serverUri = new URI(uri);
        this.maxReconnectionAttempts = maxReconnectionAttempts;
        this.messageListener = messageListener;
        this.wsClient = createClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    private WebSocketClient createClient() {
        return new WebSocketClient(serverUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                LOGGER.info("Connected to WebSocket server: " + getURI());
                reconnectionAttempts = 0;
                reconnectionStopped = false;
            }

            @Override
            public void onMessage(String message) {
                LOGGER.debug("Received message from WebSocket: " + message);
                if (messageListener != null) {
                    messageListener.onMessageReceived(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                LOGGER.warn("Disconnected from WebSocket server: " + reason + " (Code: " + code + ", Remote: " + remote + ")");
                // Attempt to reconnect after a delay
                if (remote) { // Only try to reconnect if the disconnection was from the server
                    reconnect();
                }
            }

            @Override
            public void onError(Exception ex) {
                LOGGER.error("WebSocket error: " + ex.getMessage(), ex);
                if (reconnectionAttempts >= maxReconnectionAttempts) {
                    LOGGER.error("Đã vượt quá số lần thử kết nối lại tối đa. Vui lòng sử dụng lệnh '/mcwebsocket reload' để thử lại.");
                    reconnectionStopped = true;
                }
            }
        };
    }

    public void connect() {
        if (reconnectionStopped) {
            LOGGER.info("Kết nối lại đã dừng. Vui lòng sử dụng lệnh '/mcwebsocket reload' để thử lại.");
            return;
        }
        if (wsClient == null) {
            wsClient = createClient();
        }
        if (!wsClient.isOpen()) {
            LOGGER.info("Attempting to connect to WebSocket server at: " + serverUri);
            wsClient.connect();
        } else {
            LOGGER.debug("WebSocket client is already connected.");
        }
    }
    
    public void connectBlocking() {
        if (reconnectionStopped) {
            LOGGER.info("Kết nối lại đã dừng. Vui lòng sử dụng lệnh '/mcwebsocket reload' để thử lại.");
            return;
        }
        if (wsClient == null) {
            wsClient = createClient();
        }
        if (!wsClient.isOpen()) {
            LOGGER.info("Attempting to connect to WebSocket server at: " + serverUri);
            try {
                wsClient.connectBlocking();
            } catch (InterruptedException e) {
                LOGGER.error("Kết nối WebSocket bị gián đoạn: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        } else {
            LOGGER.debug("WebSocket client is already connected.");
        }
    }


    public void disconnect() {
        if (wsClient != null && wsClient.isOpen()) {
            LOGGER.info("Disconnecting from WebSocket server.");
            wsClient.close();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow(); // Stop reconnection attempts
        }
    }

    public void send(String message) {
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.send(message);
            LOGGER.debug("Sent message: " + message);
        } else {
            LOGGER.warn("WebSocket client is not connected. Message not sent: " + message);
        }
    }

    private void reconnect() {
        if (reconnectionStopped) {
            LOGGER.warn("Reconnection attempts stopped. Not attempting to reconnect.");
            return;
        }

        reconnectionAttempts++;
        if (reconnectionAttempts > maxReconnectionAttempts) {
            LOGGER.error("Đã vượt quá số lần thử kết nối lại tối đa ({} lần). Vui lòng sử dụng lệnh '/mcwebsocket reload' để thử lại.", maxReconnectionAttempts);
            reconnectionStopped = true;
            return;
        }

        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }

        if (!scheduler.isShutdown()) {
            LOGGER.info("Attempting to reconnect to WebSocket (Lần thứ {}/{}) trong 5 giây...", reconnectionAttempts, maxReconnectionAttempts);
            scheduler.schedule(() -> {
                try {
                    // Create a new client instance for reconnection
                    wsClient = createClient();
                    wsClient.connectBlocking(); // Use connectBlocking to wait for connection
                } catch (InterruptedException e) {
                    LOGGER.error("Kết nối lại WebSocket bị gián đoạn: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }, 5, TimeUnit.SECONDS);
        }
    }

    public void resetReconnectionAttempts() {
        reconnectionAttempts = 0;
        reconnectionStopped = false;
        LOGGER.info("Đã đặt lại số lần thử kết nối lại WebSocket.");
    }

    public ConnectionStatus getConnectionStatus() {
        if (wsClient == null || !wsClient.isOpen()) {
            if (reconnectionStopped) {
                return ConnectionStatus.RECONNECTION_STOPPED;
            }
            if (reconnectionAttempts > 0) {
                return ConnectionStatus.RECONNECTING;
            }
            return ConnectionStatus.DISCONNECTED;
        }
        return ConnectionStatus.CONNECTED;
    }

    public int getReconnectionAttempts() {
        return reconnectionAttempts;
    }

    public int getMaxReconnectionAttempts() {
        return maxReconnectionAttempts;
    }
}
