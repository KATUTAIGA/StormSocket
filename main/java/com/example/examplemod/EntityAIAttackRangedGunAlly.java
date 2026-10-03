package com.example.examplemod;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import techguns.items.guns.GenericGun;

public class EntityAIAttackRangedGunAlly extends EntityAIBase {

    private static final int RAPID_FIRE_INTERVAL = 2;

    /** [無駄弾を防ぐ思考射撃] burst length is randomized between these two (inclusive) instead of full-auto spam. */
    private static final int BURST_SHOTS_MIN = 2;
    private static final int BURST_SHOTS_MAX = 3;
    /** How long the ally pauses to reassess after finishing a burst. */
    private static final int BURST_COOLDOWN_TICKS = 25;
    /**
     * Beyond this fraction of max range, hit chance is judged too low to be
     * worth spending ammo on -- close the distance instead of shooting.
     */
    private static final float EFFECTIVE_RANGE_FACTOR = 0.75F;

    private enum Phase { ENGAGE, REPOSITION, BURST_COOLDOWN }

    private final EntityFriendlyCreature entity;
    private final double moveSpeedAmp;
    private final int semiAutoInterval;
    private final float maxAttackDistanceSq;
    private final float effectiveAttackDistanceSq;
    private final float minKeepDistanceSq = 16.0F; // 敵と近すぎる時の最低距離 (4ブロックの2乗)

    private int attackTime = -1;
    private EntityLivingBase target;
    private Phase phase = Phase.ENGAGE;
    private int burstShotsFired;
    private int burstShotsTarget;
    private int burstCooldown;

    public EntityAIAttackRangedGunAlly(EntityFriendlyCreature entity, double moveSpeedAmp,
                                        int semiAutoInterval, float maxAttackDistance) {
        this.entity = entity;
        this.moveSpeedAmp = moveSpeedAmp;
        this.semiAutoInterval = semiAutoInterval;
        this.maxAttackDistanceSq = maxAttackDistance * maxAttackDistance;
        float effectiveDistance = maxAttackDistance * EFFECTIVE_RANGE_FACTOR;
        this.effectiveAttackDistanceSq = effectiveDistance * effectiveDistance;
        this.setMutexBits(3);
    }

    private GenericGun getHeldGun() {
        ItemStack stack = this.entity.getHeldItemMainhand();
        if (!stack.isEmpty() && stack.getItem() instanceof GenericGun) {
            return (GenericGun) stack.getItem();
        }
        return null;
    }

    @Override
    public boolean shouldExecute() {
        EntityLivingBase target = this.entity.getAttackTarget();
        if (target == null || !target.isEntityAlive()) {
            return false;
        }
        return getHeldGun() != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return (this.entity.getAttackTarget() != null && this.entity.getAttackTarget().isEntityAlive())
                && getHeldGun() != null;
    }

    @Override
    public void resetTask() {
        this.target = null;
        this.attackTime = -1;
        this.phase = Phase.ENGAGE;
        this.burstShotsFired = 0;
        this.burstCooldown = 0;
    }

    @Override
    public void updateTask() {
        this.target = this.entity.getAttackTarget();
        if (this.target == null) {
            return;
        }

        double distSq = this.entity.getDistanceSq(this.target.posX, this.target.getEntityBoundingBox().minY, this.target.posZ);
        boolean canSee = this.entity.getEntitySenses().canSee(this.target);

        // 常にターゲットを注視
        this.entity.getLookHelper().setLookPositionWithEntity(this.target, 30.0F, 30.0F);

        // [節約射撃] far enough that hits would mostly miss, or no clear
        // line of sight at all (cover) -- both cases mean "don't burn ammo,
        // go get a real shot instead" (Phase.REPOSITION).
        boolean lowHitChance = distSq > this.effectiveAttackDistanceSq;
        this.phase = (!canSee || lowHitChance) ? Phase.REPOSITION : Phase.ENGAGE;

        // --- 賢い位置取りロジック ---
        if (!canSee) {
            // 視線が通らない（障害物がある）場合は射線を確保できる位置へ移動
            this.entity.getNavigator().tryMoveToEntityLiving(this.target, this.moveSpeedAmp);
        } else if (distSq < this.minKeepDistanceSq) {
            // 敵と近すぎる場合は「引き撃ち」（敵から離れる方向へバックステップ）
            Vec3d backPos = findBackstepPosition(this.target);
            if (backPos != null) {
                this.entity.getNavigator().tryMoveToXYZ(backPos.x, backPos.y, backPos.z, this.moveSpeedAmp * 1.2D);
            }
        } else if (distSq > this.maxAttackDistanceSq || lowHitChance) {
            // 射程外、または命中率が低すぎる距離の場合は近づく
            this.entity.getNavigator().tryMoveToEntityLiving(this.target, this.moveSpeedAmp);
        } else {
            // 適正距離かつ視線が通っているなら停止して射撃に集中
            this.entity.getNavigator().clearPath();
        }

        if (this.attackTime > 0) {
            this.attackTime--;
        }
        if (this.burstCooldown > 0) {
            this.burstCooldown--;
        }

        if (this.phase == Phase.REPOSITION) {
            // 射線が通らない/遠すぎるうちは絶対に発射しない -- 無駄弾ゼロ
            return;
        }
        if (this.attackTime > 0 || this.burstCooldown > 0 || distSq > this.maxAttackDistanceSq) {
            return;
        }

        GenericGun gun = getHeldGun();
        if (gun == null) {
            return;
        }
        ItemStack stack = this.entity.getHeldItemMainhand();
        if (gun.getAmmoLeft(stack) <= 0) {
            // [弾薬管理] 弾切れ -- 補充は EngenderTechgunsBridge が
            // インベントリの実弾薬を確認して行う。ここでは無理に空撃ちしない。
            return;
        }

        performGunAttack(gun, this.target);
        this.attackTime = gun.isSemiAuto() ? this.semiAutoInterval : RAPID_FIRE_INTERVAL;

        // [無駄弾を防ぐ思考射撃] バースト管理: 2〜3発撃ったらクールダウンに入り、
        // フルオートで撃ち続けない。
        this.burstShotsFired++;
        if (this.burstShotsTarget <= 0) {
            this.burstShotsTarget = BURST_SHOTS_MIN
                    + this.entity.getEntityWorld().rand.nextInt(BURST_SHOTS_MAX - BURST_SHOTS_MIN + 1);
        }
        if (this.burstShotsFired >= this.burstShotsTarget) {
            this.burstShotsFired = 0;
            this.burstShotsTarget = 0;
            this.burstCooldown = BURST_COOLDOWN_TICKS;
            this.phase = Phase.BURST_COOLDOWN;
        }
    }

    /**
     * 敵から離れる位置（引き撃ち用）を計算する
     */
    private Vec3d findBackstepPosition(EntityLivingBase target) {
        Vec3d entityVec = new Vec3d(this.entity.posX, this.entity.posY, this.entity.posZ);
        Vec3d targetVec = new Vec3d(target.posX, target.posY, target.posZ);
        Vec3d dir = entityVec.subtract(targetVec).normalize(); // 敵から自分への方向ベクトル
        return entityVec.add(dir.scale(4.0D)); // 4ブロック後方
    }

    private void performGunAttack(GenericGun gun, EntityLivingBase target) {
        // [弾薬管理] ammo is real now -- reloading is handled exclusively by
        // EngenderTechgunsBridge, which checks/consumes matching ammo items
        // from the ally's own inventory before topping off the clip. This
        // method is only reached once getAmmoLeft() > 0 has already been
        // confirmed by the caller.
        this.entity.faceEntity(target, 30.0F, 30.0F);
        try {
            ItemStack stack = this.entity.getHeldItemMainhand();
            // [弾が減らない不具合の修正] GenericGun#fireWeaponFromNPC() is
            // Techguns' bare NPC-firing entry point: decompiling the actual
            // jar shows it only plays the fire sound and spawns the
            // projectile(s) (calling the protected shootGun() directly) --
            // unlike the player-facing shootGunPrimary(), it never touches
            // the "ammo" NBT counter itself (that's normally the caller's
            // job, same as the vanilla player path calls useAmmo() right
            // before shootGun()). Without this, the ally's clip never
            // actually went down once loaded, so EngenderTechgunsBridge
            // would never be asked to top it off again -- real ammo
            // consumption never happened even after a successful reload.
            gun.useAmmo(stack, 1);
            gun.fireWeaponFromNPC(this.entity, 1.0F, 1.0F);
        } catch (Exception e) {
            System.out.println("[EngenderTechgunsBridge] gun attack failed for " + this.entity + ": " + e);
            e.printStackTrace();
        }
    }
}