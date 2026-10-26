package com.ferrostash.cache;

import com.ferrostash.config.ModConfig;
import com.ferrostash.model.StorageContainer;
import com.ferrostash.model.StorageItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StorageCacheManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final StorageCacheManager INSTANCE = new StorageCacheManager();

    // Map of container ID -> StorageContainer
    private final Map<String, StorageContainer> containers = new ConcurrentHashMap<>();

    private StorageCacheManager() {}

    public static StorageCacheManager getInstance() {
        return INSTANCE;
    }

    public File getCacheFile() {
        return ModConfig.getConfigDirectory().resolve("storage_cache.json").toFile();
    }

    public synchronized void loadFromDisk() {
        File file = getCacheFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, StorageContainer>>() {}.getType();
            Map<String, StorageContainer> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                containers.clear();
                containers.putAll(loaded);
                deduplicateDoubleChests();
            }
        } catch (Exception e) {
            System.err.println("[FerroStash] Failed to load storage cache: " + e.getMessage());
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean savePending = new java.util.concurrent.atomic.AtomicBoolean(false);

    public void saveToDiskAsync() {
        if (savePending.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}
                savePending.set(false);
                saveToDisk();
            });
        }
    }

    public synchronized void saveToDisk() {
        File file = getCacheFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(containers, writer);
        } catch (Exception e) {
            System.err.println("[FerroStash] Failed to save storage cache: " + e.getMessage());
        }
    }

    public void updateContainer(StorageContainer container) {
        if (container == null || container.id == null) return;
        containers.put(container.id, container);
        saveToDiskAsync();
    }

    public void removeContainer(String id) {
        if (id == null) return;
        if (containers.remove(id) != null) {
            saveToDiskAsync();
        }
    }

    public synchronized boolean purgeBefore(long timestamp) {
        if (timestamp <= 0) return false;
        boolean changed = containers.values().removeIf(c -> c.lastOpenedTimestamp <= timestamp);
        if (changed) {
            saveToDisk();
        }
        return changed;
    }

    public synchronized void clearAll() {
        containers.clear();
        saveToDisk();
    }

    public StorageContainer getContainer(String id) {
        return containers.get(id);
    }

    public Collection<StorageContainer> getAllContainers() {
        return containers.values();
    }

    public void mergeRemoteContainers(List<StorageContainer> remoteContainers) {
        if (remoteContainers == null) return;
        boolean changed = false;

        long resetTs = com.ferrostash.zone.ClanSettingsManager.getInstance().getSettings().resetTimestamp;
        if (resetTs > 0) {
            if (containers.values().removeIf(c -> c.lastOpenedTimestamp <= resetTs)) {
                changed = true;
            }
        }

        for (StorageContainer remote : remoteContainers) {
            if (remote == null || remote.id == null) continue;
            // Never re-add containers that are older than the global reset!
            if (resetTs > 0 && remote.lastOpenedTimestamp <= resetTs) {
                continue;
            }
            // Never re-add containers that are marked private!
            if (com.ferrostash.zone.ClanSettingsManager.getInstance().isExplicitlyPrivate(remote.dimension, remote.x, remote.y, remote.z)) {
                containers.remove(remote.id);
                changed = true;
                continue;
            }
            StorageContainer local = containers.get(remote.id);
            if (local == null || remote.lastOpenedTimestamp > local.lastOpenedTimestamp) {
                containers.put(remote.id, remote);
                changed = true;
            }
        }
        if (deduplicateDoubleChests()) {
            changed = true;
        }
        if (changed) {
            saveToDisk();
        }
    }

    /**
     * Deduplicates double chests in cache by consolidating pairs into a single canonical entry.
     */
    public synchronized boolean deduplicateDoubleChests() {
        boolean changed = false;
        List<StorageContainer> list = new ArrayList<>(containers.values());
        Set<String> toRemove = new HashSet<>();

        for (int i = 0; i < list.size(); i++) {
            StorageContainer c1 = list.get(i);
            if (c1 == null || toRemove.contains(c1.id)) continue;

            for (int j = i + 1; j < list.size(); j++) {
                StorageContainer c2 = list.get(j);
                if (c2 == null || toRemove.contains(c2.id)) continue;

                if (isDoubleChestPair(c1, c2)) {
                    // Pick the entry to keep (favor newer timestamp, tie-break by smaller coordinate)
                    StorageContainer keep;
                    StorageContainer discard;
                    if (c1.lastOpenedTimestamp != c2.lastOpenedTimestamp) {
                        keep = c1.lastOpenedTimestamp > c2.lastOpenedTimestamp ? c1 : c2;
                        discard = (keep == c1) ? c2 : c1;
                    } else {
                        boolean c1Smaller = c1.x < c2.x || (c1.x == c2.x && c1.z < c2.z);
                        keep = c1Smaller ? c1 : c2;
                        discard = (keep == c1) ? c2 : c1;
                    }

                    keep.type = "DOUBLE_CHEST";
                    toRemove.add(discard.id);
                    changed = true;
                }
            }
        }

        for (String id : toRemove) {
            containers.remove(id);
        }

        if (changed) {
            saveToDisk();
        }
        return changed;
    }

    private boolean isDoubleChestPair(StorageContainer c1, StorageContainer c2) {
        if (c1 == null || c2 == null || c1.dimension == null || c2.dimension == null) return false;
        if (!c1.dimension.equals(c2.dimension)) return false;
        if (c1.y != c2.y) return false;

        int dx = Math.abs(c1.x - c2.x);
        int dz = Math.abs(c1.z - c2.z);
        // Must be directly adjacent horizontally (distance == 1)
        if (dx + dz != 1) return false;

        // Must be large chests (DOUBLE_CHEST, title contains "Large Chest"/"Große Truhe", or slots >= 27)
        if (!isLargeChestCandidate(c1) || !isLargeChestCandidate(c2)) return false;

        // Both must have significant slot/item overlap
        return hasItemOverlap(c1, c2);
    }

    private boolean isLargeChestCandidate(StorageContainer c) {
        if ("DOUBLE_CHEST".equalsIgnoreCase(c.type)) return true;
        String title = c.customTitle != null ? c.customTitle.toLowerCase() : "";
        if (title.contains("large chest") || title.contains("große truhe") || title.contains("grosse truhe")) return true;
        if (c.items != null) {
            for (StorageItem item : c.items) {
                if (item.slot >= 27) return true;
            }
        }
        return false;
    }

    private boolean hasItemOverlap(StorageContainer c1, StorageContainer c2) {
        if (c1.items == null || c2.items == null) return false;
        if (c1.items.isEmpty() || c2.items.isEmpty()) return true;

        Map<Integer, String> slots1 = new HashMap<>();
        for (StorageItem item : c1.items) {
            slots1.put(item.slot, item.itemId);
        }

        int matchingSlots = 0;
        for (StorageItem item : c2.items) {
            String id1 = slots1.get(item.slot);
            if (id1 != null && id1.equals(item.itemId)) {
                matchingSlots++;
            }
        }

        int minItems = Math.min(c1.items.size(), c2.items.size());
        if (minItems == 0) return true;
        double ratio = (double) matchingSlots / minItems;
        return ratio >= 0.25 || matchingSlots >= 5;
    }

    public static class SearchResult {
        public String itemId;
        public String displayName;
        public int totalCount;
        public List<String> enchantments = new ArrayList<>();
        public List<ContainerMatch> matches = new ArrayList<>();

        public SearchResult(String itemId, String displayName) {
            this.itemId = itemId;
            this.displayName = displayName;
        }

        public SearchResult(String itemId, String displayName, List<String> enchantments) {
            this.itemId = itemId;
            this.displayName = displayName;
            if (enchantments != null) {
                this.enchantments = new ArrayList<>(enchantments);
            }
        }
    }

    public static class ContainerMatch {
        public StorageContainer container;
        public int countInContainer;

        public ContainerMatch(StorageContainer container, int countInContainer) {
            this.container = container;
            this.countInContainer = countInContainer;
        }
    }

    /**
     * Searches all cached clan containers for items matching query.
     * Empty query returns all distinct cached items sorted by count.
     */
    public List<SearchResult> search(String query) {
        String filter = query != null ? query.trim().toLowerCase() : "";
        Map<String, SearchResult> results = new HashMap<>();

        for (StorageContainer container : containers.values()) {
            for (StorageItem item : container.items) {
                if (item.itemId == null || item.count <= 0) continue;

                String itemIdLower = item.itemId.toLowerCase();
                String displayName = item.getDisplayName();
                String displayNameLower = displayName.toLowerCase();
                String containerTitleLower = container.customTitle != null ? container.customTitle.toLowerCase() : "";

                boolean matches = filter.isEmpty()
                        || itemIdLower.contains(filter)
                        || displayNameLower.contains(filter)
                        || containerTitleLower.contains(filter);

                if (!matches && item.enchantments != null) {
                    for (String ench : item.enchantments) {
                        if (ench.toLowerCase().contains(filter)) {
                            matches = true;
                            break;
                        }
                    }
                }

                if (matches) {
                    String groupKey = item.itemId;
                    if (item.enchantments != null && !item.enchantments.isEmpty()) {
                        groupKey = item.itemId + "#" + String.join(";", item.enchantments);
                    }
                    SearchResult sr = results.computeIfAbsent(groupKey, k -> new SearchResult(item.itemId, displayName, item.enchantments));
                    sr.totalCount += item.count;

                    // Add or aggregate container match
                    boolean foundInList = false;
                    for (ContainerMatch cm : sr.matches) {
                        if (cm.container.id.equals(container.id)) {
                            cm.countInContainer += item.count;
                            foundInList = true;
                            break;
                        }
                    }
                    if (!foundInList) {
                        sr.matches.add(new ContainerMatch(container, item.count));
                    }
                }
            }
        }

        List<SearchResult> sorted = new ArrayList<>(results.values());
        sorted.sort((a, b) -> Integer.compare(b.totalCount, a.totalCount));
        return sorted;
    }
}
