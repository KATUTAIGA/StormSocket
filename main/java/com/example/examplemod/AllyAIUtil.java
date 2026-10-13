package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.ai.EntityAIWander;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * 味方AI全体で共有する小さなユーティリティ。
 */
public final class AllyAIUtil {

    /**
     * 「暇つぶし」系のバニラAI（うろつき・周囲を眺める）を移す優先度。
     * 作業系AI（採取4・建築4・クエスト3）より必ず下位になる値にする。
     */
    public static final int IDLE_TASK_PRIORITY = 8;

    private AllyAIUtil() {
    }

    /**
     * プレイヤーと同じ基準で「その道具でこのブロックを採取できるか」を判定する。
     *
     * <p>以前は {@code tool.canHarvestBlock(state)} だけを見ていたが、バニラの
     * {@code ItemAxe}/{@code ItemTool} はこれをオーバーライドしておらず、原木など
     * 「道具不要」素材に対しても false を返すため、斧では一切採取対象が見つからなかった。
     * プレイヤーの採取判定と同様に、まず素材が道具不要かを見て、次に道具側の
     * 判定、最後にブロックが要求する道具種別/採掘レベルを照合する。</p>
     */
    public static boolean canHarvestWith(ItemStack tool, IBlockState state) {
        if (state == null) {
            return false;
        }
        if (state.getMaterial().isToolNotRequired()) {
            return true;
        }
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        if (tool.canHarvestBlock(state)) {
            return true;
        }
        Block block = state.getBlock();
        String toolClass = block.getHarvestTool(state);
        if (toolClass == null) {
            return false;
        }
        int level = tool.getItem().getHarvestLevel(tool, toolClass, null, state);
        return level >= 0 && level >= block.getHarvestLevel(state);
    }

    /**
     * その味方の本当の持ち主プレイヤー。Engender 本体の {@code getOwner()} を最優先し、
     * 無ければ採取ブリッジが記録した「最後に道具を渡したプレイヤー」を使う。
     *
     * <p>以前は後者しか見ておらず、道具を手渡したことのない味方は全員
     * 「持ち主不明」扱いになっていた（護衛・配達・クエストデータ解決が全滅）。</p>
     */
    public static EntityPlayer resolveOwnerPlayer(EntityFriendlyCreature entity, EngenderGatheringBridge bridge) {
        EntityPlayer found = null;
        try {
            EntityLivingBase owner = entity.getOwner();
            if (owner instanceof EntityPlayer) {
                found = (EntityPlayer) owner;
            }
        } catch (Throwable ignored) {
            // 持ち主UUIDが壊れている等。フォールバックへ。
        }
        if (found == null && bridge != null) {
            found = bridge.getRecordedHandoffPlayer(entity);
        }
        if (found != null && found.isEntityAlive() && found.getEntityWorld() == entity.getEntityWorld()) {
            return found;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // [暗い所でたいまつをクラフトして置く]
    // ------------------------------------------------------------------

    /** この明るさ未満なら置く（敵が湧く明るさ）。 */
    private static final int DARK_LIGHT_LEVEL = 8;
    private static final int TORCH_CHECK_INTERVAL = 20;
    private static final int TORCH_SPACING = 6;
    private static final java.util.Map<EntityFriendlyCreature, Long> LAST_TORCH_CHECK =
            new java.util.WeakHashMap<EntityFriendlyCreature, Long>();

    /**
     * 暗い場所にいたら、持ち物（carried）からたいまつを1本置く。たいまつが無ければ
     * 石炭/木炭＋棒からクラフトする（棒は板材から、板材は原木から作る）。
     * 1秒に1回だけ判定し、近く（{@value #TORCH_SPACING}ブロック以内）に既にたいまつが
     * あれば置かない。毎Tick呼んでよい。
     */
    public static void placeTorchIfDark(EntityFriendlyCreature entity, List<ItemStack> carried) {
        net.minecraft.world.World world = entity.world;
        long now = world.getTotalWorldTime();
        Long last = LAST_TORCH_CHECK.get(entity);
        if (last != null && now - last < TORCH_CHECK_INTERVAL) {
            return;
        }
        LAST_TORCH_CHECK.put(entity, now);

        net.minecraft.util.math.BlockPos feet = new net.minecraft.util.math.BlockPos(entity);
        if (world.getLight(feet) >= DARK_LIGHT_LEVEL || !world.isAirBlock(feet)
                || !net.minecraft.init.Blocks.TORCH.canPlaceBlockAt(world, feet)) {
            return;
        }
        for (net.minecraft.util.math.BlockPos p : net.minecraft.util.math.BlockPos.getAllInBoxMutable(
                feet.add(-TORCH_SPACING, -2, -TORCH_SPACING), feet.add(TORCH_SPACING, 2, TORCH_SPACING))) {
            if (world.getBlockState(p).getBlock() == net.minecraft.init.Blocks.TORCH) {
                return;
            }
        }
        if (!ensureTorch(carried)) {
            return;
        }
        if (takeOne(carried, new ItemStack(net.minecraft.init.Blocks.TORCH))) {
            world.setBlockState(feet, net.minecraft.init.Blocks.TORCH.getDefaultState(), 3);
        }
    }

    /** たいまつを1本以上持っている状態にする。作れなければ false。 */
    private static boolean ensureTorch(List<ItemStack> carried) {
        ItemStack torch = new ItemStack(net.minecraft.init.Blocks.TORCH);
        if (count(carried, torch, false) > 0) {
            return true;
        }
        ItemStack coal = new ItemStack(net.minecraft.init.Items.COAL, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
        if (count(carried, coal, true) < 1) {
            return false;
        }
        ItemStack stick = new ItemStack(net.minecraft.init.Items.STICK);
        if (count(carried, stick, false) < 1) {
            // 板材2 → 棒4
            if (countOre(carried, "plankWood") < 2) {
                // 原木1 → 板材4
                ItemStack log = takeOneOre(carried, "logWood");
                if (log.isEmpty()) {
                    return false;
                }
                addTo(carried, new ItemStack(net.minecraft.init.Blocks.PLANKS, 4, 0));
            }
            if (!takeOneOre(carried, "plankWood").isEmpty() && !takeOneOre(carried, "plankWood").isEmpty()) {
                addTo(carried, new ItemStack(net.minecraft.init.Items.STICK, 4));
            } else {
                return false;
            }
        }
        if (takeOne(carried, coal) && takeOne(carried, stick)) {
            addTo(carried, new ItemStack(net.minecraft.init.Blocks.TORCH, 4));
            return true;
        }
        return false;
    }

    private static int count(List<ItemStack> list, ItemStack template, boolean anyMeta) {
        int n = 0;
        for (ItemStack s : list) {
            if (!s.isEmpty() && s.getItem() == template.getItem()
                    && (anyMeta || s.getMetadata() == template.getMetadata())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static boolean takeOne(List<ItemStack> list, ItemStack template) {
        boolean anyMeta = template.getMetadata() == net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE;
        for (ItemStack s : list) {
            if (!s.isEmpty() && s.getItem() == template.getItem()
                    && (anyMeta || s.getMetadata() == template.getMetadata())) {
                s.shrink(1);
                list.removeIf(ItemStack::isEmpty);
                return true;
            }
        }
        return false;
    }

    private static boolean hasOre(ItemStack stack, String ore) {
        if (stack.isEmpty()) {
            return false;
        }
        int id = net.minecraftforge.oredict.OreDictionary.getOreID(ore);
        for (int i : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack)) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    private static int countOre(List<ItemStack> list, String ore) {
        int n = 0;
        for (ItemStack s : list) {
            if (hasOre(s, ore)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static ItemStack takeOneOre(List<ItemStack> list, String ore) {
        for (ItemStack s : list) {
            if (hasOre(s, ore)) {
                ItemStack one = s.splitStack(1);
                list.removeIf(ItemStack::isEmpty);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void addTo(List<ItemStack> list, ItemStack stack) {
        for (ItemStack s : list) {
            if (ItemStack.areItemsEqual(s, stack) && ItemStack.areItemStackTagsEqual(s, stack)
                    && s.getCount() < s.getMaxStackSize()) {
                int move = Math.min(s.getMaxStackSize() - s.getCount(), stack.getCount());
                s.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
        if (!stack.isEmpty()) {
            list.add(stack);
        }
    }

    /** 銃を持っている間、本体の近接攻撃AIを退避させておく優先度と、元の優先度の記録。 */
    private static final int SUPPRESSED_MELEE_PRIORITY = 9;
    private static final java.util.Map<EntityFriendlyCreature, java.util.Map<EntityAIBase, Integer>> SUPPRESSED_MELEE =
            new java.util.WeakHashMap<EntityFriendlyCreature, java.util.Map<EntityAIBase, Integer>>();

    /**
     * [銃を撃たない不具合の修正] Engender の味方は自前の近接攻撃AI
     * （{@code EntityAIFriendlyAttackMelee}）を優先度2で最初に登録している。
     * 銃AI（Techguns/Flan's/HBM）も優先度2で後から追加されるため、バニラの
     * EntityAITasks の規則（同じ優先度の実行中タスクは割り込めない）により、
     * 敵が現れると毎回近接AIが先に起動し、銃AIは戦闘中ずっと起動できなかった
     * （＝銃を持っているのに殴りに行く／撃たない）。遠距離武器を持っている間だけ
     * 本体の近接AIを下位へ退避させ、持ち替えたら元に戻す。毎Tick呼んでよい（冪等）。
     */
    public static void updateMeleeSuppression(EntityFriendlyCreature entity, boolean holdingRangedGun) {
        java.util.Map<EntityAIBase, Integer> moved = SUPPRESSED_MELEE.get(entity);
        if (holdingRangedGun) {
            if (moved != null) {
                return;
            }
            moved = new java.util.HashMap<EntityAIBase, Integer>();
            for (EntityAITasks.EntityAITaskEntry entry : entity.tasks.taskEntries) {
                if (entry.priority < SUPPRESSED_MELEE_PRIORITY && isBaseMeleeTask(entry.action)) {
                    moved.put(entry.action, entry.priority);
                }
            }
            for (java.util.Map.Entry<EntityAIBase, Integer> e : moved.entrySet()) {
                entity.tasks.removeTask(e.getKey());
                entity.tasks.addTask(SUPPRESSED_MELEE_PRIORITY, e.getKey());
            }
            SUPPRESSED_MELEE.put(entity, moved);
        } else if (moved != null) {
            for (java.util.Map.Entry<EntityAIBase, Integer> e : moved.entrySet()) {
                entity.tasks.removeTask(e.getKey());
                entity.tasks.addTask(e.getValue(), e.getKey());
            }
            SUPPRESSED_MELEE.remove(entity);
        }
    }

    private static boolean isBaseMeleeTask(EntityAIBase action) {
        if (action instanceof net.minecraft.entity.ai.EntityAIAttackMelee) {
            return true;
        }
        String name = action.getClass().getName();
        return name.startsWith("net.minecraft.entity.helpful") && name.contains("AttackMelee");
    }

    /**
     * 基底Mob/サブクラスが登録しているバニラの「うろつき」「周囲を見る」AIを、
     * 作業系AIより下位の優先度へ付け替える。
     *
     * <p>例えば tier3 の味方は {@code EntityAIWander} を優先度3で持っており、
     * バニラの EntityAITasks の規則上、優先度4の採取/建築AIや優先度6だった
     * クエストAIを数秒～十数秒おきに勝手に中断させ、進捗をリセットしていた。</p>
     */
    public static void demoteIdleTasks(EntityFriendlyCreature entity) {
        EntityAITasks tasks = entity.tasks;
        List<EntityAIBase> toMove = new ArrayList<EntityAIBase>();
        for (EntityAITasks.EntityAITaskEntry entry : tasks.taskEntries) {
            if (entry.priority >= IDLE_TASK_PRIORITY) {
                continue;
            }
            EntityAIBase action = entry.action;
            if (action instanceof EntityAIWander
                    || action instanceof EntityAIWatchClosest
                    || action instanceof EntityAILookIdle) {
                toMove.add(action);
            }
        }
        for (EntityAIBase action : toMove) {
            tasks.removeTask(action);
            tasks.addTask(IDLE_TASK_PRIORITY, action);
        }
    }

    // ------------------------------------------------------------------
    // [詰まり対策] 花・草・雪・クモの巣などに引っかかって動けない時
    // ------------------------------------------------------------------

    /** 通り抜けられる（当たり判定の無い）ブロックか。空気も含む。 */
    public static boolean isPassable(net.minecraft.world.World w, net.minecraft.util.math.BlockPos p) {
        net.minecraft.block.state.IBlockState s = w.getBlockState(p);
        return w.isAirBlock(p) || (!s.getMaterial().isLiquid() && s.getCollisionBoundingBox(w, p) == null);
    }

    /** 足元・目の前にあって片付けてよい物（花・草・枯れ木・雪・ツタ・クモの巣。作物と苗木は除く）。 */
    public static boolean isPassableJunk(net.minecraft.block.state.IBlockState s) {
        net.minecraft.block.Block b = s.getBlock();
        if (b instanceof net.minecraft.block.BlockCrops || b instanceof net.minecraft.block.BlockSapling
                || b instanceof net.minecraft.block.BlockStem) {
            return false;
        }
        return b instanceof net.minecraft.block.BlockBush || b instanceof net.minecraft.block.BlockSnow
                || b instanceof net.minecraft.block.BlockVine || b == net.minecraft.init.Blocks.WEB
                || b instanceof net.minecraft.block.BlockLeaves;
    }

    /**
     * 動けなくなった時の脱出: 足元・頭・進行方向の草花や葉を片付け、目の前の1段は
     * 柔らかい物（土・砂・草）なら崩し、ジャンプする。何か片付けたら true。
     * 落ちた物は sink へ（null なら落としたまま）。
     */
    public static boolean unstick(EntityFriendlyCreature e, List<ItemStack> sink) {
        return unstick(e, sink, true);
    }

    /** allowDig=false の時は、土・砂の段差は崩さない（整地の仕上がりを傷めない）。 */
    public static boolean unstick(EntityFriendlyCreature e, List<ItemStack> sink, boolean allowDig) {
        net.minecraft.world.World w = e.world;
        net.minecraft.util.math.BlockPos feet = new net.minecraft.util.math.BlockPos(e);
        net.minecraft.util.EnumFacing f = e.getHorizontalFacing();
        boolean acted = false;
        net.minecraft.util.math.BlockPos[] around = { feet, feet.up(), feet.offset(f), feet.offset(f).up(),
                feet.offset(f.rotateY()), feet.offset(f.rotateYCCW()) };
        for (net.minecraft.util.math.BlockPos p : around) {
            net.minecraft.block.state.IBlockState s = w.getBlockState(p);
            if (w.isAirBlock(p) || !isPassableJunk(s) || w.getTileEntity(p) != null || AllyAreas.isForbidden(w, p)) {
                continue;
            }
            if (sink != null) {
                for (ItemStack d : s.getBlock().getDrops(w, p, s, 0)) {
                    if (d != null && !d.isEmpty()) {
                        sink.add(d);
                    }
                }
            }
            w.setBlockToAir(p);
            acted = true;
        }
        // 目の前の頭の高さが柔らかい物でふさがっていたら崩す（足元の段差は跳べば越えられる）
        net.minecraft.util.math.BlockPos head = feet.offset(f).up();
        net.minecraft.block.state.IBlockState hs = w.getBlockState(head);
        net.minecraft.block.material.Material m = hs.getMaterial();
        if (allowDig && !w.isAirBlock(head) && w.getTileEntity(head) == null && !AllyAreas.isForbidden(w, head)
                && (m == net.minecraft.block.material.Material.GROUND || m == net.minecraft.block.material.Material.GRASS
                || m == net.minecraft.block.material.Material.SAND || m == net.minecraft.block.material.Material.LEAVES)
                && isPassable(w, feet.offset(f).up(2))) {
            if (sink != null) {
                for (ItemStack d : hs.getBlock().getDrops(w, head, hs, 0)) {
                    if (d != null && !d.isEmpty()) {
                        sink.add(d);
                    }
                }
            }
            w.setBlockToAir(head);
            acted = true;
        }
        e.getJumpHelper().setJumping();
        return acted;
    }
}
