package com.example.examplemod;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Generic "shoot whatever HBM gun I'm holding" AI for Engender's
 * EntityFriendlyCreature allies.
 */
public class EntityAIAttackRangedHBMGun extends EntityAIBase {

    private static final int DAMAGE_MIN = 4;
    private static final int DAMAGE_MAX = 8;

    private final EntityFriendlyCreature entity;
    private final double moveSpeedAmp;
    private final int fireIntervalTicks;
    private final float maxAttackDistanceSq;
    private final float minKeepDistanceSq = 16.0F; // 引き撃ち開始距離 (4ブロック)
    private final Random rand = new Random();

    private EntityLivingBase target;
    private int attackTimer;

    public EntityAIAttackRangedHBMGun(EntityFriendlyCreature entity, double moveSpeedAmp, int fireIntervalTicks, float maxAttackDistance) {
        this.entity = entity;
        this.moveSpeedAmp = moveSpeedAmp;
        this.fireIntervalTicks = fireIntervalTicks;
        this.maxAttackDistanceSq = maxAttackDistance * maxAttackDistance;
        
        // 1.12.2では整数値(3 = 移動 + 視線制御)で指定します
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (!entity.isEntityAlive()) {
            return false;
        }
        if (!EngenderHBMBridge.isHbmGun(entity.getHeldItemMainhand().isEmpty() ? null : entity.getHeldItemMainhand().getItem())) {
            return false;
        }
        EntityLivingBase potentialTarget = entity.getAttackTarget();
        return potentialTarget != null && potentialTarget.isEntityAlive();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void resetTask() {
        this.target = null;
        this.attackTimer = -1;
        this.entity.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        target = entity.getAttackTarget();
        if (target == null) {
            return;
        }

        double distSq = entity.getDistanceSq(target.posX, target.getEntityBoundingBox().minY, target.posZ);
        boolean canSee = entity.getEntitySenses().canSee(target);

        // 常にターゲットを注視
        entity.getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);

        // --- 賢い立ち回りロジック（引き撃ち・追従） ---
        if (!canSee) {
            // 視線が通らない（障害物がある）場合は射線を確保できる位置へ移動
            entity.getNavigator().tryMoveToEntityLiving(target, moveSpeedAmp);
        } else if (distSq < minKeepDistanceSq) {
            // 敵と近すぎる場合は引き撃ち（バックステップ）
            Vec3d backPos = findBackstepPosition(target);
            if (backPos != null) {
                entity.getNavigator().tryMoveToXYZ(backPos.x, backPos.y, backPos.z, moveSpeedAmp * 1.2D);
            }
        } else if (distSq > maxAttackDistanceSq) {
            // 射程外の場合は近づく
            entity.getNavigator().tryMoveToEntityLiving(target, moveSpeedAmp);
        } else {
            // 適正距離かつ視線が通っているなら停止して射撃に集中
            entity.getNavigator().clearPath();
        }

        if (attackTimer > 0) {
            attackTimer--;
        }

        if (canSee && distSq <= maxAttackDistanceSq && attackTimer <= 0) {
            attackTimer = fire(target);
        }
    }

    private int noAmmoNoticeCooldown;

    private void noticeNoAmmo() {
        if (noAmmoNoticeCooldown-- > 0) {
            return;
        }
        noAmmoNoticeCooldown = 5;
        ItemStack need = HBMGunSupport.exampleAmmo(entity.getHeldItemMainhand());
        String msg = "[HBM] " + (need.isEmpty() ? "弾薬" : need.getDisplayName()) + " が必要です！（味方に手渡してください）";
        for (net.minecraft.entity.player.EntityPlayer p : entity.world.getEntitiesWithinAABB(
                net.minecraft.entity.player.EntityPlayer.class, entity.getEntityBoundingBox().grow(24.0D))) {
            p.sendMessage(new net.minecraft.util.text.TextComponentString(msg));
        }
    }

    /**
     * 敵から離れる位置（引き撃ち用）を計算する
     */
    private Vec3d findBackstepPosition(EntityLivingBase target) {
        Vec3d entityVec = new Vec3d(this.entity.posX, this.entity.posY, this.entity.posZ);
        Vec3d targetVec = new Vec3d(target.posX, target.posY, target.posZ);
        Vec3d dir = entityVec.subtract(targetVec).normalize();
        return entityVec.add(dir.scale(4.0D)); // 4ブロック後方
    }

    /** @return 次の射撃までの待ちTick */
    private int fire(EntityLivingBase target) {
        World world = entity.world;
        try {
            // [弾が明後日の方向へ飛ぶ不具合の修正] HBM の EntityBullet は射手の
            // 「体」の向き(rotationYaw/rotationPitch)で弾道を決める。faceEntity は
            // 1回30°までしか回らず、引き撃ち中は体が敵と逆を向いているため、
            // 撃つ瞬間だけ体・頭の向きを敵の胴体へ完全に合わせる。
            double dx = target.posX - entity.posX;
            double dz = target.posZ - entity.posZ;
            double dy = (target.posY + target.getEyeHeight() * 0.8D) - (entity.posY + entity.getEyeHeight());
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
            float pitch = (float) -(Math.atan2(dy, horizontal) * (180D / Math.PI));
            entity.rotationYaw = yaw;
            entity.rotationYawHead = yaw;
            entity.renderYawOffset = yaw;
            entity.rotationPitch = pitch;

            // その銃の本物の弾（弾設定・散弾数・ダメージ）を、渡された実弾を消費して撃つ。
            int delay = HBMGunSupport.fire(entity, entity.getHeldItemMainhand());
            if (delay == HBMGunSupport.NO_AMMO) {
                noticeNoAmmo();
                return 40;
            }
            entity.swingArm(EnumHand.MAIN_HAND);
            return delay; // 発射音は HBMGunSupport がその銃の音で鳴らす
        } catch (Throwable e) {
            System.out.println("[EngenderHBMBridge] gun attack failed for " + entity + ": " + e);
            return 40;
        }
    }
}