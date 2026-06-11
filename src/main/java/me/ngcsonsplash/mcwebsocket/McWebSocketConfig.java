package me.ngcsonsplash.mcwebsocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class McWebSocketConfig {
    public static final String CONFIG_FILE = "mcwebsocket.properties";
    private static final Logger LOGGER = LoggerFactory.getLogger(McWebSocketConfig.class);

    private String websocketUri;
    private int maxReconnectionAttempts;

    public McWebSocketConfig() {
        Properties properties = new Properties();
        try (InputStream input = McWebSocketConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                LOGGER.error("Sorry, unable to find " + CONFIG_FILE + ". Using default values.");
                loadDefaultProperties();
                return;
            }
            properties.load(input);
            this.websocketUri = properties.getProperty("websocket.uri", "ws://localhost:8080/websocket");
            this.maxReconnectionAttempts = Integer.parseInt(properties.getProperty("websocket.reconnect.maxAttempts", "5"));
        } catch (IOException ex) {
            LOGGER.error("Error loading config file: " + CONFIG_FILE, ex);
            loadDefaultProperties();
        } catch (NumberFormatException ex) {
            LOGGER.error("Invalid number format for websocket.reconnect.maxAttempts in config file: " + CONFIG_FILE, ex);
            loadDefaultProperties();
        }
    }

    private void loadDefaultProperties() {
        this.websocketUri = "ws://localhost:8080/websocket";
        this.maxReconnectionAttempts = 5; // Default value
    }

    public String getWebsocketUri() {
        return websocketUri;
    }

    public int getMaxReconnectionAttempts() {
        return maxReconnectionAttempts;
    }
}
