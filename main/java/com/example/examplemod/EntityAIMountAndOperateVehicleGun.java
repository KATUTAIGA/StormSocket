package com.example.examplemod;

import com.flansmod.common.driveables.EntityDriveable;
import com.flansmod.common.driveables.EntitySeat;
import com.flansmod.common.driveables.EntityVehicle;
import com.flansmod.common.RotatedAxes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.List;

/**
 * EntityFriendlyCreature 用の「戦車に乗り込んで銃座を操作する」AIタスク。
 */
public class EntityAIMountAndOperateVehicleGun extends EntityAIBase {

    /** 何ブロック以内の戦車を捜索対象にするか */
    private static final double SEARCH_RADIUS = 24.0D;
    /** 銃座への着席を試みる間隔 (tick) */
    private static final int MOUNT_RETRY_INTERVAL = 40;
    /** ターゲットを見失ってから降車するまでの猶予 (tick) */
    private static final int TARGET_LOST_GRACE_TICKS = 100;

    private final EntityFriendlyCreature entity;

    private EntitySeat seat;
    private EntityVehicle vehicle;
    private EntityLivingBase target;

    private int mountCooldown;
    private int noTargetTicks;

    public EntityAIMountAndOperateVehicleGun(EntityFriendlyCreature entityIn) {
        this.entity = entityIn;
        // 移動+見た目の両方をこのAIが握るため、他の移動系AIと衝突しないようビットを占有
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (!this.entity.isEntityAlive()) {
            return false;
        }

        // 既に(このAI経由で)座席に乗っている最中なら継続
        if (this.entity.getRidingEntity() instanceof EntitySeat) {
            return true;
        }

        if (this.entity.isRiding()) {
            // 何か別のものに乗っている最中は手を出さない
            return false;
        }

        if (this.mountCooldown > 0) {
            this.mountCooldown--;
            return false;
        }

        // Only board when there is actually something to shoot at -- it used
        // to board any vehicle within 24 blocks with no enemy around and then
        // get off again 100 ticks later, over and over.
        if (findNearestHostileAround(this.entity) == null) {
            this.mountCooldown = MOUNT_RETRY_INTERVAL;
            return false;
        }

        EntitySeat found = findFreeGunnerSeat();
        if (found != null) {
            this.seat = found;
            this.vehicle = (EntityVehicle) found.driveable;
            return true;
        }

        this.mountCooldown = MOUNT_RETRY_INTERVAL;
        return false;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (!this.entity.isEntityAlive()) {
            return false;
        }
        if (!(this.entity.getRidingEntity() instanceof EntitySeat)) {
            return false;
        }
        if (this.vehicle == null || this.vehicle.isDead) {
            return false;
        }
        // ターゲットを長時間見失ったら降りる(いつまでも戦車に居座らせない)
        return this.noTargetTicks < TARGET_LOST_GRACE_TICKS;
    }

    @Override
    public void startExecuting() {
        if (this.seat != null && !this.entity.isRiding()) {
            this.entity.startRiding(this.seat, true);
        }
        this.noTargetTicks = 0;
    }

    @Override
    public void resetTask() {
        // Only get off when the fight is really over (or the vehicle is gone).
        // resetTask() also runs when a higher-priority task briefly takes the
        // mutex, and dismounting then threw the ally out mid-fight.
        boolean fightOver = this.noTargetTicks >= TARGET_LOST_GRACE_TICKS
                || this.vehicle == null || this.vehicle.isDead;
        if (fightOver && this.entity.getRidingEntity() instanceof EntitySeat) {
            this.entity.dismountRidingEntity();
        }
        this.seat = null;
        this.vehicle = null;
        this.target = null;
        this.mountCooldown = MOUNT_RETRY_INTERVAL;
    }

    @Override
    public void updateTask() {
        if (this.vehicle == null || this.seat == null) {
            return;
        }

        // ターゲットの再評価(既存ターゲットが死亡/範囲外なら探し直す)
        if (this.target == null || !this.target.isEntityAlive()
                || this.target.getDistanceSq(this.entity) > SEARCH_RADIUS * SEARCH_RADIUS * 4) {
            this.target = findNearestHostile();
        }

        if (this.target == null) {
            this.noTargetTicks++;
            return;
        }
        this.noTargetTicks = 0;

        aimTurretAt(this.target);

        // EntityDriveable 自身が管理しているクールダウン (shootDelayPrimary) を見て発射。
        if (this.vehicle.shootDelayPrimary <= 0F && hasLineOfSight(this.target)) {
            this.vehicle.shoot(true);
        }
    }

    // ------------------------------------------------------------------
    // 内部処理
    // ------------------------------------------------------------------

    private EntitySeat findFreeGunnerSeat() {
        World world = this.entity.world;
        AxisAlignedBB box = this.entity.getEntityBoundingBox().grow(SEARCH_RADIUS);
        List<EntityVehicle> nearby = world.getEntitiesWithinAABB(EntityVehicle.class, box);

        EntityVehicle nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (EntityVehicle v : nearby) {
            double d = v.getDistanceSq(this.entity);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = v;
            }
        }
        if (nearest == null) {
            return null;
        }

        EntitySeat[] seats = nearest.getSeats();
        if (seats == null || seats.length == 0) {
            return null;
        }
        // [戦車が撃たない不具合の修正] Flan's EntityDriveable.shoot() does
        // nothing unless seats[0] (the driver seat) has a living rider
        // (verified in the Flan's bytecode). This used to skip the driver
        // seat on purpose, so an ally alone in a tank never fired. Take the
        // driver seat when it's free; a gunner seat is only useful when
        // someone is already driving.
        EntitySeat driverSeat = seats[0];
        if (driverSeat != null && driverSeat.getPassengers().isEmpty()) {
            return driverSeat;
        }
        boolean hasDriver = driverSeat != null && driverSeat.getControllingPassenger() instanceof EntityLivingBase;
        if (!hasDriver) {
            return null;
        }
        for (EntitySeat s : seats) {
            if (s == null || s == driverSeat) {
                continue;
            }
            if (s.getPassengers().isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * [プレイヤーを撃つ不具合の修正] The old filter accepted every living
     * thing except Engender creatures -- players (the owner included),
     * villagers and pets were all valid targets. Only real monsters now.
     */
    private static boolean isShootableHostile(EntityFriendlyCreature self, EntityLivingBase e) {
        return e != null && e != self && e.isEntityAlive()
                && e instanceof net.minecraft.entity.monster.IMob
                && !(e instanceof net.minecraft.entity.player.EntityPlayer)
                && !(e instanceof EntityFriendlyCreature)
                && !self.isOnSameTeam(e);
    }

    private static EntityLivingBase findNearestHostileAround(EntityFriendlyCreature self) {
        AxisAlignedBB box = self.getEntityBoundingBox().grow(SEARCH_RADIUS);
        EntityLivingBase nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase e : self.world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (!isShootableHostile(self, e) || !self.getEntitySenses().canSee(e)) {
                continue;
            }
            double d = e.getDistanceSq(self);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = e;
            }
        }
        return nearest;
    }

    private EntityLivingBase findNearestHostile() {
        World world = this.entity.world;
        AxisAlignedBB box = this.vehicle.getEntityBoundingBox().grow(SEARCH_RADIUS);

        List<EntityLivingBase> candidates = world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> !e.equals(this.vehicle) && isShootableHostile(this.entity, e)
                        && this.entity.getEntitySenses().canSee(e));

        EntityLivingBase nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase e : candidates) {
            double d = e.getDistanceSq(this.vehicle);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = e;
            }
        }
        return nearest;
    }

    private boolean hasLineOfSight(EntityLivingBase target) {
        return this.entity.getEntitySenses().canSee(target);
    }

    /**
     * 砲塔(座席)をターゲット方向へ向ける。
     */
    private void aimTurretAt(EntityLivingBase target) {
        Vec3d from = this.vehicle.getPositionVector();
        Vec3d to = target.getPositionVector().addVector(0, target.getEyeHeight(), 0);
        Vec3d dir = to.subtract(from);

        double horizDist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizDist));

        if (this.seat.playerLooking != null) {
            this.seat.playerLooking.setAngles(yaw, pitch, 0F);
        }
        if (this.seat.looking != null) {
            this.seat.looking.setAngles(yaw, pitch, 0F);
        }
    }
}