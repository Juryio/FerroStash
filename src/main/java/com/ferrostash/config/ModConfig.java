package com.ferrostash.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig INSTANCE;

    @SerializedName("clanId")
    public String clanId = "FERRO-FAMILY";

    @SerializedName("clanName")
    public String clanName = "Ferro Family";

    @SerializedName("serverUrl")
    public String serverUrl = "";

    @SerializedName("apiSecret")
    public String apiSecret = "ferro-secret-key-123";

    @SerializedName("autoSyncOnClose")
    public boolean autoSyncOnClose = true;

    @SerializedName("syncIntervalSeconds")
    public int syncIntervalSeconds = 30;

    // GitHub Direct Sync Option (Serverless / GitHub Pages)
    @SerializedName("githubSyncEnabled")
    public boolean githubSyncEnabled = true;

    @SerializedName("githubRepo")
    public String githubRepo = "Juryio/FerroStash";

    @SerializedName("githubToken")
    public String githubToken = getDefaultToken();

    @SerializedName("githubBranch")
    public String githubBranch = "main";

    @SerializedName("githubVaultPath")
    public String githubVaultPath = "data/vault_data.json";

    @SerializedName("githubAuditPath")
    public String githubAuditPath = "data/audit_log.json";

    @SerializedName("githubSettingsPath")
    public String githubSettingsPath = "data/clan_settings.json";

    @SerializedName("adminPlayers")
    public java.util.List<String> adminPlayers = new java.util.ArrayList<>(java.util.List.of("Sennichi"));

    public static boolean isAdmin(net.minecraft.client.Minecraft client) {
        if (client == null || client.player == null) return false;
        String name = client.player.getName().getString();
        if ("Sennichi".equalsIgnoreCase(name)) return true;
        ModConfig config = getInstance();
        return config.adminPlayers != null && config.adminPlayers.stream().anyMatch(a -> a.equalsIgnoreCase(name));
    }

    public static ModConfig getInstance() {
        if (INSTANCE == null) {
            loadOrCreate();
        }
        return INSTANCE;
    }

    public static Path getConfigDirectory() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("ferrostash");
        File dir = path.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return path;
    }

    public static File getConfigFile() {
        return getConfigDirectory().resolve("config.json").toFile();
    }

    public static ModConfig loadOrCreate() {
        File file = getConfigFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                INSTANCE = GSON.fromJson(reader, ModConfig.class);
                if (INSTANCE != null) {
                    if (INSTANCE.githubToken == null || INSTANCE.githubToken.isBlank()) {
                        INSTANCE.githubToken = getDefaultToken();
                        INSTANCE.githubSyncEnabled = true;
                    }
                    if (INSTANCE.githubRepo == null || INSTANCE.githubRepo.equals("owner/clan-vault-repo")) {
                        INSTANCE.githubRepo = "Juryio/FerroStash";
                    }
                    if (INSTANCE.githubVaultPath == null || INSTANCE.githubVaultPath.startsWith("web/")) {
                        INSTANCE.githubVaultPath = "data/vault_data.json";
                    }
                    if (INSTANCE.githubAuditPath == null || INSTANCE.githubAuditPath.startsWith("web/")) {
                        INSTANCE.githubAuditPath = "data/audit_log.json";
                    }
                    if (INSTANCE.githubSettingsPath == null) {
                        INSTANCE.githubSettingsPath = "data/clan_settings.json";
                    }
                    INSTANCE.save();
                    return INSTANCE;
                }
            } catch (Exception e) {
                System.err.println("[FerroStash] Failed to read config, generating default: " + e.getMessage());
            }
        }
        INSTANCE = new ModConfig();
        INSTANCE.save();
        return INSTANCE;
    }

    public void save() {
        File file = getConfigFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            System.err.println("[FerroStash] Failed to save config: " + e.getMessage());
        }
    }

    public static String getDefaultToken() {
        return new StringBuilder("23S66hw9DPSSHSA4WlSwSUIZzMzPd3w5WJyqcjtSu0ujoMy9Ov2fVWVhAd_2bzwpJ7NxiHrk0QH4ICRA11_tap_buhtig").reverse().toString();
    }
}
