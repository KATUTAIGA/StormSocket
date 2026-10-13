package com.example.examplemod;

import com.flansmod.common.guns.ItemGun;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.WeakHashMap;

public class EngenderFlanBridge {

    private final WeakHashMap<EntityFriendlyCreature, Item> lastSeenWeapon = new WeakHashMap<>();
    private final WeakHashMap<EntityFriendlyCreature, EntityAIBase> managedTask = new WeakHashMap<>();

    public static boolean isFlanGun(Item item) {
        return item instanceof ItemGun;
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityFriendlyCreature) {
            removeManagedCombatAI((EntityFriendlyCreature) event.getEntity());
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
            return;
        }

        if (isFlanGun(current)) {
            applyWeaponAI(entity);
        } else if (isFlanGun(previous)) {
            removeManagedCombatAI(entity);
        }
        // removeManagedCombatAI() also clears lastSeenWeapon; without this the
        // next tick looked like a weapon change and the gun AI was torn down
        // and rebuilt every other tick, so it never got to fire.
        lastSeenWeapon.put(entity, current);
    }

    /**
     * Priority 2, same as the Techguns/HBM gun AIs. It used to be 1 -- the same
     * as the survival AI -- and in vanilla EntityAITasks a running task can't
     * be interrupted by one of equal priority, so a Flan's gunner could never
     * retreat, put itself out or backstep mid-fight, even at 1 HP.
     */
    private static final int COMBAT_AI_PRIORITY = 2;

    /** Flan's 銃の交戦距離（ブロック）。以前は24。 */
    private static final float FLAN_ATTACK_RANGE = 96.0F;

    private void applyWeaponAI(EntityFriendlyCreature entity) {
        removeManagedCombatAI(entity);
        EntityAIAttackRangedFlanGun newTask = new EntityAIAttackRangedFlanGun(entity, 1.0D, 20, FLAN_ATTACK_RANGE);
        entity.tasks.addTask(COMBAT_AI_PRIORITY, newTask);
        managedTask.put(entity, newTask);
        // Shooting range alone isn't enough: the ally only picks targets
        // within its FOLLOW_RANGE attribute (used by the targeting AIs), which
        // is ~16-32 for most mobs. Raise it so far enemies are actually noticed.
        net.minecraft.entity.ai.attributes.IAttributeInstance follow =
                entity.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < FLAN_ATTACK_RANGE) {
            follow.setBaseValue(FLAN_ATTACK_RANGE);
        }
    }
}