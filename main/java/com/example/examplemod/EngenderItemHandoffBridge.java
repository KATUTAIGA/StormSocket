package com.example.examplemod;

import java.util.Set;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import techguns.items.guns.GenericGun;
import techguns.items.guns.IGenericGunMelee;
import techguns.items.guns.ammo.AmmoType;

/**
 * [アイテム受け渡し] This is the actual "hand an item to the ally" system --
 * it did not exist anywhere before this. {@code EntityFriendlyCreature.interact()}
 * is an empty stub (always returns false) and the base mod's own event
 * handler never listens for {@link PlayerInteractEvent.EntityInteract}
 * either, so right-clicking an ally with an item in hand never did
 * anything: not equipping a pickaxe/axe/shovel/gun, not delivering ammo,
 * not delivering build materials. This class implements all three:
 *
 * <ul>
 *   <li>A recognised primary tool (pickaxe/axe/shovel/Techguns gun or melee
 *       gun/sword) is equipped to the ally's main hand. Whatever it was
 *       already holding is safely dropped at its feet first -- nothing is
 *       ever silently destroyed by a second hand-off.</li>
 *   <li>Ammo matching the gun currently in the ally's main hand goes into
 *       its own 8-slot {@code basicInventory} instead of replacing the gun
 *       -- this is what fixes "can't hand over both the gun and the
 *       ammo".</li>
 *   <li>A block item, while the ally is holding a shovel (build mode), is
 *       routed to {@link EntityAIBuildBaseAlly}'s material stock instead of
 *       being equipped.</li>
 * </ul>
 */
public class EngenderItemHandoffBridge {

    private static final int MAX_HAND_TAKE = 64;

    private final EngenderGatheringBridge gatheringBridge;

    public EngenderItemHandoffBridge(EngenderGatheringBridge gatheringBridge) {
        this.gatheringBridge = gatheringBridge;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player.getEntityWorld().isRemote) {
            return;
        }
        if (!(event.getTarget() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getTarget();
        EnumHand hand = event.getHand();
        ItemStack heldStack = player.getHeldItem(hand);
        if (heldStack.isEmpty()) {
            // [プレイヤーと同じ持ち物] スニーク＋素手で持ち物画面（36スロット）を開く。
            if (player.isSneaking() && hand == EnumHand.MAIN_HAND && this.mayManage(entity, player)) {
                AllyInventory.open(player, entity);
                event.setCanceled(true);
            } else if (!player.isSneaking() && hand == EnumHand.MAIN_HAND && this.mayManage(entity, player)
                    && !this.gatheringBridge.getJob(entity).isEmpty()) {
                // 素手で右クリック: 仕事をやめて護衛に専念（道具を渡せば再開）。
                this.gatheringBridge.setJob(entity, "");
                if (EngenderGatheringBridge.jobForTool(entity.getHeldItemMainhand()) != null) {
                    AllyToolManager.stowMainhand(entity);
                    AllyToolManager.equipWeapon(entity);
                }
                player.sendMessage(new TextComponentString("[Engender] " + entity.getName()
                        + " は仕事をやめて護衛に専念します（道具を渡すと再開）。"));
                event.setCanceled(true);
            }
            return;
        }
        if (!this.mayManage(entity, player)) {
            return;
        }

        ItemStack mainhand = entity.getHeldItemMainhand();

        // 1) Ammo for the gun the ally is already holding -> its own inventory.
        if (!mainhand.isEmpty() && mainhand.getItem() instanceof GenericGun) {
            GenericGun gun = (GenericGun) mainhand.getItem();
            if (this.isAmmoForGun(gun, heldStack)) {
                this.giveAmmoToInventory(entity, player, hand, heldStack);
                event.setCanceled(true);
                return;
            }
        }

        // 1b) HBM gun: its real ammo -> the ally's ammo pouch.
        if (!mainhand.isEmpty() && EngenderHBMBridge.isHbmGun(mainhand.getItem())
                && HBMGunSupport.isAmmoFor(mainhand, heldStack)) {
            ItemStack toGive = heldStack.copy();
            toGive.setCount(Math.min(toGive.getCount(), MAX_HAND_TAKE));
            String name = heldStack.getDisplayName();
            int given = AllyAmmoStorage.add(entity, toGive);
            if (given > 0) {
                this.consumeFromHand(player, hand, heldStack, given);
                player.sendMessage(new TextComponentString("[HBM] 弾薬を受け取りました: " + name + " x" + given
                        + "（所持合計 " + AllyAmmoStorage.total(entity) + "）"));
            }
            event.setCanceled(true);
            return;
        }

        // 1c) Torch / stick / coal for a gathering ally -> its torch supplies.
        if (EntityAIGatherResourceAlly.isTorchSupply(heldStack)) {
            ItemStack toGive = heldStack.copy();
            toGive.setCount(Math.min(toGive.getCount(), MAX_HAND_TAKE));
            if (this.gatheringBridge.giveTorchSupplies(entity, toGive)) {
                this.consumeFromHand(player, hand, heldStack, toGive.getCount());
                player.sendMessage(new TextComponentString("[採取] たいまつ用の資材を受け取りました: " + toGive.getDisplayName()
                        + " x" + toGive.getCount()));
                event.setCanceled(true);
                return;
            }
        }

        // 2) Build material for a shovel-mode ally -> its material stock.
        boolean builder = "shovel".equals(this.gatheringBridge.getJob(entity))
                || (!mainhand.isEmpty() && mainhand.getItem().getToolClasses(mainhand).contains("shovel"));
        if (builder && !player.isSneaking()
                && this.isBuildMaterial(heldStack) && !this.isPrimaryTool(heldStack)) {
            ItemStack toGive = heldStack.copy();
            toGive.setCount(Math.min(toGive.getCount(), MAX_HAND_TAKE));
            if (this.gatheringBridge.giveMaterialToBuilder(entity, toGive)) {
                this.consumeFromHand(player, hand, heldStack, toGive.getCount());
                player.sendMessage(new TextComponentString("[拠点建設] 資材を受け取りました: " + toGive.getDisplayName()));
                event.setCanceled(true);
                return;
            }
        }

        // 3) A real tool/weapon -> equip it. Whatever the ally was already
        // holding goes into its own inventory (it switches back by itself).
        if (this.isPrimaryTool(heldStack)) {
            this.equipTool(entity, player, hand, heldStack);
            event.setCanceled(true);
            return;
        }

        // 4) スニーク中に他の物を渡すと、そのまま味方の持ち物へ入れる。
        if (player.isSneaking()) {
            ItemStack toGive = heldStack.copy();
            ItemStack rest = AllyInventory.insert(entity, toGive);
            int given = toGive.getCount() - rest.getCount();
            if (given > 0) {
                this.consumeFromHand(player, hand, heldStack, given);
                player.sendMessage(new TextComponentString("[Engender] 持ち物に入れました: " + toGive.getDisplayName() + " x" + given));
            } else {
                player.sendMessage(new TextComponentString("[Engender] 持ち物がいっぱいです。"));
            }
            event.setCanceled(true);
        }
    }

    /** 野生・他人の味方は触らない（持ち主が分からない味方は誰でも可）。 */
    private boolean mayManage(EntityFriendlyCreature entity, EntityPlayer player) {
        EntityPlayer owner = AllyAIUtil.resolveOwnerPlayer(entity, this.gatheringBridge);
        return owner == null || owner == player;
    }

    private boolean isPrimaryTool(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof GenericGun || item instanceof IGenericGunMelee || item instanceof ItemSword) {
            return true;
        }
        // [HBMの銃が装備させられない不具合] HBM/Flan の銃は GenericGun でも剣でもないので、
        // 以前はここで弾かれて何も起きなかった。
        if (EngenderHBMBridge.isHbmGun(item) || EngenderFlanBridge.isFlanGun(item) || looksLikeHbmWeapon(item)
                || HBMToolSupport.isAbilityTool(stack)) {
            return true;
        }
        Set<String> classes = item.getToolClasses(stack);
        return classes.contains("pickaxe") || classes.contains("axe") || classes.contains("shovel");
    }

    /** 一覧に無い HBM の銃（独自クラスの古い銃など）もクラス名で拾う。弾・マガジン類は除く。 */
    private static boolean looksLikeHbmWeapon(Item item) {
        for (Class<?> c = item.getClass(); c != null && c != Item.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (!name.startsWith("com.hbm.items.")) {
                continue;
            }
            String simple = c.getSimpleName();
            if (simple.contains("Ammo") || simple.contains("Mag") || simple.contains("Casing") || simple.contains("Clip") || simple.contains("Cell")) {
                return false;
            }
            if (simple.startsWith("Gun") || simple.startsWith("ItemGun") || simple.contains("Launcher")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuildMaterial(ItemStack stack) {
        return stack.getItem() instanceof ItemBlock;
    }

    /** Checks every ammo variant the gun accepts, not just whichever one is currently selected. */
    private boolean isAmmoForGun(GenericGun gun, ItemStack candidate) {
        AmmoType ammoType = gun.getAmmoType();
        if (ammoType == null) {
            return false;
        }
        int variantCount = (ammoType.getVariants() == null) ? 1 : Math.max(1, ammoType.getVariants().size());
        for (int i = 0; i < variantCount; i++) {
            ItemStack[] ammoStacks;
            try {
                ammoStacks = ammoType.getAmmo(i);
            } catch (Exception e) {
                continue;
            }
            if (ammoStacks == null) {
                continue;
            }
            for (ItemStack ammoStack : ammoStacks) {
                if (ammoStack != null && !ammoStack.isEmpty() && ItemStack.areItemsEqual(candidate, ammoStack)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void equipTool(EntityFriendlyCreature entity, EntityPlayer player, EnumHand hand, ItemStack heldStack) {
        ItemStack previous = entity.getHeldItemMainhand();
        ItemStack toEquip = heldStack.copy();
        toEquip.setCount(1);

        entity.setHeldItem(EnumHand.MAIN_HAND, toEquip);
        this.consumeFromHand(player, hand, heldStack, 1);

        if (!previous.isEmpty()) {
            // Never silently destroy whatever it was already holding -- it now
            // goes into the ally's own inventory so it can switch back later.
            AllyInventory.insertOrDrop(entity, previous);
        }
        String job = EngenderGatheringBridge.jobForTool(toEquip);
        if (job != null) {
            this.gatheringBridge.setJob(entity, job);
        }
        player.sendMessage(new TextComponentString("[Engender] " + toEquip.getDisplayName() + " を装備させました。"
                + (job != null ? "（仕事: " + EngenderGatheringBridge.jobLabel(job) + "）" : "")));
    }

    private void giveAmmoToInventory(EntityFriendlyCreature entity, EntityPlayer player, EnumHand hand, ItemStack heldStack) {
        // [マガジン64個が1個しか渡らない不具合の修正] basicInventory is NOT
        // storage: Engender overwrites slots 0-6 with the mob's equipment and
        // book every tick, so ammo put there vanished on the next tick. Use
        // the ally's own persistent ammo pouch instead.
        ItemStack toGive = heldStack.copy();
        toGive.setCount(Math.min(toGive.getCount(), MAX_HAND_TAKE));
        String name = heldStack.getDisplayName();
        int given = AllyAmmoStorage.add(entity, toGive);
        if (given > 0) {
            this.consumeFromHand(player, hand, heldStack, given);
            player.sendMessage(new TextComponentString("[Techguns] 弾薬を受け取りました: " + name + " x" + given
                    + "（所持合計 " + AllyAmmoStorage.total(entity) + "）"));
        } else {
            player.sendMessage(new TextComponentString("[Techguns] インベントリがいっぱいで受け取れませんでした。"));
        }
    }

    /** Returns how many items were actually absorbed into `inv`. */
    private int addToInventory(InventoryBasic inv, ItemStack stack) {
        int totalGiven = 0;
        for (int i = 0; i < inv.getSizeInventory() && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getStackInSlot(i);
            if (slot.isEmpty() || !ItemStack.areItemsEqual(slot, stack) || !ItemStack.areItemStackTagsEqual(slot, stack)) {
                continue;
            }
            int room = slot.getMaxStackSize() - slot.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, stack.getCount());
            slot.grow(move);
            stack.shrink(move);
            inv.setInventorySlotContents(i, slot);
            totalGiven += move;
        }
        for (int i = 0; i < inv.getSizeInventory() && !stack.isEmpty(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                continue;
            }
            int move = stack.getCount();
            inv.setInventorySlotContents(i, stack.copy());
            stack.setCount(0);
            totalGiven += move;
        }
        inv.markDirty();
        return totalGiven;
    }

    private void consumeFromHand(EntityPlayer player, EnumHand hand, ItemStack original, int amount) {
        if (player.capabilities.isCreativeMode) {
            return;
        }
        original.shrink(amount);
        player.setHeldItem(hand, original);
    }

    private void dropAtEntity(EntityFriendlyCreature entity, ItemStack stack) {
        World world = entity.getEntityWorld();
        EntityItem itemEntity = new EntityItem(world, entity.posX, entity.posY + 0.5, entity.posZ, stack);
        itemEntity.setPickupDelay(10);
        world.spawnEntity(itemEntity);
    }
}
