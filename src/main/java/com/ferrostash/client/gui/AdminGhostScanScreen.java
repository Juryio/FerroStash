package com.ferrostash.client.gui;

import com.ferrostash.cache.AuditLogManager;
import com.ferrostash.cache.StorageCacheManager;
import com.ferrostash.model.StorageContainer;
import com.ferrostash.model.StorageItem;
import com.ferrostash.navigation.WaypointManager;
import com.ferrostash.sync.ClanSyncClient;
import com.ferrostash.zone.ClanSettingsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AdminGhostScanScreen extends Screen {

    public static class GhostChestEntry {
        public final StorageContainer container;
        public final BlockPos pos;
        public final String currentBlockId;
        public final String currentBlockName;
        public final double distance;
        public final int totalItemCount;
        public final String sampleItemsSummary;

        public GhostChestEntry(StorageContainer container, BlockPos pos, String blockId, String blockName, double distance) {
            this.container = container;
            this.pos = pos;
            this.currentBlockId = blockId;
            this.currentBlockName = blockName;
            this.distance = distance;

            int count = 0;
            StringBuilder sb = new StringBuilder();
            if (container.items != null) {
                for (int i = 0; i < container.items.size(); i++) {
                    StorageItem item = container.items.get(i);
                    count += item.count;
                    if (i < 3) {
                        if (i > 0) sb.append(", ");
                        sb.append(item.count).append("x ").append(item.customName != null && !item.customName.isBlank() ? item.customName : formatItemName(item.itemId));
                    }
                }
                if (container.items.size() > 3) {
                    sb.append(" (+").append(container.items.size() - 3).append(" weitere)");
                }
            }
            this.totalItemCount = count;
            this.sampleItemsSummary = sb.length() > 0 ? sb.toString() : "Leer (0 Items)";
        }

        private static String formatItemName(String itemId) {
            if (itemId == null) return "Item";
            String path = itemId.contains(":") ? itemId.split(":")[1] : itemId;
            String[] words = path.split("_");
            StringBuilder sb = new StringBuilder();
            for (String w : words) {
                if (!w.isEmpty()) {
                    sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
                }
            }
            return sb.toString().trim();
        }
    }

    private final List<GhostChestEntry> ghostChests = new ArrayList<>();
    private int scrollOffset = 0;
    private int panelX, panelY, panelW, panelH;

    public AdminGhostScanScreen() {
        super(Component.literal("§c[Admin] FerroStash Ghost-Kisten Scanner"));
    }

    @Override
    protected void init() {
        super.init();

        panelW = Math.min(width - 30, 620);
        panelH = Math.min(height - 30, 420);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        performScan();
        rebuildWidgets();
    }

    public void performScan() {
        ghostChests.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        String currentDim = client.level.dimension().identifier().toString();
        BlockPos playerPos = client.player.blockPosition();

        for (StorageContainer sc : StorageCacheManager.getInstance().getAllContainers()) {
            if (sc == null || sc.dimension == null) continue;
            if (!sc.dimension.equalsIgnoreCase(currentDim)) continue;

            BlockPos pos = new BlockPos(sc.x, sc.y, sc.z);
            // Only inspect positions whose chunks are loaded in the client level
            if (client.level.hasChunkAt(pos)) {
                BlockState state = client.level.getBlockState(pos);
                Block block = state.getBlock();

                boolean isValidContainer = (block instanceof ChestBlock)
                        || (block instanceof BarrelBlock)
                        || (block instanceof AbstractFurnaceBlock);

                if (!isValidContainer) {
                    String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
                    String blockName = block.getName().getString();
                    double dist = Math.sqrt(pos.distSqr(playerPos));
                    ghostChests.add(new GhostChestEntry(sc, pos, blockId, blockName, dist));
                }
            }
        }

        // Sort by distance (closest first)
        ghostChests.sort(Comparator.comparingDouble(e -> e.distance));
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();

        // Close Button in top right
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(panelX + panelW - 26, panelY + 6, 20, 20).build());

        // Rescan button in top right
        addRenderableWidget(Button.builder(Component.literal("🔄 Neu scannen"), b -> {
            performScan();
            scrollOffset = 0;
            rebuildWidgets();
        }).bounds(panelX + panelW - 130, panelY + 6, 100, 20).build());

        // Dynamic action buttons for visible entries
        int listX = panelX + 16;
        int listY = panelY + 54;
        int listW = panelW - 32;
        int entryH = 50;
        int visibleCount = (panelH - 110) / entryH;

        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            if (idx >= ghostChests.size()) break;

            GhostChestEntry entry = ghostChests.get(idx);
            int entryY = listY + i * entryH;

            int btnY = entryY + 14;
            int btnW = 75;

            // Delete / Purge button
            addRenderableWidget(Button.builder(Component.literal("§c🗑️ Löschen"), b -> deleteGhostChest(entry))
                    .bounds(listX + listW - btnW - 8, btnY, btnW, 20).build());

            // Waypoint navigation button
            addRenderableWidget(Button.builder(Component.literal("📍 Wegpunkt"), b -> {
                WaypointManager.getInstance().setTarget(entry.container, "Ghost-Kiste");
                if (minecraft != null && minecraft.gui != null && minecraft.gui.hud != null) {
                    minecraft.gui.hud.setOverlayMessage(Component.literal("§a[FerroStash] Wegpunkt auf Ghost-Kiste [" + entry.pos.getX() + ", " + entry.pos.getY() + ", " + entry.pos.getZ() + "] gesetzt!"), false);
                }
            }).bounds(listX + listW - (btnW * 2) - 16, btnY, btnW, 20).build());
        }

        // Bottom Controls
        int bottomY = panelY + panelH - 30;
        if (!ghostChests.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("§c🧹 Alle " + ghostChests.size() + " Ghost-Kisten bereinigen"), b -> deleteAllGhostChests())
                    .bounds(panelX + 16, bottomY, 240, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Zurück zum Lager"), b -> {
            if (minecraft != null) {
                minecraft.setScreenAndShow(new StorageSearchScreen());
            }
        }).bounds(panelX + panelW - 140, bottomY, 124, 20).build());
    }

    private void deleteGhostChest(GhostChestEntry entry) {
        String id = entry.container.id != null ? entry.container.id : StorageContainer.generateId(entry.container.dimension, entry.pos.getX(), entry.pos.getY(), entry.pos.getZ());

        // 1. Remove from local storage cache & audit snapshots
        StorageCacheManager.getInstance().removeContainer(id);
        AuditLogManager.getInstance().clearSnapshot(id);

        // 2. Mark as private in ClanSettings
        ClanSettingsManager.getInstance().getSettings().privateContainers.add(id);
        ClanSettingsManager.getInstance().saveToDisk();

        // 3. Force push to GitHub
        ClanSyncClient.getInstance().forceVaultPush();

        // 4. Remove from list & refresh
        ghostChests.remove(entry);
        if (scrollOffset > 0 && scrollOffset >= ghostChests.size()) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        }
        rebuildWidgets();

        if (minecraft != null && minecraft.gui != null && minecraft.gui.hud != null) {
            minecraft.gui.hud.setOverlayMessage(Component.literal("§a[FerroStash Admin] Kiste §e" + id + " §agelöscht & privatisiert!"), false);
        }
    }

    private void deleteAllGhostChests() {
        if (ghostChests.isEmpty()) return;
        int count = ghostChests.size();

        for (GhostChestEntry entry : new ArrayList<>(ghostChests)) {
            String id = entry.container.id != null ? entry.container.id : StorageContainer.generateId(entry.container.dimension, entry.pos.getX(), entry.pos.getY(), entry.pos.getZ());
            StorageCacheManager.getInstance().removeContainer(id);
            AuditLogManager.getInstance().clearSnapshot(id);
            ClanSettingsManager.getInstance().getSettings().privateContainers.add(id);
        }

        ClanSettingsManager.getInstance().saveToDisk();
        ClanSyncClient.getInstance().forceVaultPush();
        ghostChests.clear();
        scrollOffset = 0;
        rebuildWidgets();

        if (minecraft != null && minecraft.gui != null && minecraft.gui.hud != null) {
            minecraft.gui.hud.setOverlayMessage(Component.literal("§a[FerroStash Admin] " + count + " Ghost-Kisten bereinigt & zu GitHub synchronisiert!"), false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        // Dark translucent blurred backdrop
        extractor.fill(0, 0, width, height, 0x88000000);

        // Main Admin Window
        extractor.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE161A23);
        extractor.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 2, 0xFFEF4444); // Red admin top accent
        extractor.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF3B1824);
        extractor.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF3B1824);
        extractor.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF3B1824);

        // Title & Stats
        extractor.text(font, "§c§l[FerroStash Admin] §fGhost-Kisten Scanner", panelX + 16, panelY + 10, 0xFFFFFFFF);
        String subHeader = ghostChests.isEmpty()
                ? "§aKeine Ghost-Kisten in geladenen Chunks gefunden. Deine Umgebung ist sauber!"
                : "§e" + ghostChests.size() + " Kiste(n) im Stash registriert, die in deiner Sichtweite NICHT mehr existieren:";
        extractor.text(font, subHeader, panelX + 16, panelY + 28, 0xFFCCCCCC);

        // List View
        int listX = panelX + 16;
        int listY = panelY + 48;
        int listW = panelW - 32;
        int entryH = 50;
        int visibleCount = (panelH - 100) / entryH;

        extractor.fill(listX - 1, listY - 1, listX + listW + 1, listY + visibleCount * entryH + 1, 0xFF1E2433);

        if (ghostChests.isEmpty()) {
            extractor.centeredText(font, "§7Alle Kisten an ihren registrierten Positionen existieren in der Welt.", listX + listW / 2, listY + 60, 0xFF888888);
            extractor.centeredText(font, "§8Tipp: Bewege dich durch andere Bereiche der Basis und klicke auf 'Neu scannen'.", listX + listW / 2, listY + 76, 0xFF666666);
        } else {
            for (int i = 0; i < visibleCount; i++) {
                int idx = scrollOffset + i;
                if (idx >= ghostChests.size()) break;

                GhostChestEntry entry = ghostChests.get(idx);
                int entryY = listY + i * entryH;

                // Entry background
                int bgColor = (i % 2 == 0) ? 0xFF171B26 : 0xFF1C2230;
                extractor.fill(listX, entryY, listX + listW, entryY + entryH - 2, bgColor);

                // Row 1: Title + Coords + Distance
                String title = entry.container.customTitle != null && !entry.container.customTitle.isBlank() ? entry.container.customTitle : entry.container.type;
                String coordText = "§f" + title + " §7bei §e[" + entry.pos.getX() + ", " + entry.pos.getY() + ", " + entry.pos.getZ() + "] §8(" + (int) entry.distance + "m entfernt)";
                extractor.text(font, coordText, listX + 8, entryY + 6, 0xFFFFFFFF);

                // Row 2: Crucial info: WHAT BLOCK IS CURRENTLY THERE INSTEAD OF THE CHEST!
                String blockText = "§7Aktueller Block dort: §c" + entry.currentBlockName + " §8(" + entry.currentBlockId + ")";
                extractor.text(font, blockText, listX + 8, entryY + 18, 0xFFFFAAAA);

                // Row 3: Stash items info
                String itemsText = "§7Letzter Inhalt: §6" + entry.totalItemCount + " Items §8(" + entry.sampleItemsSummary + ")";
                extractor.text(font, itemsText, listX + 8, entryY + 30, 0xFFE0E0E0);
            }
        }

        super.extractRenderState(extractor, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!ghostChests.isEmpty()) {
            int entryH = 50;
            int visibleCount = (panelH - 100) / entryH;
            int maxOffset = Math.max(0, ghostChests.size() - visibleCount);

            if (verticalAmount > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else if (verticalAmount < 0) {
                scrollOffset = Math.min(maxOffset, scrollOffset + 1);
            }
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
