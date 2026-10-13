package com.example.examplemod;

import java.util.List;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The ally's "never lose" survival brain. Registered by
 * {@link EngenderGatheringBridge} at the highest priority (1) on every
 * {@link EntityFriendlyCreature} -- independent of whatever tool it's
 * holding -- so it can seize control (move + look mutex) away from combat
 * or gathering the instant something dangerous happens, per the vanilla
 * {@code EntityAITasks} rule that a lower-priority-number task always wins
 * a mutex conflict against a higher-priority-number one.
 *
 * <p>Covers, in order of urgency:</p>
 * <ul>
 *   <li>On fire or standing in lava: drop everything and get to water (or
 *       place some) immediately.</li>
 *   <li>Health at or below 30% max: stop attacking, retreat to a safe spot
 *       (behind the owner if known), and self-heal from held potions/food.</li>
 *   <li>An ignited creeper or a fresh melee hit nearby: an immediate
 *       "hit & away" backstep, handing combat back to the ally's own attack
 *       AI once at a safe distance.</li>
 *   <li>The owner player is critically low on health or being ganged up on
 *       nearby: rush to their side and mark the threat as this ally's own
 *       attack target so its existing combat AI (lower priority, takes over
 *       once this task steps aside) engages it.</li>
 * </ul>
 *
 * <p>Two things deliberately live in {@link EngenderGatheringBridge}
 * instead of here, because they must keep working every tick regardless of
 * which AI currently owns the move/look mutex: recent-hit tracking (so a
 * melee hit is noticed the instant it happens, not just while this task
 * happens to be running) and shield auto-blocking (raising/lowering a held
 * shield needs no movement, so it shouldn't have to fight for the mutex at
 * all).</p>
 */
public class EntityAISurviveAndCombatAlly extends EntityAIBase {

    private static final float LOW_HEALTH_FRACTION = 0.3f;
    private static final float RECOVERED_HEALTH_FRACTION = 0.6f;
    private static final float OWNER_PINCH_HEALTH_FRACTION = 0.25f;

    private static final double THREAT_SCAN_RADIUS = 12.0;
    private static final double IGNITED_CREEPER_EVADE_RADIUS = 6.0;
    private static final double MELEE_THREAT_RADIUS = 4.0;
    private static final double BACKSTEP_DISTANCE = 6.0;
    private static final double RETREAT_DISTANCE = 10.0;
    private static final double RETREAT_BEHIND_PLAYER_DIST = 2.5;
    private static final double ARRIVE_DIST_SQ = 1.5 * 1.5;
    private static final double GUARD_STAND_DIST_SQ = 2.5 * 2.5;
    /** Beyond this distance from a threatened owner the ally rushes over; within it, combat AIs take over. */
    private static final double GUARD_ENGAGE_DIST = 6.0;

    private static final int WATER_SEARCH_RADIUS = 6;
    private static final int HEAL_RETRY_COOLDOWN_TICKS = 20;
    private static final int GRACE_TICKS_BEFORE_STAND_DOWN = 10;

    private static final double EVADE_MOVE_SPEED = 1.35;
    private static final double GUARD_MOVE_SPEED = 1.2;

    private enum Mode { EXTINGUISH, EMERGENCY_RETREAT, BACKSTEP, GUARD_PLAYER }

    private final EntityFriendlyCreature entity;
    private final EngenderGatheringBridge bridge;

    private Mode mode;
    private EntityLivingBase threat;
    private int healCooldown;
    private int standDownTicks;
    private BlockPos fixedRetreatPos;

    public EntityAISurviveAndCombatAlly(EntityFriendlyCreature entity, EngenderGatheringBridge bridge) {
        this.entity = entity;
        this.bridge = bridge;
        // Same move+look mutex as every other task in this mod -- the
        // priority number (set on addTask, not here) is what lets this one
        // win the fight for control whenever it actually needs to.
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        this.mode = this.detectMode();
        return this.mode != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (!this.entity.isEntityAlive()) {
            return false;
        }
        Mode current = this.detectMode();
        if (current != null) {
            this.mode = current;
            this.standDownTicks = 0;
            return true;
        }
        // A short grace period avoids flickering in and out right at a
        // threshold (e.g. health hovering exactly on the 30% line).
        return ++this.standDownTicks < GRACE_TICKS_BEFORE_STAND_DOWN;
    }

    @Override
    public void startExecuting() {
        this.standDownTicks = 0;
        this.healCooldown = 0;
        if (this.mode == Mode.EMERGENCY_RETREAT || this.mode == Mode.EXTINGUISH) {
            // Survival first: whatever it was fighting, it stops right now.
            this.entity.setAttackTarget(null);
        }
    }

    @Override
    public void resetTask() {
        this.entity.getNavigator().clearPath();
        this.threat = null;
        this.fixedRetreatPos = null;
        this.mode = null;
    }

    @Override
    public void updateTask() {
        World world = this.entity.getEntityWorld();
        switch (this.mode) {
            case EXTINGUISH:
                this.updateExtinguish(world);
                break;
            case EMERGENCY_RETREAT:
                this.updateEmergencyRetreat(world);
                break;
            case BACKSTEP:
                this.updateBackstep();
                break;
            case GUARD_PLAYER:
                this.updateGuardPlayer(world);
                break;
        }
    }

    // ------------------------------------------------------------------
    // Requirement 1/2/3/4: decide whether -- and how urgently -- to react
    // ------------------------------------------------------------------

    private Mode detectMode() {
        World world = this.entity.getEntityWorld();

        // [運転・車載銃が動かない不具合の修正] While riding (Immersive Vehicles
        // seat, Flan's vehicle) walking-based survival moves are meaningless,
        // and at the same priority/mutex this task used to block the drive AI.
        if (this.entity.isRiding()) {
            return null;
        }

        // Requirement 9: standing in lava always wins. Being on fire only
        // takes over when there is actually something to do about it --
        // [一日中消火モードの修正] undead allies burn in daylight with no water
        // around, and this used to hijack control for the whole day.
        if (this.isStandingInLava(world)) {
            return Mode.EXTINGUISH;
        }
        if (this.entity.isBurning()
                && (this.holdsWaterBucket() || this.findNearbyWater(world) != null)) {
            return Mode.EXTINGUISH;
        }

        float health = this.entity.getHealth();
        float maxHealth = Math.max(1.0f, this.entity.getMaxHealth());
        boolean low = health <= maxHealth * LOW_HEALTH_FRACTION;
        boolean stillRecovering = this.mode == Mode.EMERGENCY_RETREAT && health < maxHealth * RECOVERED_HEALTH_FRACTION;
        if (low || stillRecovering) {
            // [緊急退避が永久に終わらない不具合の修正] The base mob has no
            // natural regeneration, so "retreat until 60% HP" never ended
            // unless the ally held a potion/food. Only retreat while there is
            // a threat to get away from or something to heal with.
            EntityLivingBase nearThreat = this.findNearestThreat(world, this.entity);
            if (nearThreat != null || this.hasHealingItem()) {
                this.threat = nearThreat;
                return Mode.EMERGENCY_RETREAT;
            }
        }

        // Requirement 3: an armed creeper is an instant "get away" regardless of HP.
        EntityCreeper ignited = this.findIgnitedCreeperNearby(world);
        if (ignited != null) {
            this.threat = ignited;
            return Mode.BACKSTEP;
        }

        // Requirement 1: hit & away -- just took a melee hit from something close.
        if (this.bridge.hasRecentlyBeenHit(this.entity)) {
            EntityLivingBase attacker = this.findNearestThreat(world, this.entity);
            if (attacker != null
                    && squaredDistanceEntities(this.entity, attacker) <= MELEE_THREAT_RADIUS * MELEE_THREAT_RADIUS) {
                this.threat = attacker;
                return Mode.BACKSTEP;
            }
        }

        // Requirement 11: the player we're working for is in real trouble.
        // [戦闘に参加しない不具合の修正] This used to trigger whenever ANY
        // mob was within 12 blocks of the owner and never step aside, so at
        // priority 1 it blocked the gun/melee AIs (priority 2) for the whole
        // fight: allies just stood next to the player without shooting. Now it
        // only runs to close the distance; once near, it marks the threat and
        // hands over to the combat AIs.
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        if (owner != null && this.isOwnerInDanger(world, owner)
                && this.entity.getDistanceSq(owner) > GUARD_ENGAGE_DIST * GUARD_ENGAGE_DIST) {
            return Mode.GUARD_PLAYER;
        }
        return null;
    }

    private boolean holdsWaterBucket() {
        return this.entity.getHeldItemMainhand().getItem() == Items.WATER_BUCKET
                || this.entity.getHeldItemOffhand().getItem() == Items.WATER_BUCKET;
    }

    private boolean hasHealingItem() {
        return isHealingItem(this.entity.getHeldItemMainhand()) || isHealingItem(this.entity.getHeldItemOffhand());
    }

    private static boolean isHealingItem(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof ItemPotion || stack.getItem() instanceof ItemFood);
    }

    // ------------------------------------------------------------------
    // Requirement 9/10: fire and lava
    // ------------------------------------------------------------------

    private void updateExtinguish(World world) {
        if (this.isStandingInLava(world)) {
            BlockPos escape = this.findLavaEscapePos(world);
            if (escape != null) {
                if (this.entity.getNavigator().noPath()) {
                    this.entity.getNavigator().tryMoveToXYZ(escape.getX() + 0.5, escape.getY(), escape.getZ() + 0.5, EVADE_MOVE_SPEED);
                }
                return;
            }
        }

        BlockPos water = this.findNearbyWater(world);
        if (water != null) {
            double distSq = this.entity.getDistanceSq(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
            if (distSq <= 2.0 * 2.0) {
                // Force it out immediately instead of waiting for vanilla's
                // own per-tick fire check to notice we're near/in water.
                this.entity.extinguish();
                return;
            }
            if (this.entity.getNavigator().noPath()) {
                this.entity.getNavigator().tryMoveToXYZ(water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, EVADE_MOVE_SPEED);
            }
            return;
        }

        // No water reachable nearby: place some from a held bucket as a last resort.
        if (this.placeWaterFromBucketIfHeld(world)) {
            this.entity.extinguish();
        }
    }

    private boolean isStandingInLava(World world) {
        return world.getBlockState(new BlockPos(this.entity)).getMaterial() == Material.LAVA;
    }

    /** Requirement 10: get out of the lava pool itself before anything else. */
    private BlockPos findLavaEscapePos(World world) {
        BlockPos base = new BlockPos(this.entity);
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = base.add(dx, 0, dz);
                    if (world.getBlockState(candidate).getMaterial() == Material.LAVA) {
                        continue;
                    }
                    if (world.isAirBlock(candidate)
                            && !world.isAirBlock(candidate.down())
                            && world.getBlockState(candidate.down()).getMaterial() != Material.LAVA) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findNearbyWater(World world) {
        BlockPos base = new BlockPos(this.entity);
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -WATER_SEARCH_RADIUS; dx <= WATER_SEARCH_RADIUS; dx++) {
            for (int dz = -WATER_SEARCH_RADIUS; dz <= WATER_SEARCH_RADIUS; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    if (world.getBlockState(pos).getMaterial() == Material.WATER) {
                        double distSq = squaredDistance(pos, base);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean placeWaterFromBucketIfHeld(World world) {
        if (this.tryPourBucket(world, this.entity.getHeldItemOffhand())) {
            return true;
        }
        return this.tryPourBucket(world, this.entity.getHeldItemMainhand());
    }

    private boolean tryPourBucket(World world, ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.WATER_BUCKET) {
            return false;
        }
        BlockPos feet = new BlockPos(this.entity);
        world.setBlockState(feet, Blocks.WATER.getStateFromMeta(0), 3);
        stack.shrink(1);
        return true;
    }

    // ------------------------------------------------------------------
    // Requirement 4/5/6: emergency retreat + self-heal
    // ------------------------------------------------------------------

    private void updateEmergencyRetreat(World world) {
        // [退避先が逃げ水になる不具合の修正] Without an owner, the retreat point
        // was recomputed every tick as "10 blocks from where I stand now", so
        // the ally never arrived and never got to heal. With an owner the
        // point follows the owner (a real, reachable place); without one it's
        // fixed once when the retreat starts.
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        if (owner != null || this.fixedRetreatPos == null) {
            this.fixedRetreatPos = this.computeRetreatPosition();
        }
        BlockPos retreatPos = this.fixedRetreatPos;
        double distSq = this.entity.getDistanceSq(retreatPos.getX() + 0.5, this.entity.posY, retreatPos.getZ() + 0.5);
        if (distSq > ARRIVE_DIST_SQ) {
            if (this.entity.getNavigator().noPath()) {
                this.entity.getNavigator().tryMoveToXYZ(retreatPos.getX() + 0.5, retreatPos.getY(), retreatPos.getZ() + 0.5, EVADE_MOVE_SPEED);
            }
            return;
        }

        // At a safe-ish spot already: try to patch up while we wait to fully recover.
        if (this.healCooldown > 0) {
            this.healCooldown--;
            return;
        }
        this.healCooldown = HEAL_RETRY_COOLDOWN_TICKS;
        this.tryUseHealingItemOrFood();
    }

    /** Requirement 4: behind the owner if we know one, otherwise just straight away from the threat. */
    private BlockPos computeRetreatPosition() {
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        double threatX = this.threat != null ? this.threat.posX : this.entity.posX - 1.0;
        double threatZ = this.threat != null ? this.threat.posZ : this.entity.posZ;

        if (owner != null && owner.isEntityAlive()) {
            double dx = owner.posX - threatX;
            double dz = owner.posZ - threatZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-3) {
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            double targetX = owner.posX + (dx / len) * RETREAT_BEHIND_PLAYER_DIST;
            double targetZ = owner.posZ + (dz / len) * RETREAT_BEHIND_PLAYER_DIST;
            return new BlockPos(targetX, owner.posY, targetZ);
        }

        double dx = this.entity.posX - threatX;
        double dz = this.entity.posZ - threatZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3) {
            dx = 1.0;
            dz = 0.0;
            len = 1.0;
        }
        double targetX = this.entity.posX + (dx / len) * RETREAT_DISTANCE;
        double targetZ = this.entity.posZ + (dz / len) * RETREAT_DISTANCE;
        return new BlockPos(targetX, this.entity.posY, targetZ);
    }

    /**
     * Requirement 6: use a carried healing potion or food once it's safe to
     * do so. Since {@link EntityFriendlyCreature} has no player-style hunger
     * system, "eating" is simulated as a direct, immediate heal rather than
     * queued hunger/saturation -- tune the amounts below (or wire this into
     * the ally's real inventory system, if it has one beyond held items) to
     * match how the base mod actually wants this to feel.
     */
    private void tryUseHealingItemOrFood() {
        if (this.tryConsumeFromHand(this.entity.getHeldItemOffhand())) {
            return;
        }
        this.tryConsumeFromHand(this.entity.getHeldItemMainhand());
    }

    private boolean tryConsumeFromHand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof ItemPotion) {
            List<PotionEffect> effects = PotionUtils.getEffectsFromStack(stack);
            if (effects != null) {
                for (PotionEffect effect : effects) {
                    this.entity.addPotionEffect(new PotionEffect(effect.getPotion(), effect.getDuration(), effect.getAmplifier()));
                }
            }
            // Treat any potion the ally is carrying here as a healing draught:
            // top it straight back up to full rather than trying to distinguish
            // instant-health from regeneration-over-time in this simplified model.
            this.entity.heal(this.entity.getMaxHealth());
            stack.shrink(1);
            return true;
        }
        if (item instanceof ItemFood) {
            this.entity.heal(4.0f);
            stack.shrink(1);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Requirement 1/3: hit & away
    // ------------------------------------------------------------------

    private void updateBackstep() {
        if (this.threat == null) {
            return;
        }
        double distSq = squaredDistanceEntities(this.entity, this.threat);
        if (distSq >= BACKSTEP_DISTANCE * BACKSTEP_DISTANCE) {
            // Far enough already -- shouldContinueExecuting() will hand
            // control back to the ally's own attack AI shortly.
            return;
        }
        double dx = this.entity.posX - this.threat.posX;
        double dz = this.entity.posZ - this.threat.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3) {
            dx = 1.0;
            dz = 0.0;
            len = 1.0;
        }
        double targetX = this.entity.posX + (dx / len) * BACKSTEP_DISTANCE;
        double targetZ = this.entity.posZ + (dz / len) * BACKSTEP_DISTANCE;
        if (this.entity.getNavigator().noPath()) {
            this.entity.getNavigator().tryMoveToXYZ(targetX, this.entity.posY, targetZ, EVADE_MOVE_SPEED);
        }
    }

    // ------------------------------------------------------------------
    // Requirement 11: guard the owner when they're in trouble
    // ------------------------------------------------------------------

    private void updateGuardPlayer(World world) {
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        if (owner == null || !owner.isEntityAlive()) {
            return;
        }
        EntityLivingBase hostile = this.findNearestThreat(world, owner);
        double standX = owner.posX;
        double standZ = owner.posZ;
        if (hostile != null) {
            double dx = hostile.posX - owner.posX;
            double dz = hostile.posZ - owner.posZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0e-3) {
                standX = owner.posX + (dx / len) * 1.5;
                standZ = owner.posZ + (dz / len) * 1.5;
            }
            // Hand the actual fighting off to whatever combat AI this ally
            // already has (lower priority number wins, so ours steps aside
            // once we're close enough that shouldContinueExecuting() ends).
            this.entity.setAttackTarget(hostile);
        }
        double distSq = this.entity.getDistanceSq(standX, owner.posY, standZ);
        if (distSq > GUARD_STAND_DIST_SQ) {
            if (this.entity.getNavigator().noPath()) {
                this.entity.getNavigator().tryMoveToXYZ(standX, owner.posY, standZ, GUARD_MOVE_SPEED);
            }
        } else {
            this.entity.getNavigator().clearPath();
        }
    }

    private boolean isOwnerInDanger(World world, EntityPlayer owner) {
        if (!owner.isEntityAlive()) {
            return false;
        }
        float ownerMax = Math.max(1.0f, owner.getMaxHealth());
        if (owner.getHealth() <= ownerMax * OWNER_PINCH_HEALTH_FRACTION) {
            return true;
        }
        // Only a mob that is actually going after the owner counts -- not
        // every mob that merely happens to be within 12 blocks.
        EntityLivingBase revenge = owner.getRevengeTarget();
        if (isValidHostile(revenge) && revenge.getDistanceSq(owner) <= THREAT_SCAN_RADIUS * THREAT_SCAN_RADIUS) {
            return true;
        }
        AxisAlignedBB area = owner.getEntityBoundingBox().grow(THREAT_SCAN_RADIUS);
        for (EntityMob mob : world.getEntitiesWithinAABB(EntityMob.class, area)) {
            if (mob.isEntityAlive() && mob.getAttackTarget() == owner) {
                return true;
            }
        }
        return false;
    }

    /** Never treat the player, another player, or a fellow Engender creature as a threat. */
    private static boolean isValidHostile(EntityLivingBase candidate) {
        return candidate != null
                && candidate.isEntityAlive()
                && !(candidate instanceof EntityPlayer)
                && !(candidate instanceof EntityFriendlyCreature);
    }

    // ------------------------------------------------------------------
    // Shared threat scanning
    // ------------------------------------------------------------------

    private EntityLivingBase findNearestThreat(World world, Entity around) {
        if (around instanceof EntityLivingBase) {
            EntityLivingBase self = (EntityLivingBase) around;
            EntityLivingBase revenge = self.getRevengeTarget();
            // A revenge target can be the player (friendly fire) or another
            // ally (stray shot); setting those as attack targets made allies
            // turn on the player / each other.
            if (isValidHostile(revenge)) {
                return revenge;
            }
        }
        AxisAlignedBB area = new AxisAlignedBB(new BlockPos(around)).grow(THREAT_SCAN_RADIUS, THREAT_SCAN_RADIUS, THREAT_SCAN_RADIUS);
        List<EntityMob> mobs = world.getEntitiesWithinAABB(EntityMob.class, area);
        if (mobs == null) {
            return null;
        }
        EntityMob closest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityMob mob : mobs) {
            if (!mob.isEntityAlive()) {
                continue;
            }
            double distSq = squaredDistanceEntities(around, mob);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                closest = mob;
            }
        }
        return closest;
    }

    private EntityCreeper findIgnitedCreeperNearby(World world) {
        AxisAlignedBB area = new AxisAlignedBB(new BlockPos(this.entity))
                .grow(IGNITED_CREEPER_EVADE_RADIUS, IGNITED_CREEPER_EVADE_RADIUS, IGNITED_CREEPER_EVADE_RADIUS);
        List<EntityCreeper> creepers = world.getEntitiesWithinAABB(EntityCreeper.class, area);
        if (creepers == null) {
            return null;
        }
        for (EntityCreeper creeper : creepers) {
            // MCP 1.12.2 has no isIgnited(): the fuse state is exposed as
            // getCreeperState(), where > 0 means the fuse is lit.
            if (creeper.isEntityAlive() && creeper.getCreeperState() > 0) {
                return creeper;
            }
        }
        return null;
    }

    private static double squaredDistanceEntities(Entity a, Entity b) {
        double dx = a.posX - b.posX;
        double dy = a.posY - b.posY;
        double dz = a.posZ - b.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return (double) (dx * dx + dy * dy + dz * dz);
    }
}
