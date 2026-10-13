package com.example.examplemod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;

import mcinterface1122.WrapperEntity;
import mcinterface1122.WrapperWorld;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;

/**
 * Engender Mod 系の味方モブに、近くの Immersive Vehicles 車両の「運転席
 * （{@code isController} フラグの立った {@link PartSeat}）」を最優先で見つけ出し、
 * 近づいて乗り込ませるための {@link EntityAIBase} 実装。
 *
 * <p>{@link EntityAIDriveImmersiveVehicle} とセットで、
 * {@link EngenderImmersiveVehicleBridge} 経由で全ての {@code EntityFriendlyCreature} に
 * 高優先度（addTask の priority を 0 など、他の全AIより小さい値）で登録することを想定している。
 * 優先度を最も高くすることで、「車があれば何よりもまず乗り込む」という動きになる。
 *
 * <h2>乗車の実装方法</h2>
 * IV 24.0.0 系では、プレイヤーが座席を右クリックした際に内部で行われているのは
 * 単純に {@code seat.setRider(wrapperEntity, true)} の呼び出しであり（IV の
 * {@code PartSeat#interact} を逆コンパイルして確認済み）、この呼び出しの中で
 * 自動的に {@code IWrapperEntity#setRiding(...)} を介した vanilla 側の
 * {@code startRiding} まで面倒を見てくれる。そのため本AIは、モブ用の
 * {@link IWrapperEntity} ラッパー（{@link WrapperEntity#getWrapperFor(net.minecraft.entity.Entity)}）
 * を取得し、対象座席の {@code setRider} を直接呼び出すだけでよい。
 */
public class EntityAIMountImmersiveVehicle extends EntityAIBase {

    /** 車両を捜索する半径（ブロック）。 */
    private static final double SEARCH_RADIUS = 24.0D;
    /** この距離まで近づいたら実際に着座させる（ブロック）。 */
    private static final double MOUNT_RANGE = 2.2D;
    /** 車両へ向かう際の移動速度係数（EntityMoveHelper 準拠）。 */
    private static final double MOVE_SPEED = 1.0D;
    /** 乗車先が見つからなかった場合、次に探索を再試行するまでの待機Tick数。 */
    private static final int MOUNT_RETRY_INTERVAL = 20;
    /** これ以上かかっても座席に辿り着けなければ諦める（Tick）。 */
    private static final int APPROACH_TIMEOUT_TICKS = 200;
    /** 辿り着けなかった座席を候補から外しておく時間（Tick）。 */
    private static final long UNREACHABLE_SEAT_TICKS = 600L;
    private static final Map<PartSeat, Long> unreachableSeatUntil = new java.util.WeakHashMap<PartSeat, Long>();

    private int approachTicks;

    /**
     * 座席の「予約」テーブル。同一Tick内で複数のモブが同じ空席へ殺到し、
     * 到着後に取り合いになるのを防ぐための簡易排他制御。
     * サーバーは基本的に単一スレッドでAIを更新するため、通常の {@link HashMap} で十分。
     */
    private static final Map<PartSeat, EntityFriendlyCreature> claimedSeats = new HashMap<>();

    private final EntityFriendlyCreature mob;

    private PartSeat targetSeat;
    private EntityVehicleF_Physics targetVehicle;
    private int cooldownTicks;

    public EntityAIMountImmersiveVehicle(EntityFriendlyCreature mob) {
        this.mob = mob;
        // 移動(1)・見た目/視線(2) を占有。EntityAIDriveImmersiveVehicle と同じビット。
        this.setMutexBits(3);
    }

    // ------------------------------------------------------------------
    // EntityAIBase
    // ------------------------------------------------------------------

    @Override
    public boolean shouldExecute() {
        if (mob == null || !mob.isEntityAlive() || mob.getRidingEntity() != null) {
            return false;
        }
        if (mob.world == null || mob.world.isRemote) {
            return false;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        // Parked at its waypoint: stay out of the car until a new waypoint is
        // given -- unless an enemy shows up (then board and charge).
        if (EntityAIDriveImmersiveVehicle.isParked(mob) && !hostileNearby()) {
            cooldownTicks = MOUNT_RETRY_INTERVAL;
            return false;
        }

        PartSeat seat = findNearestFreeControllerSeat();
        if (seat == null) {
            return false;
        }
        this.targetSeat = seat;
        this.targetVehicle = seat.vehicleOn;
        return targetVehicle != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return mob.isEntityAlive()
                && mob.getRidingEntity() == null
                && targetSeat != null
                && targetSeat.rider == null
                && targetVehicle != null
                && targetVehicle.isValid
                && approachTicks < APPROACH_TIMEOUT_TICKS;
    }

    @Override
    public void startExecuting() {
        approachTicks = 0;
        if (targetSeat != null) {
            claimedSeats.put(targetSeat, mob);
        }
    }

    private boolean hostileNearby() {
        return !mob.world.getEntitiesWithinAABB(net.minecraft.entity.monster.EntityMob.class,
                mob.getEntityBoundingBox().grow(SEARCH_RADIUS, 6.0D, SEARCH_RADIUS),
                e -> e != null && e.isEntityAlive()).isEmpty();
    }

    @Override
    public void resetTask() {
        if (targetSeat != null && claimedSeats.get(targetSeat) == mob) {
            claimedSeats.remove(targetSeat);
        }
        if (approachTicks >= APPROACH_TIMEOUT_TICKS && targetSeat != null) {
            // Couldn't reach it: leave this seat alone for a while instead of
            // re-pathing to it forever (as a priority-0 task this blocked all
            // other AI of the ally).
            unreachableSeatUntil.put(targetSeat, mob.world.getTotalWorldTime() + UNREACHABLE_SEAT_TICKS);
        }
        targetSeat = null;
        targetVehicle = null;
        if (mob.getNavigator() != null) {
            mob.getNavigator().clearPath();
        }
        cooldownTicks = MOUNT_RETRY_INTERVAL;
    }

    @Override
    public void updateTask() {
        if (targetVehicle == null || targetSeat == null || !targetVehicle.isValid || targetSeat.rider != null) {
            return;
        }

        approachTicks++;
        // Walk to the SEAT, not the vehicle's centre (on a bus or truck the
        // driver's seat can be several blocks from the middle), and compare
        // horizontally so a tall vehicle doesn't make the 3D distance too big.
        double tx = targetSeat.position.x;
        double ty = targetSeat.position.y;
        double tz = targetSeat.position.z;

        if (mob.getLookHelper() != null) {
            mob.getLookHelper().setLookPosition(tx, ty, tz, 30.0F, 30.0F);
        }

        double hdx = tx - mob.posX;
        double hdz = tz - mob.posZ;
        double distSq = hdx * hdx + hdz * hdz;
        if (distSq > MOUNT_RANGE * MOUNT_RANGE || Math.abs(ty - mob.posY) > 3.0D) {
            if (mob.getNavigator() != null && mob.getNavigator().noPath()) {
                mob.getNavigator().tryMoveToXYZ(tx, ty, tz, MOVE_SPEED);
            }
            return;
        }

        // 十分近づいたので実際に着座させる。
        if (mob.getNavigator() != null) {
            mob.getNavigator().clearPath();
        }
        try {
            IWrapperEntity wrapper = WrapperEntity.getWrapperFor(mob);
            if (wrapper != null) {
                targetSeat.setRider(wrapper, true);
            }
        } catch (Throwable ignored) {
            // 座席が乗車直前に消失/破壊された等の一時的な不整合。次Tickで再評価される。
        }
    }

    // ------------------------------------------------------------------
    // 車両/座席の捜索
    // ------------------------------------------------------------------

    private PartSeat findNearestFreeControllerSeat() {
        AWrapperWorld ivWorld;
        try {
            ivWorld = WrapperWorld.getWrapperFor(mob.world);
        } catch (Throwable t) {
            return null;
        }
        if (ivWorld == null) {
            return null;
        }

        List<EntityVehicleF_Physics> vehicles;
        try {
            vehicles = ivWorld.getEntitiesExtendingType(EntityVehicleF_Physics.class);
        } catch (Throwable t) {
            return null;
        }
        if (vehicles == null || vehicles.isEmpty()) {
            return null;
        }

        cleanupStaleClaims();

        PartSeat best = null;
        double bestDistSq = SEARCH_RADIUS * SEARCH_RADIUS;
        for (EntityVehicleF_Physics vehicle : vehicles) {
            if (vehicle == null || !vehicle.isValid) {
                continue;
            }
            double dx = vehicle.position.x - mob.posX;
            double dy = vehicle.position.y - mob.posY;
            double dz = vehicle.position.z - mob.posZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > bestDistSq) {
                continue;
            }

            PartSeat seat = findFreeControllerSeat(vehicle);
            if (seat == null) {
                continue;
            }
            Long blockedUntil = unreachableSeatUntil.get(seat);
            if (blockedUntil != null && mob.world.getTotalWorldTime() < blockedUntil) {
                continue;
            }

            EntityFriendlyCreature claimant = claimedSeats.get(seat);
            if (claimant != null && claimant != mob && claimant.isEntityAlive()) {
                continue;
            }

            best = seat;
            bestDistSq = distSq;
        }
        return best;
    }

    private static PartSeat findFreeControllerSeat(EntityVehicleF_Physics vehicle) {
        for (APart part : vehicle.allParts) {
            if (part instanceof PartSeat) {
                PartSeat seat = (PartSeat) part;
                if (seat.rider == null
                        && seat.placementDefinition != null
                        && seat.placementDefinition.isController) {
                    return seat;
                }
            }
        }
        return null;
    }

    private static void cleanupStaleClaims() {
        Iterator<Map.Entry<PartSeat, EntityFriendlyCreature>> it = claimedSeats.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PartSeat, EntityFriendlyCreature> entry = it.next();
            PartSeat seat = entry.getKey();
            EntityFriendlyCreature claimant = entry.getValue();
            // Also drop claims from allies whose chunk unloaded (not "dead",
            // but gone) and from seats whose vehicle no longer exists --
            // those used to block the seat for everyone else indefinitely.
            if (seat.rider != null || !claimant.isEntityAlive()
                    || claimant.world == null || !claimant.world.loadedEntityList.contains(claimant)
                    || seat.vehicleOn == null || !seat.vehicleOn.isValid) {
                it.remove();
            }
        }
    }
}
