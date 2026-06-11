package me.ngcsonsplash.mcwebsocket;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerAdvancementCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;

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
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String formattedMessage = String.format("[MC] %s %s", sender.getName().getString(), message.getContent().getString());
			wsManager.send(formattedMessage);
			LOGGER.debug("Sent chat message: " + formattedMessage);
		});

		// Register event for player advancements
		PlayerAdvancementCallback.EVENT.register((player, advancement, criterionName) -> {
			if (advancement.getDisplay() != null && advancement.getDisplay().shouldAnnounceToChat()) {
				Text messageText = Text.translatable("chat.type.advancement." + advancement.getDisplay().getType().getName(), 
						player.getDisplayName(), 
						advancement.getDisplay().getTitle());
				String message = messageText.getString();
				wsManager.send(message);
				LOGGER.debug("Sent advancement message: " + message);
			}
		});

		// Register event for player death
		ServerPlayerEvents.AFTER_DEATH.register((originalPlayer, newPlayer, conqueredRun) -> {
			Text deathMessage = originalPlayer.getDamageTracker().getDeathMessage();
			String message = deathMessage.getString();
			wsManager.send(message);
			LOGGER.debug("Sent death message: " + message);
		});

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("mcwebsocket")
				.requires(source -> source.hasPermissionLevel(4)) // OPs only
				.then(CommandManager.literal("reload")
					.executes(context -> {
						if (minecraftServer == null) {
							context.getSource().sendFeedback(() -> Text.literal("§c[McWebSocket] Lỗi: Không thể tải lại khi server chưa khởi động hoàn toàn."), false);
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
							context.getSource().sendFeedback(() -> Text.literal("§c[McWebSocket] Lỗi khi tải lại cấu hình: URI WebSocket không hợp lệ."), false);
							return 0;
						}

						// Reset reconnection attempts and try to connect
						wsManager.resetReconnectionAttempts();
						// Use the stored server reference to trigger a connect if server is already started
						// This ensures the connection attempt happens after server is ready
						// (The server lifecycle event is not truly "re-invoked" here, we just call connect directly if server is running)
						if (minecraftServer != null && minecraftServer.isRunning()) {
							wsManager.connect();
						}

						context.getSource().sendFeedback(() -> Text.literal("§a[McWebSocket] Đã tải lại cấu hình và cố gắng kết nối lại WebSocket. Trạng thái hiện tại: " + wsManager.getConnectionStatus().getVietnameseStatus()), false);
						return 1;
					}))
				.then(CommandManager.literal("status")
					.executes(context -> {
						if (wsManager == null) {
							context.getSource().sendFeedback(() -> Text.literal("§c[McWebSocket] Mod chưa khởi tạo hoàn toàn."), false);
							return 0;
						}
						WebSocketClientManager.ConnectionStatus status = wsManager.getConnectionStatus();
						String message = String.format("§a[McWebSocket] Trạng thái WebSocket hiện tại: §b%s", status.getVietnameseStatus());
						if (status == WebSocketClientManager.ConnectionStatus.RECONNECTING) {
							message += String.format(" (Đã thử %d/%d lần)", wsManager.getReconnectionAttempts(), wsManager.getMaxReconnectionAttempts());
						}
						context.getSource().sendFeedback(() -> Text.literal(message), false);
						return 1;
					}))
			);
		});

		LOGGER.info("McWebSocket Mod initialized successfully on server-side.");
	}

	private void broadcastWebSocketMessage(String message) {
		if (minecraftServer != null) {
			// Format the message with a prefix and Aqua color for distinction
			Text formattedText = Text.literal("§b[WebSocket] " + message);
			minecraftServer.execute(() -> {
				minecraftServer.getPlayerManager().broadcast(formattedText, false);
			});
		}
	}
}
