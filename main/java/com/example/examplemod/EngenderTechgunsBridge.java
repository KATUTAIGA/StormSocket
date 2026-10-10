package com.example.examplemod;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.helpful.ai.EntityAIFriendlyAttackMelee;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.init.SoundEvents;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import techguns.items.guns.GenericGun;
import techguns.items.guns.IGenericGunMelee;
import techguns.items.guns.ammo.AmmoType;
import techguns.items.guns.ammo.AmmoVariant;

public class EngenderTechgunsBridge {

    private static final double GUN_MOVE_SPEED = 1.0D;
    private static final int GUN_SEMI_AUTO_INTERVAL = 14;
    private static final float GUN_MAX_ATTACK_DIST = 56.0F;

    private static final double MELEE_MOVE_SPEED = 1.2D;
    private static final boolean MELEE_LONG_MEMORY = true;

    private static final int COMBAT_AI_PRIORITY = 2;

    /** [弾薬管理] how often (in ticks) a missing-ammo notice may repeat while still out of ammo. */
    private static final int AMMO_WARNING_INTERVAL_TICKS = 60; // 3s
    /** How long a status message stays shown above the ally's head before its real name returns. */
    private static final int NAME_NOTICE_DURATION_TICKS = 100; // 5s
    private static final double NOTICE_BROADCAST_RANGE = 24.0D;

    private final Map<EntityFriendlyCreature, Item> lastSeenWeapon = new WeakHashMap<>();
    private final Map<EntityFriendlyCreature, EntityAIBase> managedTask = new WeakHashMap<>();

    // [弾薬管理] status-notice bookkeeping, keyed per ally.
    private final Map<EntityFriendlyCreature, Integer> ammoWarningCooldown = new WeakHashMap<>();
    private final Map<EntityFriendlyCreature, String> savedNameTag = new WeakHashMap<>();
    private final Map<EntityFriendlyCreature, Boolean> savedAlwaysRenderName = new WeakHashMap<>();
    private final Map<EntityFriendlyCreature, Integer> noticeExpiryTick = new WeakHashMap<>();
    private int tickCounter;

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityFriendlyCreature) {
            EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntity();
            removeManagedCombatAI(entity);
            this.lastSeenWeapon.remove(entity);
            this.ammoWarningCooldown.remove(entity);
            this.savedNameTag.remove(entity);
            this.savedAlwaysRenderName.remove(entity);
            this.noticeExpiryTick.remove(entity);
        }
    }

    /**
     * プレイヤーが攻撃された時、または攻撃した時に周囲の味方Mobにその敵を攻撃させる（護衛AI）
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntityLiving().getEntityWorld().isRemote) {
            return;
        }

        // 1. プレイヤーが敵から攻撃を受けた場合 -> 周囲の味方がその敵を優先攻撃！
        if (event.getEntityLiving() instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) event.getEntityLiving();
            if (event.getSource().getTrueSource() instanceof EntityLivingBase) {
                EntityLivingBase attacker = (EntityLivingBase) event.getSource().getTrueSource();
                alertNearbyAllies(player, attacker);
            }
        }

        // 2. プレイヤーが敵を攻撃した場合 -> 周囲の味方も一緒に追撃！
        if (event.getSource().getTrueSource() instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) event.getSource().getTrueSource();
            EntityLivingBase target = event.getEntityLiving();
            alertNearbyAllies(player, target);
        }
    }

    private void alertNearbyAllies(EntityPlayer player, EntityLivingBase target) {
        // [味方がプレイヤーや味方同士を攻撃する不具合の修正] The alert had no
        // filter at all: self-inflicted TNT/rocket damage (source = the player
        // himself) made every ally within 30 blocks target the PLAYER; a stray
        // ally bullet hitting the player made the others target that ALLY; and
        // other players' / wild Engender creatures were recruited too.
        if (target == null || target == player || !target.isEntityAlive()
                || target instanceof EntityPlayer || target instanceof EntityFriendlyCreature) {
            return;
        }
        double range = 30.0D;
        player.getEntityWorld().getEntitiesWithinAABB(
                EntityFriendlyCreature.class,
                player.getEntityBoundingBox().grow(range, 10.0D, range)
        ).forEach(ally -> {
            if (ally == target || ally.isWild() || !ally.isOwner(player) || ally.isOnSameTeam(target)) {
                return;
            }
            if (ally.getAttackTarget() == null || !ally.getAttackTarget().isEntityAlive()) {
                ally.setAttackTarget(target);
            }
        });
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!(event.getEntityLiving() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntityLiving();
        if (entity.getEntityWorld().isRemote) {
            return;
        }
        // Real world clock (the old counter ran once per ally per tick, so
        // notices vanished N times too fast with N allies loaded).
        this.tickCounter = (int) entity.getEntityWorld().getTotalWorldTime();

        ItemStack mainhand = entity.getHeldItemMainhand();
        Item current = mainhand.isEmpty() ? null : mainhand.getItem();
        Item previous = this.lastSeenWeapon.get(entity);

        if (current != previous) {
            this.lastSeenWeapon.put(entity, current);
            applyWeaponAI(entity, mainhand);
        }

        // [Techguns連携] runs every tick, independent of combat state, so
        // ammo gets requested/topped off the moment the gun is handed over
        // and kept ready even before a fight starts.
        if (current instanceof GenericGun) {
            this.manageAmmo(entity, mainhand);
        } else {
            this.ammoWarningCooldown.remove(entity);
        }
        this.tickStatusNoticeExpiry(entity);
    }

    // ------------------------------------------------------------------
    // [Techguns連携] 弾薬管理・節約射撃: 銃を渡された際の弾薬チェックと
    // インベントリからの実際の弾薬消費/補充、わかりやすい状態通知。
    // ------------------------------------------------------------------

    /**
     * [銃が発射しない不具合の修正: バリアント不一致] Decompiling the actual
     * techguns-2.1.3.0.jar shows {@code GenericGun.getReloadItem(stack)} is
     * implemented as {@code ammoType.getAmmo(getCurrentAmmoVariant(stack))}
     * -- it only ever looks at whichever ammo variant string happens to
     * already be stamped in THIS gun stack's own NBT ({@code "ammovariant"},
     * defaulting to {@code "default"} the first time the gun is ever
     * touched). It has no way to know which variant the player actually
     * handed to this ally. For any gun with more than one valid ammo type
     * (e.g. standard vs. armor-piercing rounds), this meant: the player
     * hands over perfectly valid ammo, {@link EngenderItemHandoffBridge}
     * happily accepts it into the ally's inventory (it checks every
     * variant), but this method kept checking inventory for the *wrong*
     * variant's item forever -- {@code hasAllAmmo} never passed, the gun
     * never reloaded, and the ally looked like it was simply refusing to
     * shoot despite "having ammo". Fixed by trying every variant the gun's
     * {@link AmmoType} accepts, reloading with whichever one is actually in
     * stock, and stamping that variant onto the gun via
     * {@code setCurrentAmmoVariant} so subsequent reloads/tooltips/firing
     * stay consistent with what was actually loaded.
     */
    private void manageAmmo(EntityFriendlyCreature entity, ItemStack gunStack) {
        if (gunStack.isEmpty() || !(gunStack.getItem() instanceof GenericGun)) {
            return;
        }
        GenericGun gun = (GenericGun) gunStack.getItem();
        if (gun.getAmmoLeft(gunStack) > 0) {
            // まだ弾があるなら催促しない。
            return;
        }

        AmmoType ammoType = gun.getAmmoType();
        if (ammoType == null) {
            return;
        }
        List<AmmoVariant> variants = ammoType.getVariants();
        int variantCount = (variants == null || variants.isEmpty()) ? 1 : variants.size();

        ItemStack[] representativeRequirement = null;
        for (int variantId = 0; variantId < variantCount; variantId++) {
            ItemStack[] required;
            try {
                required = ammoType.getAmmo(variantId);
            } catch (Exception e) {
                continue;
            }
            if (required == null || required.length == 0) {
                continue;
            }
            if (representativeRequirement == null) {
                representativeRequirement = required;
            }
            if (!this.hasAllAmmo(entity, required)) {
                continue;
            }
            this.consumeAmmo(entity, required);
            if (variants != null && variantId < variants.size()) {
                gun.setCurrentAmmoVariant(gunStack, variants.get(variantId).getKey());
            }
            gun.reloadAmmo(gunStack);
            this.ammoWarningCooldown.remove(entity);
            this.announceReloadComplete(entity);
            return;
        }

        // Nothing in the ally's inventory covers any variant this gun
        // accepts -- warn using whichever variant's requirement we found
        // first as the representative example (falls back to the gun's own
        // currently-recorded variant if something odd made the loop above
        // find nothing at all, e.g. a gun with zero configured variants).
        ItemStack[] toWarnAbout = representativeRequirement != null ? representativeRequirement : gun.getReloadItem(gunStack);
        if (toWarnAbout != null && toWarnAbout.length > 0) {
            this.warnMissingAmmo(entity, toWarnAbout);
        }
    }

    private InventoryBasic getAllyInventory(EntityFriendlyCreature entity) {
        return entity.basicInventory;
    }

    /**
     * Ammo now lives in {@link AllyAmmoStorage}: Engender overwrites
     * basicInventory slots 0-6 with the mob's equipment/book every tick, so
     * it could never hold ammo (64 magazines handed over -> gone next tick).
     * Anything left in the one free slot (7) from before is moved over.
     */
    private boolean hasAllAmmo(EntityFriendlyCreature entity, ItemStack[] required) {
        this.migrateLegacyInventoryAmmo(entity);
        for (ItemStack need : required) {
            if (need == null || need.isEmpty()) {
                continue;
            }
            if (AllyAmmoStorage.count(entity, need) < need.getCount()) {
                return false;
            }
        }
        return true;
    }

    private void migrateLegacyInventoryAmmo(EntityFriendlyCreature entity) {
        InventoryBasic inv = this.getAllyInventory(entity);
        if (inv == null || inv.getSizeInventory() < 8) {
            return;
        }
        ItemStack slot7 = inv.getStackInSlot(7);
        if (!slot7.isEmpty()) {
            int moved = AllyAmmoStorage.add(entity, slot7);
            slot7.shrink(moved);
            inv.setInventorySlotContents(7, slot7.isEmpty() ? ItemStack.EMPTY : slot7);
        }
    }

    private void consumeAmmo(EntityFriendlyCreature entity, ItemStack[] required) {
        for (ItemStack need : required) {
            if (need == null || need.isEmpty()) {
                continue;
            }
            AllyAmmoStorage.consume(entity, need, need.getCount());
        }
    }

    private void warnMissingAmmo(EntityFriendlyCreature entity, ItemStack[] required) {
        Integer cooldown = this.ammoWarningCooldown.get(entity);
        if (cooldown != null && cooldown > 0) {
            this.ammoWarningCooldown.put(entity, cooldown - 1);
            return;
        }
        this.ammoWarningCooldown.put(entity, AMMO_WARNING_INTERVAL_TICKS);
        ItemStack primaryNeed = required[0];
        String ammoName = primaryNeed.isEmpty() ? "弾薬" : primaryNeed.getDisplayName();
        String message = "[Techguns] " + ammoName + " が必要です！";
        this.showStatusNotice(entity, message);
        this.broadcastNearby(entity, message);
    }

    private void announceReloadComplete(EntityFriendlyCreature entity) {
        World world = entity.getEntityWorld();
        world.playSound(null, entity.posX, entity.posY, entity.posZ,
                SoundEvents.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.NEUTRAL, 1.0F, 1.0F);
        String message = "[Techguns] 装填完了！射撃可能です！";
        this.showStatusNotice(entity, message);
        this.broadcastNearby(entity, message);
    }

    private void broadcastNearby(EntityFriendlyCreature entity, String message) {
        World world = entity.getEntityWorld();
        AxisAlignedBB area = entity.getEntityBoundingBox().grow(NOTICE_BROADCAST_RANGE, NOTICE_BROADCAST_RANGE, NOTICE_BROADCAST_RANGE);
        List<EntityPlayer> nearby = world.getEntitiesWithinAABB(EntityPlayer.class, area);
        for (EntityPlayer player : nearby) {
            player.sendMessage(new TextComponentString(message));
        }
    }

    /** [わかりやすい状態通知] temporarily shows `message` as the ally's floating name tag. */
    private void showStatusNotice(EntityFriendlyCreature entity, String message) {
        if (!this.noticeExpiryTick.containsKey(entity)) {
            // First notice since the ally's own name was last showing --
            // remember what to restore once the notice expires.
            this.savedNameTag.put(entity, entity.hasCustomName() ? entity.getCustomNameTag() : null);
            this.savedAlwaysRenderName.put(entity, entity.getAlwaysRenderNameTag());
        }
        entity.setCustomNameTag(message);
        entity.setAlwaysRenderNameTag(true);
        this.noticeExpiryTick.put(entity, this.tickCounter + NAME_NOTICE_DURATION_TICKS);
    }

    private void tickStatusNoticeExpiry(EntityFriendlyCreature entity) {
        Integer expiry = this.noticeExpiryTick.get(entity);
        if (expiry == null || this.tickCounter < expiry) {
            return;
        }
        String original = this.savedNameTag.get(entity);
        if (original != null) {
            entity.setCustomNameTag(original);
        } else {
            entity.setCustomNameTag("");
        }
        Boolean alwaysRender = this.savedAlwaysRenderName.get(entity);
        entity.setAlwaysRenderNameTag(alwaysRender != null && alwaysRender);
        this.noticeExpiryTick.remove(entity);
        this.savedNameTag.remove(entity);
        this.savedAlwaysRenderName.remove(entity);
    }

    private void applyWeaponAI(EntityFriendlyCreature entity, ItemStack heldItem) {
        removeManagedCombatAI(entity);

        if (heldItem.isEmpty()) {
            return;
        }

        Item item = heldItem.getItem();
        EntityAIBase newTask;

        if (item instanceof GenericGun && !(item instanceof IGenericGunMelee)) {
            newTask = new EntityAIAttackRangedGunAlly(entity, GUN_MOVE_SPEED, GUN_SEMI_AUTO_INTERVAL, GUN_MAX_ATTACK_DIST);
        } else if (item instanceof IGenericGunMelee || item instanceof ItemSword) {
            newTask = new EntityAIFriendlyAttackMelee(entity, MELEE_MOVE_SPEED, MELEE_LONG_MEMORY);
        } else {
            return;
        }

        entity.tasks.addTask(COMBAT_AI_PRIORITY, newTask);
        this.managedTask.put(entity, newTask);
    }

    private void removeManagedCombatAI(EntityFriendlyCreature entity) {
        EntityAIBase existing = this.managedTask.remove(entity);
        if (existing != null) {
            entity.tasks.removeTask(existing);
        }
    }
}