package com.ferrostash.mixin;

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
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Shadow
    @Final
    protected AbstractContainerMenu menu;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    @Unique
    private String ferrostash$currentContainerId;

    @Unique
    private BlockPos ferrostash$containerPos;

    @Unique
    private BlockPos ferrostash$duplicateHalfPos;

    @Unique
    private String ferrostash$dimension;

    @Unique
    private boolean ferrostash$isSupportedContainer = false;

    @Unique
    private boolean ferrostash$openSnapshotCaptured = false;

    @Inject(method = "init", at = @At("TAIL"))
    private void ferrostash$onScreenInit(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        // Skip player's personal inventory screen ('E' key)
        if ((Object) this instanceof InventoryScreen) {
            return;
        }

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        String title = screen.getTitle().getString();

        // 1. STRICT CONTAINER FILTER: ONLY Chests, Furnaces and Barrels! Shulkers and others are completely ignored.
        if (!ferrostash$checkIfSupported(screen, menu, title)) {
            ferrostash$isSupportedContainer = false;
            return;
        }
        ferrostash$isSupportedContainer = true;

        // Determine container position from player crosshair hit result
        BlockPos pos = null;
        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK && client.hitResult instanceof BlockHitResult bhr) {
            pos = bhr.getBlockPos();
        }
        if (pos == null) {
            pos = client.player.blockPosition();
        }

        // Canonical double chest normalization: ensure both halves map to the exact same canonical block!
        BlockPos duplicatePos = null;
        if (client.level != null && pos != null) {
            BlockState state = client.level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock) {
                ChestType chestType = state.getValue(ChestBlock.TYPE);
                if (chestType != ChestType.SINGLE) {
                    Direction dir = ChestBlock.getConnectedDirection(state);
                    BlockPos otherPos = pos.relative(dir);
                    if (pos.compareTo(otherPos) > 0) {
                        duplicatePos = pos;
                        pos = otherPos;
                    } else {
                        duplicatePos = otherPos;
                    }
                }
            }
        }

        ferrostash$duplicateHalfPos = duplicatePos;
        ferrostash$containerPos = pos;
        ferrostash$dimension = client.level.dimension().identifier().toString();
        ferrostash$currentContainerId = StorageContainer.generateId(ferrostash$dimension, pos.getX(), pos.getY(), pos.getZ());

        // 2. AUTO-CLEAR WAYPOINT: If player opens the destination container, tracking clears automatically!
        WaypointManager.getInstance().clearIfMatching(ferrostash$dimension, pos.getX(), pos.getY(), pos.getZ());
        if (duplicatePos != null) {
            WaypointManager.getInstance().clearIfMatching(ferrostash$dimension, duplicatePos.getX(), duplicatePos.getY(), duplicatePos.getZ());
            // Immediately purge the duplicate half from local storage cache
            String dupId = StorageContainer.generateId(ferrostash$dimension, duplicatePos.getX(), duplicatePos.getY(), duplicatePos.getZ());
            StorageCacheManager.getInstance().removeContainer(dupId);
            AuditLogManager.getInstance().clearSnapshot(dupId);
        }

        // 3. CHECK CLAN OR PRIVATE STATUS
        boolean isClan = ClanSettingsManager.getInstance().isClanContainer(
                ferrostash$dimension, pos.getX(), pos.getY(), pos.getZ()
        );

        // 4. IN-GAME TOGGLE BUTTON ([✓ Clan] / [✗ Privat]) in top right of chest window
        Button toggleBtn = Button.builder(Component.literal(isClan ? "§a[✓ Clan]" : "§c[✗ Privat]"), b -> {
            boolean newClan = ClanSettingsManager.getInstance().toggleContainerPrivacy(
                    ferrostash$dimension, ferrostash$containerPos.getX(), ferrostash$containerPos.getY(), ferrostash$containerPos.getZ()
            );
            b.setMessage(Component.literal(newClan ? "§a[✓ Clan]" : "§c[✗ Privat]"));
            if (!newClan) {
                AuditLogManager.getInstance().clearSnapshot(ferrostash$currentContainerId);
                StorageCacheManager.getInstance().removeContainer(ferrostash$currentContainerId);
                if (client.gui != null && client.gui.hud != null) {
                    client.gui.hud.setOverlayMessage(Component.literal("§e[FerroStash] Kiste auf §cPrivat §egesetzt."), false);
                }
            } else {
                List<StorageItem> items = ferrostash$extractContainerItems(client);
                AuditLogManager.getInstance().recordOpenSnapshot(ferrostash$currentContainerId, items);

                String cTitle = screen.getTitle().getString();
                String cType = screen.getClass().getSimpleName().replace("Screen", "").toUpperCase();
                if (cType.isBlank()) cType = "CONTAINER";
                String playerName = client.player.getName().getString();

                StorageContainer sc = new StorageContainer(
                        ferrostash$dimension,
                        ferrostash$containerPos.getX(),
                        ferrostash$containerPos.getY(),
                        ferrostash$containerPos.getZ(),
                        cType,
                        cTitle,
                        playerName
                );
                sc.items = items;
                StorageCacheManager.getInstance().updateContainer(sc);

                if (client.gui != null && client.gui.hud != null) {
                    client.gui.hud.setOverlayMessage(Component.literal("§e[FerroStash] Kiste auf §aClan-Lager §egesetzt."), false);
                }
            }
            ClanSyncClient.getInstance().forceVaultPush();
        }).bounds(this.leftPos + this.imageWidth - 62, this.topPos + 4, 58, 12).build();

        ((ScreenAccessor) this).invokeAddRenderableWidget(toggleBtn);

        ferrostash$openSnapshotCaptured = false;

        // Snapshot initial slots upon opening only if it's a clan container
        if (isClan) {
            StorageContainer cached = StorageCacheManager.getInstance().getContainer(ferrostash$currentContainerId);
            if (cached != null && cached.items != null && !cached.items.isEmpty()) {
                AuditLogManager.getInstance().recordOpenSnapshot(ferrostash$currentContainerId, cached.items);
            }
            List<StorageItem> initialItems = ferrostash$extractContainerItems(client);
            if (!initialItems.isEmpty()) {
                AuditLogManager.getInstance().recordOpenSnapshot(ferrostash$currentContainerId, initialItems);
                ferrostash$openSnapshotCaptured = true;
            }
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ferrostash$onRenderExtract(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!ferrostash$isSupportedContainer || ferrostash$openSnapshotCaptured) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || ferrostash$currentContainerId == null) return;

        boolean isClan = ClanSettingsManager.getInstance().isClanContainer(
                ferrostash$dimension, ferrostash$containerPos.getX(), ferrostash$containerPos.getY(), ferrostash$containerPos.getZ()
        );
        if (!isClan) return;

        List<StorageItem> items = ferrostash$extractContainerItems(client);
        if (!items.isEmpty()) {
            AuditLogManager.getInstance().recordOpenSnapshot(ferrostash$currentContainerId, items);
            ferrostash$openSnapshotCaptured = true;
        }
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void ferrostash$onScreenClose(CallbackInfo ci) {
        if (!ferrostash$isSupportedContainer) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || ferrostash$currentContainerId == null) return;
        if ((Object) this instanceof InventoryScreen) return;

        // Check if this container belongs to clan or is private/outside
        boolean isClan = ClanSettingsManager.getInstance().isClanContainer(
                ferrostash$dimension, ferrostash$containerPos.getX(), ferrostash$containerPos.getY(), ferrostash$containerPos.getZ()
        );
        if (!isClan) {
            // Completely ignore private/foreign chests
            return;
        }

        List<StorageItem> currentItems = ferrostash$extractContainerItems(client);

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        String title = screen.getTitle().getString();
        String type = screen.getClass().getSimpleName().replace("Screen", "").toUpperCase();
        if (type.isBlank()) type = "CONTAINER";
        if (ferrostash$duplicateHalfPos != null || currentItems.size() > 27) {
            type = "DOUBLE_CHEST";
        }

        // Clean up duplicate half if still in cache
        if (ferrostash$duplicateHalfPos != null) {
            String dupId = StorageContainer.generateId(ferrostash$dimension, ferrostash$duplicateHalfPos.getX(), ferrostash$duplicateHalfPos.getY(), ferrostash$duplicateHalfPos.getZ());
            StorageCacheManager.getInstance().removeContainer(dupId);
            AuditLogManager.getInstance().clearSnapshot(dupId);
        }

        String playerName = client.player.getName().getString();

        StorageContainer container = new StorageContainer(
                ferrostash$dimension,
                ferrostash$containerPos.getX(),
                ferrostash$containerPos.getY(),
                ferrostash$containerPos.getZ(),
                type,
                title,
                playerName
        );
        container.items = currentItems;

        // 1. Compute diff and register audit transaction FIRST before updating cache!
        AuditLogManager.getInstance().recordCloseAndDiff(container, playerName);

        // 2. Update persistent local cache
        StorageCacheManager.getInstance().updateContainer(container);
    }

    @Unique
    private boolean ferrostash$hasContainerSlots(Minecraft client) {
        if (menu == null || client.player == null) return false;
        for (Slot slot : menu.slots) {
            if (slot.container != client.player.getInventory()) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private List<StorageItem> ferrostash$extractContainerItems(Minecraft client) {
        List<StorageItem> result = new ArrayList<>();
        if (menu == null || client.player == null) return result;

        int index = 0;
        for (Slot slot : menu.slots) {
            // Only examine slots belonging to the opened container, not player's bottom hotbar/inv
            if (slot.container != client.player.getInventory()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    String customName = stack.getHoverName().getString();
                    List<String> enchantments = ferrostash$extractEnchantments(stack);
                    result.add(new StorageItem(itemId, stack.getCount(), customName, index, enchantments));
                }
                index++;
            }
        }
        return result;
    }

    @Unique
    private List<String> ferrostash$extractEnchantments(ItemStack stack) {
        List<String> list = new ArrayList<>();
        if (stack.isEmpty()) return list;

        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) {
            for (Holder<Enchantment> holder : enchants.keySet()) {
                int level = enchants.getLevel(holder);
                list.add(Enchantment.getFullname(holder, level).getString());
            }
        }

        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            for (Holder<Enchantment> holder : stored.keySet()) {
                int level = stored.getLevel(holder);
                list.add(Enchantment.getFullname(holder, level).getString());
            }
        }

        return list;
    }

    @Unique
    private boolean ferrostash$checkIfSupported(AbstractContainerScreen<?> screen, AbstractContainerMenu menu, String title) {
        String sClass = screen.getClass().getSimpleName().toLowerCase();
        String mClass = (menu != null) ? menu.getClass().getSimpleName().toLowerCase() : "";
        String t = title != null ? title.toLowerCase() : "";

        // BLACKLIST CHECK: If title contains any blacklisted keyword (e.g. inventory, enderchest, shop, auktion)
        if (ClanSettingsManager.getInstance().isBlacklistedTitle(title)) {
            return false;
        }

        // STRICT EXCLUSIONS: SHULKERS MUST NEVER BE TRACKED OR SHOW BUTTON
        if (sClass.contains("shulker") || mClass.contains("shulker") || t.contains("shulker")) {
            return false;
        }

        // HOPPER EXCLUSION
        if (sClass.contains("hopper") || mClass.contains("hopper") || t.contains("hopper") || t.contains("trichter")) {
            return false;
        }

        // ONLY CHESTS, FURNACES, BARRELS
        boolean isChest = sClass.contains("chest") || mClass.contains("chest")
                || sClass.contains("genericcontainer") || mClass.contains("genericcontainer")
                || t.contains("kiste") || t.contains("chest") || t.contains("truhe");

        boolean isFurnace = sClass.contains("furnace") || mClass.contains("furnace")
                || sClass.contains("smoker") || mClass.contains("smoker")
                || sClass.contains("blast") || mClass.contains("blast")
                || t.contains("ofen") || t.contains("furnace") || t.contains("räucherofen") || t.contains("schmelzofen") || t.contains("smoker");

        boolean isBarrel = sClass.contains("barrel") || mClass.contains("barrel")
                || t.contains("fass") || t.contains("barrel");

        return isChest || isFurnace || isBarrel;
    }
}
