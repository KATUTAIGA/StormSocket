package com.example.examplemod;

import com.flansmod.common.driveables.EntitySeat;
import com.flansmod.common.guns.GunType;
import com.flansmod.common.guns.ItemGun;
import com.flansmod.common.guns.ShootableType;
import com.magistumod.entity.FlansModShooter;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class EntityAIAttackRangedFlanGun extends EntityAIBase {

    private final EntityFriendlyCreature entity;
    private final double moveSpeedAmp;
    private final float minKeepDistanceSq = 16.0F; // 引き撃ち（4ブロック）

    private float maxAttackDistanceSq = 4096.0F; // デフォルト射程64ブロック

    private FlansModShooter shadow;
    private EntityLivingBase target;
    
    private int attackTimer;
    private int reloadTimer;  // リロード用タイマー
    private boolean isReloading; // 現在リロード中かどうかのフラグ

    public EntityAIAttackRangedFlanGun(EntityFriendlyCreature entity, double moveSpeedAmp, int defaultFireInterval, float maxAttackDistance) {
        this.entity = entity;
        this.moveSpeedAmp = moveSpeedAmp;
        
        if (maxAttackDistance > 0) {
            this.maxAttackDistanceSq = maxAttackDistance * maxAttackDistance;
        }

        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (!entity.isEntityAlive()) {
            return false;
        }
        ItemStack mainhand = entity.getHeldItemMainhand();
        if (mainhand.isEmpty() || !EngenderFlanBridge.isFlanGun(mainhand.getItem())) {
            return false;
        }
        EntityLivingBase potentialTarget = entity.getAttackTarget();
        if (potentialTarget == null) {
            return false;
        }
        return potentialTarget.isEntityAlive();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void resetTask() {
        this.target = null;
        this.attackTimer = -1;
        this.reloadTimer = 0;
        this.isReloading = false;
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

        // ターゲットを注視
        entity.getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);

        // --- 位置取り（引き撃ち・追従） ---
        if (!canSee) {
            entity.getNavigator().tryMoveToEntityLiving(target, moveSpeedAmp);
        } else if (distSq < minKeepDistanceSq) {
            Vec3d backPos = findBackstepPosition(target);
            if (backPos != null) {
                entity.getNavigator().tryMoveToXYZ(backPos.x, backPos.y, backPos.z, moveSpeedAmp * 1.2D);
            }
        } else if (distSq > maxAttackDistanceSq) {
            entity.getNavigator().tryMoveToEntityLiving(target, moveSpeedAmp);
        } else {
            entity.getNavigator().clearPath();
        }

        // リロード処理の進行
        if (isReloading) {
            if (reloadTimer > 0) {
                reloadTimer--;
            } else {
                // リロード完了処理
                finishReload();
                isReloading = false;
            }
            return; // リロード中は射撃を行わない
        }

        if (attackTimer > 0) {
            attackTimer--;
        }

        if (canSee && distSq <= maxAttackDistanceSq && attackTimer <= 0) {
            // 残弾数が 0 の場合のみリロードを開始
            if (getGunAmmoCount() <= 0) {
                startReload();
            } else {
                fire(target);
                // 射撃後に弾数を消費
                consumeAmmo();
                // 銃個別の射撃遅延(shootDelay)を取得してタイマーにセット
                this.attackTimer = getGunShootDelay();
            }
        }
    }

    /**
     * 手に持っているFlan's Modの銃データから発射遅延(shootDelay)を取得する
     */
    private int getGunShootDelay() {
        ItemStack mainhand = entity.getHeldItemMainhand();
        if (!mainhand.isEmpty() && mainhand.getItem() instanceof ItemGun) {
            ItemGun gunItem = (ItemGun) mainhand.getItem();
            GunType type = gunItem.GetType();
            if (type != null) {
                float delay = type.shootDelay;
                if (delay > 0) {
                    return Math.max(1, Math.round(delay));
                }
            }
        }
        return 10;
    }

    /**
     * リロード動作の開始
     */
    private void startReload() {
        ItemStack mainhand = entity.getHeldItemMainhand();
        if (!mainhand.isEmpty() && mainhand.getItem() instanceof ItemGun) {
            ItemGun gunItem = (ItemGun) mainhand.getItem();
            GunType type = gunItem.GetType();
            if (type != null) {
                this.isReloading = true;
                this.reloadTimer = Math.max(20, type.reloadTime);

                if (type.reloadSound != null && !type.reloadSound.isEmpty()) {
                    SoundEvent soundEvent = SoundEvent.REGISTRY.getObject(new ResourceLocation(type.reloadSound));
                    if (soundEvent != null) {
                        entity.world.playSound(null, entity.posX, entity.posY, entity.posZ, 
                            soundEvent, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                    }
                }
            }
        }
    }

    /**
     * リロード完了時にNBT内の弾薬数を全回復する
     */
    private void finishReload() {
        ItemStack mainhand = entity.getHeldItemMainhand();
        if (!mainhand.isEmpty() && mainhand.getItem() instanceof ItemGun) {
            ItemGun gunItem = (ItemGun) mainhand.getItem();
            GunType type = gunItem.GetType();
            if (type != null) {
                NBTTagCompound tag = mainhand.getTagCompound();
                if (tag == null) {
                    tag = new NBTTagCompound();
                    mainhand.setTagCompound(tag);
                }
                
                NBTTagList ammoList = new NBTTagList();
                if (type.ammo != null && !type.ammo.isEmpty()) {
                    int numAmmo = Math.max(1, type.numAmmoItemsInGun);
                    for (int i = 0; i < numAmmo; i++) {
                        ShootableType ammoType = type.ammo.get(i % type.ammo.size());
                        if (ammoType != null && ammoType.item != null) {
                            ItemStack ammoStack = new ItemStack(ammoType.item);
                            NBTTagCompound ammoTag = new NBTTagCompound();
                            ammoStack.writeToNBT(ammoTag);
                            ammoList.appendTag(ammoTag);
                        }
                    }
                }
                tag.setTag("ammo", ammoList);
            }
        }
    }

    /**
     * NBTから銃の現在残弾数を取得する（NBT未初期化の場合は初期化）
     */
    private int getGunAmmoCount() {
        ItemStack mainhand = entity.getHeldItemMainhand();
        if (!mainhand.isEmpty() && mainhand.getItem() instanceof ItemGun) {
            if (!mainhand.hasTagCompound() || !mainhand.getTagCompound().hasKey("ammo")) {
                // NBTが存在しない初期状態はすぐに装填処理を行う
                finishReload();
            }
            
            NBTTagCompound tag = mainhand.getTagCompound();
            if (tag == null || !tag.hasKey("ammo")) {
                return 0;
            }

            NBTTagList ammoList = tag.getTagList("ammo", 10);
            if (ammoList.tagCount() == 0) {
                return 0; // マガジンが空
            }

            int totalAmmo = 0;
            for (int i = 0; i < ammoList.tagCount(); i++) {
                NBTTagCompound ammoTag = ammoList.getCompoundTagAt(i);
                ItemStack ammoStack = new ItemStack(ammoTag);
                if (!ammoStack.isEmpty()) {
                    int max = ammoStack.getMaxDamage();
                    int damage = ammoStack.getItemDamage();
                    if (max > 0) {
                        totalAmmo += Math.max(0, max - damage);
                    } else {
                        totalAmmo += 1;
                    }
                }
            }
            return totalAmmo;
        }
        return 0;
    }

    /**
     * 1発射撃ごとにNBT内の弾薬の耐久値（残弾）を消費させる
     */
    private void consumeAmmo() {
        ItemStack mainhand = entity.getHeldItemMainhand();
        if (!mainhand.isEmpty() && mainhand.getItem() instanceof ItemGun) {
            if (!mainhand.hasTagCompound() || !mainhand.getTagCompound().hasKey("ammo")) {
                finishReload();
            }

            NBTTagCompound tag = mainhand.getTagCompound();
            if (tag != null && tag.hasKey("ammo")) {
                NBTTagList ammoList = tag.getTagList("ammo", 10);
                for (int i = 0; i < ammoList.tagCount(); i++) {
                    NBTTagCompound ammoTag = ammoList.getCompoundTagAt(i);
                    ItemStack ammoStack = new ItemStack(ammoTag);
                    if (!ammoStack.isEmpty()) {
                        int max = ammoStack.getMaxDamage();
                        int damage = ammoStack.getItemDamage();
                        if (max > 0 && damage < max) {
                            ammoStack.setItemDamage(damage + 1);
                            ammoStack.writeToNBT(ammoTag);
                            break;
                        } else if (max == 0) {
                            ammoStack.setItemDamage(damage + 1);
                            ammoStack.writeToNBT(ammoTag);
                            break;
                        }
                    }
                }
            }
        }
    }

    private Vec3d findBackstepPosition(EntityLivingBase target) {
        Vec3d entityVec = new Vec3d(this.entity.posX, this.entity.posY, this.entity.posZ);
        Vec3d targetVec = new Vec3d(target.posX, target.posY, target.posZ);
        Vec3d dir = entityVec.subtract(targetVec).normalize();
        return entityVec.add(dir.scale(4.0D));
    }

    private FlansModShooter getOrCreateShadow(World world) {
        if (shadow == null || shadow.world != world) {
            shadow = new FlansModShooter(world) {
                @Override
                public void addControlTask(EntitySeat seat, int arg1, float arg2) {}
                @Override
                public void removeControlTask() {}
                @Override
                public void addFollowTask(EntityLiving entityLiving, EntityPlayer player, float arg1, float arg2, double arg3) {}
                @Override
                public void removeFollowTask() {}
                @Override
                public void addAvoidTask() {}
                @Override
                public void removeAvoidTask() {}
            };
        }
        return shadow;
    }

    private void fire(EntityLivingBase target) {
        World world = entity.world;
        try {
            entity.faceEntity(target, 30.0F, 30.0F);

            FlansModShooter s = getOrCreateShadow(world);
            // 足元ではなく「目の高さ」から発射するようにY座標を調整
            s.setPosition(entity.posX, entity.posY + entity.getEyeHeight(), entity.posZ);
            s.rotationYaw = entity.rotationYawHead;
            s.rotationPitch = entity.rotationPitch;

            ItemStack gunCopy = entity.getHeldItemMainhand().copy();

            int ammoAmount = 1;
            if (gunCopy.getItem() instanceof ItemGun) {
                GunType type = ((ItemGun) gunCopy.getItem()).GetType();
                if (type != null && type.numAmmoItemsInGun > 0) {
                    ammoAmount = type.numAmmoItemsInGun;
                }
            }
            s.initEquipment(gunCopy, ammoAmount, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

            s.shootEntity(target, 1.0F);
        } catch (Exception e) {
            System.out.println("[EngenderFlanBridge] gun attack failed for " + entity + " -- " + e);
            e.printStackTrace();
        }
    }
}