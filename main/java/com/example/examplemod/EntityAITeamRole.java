package com.example.examplemod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * [チーム連携] {@link AllyTeamManager} が決めた役割（回収係・護衛・トンネル支援・レール敷設）を実行する。
 * 作業役（WORKER）の間は何もしない（採取・建築AIが動く）。
 */
public class EntityAITeamRole extends EntityAIBase {

    private static final int DELIVER_THRESHOLD_SLOTS = 18;
    private static final int TORCH_EVERY_STEPS = 5;

    private final EntityFriendlyCreature entity;
    private final EngenderGatheringBridge bridge;

    private int tick;
    private int lastPlanTick = -1000;
    private EntityFriendlyCreature lastLeader;
    private boolean delivering;
    private int deliverTicks;
    private BlockPos chestPos;
    private int logIndex = -1;
    private int lastTorchIndex = -100;
    private int navFails;
    private boolean railNotice;
    private long railWaitUntil;
    private AllyTeamManager.Role lastRole = AllyTeamManager.Role.WORKER;

    public EntityAITeamRole(EntityFriendlyCreature entity, EngenderGatheringBridge bridge) {
        this.entity = entity;
        this.bridge = bridge;
        this.setMutexBits(3);
    }

    private AllyTeamManager.Role role() {
        return AllyTeamManager.roleOf(entity);
    }

    @Override
    public boolean shouldExecute() {
        return entity.isEntityAlive() && !entity.isRiding() && role() != AllyTeamManager.Role.WORKER;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void startExecuting() {
        AllyTeamManager.Role r = role();
        if (r != lastRole) {
            lastRole = r;
            logIndex = -1;
            delivering = false;
            // 役割に合わせて持ち替え（護衛は武器、支援・回収は手を空けておく必要はない）
            if (r == AllyTeamManager.Role.GUARD) {
                AllyToolManager.equipWeapon(entity);
            }
        }
    }

    @Override
    public void resetTask() {
        entity.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        tick++;
        switch (role()) {
            case COLLECTOR:
                tickCollector();
                break;
            case GUARD:
                tickGuard();
                break;
            case TUNNEL_SUPPORT:
                tickTunnelHelper(false);
                break;
            case RAIL_LAYER:
                tickTunnelHelper(true);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    private void moveTo(double x, double y, double z, double speed) {
        boolean due = tick - lastPlanTick >= 40 || (entity.getNavigator().noPath() && tick - lastPlanTick >= 15);
        if (due) {
            lastPlanTick = tick;
            net.minecraft.pathfinding.Path path = SafeRoutePlanner.plan(entity, x, y, z);
            if (path == null || !entity.getNavigator().setPath(path, speed)) {
                navFails++;
            } else {
                navFails = 0;
            }
        }
    }

    private void follow(EntityLivingBase target, double keep) {
        if (target == null) {
            return;
        }
        if (entity.getDistanceSq(target) > keep * keep) {
            moveTo(target.posX, target.posY, target.posZ, 1.15D);
        } else {
            entity.getNavigator().clearPath();
            entity.getLookHelper().setLookPositionWithEntity(target, 30.0F, 30.0F);
        }
    }

    // ------------------------------------------------------------------
    // 回収係
    // ------------------------------------------------------------------

    /** 回収して運ぶ物（道具・武器・食べ物・たいまつ材料は自分用に残す）。 */
    private static boolean isLoot(ItemStack s) {
        if (s.isEmpty() || AllyToolManager.isKeepable(s) || s.getItem() instanceof ItemFood
                || EntityAIGatherResourceAlly.isTorchSupply(s)) {
            return false;
        }
        return !AllyToolManager.isRangedGun(s);
    }

    private int lootSlots() {
        InventoryBasic inv = AllyInventory.get(entity);
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (isLoot(inv.getStackInSlot(i))) {
                n++;
            }
        }
        return n;
    }

    private void tickCollector() {
        EntityFriendlyCreature leader = AllyTeamManager.leaderOf(entity);
        if (delivering) {
            tickDeliver(leader);
            return;
        }
        EntityAIGatherResourceAlly leaderTask = leader == null ? null : bridge.getGatherTask(leader);
        int loot = lootSlots();
        if (loot >= DELIVER_THRESHOLD_SLOTS || (loot > 0 && (leader == null || (leaderTask != null && leaderTask.isHeadingHome())))) {
            delivering = true;
            deliverTicks = 0;
            chestPos = findChest(leader != null ? leader : entity);
            return;
        }
        if (leader == null) {
            return;
        }
        follow(leader, 3.0);
        if (tick % 5 != 0) {
            return;
        }
        // 伐採役が集めた物を受け取る（伐採役は配達に戻らず作業を続けられる）
        if (leaderTask != null && entity.getDistanceSq(leader) <= 5.0 * 5.0) {
            leaderTask.giveCarriedTo(entity);
        }
        // 落ちてくる物・散らばった物を拾う
        for (EntityItem item : entity.world.getEntitiesWithinAABB(EntityItem.class, entity.getEntityBoundingBox().grow(6.0D))) {
            if (!item.isEntityAlive() || item.getItem().isEmpty() || item.ticksExisted < 10) {
                continue;
            }
            ItemStack rest = AllyInventory.insert(entity, item.getItem());
            if (rest.isEmpty()) {
                item.setDead();
            } else {
                item.setItem(rest);
            }
        }
    }

    private BlockPos findChest(EntityLivingBase around) {
        BlockPos best = null;
        double bestD = 32.0 * 32.0;
        for (TileEntity te : entity.world.loadedTileEntityList) {
            if (!(te instanceof TileEntityChest)) {
                continue;
            }
            double d = te.getDistanceSq(around.posX, around.posY, around.posZ);
            if (d < bestD) {
                bestD = d;
                best = te.getPos();
            }
        }
        return best;
    }

    private void tickDeliver(EntityFriendlyCreature leader) {
        if (++deliverTicks > 600) {
            delivering = false; // たどり着けない -- 次の機会に
            return;
        }
        World world = entity.world;
        if (chestPos != null) {
            TileEntity te = world.getTileEntity(chestPos);
            if (!(te instanceof IInventory)) {
                chestPos = null;
                return;
            }
            if (entity.getDistanceSq(chestPos) > 2.8 * 2.8) {
                moveTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.15D);
                if (navFails > 6) {
                    chestPos = null;
                }
                return;
            }
            IInventory chest = (IInventory) te;
            InventoryBasic inv = AllyInventory.get(entity);
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!isLoot(s)) {
                    continue;
                }
                ItemStack rest = TileEntityHopper.putStackInInventoryAllSlots(null, chest, s.copy(), EnumFacing.UP);
                inv.setInventorySlotContents(i, rest);
            }
            inv.markDirty();
            chest.markDirty();
            world.playSound(null, chestPos, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.5F, 1.0F);
            delivering = false;
            return;
        }
        EntityPlayer owner = AllyAIUtil.resolveOwnerPlayer(entity, bridge);
        if (owner == null) {
            delivering = false;
            return;
        }
        if (entity.getDistanceSq(owner) > 2.8 * 2.8) {
            moveTo(owner.posX, owner.posY, owner.posZ, 1.2D);
            return;
        }
        InventoryBasic inv = AllyInventory.get(entity);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!isLoot(s)) {
                continue;
            }
            ItemStack give = s.copy();
            owner.inventory.addItemStackToInventory(give);
            inv.setInventorySlotContents(i, give);
        }
        inv.markDirty();
        delivering = false;
    }

    // ------------------------------------------------------------------
    // 護衛
    // ------------------------------------------------------------------

    private void tickGuard() {
        EntityLivingBase anchor = AllyTeamManager.leaderOf(entity);
        if (anchor == null) {
            anchor = AllyAIUtil.resolveOwnerPlayer(entity, bridge);
        }
        if (anchor == null) {
            return;
        }
        if (tick % 10 == 0) {
            EntityLivingBase threat = anchor.getRevengeTarget();
            // 持ち主や味方同士の誤爆では反撃しない（敵モブだけ）
            if (threat != null && (!(threat instanceof net.minecraft.entity.monster.IMob)
                    || threat instanceof EntityFriendlyCreature || threat instanceof EntityPlayer)) {
                threat = null;
            }
            if (threat == null || !threat.isEntityAlive()) {
                threat = null;
                double best = 16.0 * 16.0;
                List<EntityMob> mobs = entity.world.getEntitiesWithinAABB(EntityMob.class, anchor.getEntityBoundingBox().grow(16.0D));
                for (EntityMob mob : mobs) {
                    double d = mob.getDistanceSq(anchor);
                    if (mob.isEntityAlive() && d < best && entity.getDistanceSq(mob) < 28.0 * 28.0) {
                        best = d;
                        threat = mob;
                    }
                }
            }
            if (threat != null && (entity.getAttackTarget() == null || !entity.getAttackTarget().isEntityAlive())) {
                AllyToolManager.equipWeapon(entity);
                entity.setAttackTarget(threat);
            }
        }
        follow(anchor, 6.0);
    }

    // ------------------------------------------------------------------
    // トンネル支援・レール敷設
    // ------------------------------------------------------------------

    private void tickTunnelHelper(boolean rails) {
        EntityFriendlyCreature leader = AllyTeamManager.leaderOf(entity);
        if (leader == null) {
            return;
        }
        if (leader != lastLeader) {
            lastLeader = leader;
            logIndex = -1;
        }
        AllyTeamManager.TunnelLog log = AllyTeamManager.tunnelLog(leader);
        if (log == null || log.end() == 0) {
            follow(leader, 5.0);
            if (tick % 20 == 0) {
                AllyMaintenance.placeTorchFromInventory(entity);
            }
            return;
        }
        if (logIndex < log.removed || logIndex > log.end()) {
            logIndex = Math.max(log.removed, log.end() - 48);
        }
        int lag = rails ? 5 : 2;
        if (logIndex >= log.end() - lag) {
            // 先頭に追いついた: 少し後ろで待つ
            if (entity.getDistanceSq(leader) > 10.0 * 10.0) {
                follow(leader, 6.0);
            } else {
                entity.getNavigator().clearPath();
            }
            return;
        }
        BlockPos p = log.at(logIndex);
        if (p == null) {
            logIndex++;
            return;
        }
        double d = entity.getDistanceSq(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        if (d > 2.2 * 2.2) {
            moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.1D);
            if (navFails > 4 && d < 14.0 * 14.0 && entity.world.isAirBlock(p) && entity.world.isAirBlock(p.up())) {
                // 狭い階段で経路が取れない時は、掘られた通路の中へ直接入る
                entity.setPositionAndUpdate(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                navFails = 0;
            } else if (navFails > 6) {
                // 掘削役が遠くで新しいトンネルを始めた等: 最新の所まで飛ばす
                logIndex = Math.max(logIndex + 1, log.end() - 8);
                navFails = 0;
            }
            return;
        }
        if (tick % 4 != 0) {
            return;
        }
        if (rails) {
            if (!placeRail(p)) {
                return; // レールが無い -- ここで待つ
            }
        } else {
            reinforce(log, logIndex, p);
            if (logIndex - lastTorchIndex >= TORCH_EVERY_STEPS) {
                AllyMaintenance.placeTorchFromInventory(entity);
                lastTorchIndex = logIndex;
            }
        }
        logIndex++;
    }

    private boolean needsFill(World w, BlockPos pos) {
        IBlockState s = w.getBlockState(pos);
        Block b = s.getBlock();
        return w.isAirBlock(pos) || s.getMaterial().isLiquid() || b instanceof BlockFalling;
    }

    /** 壁・床・天井の穴、流れ込む水・溶岩、落ちてくる砂利/砂を丸石等でふさぐ。 */
    private void reinforce(AllyTeamManager.TunnelLog log, int index, BlockPos p) {
        World w = entity.world;
        BlockPos prev = log.at(index - 1);
        BlockPos next = log.at(index + 1);
        Set<BlockPos> passage = new HashSet<BlockPos>();
        for (int i = index - 6; i <= index + 6; i++) {
            BlockPos q = log.at(i);
            if (q != null) {
                passage.add(q);
                passage.add(q.up());
                passage.add(q.up(2));
            }
        }
        int dx = 0;
        int dz = 0;
        BlockPos ref = next != null ? next : prev;
        if (ref != null) {
            dx = Integer.signum(ref.getX() - p.getX());
            dz = Integer.signum(ref.getZ() - p.getZ());
        }
        BlockPos[] sides = dx != 0 ? new BlockPos[] { p.north(), p.south(), p.up().north(), p.up().south() }
                : new BlockPos[] { p.east(), p.west(), p.up().east(), p.up().west() };
        java.util.List<BlockPos> targets = new java.util.ArrayList<BlockPos>();
        for (BlockPos s : sides) {
            targets.add(s);
        }
        targets.add(p.down());
        // 天井: 階段の上り下りで頭が通る所（p.up(2)）は、前後が同じ高さの時だけふさぐ
        boolean stair = (prev != null && prev.getY() != p.getY()) || (next != null && next.getY() != p.getY());
        targets.add(stair ? p.up(3) : p.up(2));
        for (BlockPos t : targets) {
            if (passage.contains(t) || !needsFill(w, t) || AllyAreas.isForbidden(w, t)) {
                continue;
            }
            // 地上に抜けている大きな空間（空が見える所）は無理にふさがない
            if (w.isAirBlock(t) && w.canSeeSky(t) && t.getY() > p.getY()) {
                continue;
            }
            ItemStack block = takeFillBlock(AllyTeamManager.leaderOf(entity));
            if (block.isEmpty()) {
                return;
            }
            Block b = Block.getBlockFromItem(block.getItem());
            w.setBlockState(t, b.getStateFromMeta(block.getMetadata()), 3);
            AllySkills.addXp(entity, AllySkills.Skill.BUILDING, 1);
            entity.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
        }
    }

    private static boolean isFillBlock(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        Block b = Block.getBlockFromItem(s.getItem());
        return b == Blocks.COBBLESTONE || b == Blocks.STONE || b == Blocks.DIRT || b == Blocks.NETHERRACK;
    }

    private ItemStack takeFillBlock(EntityFriendlyCreature leader) {
        InventoryBasic inv = AllyInventory.get(entity);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (isFillBlock(inv.getStackInSlot(i))) {
                return inv.decrStackSize(i, 1);
            }
        }
        // 掘削役が掘った丸石を分けてもらう
        if (leader != null) {
            EntityAIGatherResourceAlly task = bridge.getGatherTask(leader);
            if (task != null && task.shareFillBlocks(entity, 32) > 0) {
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    if (isFillBlock(inv.getStackInSlot(i))) {
                        return inv.decrStackSize(i, 1);
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 床が固ければレールを敷く。レールが無くて作れない時は false（待つ）。 */
    private boolean placeRail(BlockPos p) {
        World w = entity.world;
        if (!w.isAirBlock(p) || AllyAreas.isForbidden(w, p)) {
            return true; // 敷けない/既に何かある所は飛ばす
        }
        if (!w.getBlockState(p.down()).isSideSolid(w, p.down(), EnumFacing.UP)) {
            return true;
        }
        if (w.getTotalWorldTime() < railWaitUntil) {
            return false;
        }
        Item railItem = Item.getItemFromBlock(Blocks.RAIL);
        InventoryBasic inv = AllyInventory.get(entity);
        int slot = findSlot(inv, railItem);
        if (slot < 0 && craftRails(inv)) {
            slot = findSlot(inv, railItem);
        }
        if (slot < 0) {
            railWaitUntil = w.getTotalWorldTime() + 100;
            if (!railNotice) {
                railNotice = true;
                EntityPlayer owner = AllyAIUtil.resolveOwnerPlayer(entity, bridge);
                if (owner != null) {
                    owner.sendMessage(new TextComponentString("[" + entity.getName()
                            + "] レールが足りません。レールか、鉄インゴット6個＋棒（スニーク右クリックで持ち物へ）を渡してください。"));
                }
            }
            return false;
        }
        railNotice = false;
        inv.decrStackSize(slot, 1);
        w.setBlockState(p, Blocks.RAIL.getDefaultState(), 3);
        w.playSound(null, p, SoundEvents.BLOCK_METAL_PLACE, SoundCategory.BLOCKS, 0.6F, 1.0F);
        AllySkills.addXp(entity, AllySkills.Skill.BUILDING, 1);
        entity.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
        return true;
    }

    private static int findSlot(InventoryBasic inv, Item item) {
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (!inv.getStackInSlot(i).isEmpty() && inv.getStackInSlot(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static int count(InventoryBasic inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (!inv.getStackInSlot(i).isEmpty() && inv.getStackInSlot(i).getItem() == item) {
                n += inv.getStackInSlot(i).getCount();
            }
        }
        return n;
    }

    private static void take(InventoryBasic inv, Item item, int amount) {
        for (int i = 0; i < inv.getSizeInventory() && amount > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == item) {
                int t = Math.min(amount, s.getCount());
                inv.decrStackSize(i, t);
                amount -= t;
            }
        }
    }

    /** 鉄インゴット6＋棒1 → レール16（作業台と同じレシピ）。棒は板材から作る。 */
    private boolean craftRails(InventoryBasic inv) {
        if (count(inv, Items.IRON_INGOT) < 6) {
            return false;
        }
        if (count(inv, Items.STICK) < 1) {
            Item planks = Item.getItemFromBlock(Blocks.PLANKS);
            if (count(inv, planks) < 2) {
                return false;
            }
            take(inv, planks, 2);
            AllyInventory.insertOrDrop(entity, new ItemStack(Items.STICK, 4));
        }
        take(inv, Items.IRON_INGOT, 6);
        take(inv, Items.STICK, 1);
        AllyInventory.insertOrDrop(entity, new ItemStack(Blocks.RAIL, 16));
        return true;
    }
}
