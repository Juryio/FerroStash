package com.ferrostash;

import com.ferrostash.cache.AuditLogManager;
import com.ferrostash.cache.StorageCacheManager;
import com.ferrostash.client.gui.StorageSearchScreen;
import com.ferrostash.config.ModConfig;
import com.ferrostash.sync.ClanSyncClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FerroStashMod implements ClientModInitializer {
    public static final String MOD_ID = "ferrostash";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping searchKeyBinding;
    private static KeyMapping adminGhostScanKeyBinding;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[FerroStash] Initializing Minecraft 26.2 Clan Storage Cache & Sync...");

        // 1. Load or generate configuration
        ModConfig.loadOrCreate();

        // 2. Load storage cache and audit log from disk
        StorageCacheManager.getInstance().loadFromDisk();
        AuditLogManager.getInstance().loadFromDisk();

        // 3. Register In-Game Search Keybinding (Default 'O' key)
        searchKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ferrostash.open_vault",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyMapping.Category.INVENTORY
        ));

        // Admin Ghost-Scanner Keybinding (Default 'P' key, active for Sennichi / Admins)
        adminGhostScanKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ferrostash.admin_ghost_scan",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyMapping.Category.INVENTORY
        ));

        // 4. Listen to client tick events to open GUI upon key press and update waypoint particles
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (searchKeyBinding.consumeClick()) {
                openVaultScreen(client);
            }
            while (adminGhostScanKeyBinding.consumeClick()) {
                if (ModConfig.isAdmin(client)) {
                    client.setScreenAndShow(new com.ferrostash.client.gui.AdminGhostScanScreen());
                } else {
                    if (client.gui != null && client.gui.hud != null) {
                        client.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("§c[FerroStash] Ghost-Scanner ist Clan-Admins vorbehalten."), false);
                    }
                }
            }
            com.ferrostash.navigation.WaypointManager.getInstance().tick(client);
        });

        // 5. Start background synchronization worker
        ClanSyncClient.getInstance().start();

        LOGGER.info("[FerroStash] Initialized! Press 'O' in-game to search cached clan chests.");
    }

    public static void openVaultScreen(Minecraft client) {
        if (client.player == null) return;
        client.setScreenAndShow(new StorageSearchScreen());
    }
}
