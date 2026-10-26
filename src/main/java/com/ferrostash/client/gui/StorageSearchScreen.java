package com.ferrostash.client.gui;

import com.ferrostash.cache.AuditLogManager;
import com.ferrostash.cache.StorageCacheManager;
import com.ferrostash.config.ModConfig;
import com.ferrostash.model.AuditEvent;
import com.ferrostash.model.ItemDiff;
import com.ferrostash.model.StorageContainer;
import com.ferrostash.navigation.WaypointManager;
import com.ferrostash.sync.ClanSyncClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StorageSearchScreen extends Screen {

    private enum Tab {
        INVENTORY,
        AUDIT,
        SETTINGS
    }

    private Tab currentTab = Tab.INVENTORY;

    // Tab 1 (Inventory) widgets
    private EditBox searchBox;
    private List<StorageCacheManager.SearchResult> currentResults = new ArrayList<>();
    private StorageCacheManager.SearchResult selectedItem = null;
    private int scrollOffset = 0;
    private int auditScrollOffset = 0;

    // Performance Caches
    private final java.util.Map<String, ItemStack> itemStackCache = new java.util.HashMap<>();
    private List<StorageCacheManager.ContainerMatch> cachedSortedMatches = new ArrayList<>();
    private String lastSelectedMatchKey = null;

    // Tab 3 (Settings & Sync) widgets
    private boolean isGithubMode;
    private EditBox githubRepoBox;
    private EditBox githubTokenBox;
    private EditBox serverUrlBox;
    private EditBox apiSecretBox;
    private EditBox clanNameBox;
    private EditBox clanIdBox;

    // Layout dimensions
    private int panelX, panelY, panelW, panelH;

    public StorageSearchScreen() {
        super(Component.literal("FerroStash Clan-Lager"));
        this.isGithubMode = ModConfig.getInstance().githubSyncEnabled;
    }

    @Override
    protected void init() {
        super.init();

        panelW = Math.min(width - 40, 560);
        panelH = Math.min(height - 40, 370);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        clearWidgets();

        // Top Navigation Tabs
        int tabW = 120;
        int tabH = 20;
        int startX = panelX + 16;
        int tabY = panelY + 28;

        addRenderableWidget(Button.builder(Component.literal("Lagerbestand"), b -> switchTab(Tab.INVENTORY))
                .bounds(startX, tabY, tabW, tabH).build());

        addRenderableWidget(Button.builder(Component.literal("Aktivitäten"), b -> switchTab(Tab.AUDIT))
                .bounds(startX + tabW + 6, tabY, tabW, tabH).build());

        addRenderableWidget(Button.builder(Component.literal("Clan & Sync"), b -> switchTab(Tab.SETTINGS))
                .bounds(startX + (tabW + 6) * 2, tabY, tabW, tabH).build());

        // Admin Ghost-Scanner Button (Visible for Sennichi / Clan Admins)
        if (ModConfig.isAdmin(minecraft)) {
            addRenderableWidget(Button.builder(Component.literal("§c[Ghost-Scan]"), b -> {
                if (minecraft != null) {
                    minecraft.setScreenAndShow(new AdminGhostScanScreen());
                }
            }).bounds(startX + (tabW + 6) * 3, tabY, 95, tabH).build());
        }

        // Close Button in top right
        addRenderableWidget(Button.builder(Component.literal("X"), b -> onClose())
                .bounds(panelX + panelW - 28, panelY + 6, 20, 20).build());

        if (currentTab == Tab.INVENTORY) {
            initInventoryTab();
        } else if (currentTab == Tab.AUDIT) {
            initAuditTab();
        } else if (currentTab == Tab.SETTINGS) {
            initSettingsTab();
        }
    }

    private void switchTab(Tab tab) {
        if (currentTab == Tab.SETTINGS && tab != Tab.SETTINGS) {
            saveSettingsQuietly();
        }
        this.currentTab = tab;
        this.scrollOffset = 0;
        this.auditScrollOffset = 0;
        init();
    }

    private void initInventoryTab() {
        int searchY = panelY + 54;
        searchBox = new EditBox(font, panelX + 16, searchY, panelW - 32, 20, Component.literal("Suche"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal("Item- oder Kistenname suchen..."));
        searchBox.setResponder(query -> {
            updateSearchResults(query);
            scrollOffset = 0;
        });
        addRenderableWidget(searchBox);

        updateSearchResults(searchBox.getValue());
    }

    private void updateSearchResults(String query) {
        currentResults = StorageCacheManager.getInstance().search(query);
        if (selectedItem != null) {
            boolean exists = currentResults.stream().anyMatch(r -> r.itemId.equals(selectedItem.itemId) && r.displayName.equals(selectedItem.displayName));
            if (!exists) {
                selectedItem = currentResults.isEmpty() ? null : currentResults.get(0);
            }
        } else if (!currentResults.isEmpty()) {
            selectedItem = currentResults.get(0);
        }
    }

    private void initAuditTab() {
        addRenderableWidget(Button.builder(Component.literal("Aktualisieren"), b -> init())
                .bounds(panelX + panelW - 120, panelY + 54, 104, 20).build());
    }

    private void initSettingsTab() {
        ModConfig config = ModConfig.getInstance();
        int leftX = panelX + 20;
        int inputW = panelW - 40;
        int currentY = panelY + 56;

        // Mode switch button
        String modeText = isGithubMode ? "Sync-Modus: §bGitHub Pages (Serverless) §7[Klick zum Wechseln]" : "Sync-Modus: §aEigener Server (REST API) §7[Klick zum Wechseln]";
        addRenderableWidget(Button.builder(Component.literal(modeText), b -> {
            isGithubMode = !isGithubMode;
            init();
        }).bounds(leftX, currentY, inputW, 20).build());

        currentY += 28;

        if (isGithubMode) {
            // GitHub Repo input
            githubRepoBox = new EditBox(font, leftX, currentY + 12, inputW, 18, Component.literal("GitHub Repo"));
            githubRepoBox.setMaxLength(128);
            githubRepoBox.setValue(config.githubRepo != null ? config.githubRepo : "");
            githubRepoBox.setHint(Component.literal("z.B. benutzername/clan-vault-repo"));
            addRenderableWidget(githubRepoBox);

            currentY += 34;

            // GitHub Token input
            githubTokenBox = new EditBox(font, leftX, currentY + 12, inputW, 18, Component.literal("GitHub Token"));
            githubTokenBox.setMaxLength(256);
            githubTokenBox.setValue(config.githubToken != null ? config.githubToken : "");
            githubTokenBox.setHint(Component.literal("Personal Access Token (PAT) mit repo-Rechten"));
            addRenderableWidget(githubTokenBox);

            currentY += 34;
        } else {
            // Server URL input
            serverUrlBox = new EditBox(font, leftX, currentY + 12, inputW, 18, Component.literal("Server URL"));
            serverUrlBox.setMaxLength(256);
            serverUrlBox.setValue(config.serverUrl != null ? config.serverUrl : "http://localhost:3000/api/sync");
            serverUrlBox.setHint(Component.literal("http://dein-server:3000/api/sync"));
            addRenderableWidget(serverUrlBox);

            currentY += 34;

            // Clan Secret input
            apiSecretBox = new EditBox(font, leftX, currentY + 12, inputW, 18, Component.literal("API Secret"));
            apiSecretBox.setMaxLength(128);
            apiSecretBox.setValue(config.apiSecret != null ? config.apiSecret : "");
            apiSecretBox.setHint(Component.literal("Clan-Passwort / Token"));
            addRenderableWidget(apiSecretBox);

            currentY += 34;
        }

        // Clan Info row (Clan Name & Clan ID)
        int halfW = (inputW - 12) / 2;
        clanNameBox = new EditBox(font, leftX, currentY + 12, halfW, 18, Component.literal("Clan Name"));
        clanNameBox.setMaxLength(64);
        clanNameBox.setValue(config.clanName != null ? config.clanName : "Iron Vanguard");
        addRenderableWidget(clanNameBox);

        clanIdBox = new EditBox(font, leftX + halfW + 12, currentY + 12, halfW, 18, Component.literal("Clan ID"));
        clanIdBox.setMaxLength(32);
        clanIdBox.setValue(config.clanId != null ? config.clanId : "CLAN-ALPHA");
        addRenderableWidget(clanIdBox);

        // Bottom Action buttons
        int btnY = panelY + panelH - 34;

        addRenderableWidget(Button.builder(Component.literal("💾 Speichern"), b -> {
            saveSettings();
            java.util.concurrent.CompletableFuture.runAsync(() -> ClanSyncClient.getInstance().syncAsync());
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("§a[FerroStash] Einstellungen gespeichert & Synchronisation gestartet!"));
            }
        }).bounds(leftX, btnY, 130, 22).build());

        addRenderableWidget(Button.builder(Component.literal("🗑️ Cache leeren"), b -> {
            StorageCacheManager.getInstance().clearAll();
            ClanSyncClient.getInstance().setLastPushedVaultJson("[]");
            updateSearchResults("");
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("§e[FerroStash] Lokaler Kisten-Cache geleert."));
            }
        }).bounds(leftX + 135, btnY, 120, 22).build());

        if (WaypointManager.getInstance().hasTarget()) {
            addRenderableWidget(Button.builder(Component.literal("✕ Wegpunkt"), b -> {
                WaypointManager.getInstance().clearTarget();
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.literal("§e[FerroStash] Aktiver Wegpunkt entfernt."));
                }
                init();
            }).bounds(leftX + 260, btnY, 110, 22).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Schließen"), b -> onClose())
                .bounds(panelX + panelW - 95, btnY, 75, 22).build());
    }

    private void saveSettingsQuietly() {
        ModConfig config = ModConfig.getInstance();
        config.githubSyncEnabled = isGithubMode;
        if (githubRepoBox != null) config.githubRepo = githubRepoBox.getValue().trim();
        if (githubTokenBox != null) config.githubToken = githubTokenBox.getValue().trim();
        if (serverUrlBox != null) config.serverUrl = serverUrlBox.getValue().trim();
        if (apiSecretBox != null) config.apiSecret = apiSecretBox.getValue().trim();
        if (clanNameBox != null) config.clanName = clanNameBox.getValue().trim();
        if (clanIdBox != null) config.clanId = clanIdBox.getValue().trim();
        config.save();
    }

    private void saveSettings() {
        saveSettingsQuietly();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        // Dark translucent background overlay
        extractor.fill(0, 0, width, height, 0x88000000);

        // Main Panel background
        extractor.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0131722);
        extractor.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1, 0xFF1C2230);

        // Header
        ModConfig config = ModConfig.getInstance();
        extractor.text(font, "§6§lFerroStash §7- Clan: §e" + config.clanName, panelX + 16, panelY + 10, 0xFFFFFFFF);

        // Tab indicator line
        int lineY = panelY + 50;
        extractor.fill(panelX + 16, lineY, panelX + panelW - 16, lineY + 1, 0xFF2F3B52);

        if (currentTab == Tab.INVENTORY) {
            renderInventoryTab(extractor, mouseX, mouseY);
        } else if (currentTab == Tab.AUDIT) {
            renderAuditTab(extractor, mouseX, mouseY);
        } else if (currentTab == Tab.SETTINGS) {
            renderSettingsTab(extractor, mouseX, mouseY);
        }

        super.extractRenderState(extractor, mouseX, mouseY, delta);
    }

    private void renderInventoryTab(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int slotSize = 20;
        int cols = 9;
        int gridW = cols * slotSize;
        int gridX = panelX + 16;
        int gridY = panelY + 80;
        int gridH = panelH - 96;
        int visibleRows = gridH / slotSize;

        int detailsX = gridX + gridW + 12;
        int detailsW = panelW - (gridW + 44);

        // 1. Grid Background
        extractor.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + visibleRows * slotSize + 2, 0xFF141824);

        StorageCacheManager.SearchResult hoveredSlotRes = null;

        // Render slots
        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = (scrollOffset + row) * cols + col;
                int sx = gridX + col * slotSize;
                int sy = gridY + row * slotSize;

                // Authentic Minecraft Slot Border
                extractor.fill(sx, sy, sx + slotSize - 1, sy + 1, 0xFF373737);
                extractor.fill(sx, sy, sx + 1, sy + slotSize - 1, 0xFF373737);
                extractor.fill(sx + 1, sy + slotSize - 1, sx + slotSize, sy + slotSize, 0xFF66738A);
                extractor.fill(sx + slotSize - 1, sy + 1, sx + slotSize, sy + slotSize, 0xFF66738A);
                extractor.fill(sx + 1, sy + 1, sx + slotSize - 1, sy + slotSize - 1, 0xFF1E2433);

                if (idx < currentResults.size()) {
                    StorageCacheManager.SearchResult res = currentResults.get(idx);
                    boolean isHovered = mouseX >= sx && mouseX < sx + slotSize && mouseY >= sy && mouseY < sy + slotSize;
                    boolean isSelected = selectedItem != null
                            && selectedItem.itemId.equals(res.itemId)
                            && selectedItem.displayName.equals(res.displayName);

                    if (isSelected) {
                        // Golden selection glow & border
                        extractor.fill(sx + 1, sy + 1, sx + slotSize - 1, sy + slotSize - 1, 0x66FFD700);
                        extractor.fill(sx, sy, sx + slotSize, sy + 1, 0xFFFFD700);
                        extractor.fill(sx, sy + slotSize - 1, sx + slotSize, sy + slotSize, 0xFFFFD700);
                        extractor.fill(sx, sy, sx + 1, sy + slotSize, 0xFFFFD700);
                        extractor.fill(sx + slotSize - 1, sy, sx + slotSize, sy + slotSize, 0xFFFFD700);
                    } else if (isHovered) {
                        extractor.fill(sx + 1, sy + 1, sx + slotSize - 1, sy + slotSize - 1, 0x44FFFFFF);
                    }

                    // Render Item Icon
                    ItemStack stack = getItemStack(res.itemId);
                    extractor.item(stack, sx + 2, sy + 2);

                    // Stack Count Overlay in bottom right
                    if (res.totalCount > 1) {
                        String countText = res.totalCount > 999 ? (res.totalCount / 1000) + "k" : String.valueOf(res.totalCount);
                        extractor.text(font, countText, sx + slotSize - font.width(countText) - 1, sy + slotSize - 8, 0xFFFFFFFF);
                    }

                    if (isHovered) {
                        hoveredSlotRes = res;
                    }
                }
            }
        }

        if (currentResults.isEmpty()) {
            extractor.centeredText(font, "§7Keine Gegenstände im Lager.", gridX + gridW / 2, gridY + gridH / 2 - 5, 0xFF888888);
        }

        // 2. Details Sidebar: Selected Item & Container Locations
        extractor.fill(detailsX, gridY, detailsX + detailsW, gridY + gridH, 0xFF141824);

        if (selectedItem != null) {
            ItemStack selectedStack = getItemStack(selectedItem.itemId);
            String selectedName = selectedItem.displayName;

            // Header with big icon & name
            extractor.item(selectedStack, detailsX + 10, gridY + 8);
            extractor.text(font, "§e§l" + selectedName, detailsX + 32, gridY + 8, 0xFFFFFFFF);
            extractor.text(font, "§7Gesamtbestand: §a" + selectedItem.totalCount + "x §8(" + selectedItem.matches.size() + " Kisten)", detailsX + 32, gridY + 20, 0xFFDDDDDD);

            int curY = gridY + 36;

            // Enchantments list
            if (selectedItem.enchantments != null && !selectedItem.enchantments.isEmpty()) {
                extractor.text(font, "§dVerzauberungen:", detailsX + 10, curY, 0xFFD8B4FE);
                curY += 12;
                for (String ench : selectedItem.enchantments) {
                    extractor.text(font, " §d✦ §f" + ench, detailsX + 14, curY, 0xFFFFFFFF);
                    curY += 11;
                }
                curY += 4;
            }

            // Divider line
            extractor.fill(detailsX + 8, curY, detailsX + detailsW - 8, curY + 1, 0xFF2F3B52);
            curY += 6;

            updateCachedMatches();
            List<StorageCacheManager.ContainerMatch> sortedMatches = cachedSortedMatches;

            int locH = 34;
            int maxVisibleLocs = Math.max(1, (gridH - (curY - gridY)) / locH);

            for (int idx = 0; idx < Math.min(sortedMatches.size(), maxVisibleLocs); idx++) {
                StorageCacheManager.ContainerMatch match = sortedMatches.get(idx);
                StorageContainer c = match.container;

                boolean locHovered = mouseX >= detailsX + 6 && mouseX <= detailsX + detailsW - 6 && mouseY >= curY && mouseY < curY + locH - 2;
                boolean isCurrentTarget = WaypointManager.getInstance().hasTarget()
                        && WaypointManager.getInstance().getTargetContainer() != null
                        && WaypointManager.getInstance().getTargetContainer().id.equals(c.id);

                int bg;
                if (isCurrentTarget) {
                    bg = 0xFF352654;
                } else if (locHovered) {
                    bg = 0xFF27334D;
                } else {
                    bg = 0xFF1A2131;
                }

                extractor.fill(detailsX + 6, curY, detailsX + detailsW - 6, curY + locH - 2, bg);

                String title = (c.customTitle != null && !c.customTitle.isBlank()) ? c.customTitle : c.type;
                int dist = calculateDistance(c);

                String nearestBadge = (idx == 0) ? "§a[Nächste] " : "";
                extractor.text(font, nearestBadge + "§f" + title + " §e(x" + match.countInContainer + ")", detailsX + 10, curY + 4, 0xFFFFFFFF);
                extractor.text(font, "§7" + formatCoords(c) + " §6[" + dist + "m]", detailsX + 10, curY + 17, 0xFFCCCCCC);

                if (isCurrentTarget) {
                    extractor.text(font, "§d[Markiert]", detailsX + detailsW - 74, curY + 17, 0xFFD8B4FE);
                } else {
                    extractor.text(font, "§b[📍 Zum Ziel]", detailsX + detailsW - 74, curY + 17, 0xFF55FFFF);
                }

                curY += locH;
            }
        } else {
            extractor.centeredText(font, "§7Wähle ein Item aus dem Kistenlager", detailsX + detailsW / 2, gridY + gridH / 2 - 5, 0xFF888888);
        }

        // 3. Floating Tooltip when hovering over a slot in the chest grid
        if (hoveredSlotRes != null) {
            List<String> tipLines = new ArrayList<>();
            tipLines.add("§f§l" + hoveredSlotRes.displayName);
            if (hoveredSlotRes.enchantments != null && !hoveredSlotRes.enchantments.isEmpty()) {
                for (String ench : hoveredSlotRes.enchantments) {
                    tipLines.add(" §d✦ " + ench);
                }
            }
            tipLines.add("§7Bestand: §a" + hoveredSlotRes.totalCount + "x §8(" + hoveredSlotRes.matches.size() + " Kisten)");
            tipLines.add("§b[Klick für Standort & Wegweiser]");

            int tipW = 0;
            for (String l : tipLines) {
                tipW = Math.max(tipW, font.width(l));
            }
            tipW += 12;
            int tipH = tipLines.size() * 11 + 8;

            int tipX = Math.min(mouseX + 12, width - tipW - 4);
            int tipY = Math.min(Math.max(mouseY - 12, 4), height - tipH - 4);

            extractor.fill(tipX, tipY, tipX + tipW, tipY + tipH, 0xF510141E);
            extractor.fill(tipX, tipY, tipX + tipW, tipY + 1, 0xFF3E4E6D);
            extractor.fill(tipX, tipY + tipH - 1, tipX + tipW, tipY + tipH, 0xFF3E4E6D);
            extractor.fill(tipX, tipY, tipX + 1, tipY + tipH, 0xFF3E4E6D);
            extractor.fill(tipX + tipW - 1, tipY, tipX + tipW, tipY + tipH, 0xFF3E4E6D);

            for (int li = 0; li < tipLines.size(); li++) {
                extractor.text(font, tipLines.get(li), tipX + 6, tipY + 5 + li * 11, 0xFFFFFFFF);
            }
        }
    }

    private void renderAuditTab(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int listX = panelX + 16;
        int listY = panelY + 80;
        int listW = panelW - 32;
        int listH = panelH - 96;

        extractor.fill(listX, listY, listX + listW, listY + listH, 0xFF141824);

        List<AuditEvent> events = AuditLogManager.getInstance().getRecentEvents(50);
        int entryH = 34;
        int visibleCount = listH / entryH;
        int startIdx = Math.max(0, auditScrollOffset);
        int endIdx = Math.min(events.size(), startIdx + visibleCount);

        for (int i = startIdx; i < endIdx; i++) {
            AuditEvent ev = events.get(i);
            int entryY = listY + (i - startIdx) * entryH;

            extractor.fill(listX + 4, entryY + 2, listX + listW - 4, entryY + entryH - 2, 0xFF1A2131);

            String timeAgo = formatTimeAgo(ev.timestamp);
            String playerText = "§e" + ev.player;
            String locText = "§7in §f" + ev.containerTitle + " §8[" + ev.x + ", " + ev.y + ", " + ev.z + "]";

            extractor.text(font, playerText + " " + locText, listX + 10, entryY + 5, 0xFFFFFFFF);
            extractor.text(font, "§8" + timeAgo, listX + listW - 80, entryY + 5, 0xFFAAAAAA);

            StringBuilder changeStr = new StringBuilder();
            for (ItemDiff diff : ev.changes) {
                if (diff.delta < 0) {
                    changeStr.append("§c").append(diff.delta).append("x ").append(diff.displayName).append("  ");
                } else {
                    changeStr.append("§a+").append(diff.delta).append("x ").append(diff.displayName).append("  ");
                }
            }
            extractor.text(font, changeStr.toString().trim(), listX + 10, entryY + 18, 0xFFDDDDDD);
        }

        if (events.isEmpty()) {
            extractor.centeredText(font, "§7Noch keine Aktivitäten registriert.", listX + listW / 2, listY + listH / 2 - 5, 0xFF888888);
        }
    }

    private void renderSettingsTab(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int leftX = panelX + 20;
        int currentY = panelY + 56 + 28;

        if (isGithubMode) {
            extractor.text(font, "§eGitHub Repository (z.B. dein-name/clan-vault):", leftX, currentY, 0xFFDDDDDD);
            currentY += 34;
            extractor.text(font, "§eGitHub Personal Access Token (mit repo Scope):", leftX, currentY, 0xFFDDDDDD);
            currentY += 34;
        } else {
            extractor.text(font, "§eServer Sync URL (z.B. http://dein-server:3000/api/sync):", leftX, currentY, 0xFFDDDDDD);
            currentY += 34;
            extractor.text(font, "§eClan Secret Key / Passwort:", leftX, currentY, 0xFFDDDDDD);
            currentY += 34;
        }

        int halfW = (panelW - 40 - 12) / 2;
        extractor.text(font, "§7Clan Name:", leftX, currentY, 0xFFCCCCCC);
        extractor.text(font, "§7Clan ID:", leftX + halfW + 12, currentY, 0xFFCCCCCC);

        // Status & Info block
        int statusY = currentY + 36;
        String status = ClanSyncClient.getInstance().getSyncStatus();
        long last = ClanSyncClient.getInstance().getLastSyncTimestamp();
        String lastStr = last > 0 ? formatTimeAgo(last) : "Nie";

        extractor.text(font, "§7Sync-Status: §a" + status, leftX, statusY, 0xFFFFFFFF);
        extractor.text(font, "§7Letzter Sync: §f" + lastStr, leftX, statusY + 12, 0xFFCCCCCC);
        extractor.text(font, "§8Alle Kisten, die du öffnest, werden automatisch mit diesem Repository synchronisiert.", leftX, statusY + 26, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isFocused) {
        double mouseX = event.x();
        double mouseY = event.y();

        if (currentTab == Tab.INVENTORY) {
            int slotSize = 20;
            int cols = 9;
            int gridW = cols * slotSize;
            int gridX = panelX + 16;
            int gridY = panelY + 80;
            int gridH = panelH - 96;
            int visibleRows = gridH / slotSize;

            int detailsX = gridX + gridW + 12;
            int detailsW = panelW - (gridW + 44);

            // 1. Click inside chest slot grid
            if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + visibleRows * slotSize) {
                int col = (int) ((mouseX - gridX) / slotSize);
                int row = (int) ((mouseY - gridY) / slotSize);
                int clickedIdx = (scrollOffset + row) * cols + col;
                if (clickedIdx >= 0 && clickedIdx < currentResults.size()) {
                    selectedItem = currentResults.get(clickedIdx);
                    return true;
                }
            }

            // 2. Click on container location in details sidebar
            if (selectedItem != null) {
                int curY = gridY + 36;
                if (selectedItem.enchantments != null && !selectedItem.enchantments.isEmpty()) {
                    curY += 12 + selectedItem.enchantments.size() * 11 + 4;
                }
                curY += 7;

                updateCachedMatches();
                List<StorageCacheManager.ContainerMatch> sortedMatches = cachedSortedMatches;

                int locH = 34;
                int maxVisibleLocs = Math.max(1, (gridH - (curY - gridY)) / locH);

                for (int idx = 0; idx < Math.min(sortedMatches.size(), maxVisibleLocs); idx++) {
                    StorageCacheManager.ContainerMatch match = sortedMatches.get(idx);
                    if (mouseX >= detailsX + 6 && mouseX <= detailsX + detailsW - 6 && mouseY >= curY && mouseY < curY + locH - 2) {
                        setWaypointTarget(match.container);
                        return true;
                    }
                    curY += locH;
                }
            }
        }

        return super.mouseClicked(event, isFocused);
    }

    private void setWaypointTarget(StorageContainer c) {
        if (minecraft == null || selectedItem == null) return;
        ItemStack stack = getItemStack(selectedItem.itemId);
        String name = !stack.isEmpty() ? stack.getHoverName().getString() : selectedItem.displayName;

        WaypointManager.getInstance().setTarget(c, name);

        String title = (c.customTitle != null && !c.customTitle.isBlank()) ? c.customTitle : c.type;
        int dist = calculateDistance(c);

        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(
                    "§a[FerroStash] §fWegweiser gesetzt auf: §6" + title + " §7(" + name + ", " + dist + "m)! Die Kiste leuchtet bei Ankunft auf."
            ));
        }
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentTab == Tab.INVENTORY) {
            int slotSize = 20;
            int cols = 9;
            int gridH = panelH - 96;
            int visibleRows = gridH / slotSize;
            int totalRows = (currentResults.size() + cols - 1) / cols;
            int maxScroll = Math.max(0, totalRows - visibleRows);

            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) verticalAmount));
            return true;
        } else if (currentTab == Tab.AUDIT) {
            auditScrollOffset = Math.max(0, auditScrollOffset - (int) verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private ItemStack getItemStack(String itemId) {
        if (itemId == null) return new ItemStack(Items.CHEST);
        return itemStackCache.computeIfAbsent(itemId, id -> {
            try {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                if (item != null) {
                    return new ItemStack(item);
                }
            } catch (Exception ignored) {}
            return new ItemStack(Items.CHEST);
        });
    }

    private void updateCachedMatches() {
        if (selectedItem == null) {
            cachedSortedMatches.clear();
            lastSelectedMatchKey = null;
            return;
        }
        String key = selectedItem.itemId + "#" + selectedItem.displayName;
        if (!key.equals(lastSelectedMatchKey)) {
            lastSelectedMatchKey = key;
            cachedSortedMatches = new ArrayList<>(selectedItem.matches);
            cachedSortedMatches.sort(Comparator.comparingInt(m -> calculateDistance(m.container)));
        }
    }

    private int calculateDistance(StorageContainer c) {
        if (minecraft == null || minecraft.player == null) return 0;
        double dx = minecraft.player.getX() - c.x;
        double dy = minecraft.player.getY() - c.y;
        double dz = minecraft.player.getZ() - c.z;
        return (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String formatCoords(StorageContainer c) {
        String dimName = c.dimension;
        if (dimName.contains(":")) dimName = dimName.split(":", 2)[1];
        return dimName + " (" + c.x + ", " + c.y + ", " + c.z + ")";
    }

    private String formatTimeAgo(long timestamp) {
        long diff = Math.max(0, System.currentTimeMillis() - timestamp);
        long sec = diff / 1000;
        if (sec < 60) return "gerade eben";
        long min = sec / 60;
        if (min < 60) return "vor " + min + "m";
        long hours = min / 60;
        if (hours < 24) return "vor " + hours + "h";
        long days = hours / 24;
        return "vor " + days + "d";
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (currentTab == Tab.SETTINGS) {
            saveSettingsQuietly();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
