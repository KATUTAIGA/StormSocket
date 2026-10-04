package com.example.examplemod;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Mirrors EngenderTechgunsBridge, but for HBM's NTM Reforged guns.
 *
 * HBM's guns are NOT built on one common "any mob can fire this" API like
 * Techguns' GenericGun is - most of them (GunBoltAction, GunB93, GunStinger...)
 * assume an EntityPlayer and read straight from the player's inventory.
 * So instead of calling the gun item's own fire logic, we detect that the
 * ally is HOLDING one of HBM's gun items and, if so, attach a generic
 * ranged-attack AI (EntityAIAttackRangedHBMGun) that fires HBM's bullet
 * entity on its own. The held item is only used as "what does the mob look
 * like it's using" - the actual shot is generic for now.
 */
public class EngenderHBMBridge {

    // Every simple class name of an HBM gun item, gathered by inspecting
    // HBM-NTM-Reforged-1_0_7.jar directly. Update this set if the HBM
    // version you use adds/renames guns.
    private static final Set<String> HBM_GUN_CLASS_NAMES = new HashSet<>();
    static {
        // com.hbm.items.weapon.* (extend Item directly)
        HBM_GUN_CLASS_NAMES.add("GunB93");
        HBM_GUN_CLASS_NAMES.add("GunBoltAction");
        HBM_GUN_CLASS_NAMES.add("GunBrimstone");
        HBM_GUN_CLASS_NAMES.add("GunCryolator");
        HBM_GUN_CLASS_NAMES.add("GunDampfmaschine");
        HBM_GUN_CLASS_NAMES.add("GunDefabricator");
        HBM_GUN_CLASS_NAMES.add("GunEMPRay");
        HBM_GUN_CLASS_NAMES.add("GunEuthanasia");
        HBM_GUN_CLASS_NAMES.add("GunHP");
        HBM_GUN_CLASS_NAMES.add("GunImmolator");
        HBM_GUN_CLASS_NAMES.add("GunJack");
        HBM_GUN_CLASS_NAMES.add("GunLeverActionS");
        HBM_GUN_CLASS_NAMES.add("GunSpark");
        HBM_GUN_CLASS_NAMES.add("GunStinger");
        HBM_GUN_CLASS_NAMES.add("GunSuicide");
        HBM_GUN_CLASS_NAMES.add("GunZOMG");
        // com.hbm.items.weapon.* (extend the shared ItemGunBase)
        HBM_GUN_CLASS_NAMES.add("ItemGunCCPlasmaCannon");
        HBM_GUN_CLASS_NAMES.add("ItemGunDart");
        HBM_GUN_CLASS_NAMES.add("ItemGunEgon");
        HBM_GUN_CLASS_NAMES.add("ItemGunGauss");
        HBM_GUN_CLASS_NAMES.add("ItemGunJShotty");
        HBM_GUN_CLASS_NAMES.add("ItemGunLacunae");
        HBM_GUN_CLASS_NAMES.add("ItemGunOSIPR");
        HBM_GUN_CLASS_NAMES.add("ItemGunShotty");
        HBM_GUN_CLASS_NAMES.add("ItemGunVortex");
        // com.hbm.items.special.weapon.*
        HBM_GUN_CLASS_NAMES.add("GunB92");
        // com.hbm.items.tool.*
        HBM_GUN_CLASS_NAMES.add("ItemBoltgun");
    }

    /**
     * HBM registers 46 guns as plain {@code new ItemGunBase(...)} and newer ones
     * as {@code ItemGunBaseNT}; neither class name was in the set, so those guns
     * never got the combat AI. Walk the class hierarchy and accept anything
     * that IS one of the listed classes or extends the two shared bases.
     */
    public static boolean isHbmGun(Item item) {
        if (item == null) {
            return false;
        }
        for (Class<?> c = item.getClass(); c != null && c != Item.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (!name.startsWith("com.hbm.")) {
                continue;
            }
            String simple = c.getSimpleName();
            if (HBM_GUN_CLASS_NAMES.contains(simple) || "ItemGunBase".equals(simple) || "ItemGunBaseNT".equals(simple)
                    || "ItemGunBaseSedna".equals(simple)) {
                return true;
            }
        }
        return false;
    }

    private final WeakHashMap<EntityFriendlyCreature, Item> lastSeenWeapon = new WeakHashMap<>();
    private final WeakHashMap<EntityFriendlyCreature, EntityAIBase> managedTask = new WeakHashMap<>();

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        World world = event.getWorld();
        if (world.isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityFriendlyCreature) {
            // reset bookkeeping in case the same entity object is reused (e.g. dimension change)
            EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntity();
            removeManagedCombatAI(entity);
        }
    }

    private void removeManagedCombatAI(EntityFriendlyCreature entity) {
        EntityAIBase existing = managedTask.remove(entity);
        if (existing != null) {
            entity.tasks.removeTask(existing);
        }
        lastSeenWeapon.remove(entity);
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!(event.getEntityLiving() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntityLiving();
        if (entity.world.isRemote) {
            return;
        }

        ItemStack mainhand = entity.getHeldItemMainhand();
        Item current = mainhand.isEmpty() ? null : mainhand.getItem();
        Item previous = lastSeenWeapon.put(entity, current);

        if (current == previous) {
            return; // held item unchanged since last tick
        }

        if (isHbmGun(current)) {
            applyWeaponAI(entity);
        } else if (isHbmGun(previous)) {
            removeManagedCombatAI(entity);
        }
        // removeManagedCombatAI() also clears lastSeenWeapon; without putting
        // it back, the very next tick saw a "new" weapon and tore down and
        // re-created the gun AI -- every other tick, so it never got to shoot.
        lastSeenWeapon.put(entity, current);
    }

    private void applyWeaponAI(EntityFriendlyCreature entity) {
        removeManagedCombatAI(entity);
        EntityAIAttackRangedHBMGun newTask = new EntityAIAttackRangedHBMGun(entity, 1.0D, 20, 40.0F);
        entity.tasks.addTask(2, newTask);
        managedTask.put(entity, newTask);
    }
}
