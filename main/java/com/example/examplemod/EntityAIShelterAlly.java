package com.example.examplemod;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/**
 * [危険な時間帯の退避] 夜間や雷雨の時、外で仕事をしている味方は作業を中断して
 * 安全な場所へ避難する。
 *
 * <ul>
 *   <li>持ち主が近く（64ブロック）にいれば、持ち主のそばへ（一緒にいるのが一番安全）</li>
 *   <li>いなければ、近く（16ブロック）で屋根があり、たいまつ等で明るい場所へ</li>
 *   <li>どちらも無ければその場でたいまつを置いて待つ</li>
 * </ul>
 * <p>地下（空が見えない場所）で作業中は夜でも関係ないので続ける。仕事の無い護衛役は
 * 避難しない。敵に狙われたら戦闘AIに任せる（優先度2で、戦闘中は起動しない）。</p>
 */
public class EntityAIShelterAlly extends EntityAIBase {

    private static final int SEARCH_RADIUS = 16;

    private final EntityFriendlyCreature entity;
    private final EngenderGatheringBridge bridge;
    private BlockPos spot;
    private EntityPlayer owner;
    private int checkCooldown;
    private int repathCooldown;
    private long announcedDay = -1;

    public EntityAIShelterAlly(EntityFriendlyCreature entity, EngenderGatheringBridge bridge) {
        this.entity = entity;
        this.bridge = bridge;
        this.setMutexBits(3);
    }

    private boolean dangerousTime() {
        World w = entity.world;
        if (!w.provider.isSurfaceWorld()) {
            return false;
        }
        return w.isThundering() || !w.isDaytime();
    }

    private boolean outdoors() {
        return entity.world.canSeeSky(new BlockPos(entity).up());
    }

    @Override
    public boolean shouldExecute() {
        if (--checkCooldown > 0) {
            return false;
        }
        checkCooldown = 40;
        if (!entity.isEntityAlive() || entity.isRiding() || !dangerousTime() || bridge.isFighting(entity)) {
            return false;
        }
        if (bridge.getJob(entity).isEmpty() && AllyTeamManager.roleOf(entity) != AllyTeamManager.Role.COLLECTOR) {
            return false; // 護衛役・待機中の味方はそのまま
        }
        if (!outdoors()) {
            return false;
        }
        owner = AllyAIUtil.resolveOwnerPlayer(entity, bridge);
        if (owner != null && owner.getDistanceSq(entity) > 64 * 64) {
            owner = null;
        }
        spot = owner != null ? null : findShelter();
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return entity.isEntityAlive() && !entity.isRiding() && dangerousTime() && !bridge.isFighting(entity);
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
        long day = entity.world.getWorldTime() / 24000L;
        if (announcedDay != day) {
            announcedDay = day;
            EntityPlayer p = AllyAIUtil.resolveOwnerPlayer(entity, bridge);
            if (p != null) {
                p.sendMessage(new TextComponentString("[" + entity.getName() + "] "
                        + (entity.world.isThundering() ? "雷雨" : "夜") + "なので作業を中断して"
                        + (owner != null ? "あなたのそばへ" : spot != null ? "明るい屋内へ" : "その場で明かりをつけて")
                        + "避難します。"));
            }
        }
    }

    @Override
    public void resetTask() {
        entity.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        if (--repathCooldown > 0) {
            return;
        }
        repathCooldown = 20;
        if (owner != null && owner.isEntityAlive()) {
            entity.getLookHelper().setLookPositionWithEntity(owner, 30.0F, 30.0F);
            if (entity.getDistanceSq(owner) > 3.5 * 3.5) {
                entity.getNavigator().tryMoveToEntityLiving(owner, 1.1D);
            } else {
                entity.getNavigator().clearPath();
            }
            return;
        }
        if (spot != null && entity.getDistanceSq(spot) > 2.0 * 2.0) {
            if (!entity.getNavigator().tryMoveToXYZ(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.1D)) {
                spot = null;
            }
            return;
        }
        entity.getNavigator().clearPath();
        AllyMaintenance.placeTorchFromInventory(entity);
    }

    /** 屋根があって明るい（ブロック光11以上）、立てる場所。無ければ null。 */
    private BlockPos findShelter() {
        World w = entity.world;
        BlockPos c = new BlockPos(entity);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.getAllInBoxMutable(c.add(-SEARCH_RADIUS, -4, -SEARCH_RADIUS), c.add(SEARCH_RADIUS, 4, SEARCH_RADIUS))) {
            if (!w.isAirBlock(p) || !w.isAirBlock(p.up()) || !w.getBlockState(p.down()).isSideSolid(w, p.down(), net.minecraft.util.EnumFacing.UP)) {
                continue;
            }
            if (w.getLightFor(EnumSkyBlock.BLOCK, p) < 11 || w.canSeeSky(p.up())) {
                continue;
            }
            double d = p.distanceSq(c);
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }
}
