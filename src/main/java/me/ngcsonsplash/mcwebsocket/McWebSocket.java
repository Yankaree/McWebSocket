package me.ngcsonsplash.mcwebsocket;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;


public class McWebSocket implements ModInitializer {
	public static final String MOD_ID = "mcwebsocket";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static MinecraftServer minecraftServer; // Keep a reference to the server

	private McWebSocketConfig config;
	private WebSocketClientManager wsManager;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing McWebSocket Mod...");

		// Client-side disabling logic
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			LOGGER.info("Mod hiện tại chỉ thiết kế cho server-side, mod sẽ tự động tắt sau khi tin nhắn này được hiển thị, và rồi disable mod này (nếu ở client) server không liên quan.");
			// Effectively disable the mod on the client by not initializing server-side components
			return;
		}

		// Load configuration
		config = new McWebSocketConfig();

		// Initialize WebSocket client manager
		try {
			wsManager = new WebSocketClientManager(config.getWebsocketUri(), config.getMaxReconnectionAttempts(), this::broadcastWebSocketMessage);
		} catch (URISyntaxException e) {
			LOGGER.error("Invalid WebSocket URI in config: " + config.getWebsocketUri(), e);
			return; // Cannot proceed without a valid URI
		}

		// Register server lifecycle events for WebSocket connection management
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			minecraftServer = server; // Store server reference
			LOGGER.info("Minecraft server started. Connecting WebSocket client.");
			wsManager.connect();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Minecraft server stopping. Disconnecting WebSocket client.");
			wsManager.disconnect();
		});

		// Register event for player chat messages
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, metadata) -> {
			String formattedMessage = String.format("[MC] %s %s", sender.getName().getString(), message.decoratedContent().getString());
			wsManager.send(formattedMessage);
			LOGGER.debug("Sent chat message: " + formattedMessage);
		});

		// Advancement tracking using mixin-like or generic server events if specific ones fail
		// For now, let's keep it simple and try to fix the existing death event first
		
		// Register event for player death
		ServerPlayerEvents.AFTER_DEATH.register((player, source) -> {
			Component deathMessage = player.getDamageTracker().getDeathMessage();
			String message = deathMessage.getString();
			wsManager.send(message);
			LOGGER.debug("Sent death message: " + message);
		});

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("mcwebsocket")
				.requires(source -> source.hasPermission(4)) // OPs only
				.then(Commands.literal("reload")
					.executes(context -> {
						if (minecraftServer == null) {
							context.getSource().sendSystemMessage(Component.literal("§c[McWebSocket] Lỗi: Không thể tải lại khi server chưa khởi động hoàn toàn."));
							return 0;
						}

						// Disconnect existing manager
						if (wsManager != null) {
							wsManager.disconnect();
						}

						// Reload configuration
						config = new McWebSocketConfig();
						
						// Reinitialize WebSocket client manager with new config
						try {
							wsManager = new WebSocketClientManager(config.getWebsocketUri(), config.getMaxReconnectionAttempts(), this::broadcastWebSocketMessage);
						} catch (URISyntaxException e) {
							LOGGER.error("Invalid WebSocket URI in reloaded config: " + config.getWebsocketUri(), e);
							context.getSource().sendSystemMessage(Component.literal("§c[McWebSocket] Lỗi khi tải lại cấu hình: URI WebSocket không hợp lệ."));
							return 0;
						}

						// Reset reconnection attempts and try to connect
						wsManager.resetReconnectionAttempts();
						if (minecraftServer != null && minecraftServer.isRunning()) {
							wsManager.connect();
						}

						context.getSource().sendSystemMessage(Component.literal("§a[McWebSocket] Đã tải lại cấu hình và cố gắng kết nối lại WebSocket. Trạng thái hiện tại: " + wsManager.getConnectionStatus().getVietnameseStatus()));
						return 1;
					}))
				.then(Commands.literal("status")
					.executes(context -> {
						if (wsManager == null) {
							context.getSource().sendSystemMessage(Component.literal("§c[McWebSocket] Mod chưa khởi tạo hoàn toàn."));
							return 0;
						}
						WebSocketClientManager.ConnectionStatus status = wsManager.getConnectionStatus();
						String message = String.format("§a[McWebSocket] Trạng thái WebSocket hiện tại: §b%s", status.getVietnameseStatus());
						if (status == WebSocketClientManager.ConnectionStatus.RECONNECTING) {
							message += String.format(" (Đã thử %d/%d lần)", wsManager.getReconnectionAttempts(), wsManager.getMaxReconnectionAttempts());
						}
						context.getSource().sendSystemMessage(Component.literal(message));
						return 1;
					}))
			);
		});

		LOGGER.info("McWebSocket Mod initialized successfully on server-side.");
	}

	private void broadcastWebSocketMessage(String message) {
		if (minecraftServer != null) {
			Component formattedText = Component.literal("§b[WebSocket] " + message);
			minecraftServer.execute(() -> {
				minecraftServer.getPlayerList().broadcastSystemMessage(formattedText, false);
			});
		}
	}
}
