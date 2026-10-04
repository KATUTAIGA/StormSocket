package com.example.examplemod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.feed_the_beast.ftbquests.quest.QuestData;
import com.feed_the_beast.ftbquests.quest.ServerQuestFile;
import com.feed_the_beast.ftbquests.quest.Chapter;
import com.feed_the_beast.ftbquests.quest.Quest;
import com.feed_the_beast.ftbquests.quest.task.FluidTask;
import com.feed_the_beast.ftbquests.quest.task.ItemTask;
import com.feed_the_beast.ftbquests.quest.task.Task;
import com.feed_the_beast.ftbquests.quest.task.TaskData;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.oredict.OreDictionary;

/**
 * GregTech Quantum Transition (FTB Quests) 対応: 未達成クエストが要求するアイテムを、
 * 採掘 → かまど / 作業台 / GregTech 機械加工（全レシピマップ）の手順を自動で組み立てて
 * 生産し、クエストへ納品する味方AI。
 *
 * <p>「何をどう作るか」は {@link QuestRecipeResolver} が全レシピから前計算した
 * 生産手順（必ず循環しない）に従う。GregTech の加工は機械・電力なしの「仮想加工」で、
 * 代わりにレシピの処理時間ぶん作業する。液体（水は無限、その他はGTレシピで生成）も扱う。
 * 地下深くの鉱石へは階段状にトンネルを掘って向かう。</p>
 *
 * <p>前提クエスト未達成のクエストには手を出さないので、パックの進行順は守られる。</p>
 */
public class EntityAIQuestSupplyAlly extends EntityAIBase {

    private static final int RESCAN_COOLDOWN_TICKS = 100;
    private static final int UNRESOLVABLE_SKIP_TICKS = 1200;
    private static final int TASK_TIME_LIMIT_TICKS = 24000; // 1タスクに最大約20分

    private static final int SEARCH_RADIUS_HORIZONTAL = 24;
    private static final int SEARCH_UP = 16;
    private static final int SEARCH_DOWN = 56;

    private static final double REACH_SQ = 4.5 * 4.5;
    private static final double MOVE_SPEED = 1.0;
    private static final int MINE_TICKS_REQUIRED = 25;
    private static final int NAV_FAIL_LIMIT = 10;
    private static final int GOTO_TIMEOUT_TICKS = 300;
    private static final int TUNNEL_STEP_TICKS = 6;
    private static final int MAX_TUNNEL_STEPS = 120;
    private static final int MAX_GOAL_STACK_SIZE = 256;
    private static final int MAX_PROCESS_ATTEMPTS = 6;
    private static final int MAX_CARRIED_TOTAL_ITEMS = 1024;
    private static final int MAX_FRUITLESS_MINES = 4;
    private static final double OWNER_SEARCH_RANGE = 64.0;
    private static final int MAX_TASKS_EXAMINED_PER_SCAN = 40;

    private enum Phase { FIND_TASK, RESOLVE, GOTO_BLOCK, TUNNEL, MINE, PROCESS_WAIT, SUBMIT, MACHINE_GOTO, MACHINE_WAIT, WAIT_SUPPLY }

    private static final class ItemGoal {
        ItemStack[] accepted;
        final int count;
        ItemGoal(ItemStack[] accepted, int count) { this.accepted = accepted; this.count = count; }
    }

    private static final class FluidGoal {
        final String fluid;
        final int amount;
        FluidGoal(String fluid, int amount) { this.fluid = fluid; this.amount = amount; }
    }

    private static final class ProcessGoal {
        final QuestRecipeResolver.ProcRecipe recipe;
        int attempts;
        /** 実機で失敗した → 仮想加工で進める。 */
        boolean forceVirtual;
        ProcessGoal(QuestRecipeResolver.ProcRecipe recipe) { this.recipe = recipe; }
    }

    private final EntityFriendlyCreature entity;
    private final EngenderGatheringBridge bridge;

    private Phase phase = Phase.FIND_TASK;
    private int cooldown = 40;

    private ItemTask targetTask;
    /** [液体タスク] FTB Quests の液体納品タスク。 */
    private FluidTask targetFluidTask;
    private QuestData targetData;
    private long taskStartTime;

    private final Deque<Object> goalStack = new ArrayDeque<Object>();
    private final List<ItemStack> carried = new ArrayList<ItemStack>();
    private final Map<String, Integer> carriedFluids = new HashMap<String, Integer>();
    private final Map<Task, Long> unresolvableUntil = new HashMap<Task, Long>();
    private int taskScanCursor;

    private QuestRecipeResolver.Closure closure;

    private Block mineTargetBlock;
    private BlockPos mineTargetPos;
    private ItemGoal mineGoal;
    private int mineProgress;
    private int navFailStreak;
    private int gotoTicks;
    private int fruitlessMines;
    private int tunnelSteps;
    private int tunnelCooldown;
    private int processWait;

    public EntityAIQuestSupplyAlly(EntityFriendlyCreature entity, EngenderGatheringBridge bridge) {
        this.entity = entity;
        this.bridge = bridge;
        this.setMutexBits(3);
    }

    // ------------------------------------------------------------------
    // EntityAIBase
    // ------------------------------------------------------------------

    @Override
    public boolean shouldExecute() {
        if (!entity.isEntityAlive() || entity.world == null || entity.world.isRemote || entity.isRiding()) {
            return false;
        }
        // 計画器は待機中も毎回少しずつ進める（1ワールドTickに1回まで）。
        closure = QuestRecipeResolver.work(QuestRecipeResolver.effectiveLevel(pickaxeLevel()), now());
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        // [GTQTのクエスト最優先] 採取・建築中でも1秒ごとに未達成クエストを探し、
        // 見つかれば優先度の差で採取/建築AIから制御を奪う（戦闘・生存だけがこれより上）。
        cooldown = 20;
        if (!closure.isDone()) {
            return false;
        }
        // [並列分散] 斧/ツルハシの仕事の味方は、近くにクラフト役（仕事の無い味方）がいれば
        // 自分でクエストを抱えず、掲示板の発注に従って素材を集めて届ける。
        if (isSupplierWhileCrafterNearby()) {
            return false;
        }
        // [/allycraft] 持ち主から直接頼まれた物づくりを先に
        if (startManualRequest()) {
            return true;
        }
        if (ServerQuestFile.INSTANCE == null) {
            return false;
        }
        return findWorkableTask(ServerQuestFile.INSTANCE);
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (manualActive && AllyCraftRequests.get(entity) != manualRequest) {
            return false; // /allycraft cancel された
        }
        return entity.isEntityAlive() && !entity.isRiding() && (targetTask != null || targetFluidTask != null || manualActive);
    }

    @Override
    public void resetTask() {
        recoverMachine();
        AllyProjectBoard.finish(AllyAreas.ownerId(entity), entity);
        // 集めた途中の素材・作った道具は捨てず、味方の持ち物へしまう（次のタスクで取り出して使う）。
        for (ItemStack stack : carried) {
            if (!stack.isEmpty()) {
                AllyInventory.insertOrDrop(entity, stack);
            }
        }
        releaseQuestClaim();
        carried.clear();
        targetTask = null;
        targetFluidTask = null;
        targetData = null;
        manualActive = false;
        goalStack.clear();
        mineTargetBlock = null;
        mineTargetPos = null;
        mineGoal = null;
        phase = Phase.FIND_TASK;
        entity.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        if (now() - taskStartTime > TASK_TIME_LIMIT_TICKS) {
            abortCurrentTask();
            return;
        }
        if (phase == Phase.GOTO_BLOCK || phase == Phase.TUNNEL || phase == Phase.MINE) {
            AllyAIUtil.placeTorchIfDark(entity, carried);
        }
        switch (phase) {
            case RESOLVE:
                tickResolve();
                break;
            case GOTO_BLOCK:
                tickGotoBlock();
                break;
            case TUNNEL:
                tickTunnel();
                break;
            case MINE:
                tickMine();
                break;
            case PROCESS_WAIT:
                if (--processWait <= 0) {
                    phase = Phase.RESOLVE;
                } else if (processWait % 10 == 0) {
                    entity.swingArm(EnumHand.MAIN_HAND);
                }
                break;
            case SUBMIT:
                tickSubmit();
                break;
            case MACHINE_GOTO:
                tickMachineGoto();
                break;
            case MACHINE_WAIT:
                tickMachineWait();
                break;
            case WAIT_SUPPLY:
                tickWaitSupply();
                break;
            default:
                targetTask = null;
                targetFluidTask = null;
                manualActive = false;
                break;
        }
    }

    private long now() {
        return entity.world.getTotalWorldTime();
    }

    /**
     * 計画に使う採掘レベル。手・持ち物・集めた物の中の一番強いツルハシ。
     * 持っていなくても、素手で原木→作業台→木のツルハシが作れるので最低 0。
     */
    private int pickaxeLevel() {
        try {
            return Math.max(0, AllyToolManager.bestLevel(entity, "pickaxe", carried));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // タスク探索
    // ------------------------------------------------------------------

    private EntityPlayer resolveQuestPlayer() {
        EntityPlayer owner = bridge != null ? bridge.getOwnerPlayer(entity) : AllyAIUtil.resolveOwnerPlayer(entity, null);
        return owner != null ? owner : entity.world.getClosestPlayerToEntity(entity, OWNER_SEARCH_RANGE);
    }

    // ------------------------------------------------------------------
    // [Quest Auto-Scanner] 解放済み・未達成のクエストを優先度順に自動抽出
    // ------------------------------------------------------------------

    /** 複数の味方が同じクエストタスクを取り合わないための予約（タスクID → 味方UUID, 期限）。 */
    private static final Map<String, Object[]> QUEST_CLAIMS = new HashMap<String, Object[]>();
    private String claimedTaskKey;

    /** チーム（持ち主）ごとに別々に予約する。 */
    private String claimKey(Task task) {
        java.util.UUID owner = AllyAreas.ownerId(entity);
        return (owner == null ? "?" : owner.toString()) + ":" + task.id;
    }

    private boolean claimQuestTask(Task task) {
        synchronized (QUEST_CLAIMS) {
            String k = claimKey(task);
            Object[] c = QUEST_CLAIMS.get(k);
            long t = now();
            if (c != null && !entity.getUniqueID().equals(c[0]) && (Long) c[1] > t) {
                return false;
            }
            QUEST_CLAIMS.put(k, new Object[] { entity.getUniqueID(), t + TASK_TIME_LIMIT_TICKS });
            claimedTaskKey = k;
            return true;
        }
    }

    private boolean isQuestClaimedByOther(Task task) {
        synchronized (QUEST_CLAIMS) {
            Object[] c = QUEST_CLAIMS.get(claimKey(task));
            return c != null && !entity.getUniqueID().equals(c[0]) && (Long) c[1] > now();
        }
    }

    private void releaseQuestClaim() {
        if (claimedTaskKey == null) {
            return;
        }
        synchronized (QUEST_CLAIMS) {
            Object[] c = QUEST_CLAIMS.get(claimedTaskKey);
            if (c != null && entity.getUniqueID().equals(c[0])) {
                QUEST_CLAIMS.remove(claimedTaskKey);
            }
        }
        claimedTaskKey = null;
    }

    /**
     * 解放済み・未達成のアイテム/液体タスクを優先度順に並べる:
     * 章の並び順 → 前提クエストの少ない物（ツリーの根元）→ 章の中の位置（上・左から）。
     * 「機械を持っているか」を確かめるだけのタスク（消費しないアイテムタスク）も含む。
     */
    private List<Task> orderedCandidates(ServerQuestFile file, QuestData data) {
        List<Task> out = new ArrayList<Task>();
        List<Quest> quests = new ArrayList<Quest>();
        final Map<Quest, Integer> chapterIndex = new HashMap<Quest, Integer>();
        int ci = 0;
        for (Chapter chapter : file.chapters) {
            for (Quest q : chapter.quests) {
                try {
                    if (q.invalid || q.isComplete(data) || !q.canStartTasks(data)) {
                        continue;
                    }
                } catch (Throwable t) {
                    continue;
                }
                quests.add(q);
                chapterIndex.put(q, ci);
            }
            ci++;
        }
        java.util.Collections.sort(quests, new java.util.Comparator<Quest>() {
            @Override
            public int compare(Quest a, Quest b) {
                int c = Integer.compare(chapterIndex.get(a), chapterIndex.get(b));
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.dependencies.size(), b.dependencies.size());
                if (c != 0) {
                    return c;
                }
                c = Double.compare(a.y, b.y);
                return c != 0 ? c : Double.compare(a.x, b.x);
            }
        });
        for (Quest q : quests) {
            for (Task t : q.tasks) {
                if (t instanceof ItemTask || t instanceof FluidTask) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private boolean findWorkableTask(ServerQuestFile file) {
        EntityPlayer player = resolveQuestPlayer();
        if (player == null) {
            return false;
        }
        QuestData data;
        try {
            data = file.getData(player);
        } catch (Throwable t) {
            return false;
        }
        if (data == null) {
            return false;
        }
        List<Task> tasks = orderedCandidates(file, data);
        long time = now();
        int examined = 0;
        for (Task task : tasks) {
            if (examined >= MAX_TASKS_EXAMINED_PER_SCAN) {
                break;
            }
            Long skipUntil = unresolvableUntil.get(task);
            if (skipUntil != null && time < skipUntil) {
                continue;
            }
            if (task.invalid || isQuestClaimedByOther(task)) {
                continue;
            }
            TaskData taskData;
            try {
                taskData = data.getTaskData(task);
            } catch (Throwable t) {
                continue;
            }
            if (taskData == null || taskData.isComplete()) {
                continue;
            }
            long remaining = task.getMaxProgress() - taskData.progress;
            if (remaining <= 0) {
                continue;
            }
            examined++;

            if (task instanceof FluidTask) {
                FluidTask ft = (FluidTask) task;
                if (ft.fluid == null) {
                    unresolvableUntil.put(task, time + UNRESOLVABLE_SKIP_TICKS);
                    continue;
                }
                String name = ft.fluid.getName();
                if ("water".equals(name) || closure.isFluidObtainable(name)) {
                    if (!claimQuestTask(task)) {
                        continue;
                    }
                    targetFluidTask = ft;
                    targetTask = null;
                    targetData = data;
                    taskStartTime = time;
                    goalStack.clear();
                    goalStack.push(new FluidGoal(name, (int) Math.min(remaining, 16000L)));
                    phase = Phase.RESOLVE;
                    announceTask(player, task, null, (int) Math.min(remaining, 16000L));
                    return true;
                }
                unresolvableUntil.put(task, time + UNRESOLVABLE_SKIP_TICKS);
                continue;
            }

            ItemTask itemTask = (ItemTask) task;
            List<ItemStack> valid;
            try {
                valid = itemTask.getValidItems();
            } catch (Throwable t) {
                valid = null;
            }
            if (valid == null || valid.isEmpty()) {
                unresolvableUntil.put(task, time + UNRESOLVABLE_SKIP_TICKS);
                continue;
            }
            ItemStack[] accepted = valid.toArray(new ItemStack[0]);
            QuestRecipeResolver.Key key = closure.bestAccepted(accepted);
            if (countMatching(accepted) + countInInventory(accepted) > 0 || key != null) {
                if (!claimQuestTask(task)) {
                    continue;
                }
                pullFromInventory(accepted, (int) Math.min(remaining, 64));
                targetTask = itemTask;
                targetFluidTask = null;
                targetData = data;
                taskStartTime = time;
                int wantNow = (int) Math.max(1L, Math.min(remaining, 16L));
                int goalCount = Math.min(countMatching(accepted) + wantNow, (int) Math.min(remaining, Integer.MAX_VALUE));
                goalStack.clear();
                goalStack.push(new ItemGoal(accepted, goalCount));
                phase = Phase.RESOLVE;
                announceTask(player, task, key, goalCount);
                return true;
            }
            unresolvableUntil.put(task, time + UNRESOLVABLE_SKIP_TICKS);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 生産手順の実行
    // ------------------------------------------------------------------

    private void tickResolve() {
        if (carriedItemCount() >= MAX_CARRIED_TOTAL_ITEMS) {
            handOverLeftovers();
        }
        if (goalStack.isEmpty()) {
            phase = Phase.SUBMIT;
            return;
        }
        if (goalStack.size() > MAX_GOAL_STACK_SIZE) {
            abortCurrentTask();
            return;
        }
        Object top = goalStack.peek();

        if (top instanceof ProcessGoal) {
            goalStack.pop();
            performProcess((ProcessGoal) top);
            return;
        }

        if (top instanceof FluidGoal) {
            FluidGoal fg = (FluidGoal) top;
            if (fluidAmount(fg.fluid) >= fg.amount) {
                goalStack.pop();
                return;
            }
            if ("water".equals(fg.fluid)) {
                addFluid("water", fg.amount - fluidAmount("water")); // 水はどこでも汲める
                goalStack.pop();
                return;
            }
            QuestRecipeResolver.ProcRecipe r = closure.fluidProducer.get(fg.fluid);
            if (r == null) {
                abortCurrentTask();
                return;
            }
            pushRecipe(r);
            return;
        }

        ItemGoal goal = (ItemGoal) top;
        // まず自分の持ち物に入っている分を使う
        pullFromInventory(goal.accepted, goal.count - countMatching(goal.accepted));
        if (countMatching(goal.accepted) >= goal.count) {
            goalStack.pop();
            return;
        }
        QuestRecipeResolver.Key key = closure.bestAccepted(goal.accepted);
        if (key == null) {
            abortCurrentTask();
            return;
        }
        Block block = closure.mineBlock.get(key);
        if (block != null && !AllyToolManager.equipForBlock(entity, block.getDefaultState(), carried)) {
            // 掘るのに道具が要る（石・鉱石など）→ 先にその道具を作る（木/石のツルハシ等）
            ItemStack toolNeeded = basicToolFor(block.getDefaultState());
            if (toolNeeded != null && closure.bestAccepted(new ItemStack[] { toolNeeded }) != null) {
                goalStack.push(new ItemGoal(new ItemStack[] { toolNeeded }, 1));
                return;
            }
            block = null; // 掘れない → クラフト等の手段へ
        }
        if (block != null && shouldWaitForSupply(block)) {
            supplyGoal = goal;
            supplyBlock = block;
            supplyWait = 0;
            supplyLastCount = countMatching(goal.accepted);
            phase = Phase.WAIT_SUPPLY;
            return;
        }
        if (block != null) {
            BlockPos pos = findNearestMatchingBlock(block);
            if (pos != null) {
                TargetRegistry.claim(entity, pos, 1200);
                mineTargetBlock = block;
                mineTargetPos = pos;
                mineGoal = goal;
                mineProgress = 0;
                navFailStreak = 0;
                gotoTicks = 0;
                fruitlessMines = 0;
                phase = Phase.GOTO_BLOCK;
                return;
            }
            // 近くに無ければ、作れるなら作る（下へ）
        }
        QuestRecipeResolver.ProcRecipe recipe = closure.producer.get(key);
        if (recipe == null) {
            abortCurrentTask();
            return;
        }
        pushRecipe(recipe);
    }

    /** ProcessGoal を積み、その上に足りない材料の目標を積む（材料が先に処理される）。 */
    private void pushRecipe(QuestRecipeResolver.ProcRecipe recipe) {
        ProcessGoal pg = new ProcessGoal(recipe);
        goalStack.push(pg);
        pushMissingInputs(recipe);
        // 3x3 のクラフト（材料5個以上）には作業台が要る。持っていなければ先に作る。
        if ("crafting".equals(recipe.source) && totalConsumed(recipe) > 4) {
            ItemStack[] table = { new ItemStack(Blocks.CRAFTING_TABLE) };
            pullFromInventory(table, 1);
            if (countMatching(table) < 1 && closure.bestAccepted(table) != null) {
                goalStack.push(new ItemGoal(table, 1));
            }
        }
        // 精錬にはかまどが要る。持っていなければ先に作る（丸石8個）。
        if ("furnace".equals(recipe.source)) {
            ItemStack[] furnace = { new ItemStack(Blocks.FURNACE) };
            pullFromInventory(furnace, 1);
            if (countMatching(furnace) < 1 && closure.bestAccepted(furnace) != null) {
                goalStack.push(new ItemGoal(furnace, 1));
            }
        }
    }

    /** かまどの燃料（石炭・木炭・原木・板材）。 */
    private static final ItemStack[] FUELS = {
            new ItemStack(net.minecraft.init.Items.COAL, 1, 0), new ItemStack(net.minecraft.init.Items.COAL, 1, 1),
            new ItemStack(Blocks.LOG, 1, 0), new ItemStack(Blocks.LOG, 1, 1), new ItemStack(Blocks.LOG, 1, 2),
            new ItemStack(Blocks.LOG, 1, 3), new ItemStack(Blocks.LOG2, 1, 0), new ItemStack(Blocks.LOG2, 1, 1),
            new ItemStack(Blocks.PLANKS, 1, 0), new ItemStack(Blocks.PLANKS, 1, 1), new ItemStack(Blocks.PLANKS, 1, 2),
            new ItemStack(Blocks.PLANKS, 1, 3), new ItemStack(Blocks.PLANKS, 1, 4), new ItemStack(Blocks.PLANKS, 1, 5) };

    /** あと何回精錬できるだけの燃料が入っているか。 */
    private int fuelSmelts;

    /** 燃料を1つ燃やす。燃やせたら true。 */
    private boolean burnFuel() {
        pullFromInventory(FUELS, 1);
        for (ItemStack stack : carried) {
            if (stack.isEmpty() || !acceptsAny(FUELS, stack)) {
                continue;
            }
            int burn = net.minecraft.tileentity.TileEntityFurnace.getItemBurnTime(stack);
            if (burn <= 0) {
                continue;
            }
            stack.shrink(1);
            carried.removeIf(ItemStack::isEmpty);
            fuelSmelts += Math.max(1, burn / 200);
            return true;
        }
        return false;
    }

    private static int totalConsumed(QuestRecipeResolver.ProcRecipe r) {
        int n = 0;
        for (QuestRecipeResolver.Input in : r.inputs) {
            n += in.count;
        }
        return n;
    }

    /** 採掘に必要な最低限の道具（木/石/鉄/ダイヤのツルハシ、木の斧・シャベル）。 */
    private static ItemStack basicToolFor(IBlockState state) {
        String cls = state.getBlock().getHarvestTool(state);
        int level = state.getBlock().getHarvestLevel(state);
        if ("pickaxe".equals(cls)) {
            if (level <= 0) {
                return new ItemStack(net.minecraft.init.Items.WOODEN_PICKAXE);
            } else if (level == 1) {
                return new ItemStack(net.minecraft.init.Items.STONE_PICKAXE);
            } else if (level == 2) {
                return new ItemStack(net.minecraft.init.Items.IRON_PICKAXE);
            } else if (level == 3) {
                return new ItemStack(net.minecraft.init.Items.DIAMOND_PICKAXE);
            }
            return null;
        }
        if ("axe".equals(cls)) {
            return new ItemStack(net.minecraft.init.Items.WOODEN_AXE);
        }
        if ("shovel".equals(cls)) {
            return new ItemStack(net.minecraft.init.Items.WOODEN_SHOVEL);
        }
        return null;
    }

    /** 持ち物（AllyInventory）に accepted に合う物が何個あるか（移さずに数えるだけ）。 */
    private int countInInventory(ItemStack[] accepted) {
        net.minecraft.inventory.InventoryBasic inv = AllyInventory.get(entity);
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && acceptsAny(accepted, s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 持ち物から、accepted に合う物を最大 amount 個 carried へ移す。 */
    private void pullFromInventory(ItemStack[] accepted, int amount) {
        if (amount <= 0) {
            return;
        }
        net.minecraft.inventory.InventoryBasic inv = AllyInventory.get(entity);
        for (int i = 0; i < inv.getSizeInventory() && amount > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !acceptsAny(accepted, s)) {
                continue;
            }
            int take = Math.min(amount, s.getCount());
            addToCarried(inv.decrStackSize(i, take));
            amount -= take;
        }
    }

    private void pushMissingInputs(QuestRecipeResolver.ProcRecipe recipe) {
        for (QuestRecipeResolver.FluidAmount f : recipe.fluidInputs) {
            if (fluidAmount(f.fluid) < f.amount) {
                goalStack.push(new FluidGoal(f.fluid, f.amount));
            }
        }
        for (QuestRecipeResolver.Input in : recipe.inputs) {
            if (!in.consumed) {
                continue;
            }
            if (countMatching(in.accepted) < in.count) {
                goalStack.push(new ItemGoal(in.accepted, in.count));
            }
        }
    }

    private void performProcess(ProcessGoal pg) {
        QuestRecipeResolver.ProcRecipe r = pg.recipe;
        if ("furnace".equals(r.source) && !(!pg.forceVirtual && findFurnace() != null) && fuelSmelts <= 0 && !burnFuel()) {
            // 燃料が無い -- 原木か石炭を先に取ってくる
            if (++pg.attempts > MAX_PROCESS_ATTEMPTS || closure.bestAccepted(FUELS) == null) {
                abortCurrentTask();
                return;
            }
            goalStack.push(pg);
            goalStack.push(new ItemGoal(FUELS, countMatching(FUELS) + 1));
            return;
        }
        boolean missing = false;
        for (QuestRecipeResolver.Input in : r.inputs) {
            if (in.consumed && countMatching(in.accepted) < in.count) {
                missing = true;
            }
        }
        for (QuestRecipeResolver.FluidAmount f : r.fluidInputs) {
            if (fluidAmount(f.fluid) < f.amount) {
                missing = true;
            }
        }
        if (missing) {
            // 他の手順が材料を先に使ってしまった等。取り直す（回数制限付き）。
            if (++pg.attempts > MAX_PROCESS_ATTEMPTS) {
                abortCurrentTask();
                return;
            }
            goalStack.push(pg);
            pushMissingInputs(r);
            return;
        }
        // [実機ハイブリッド] 近くに実機（かまど・GT機械）があれば、実際に搬入して加工する
        if (!pg.forceVirtual && tryStartReal(pg)) {
            return;
        }
        for (QuestRecipeResolver.Input in : r.inputs) {
            if (in.consumed) {
                consumeMatching(in, in.count);
            }
        }
        for (QuestRecipeResolver.FluidAmount f : r.fluidInputs) {
            addFluid(f.fluid, -f.amount);
        }
        for (ItemStack out : r.outputs) {
            addToCarried(out.copy());
        }
        for (QuestRecipeResolver.FluidAmount f : r.fluidOutputs) {
            addFluid(f.fluid, f.amount);
        }
        if ("furnace".equals(r.source)) {
            fuelSmelts--;
        }
        AllySkills.addXp(entity, AllySkills.Skill.BUILDING, 1);
        // 実際の機械/作業の時間ぶん手を動かす（GTの長いレシピは最大5秒で打ち切り）
        processWait = Math.max(5, Math.min(100, r.durationTicks / 5));
        phase = Phase.PROCESS_WAIT;
    }

    // ------------------------------------------------------------------
    // 採掘（トンネル掘り対応）
    // ------------------------------------------------------------------

    private double gotoBestDistSq = Double.MAX_VALUE;
    private int gotoNoProgress;
    private int tunnelLastDx;
    private int tunnelLastDz;

    private void tickGotoBlock() {
        if (mineTargetPos == null || entity.world.getBlockState(mineTargetPos).getBlock() != mineTargetBlock) {
            phase = Phase.RESOLVE;
            return;
        }
        double cx = mineTargetPos.getX() + 0.5;
        double cy = mineTargetPos.getY() + 0.5;
        double cz = mineTargetPos.getZ() + 0.5;
        entity.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
        double distSq = entity.getDistanceSq(cx, cy, cz);
        if (distSq <= REACH_SQ) {
            entity.getNavigator().clearPath();
            // 石の向こうの鉱石は壁越しに掘らず、視線上の石を掘ってから（TUNNEL が処理）
            startQuestTunnel();
            return;
        }
        if (gotoTicks == 0) {
            gotoBestDistSq = distSq;
            gotoNoProgress = 0;
        }
        gotoTicks++;
        // [途中までしか行けない経路で立ち尽くす不具合] 近づけなくなったらすぐ掘り進む。
        if (distSq < gotoBestDistSq - 0.25) {
            gotoBestDistSq = distSq;
            gotoNoProgress = 0;
        }
        boolean stuck = ++gotoNoProgress > 50 || gotoTicks > GOTO_TIMEOUT_TICKS;
        if (!stuck && entity.getNavigator().noPath()) {
            net.minecraft.pathfinding.Path planned = SafeRoutePlanner.plan(entity, cx, cy, cz);
            if (planned != null && entity.getNavigator().setPath(planned, MOVE_SPEED)) {
                navFailStreak = 0;
                net.minecraft.pathfinding.Path path = entity.getNavigator().getPath();
                net.minecraft.pathfinding.PathPoint end = path == null ? null : path.getFinalPathPoint();
                if (end != null) {
                    double ex = end.x + 0.5 - cx;
                    double ey = end.y + 0.5 - cy;
                    double ez = end.z + 0.5 - cz;
                    if (ex * ex + ey * ey + ez * ez > REACH_SQ
                            && entity.getDistanceSq(end.x + 0.5, end.y, end.z + 0.5) < 2.25) {
                        stuck = true; // 途中経路の終点に既に居る
                    }
                }
            } else {
                stuck = ++navFailStreak > 2;
            }
        }
        if (stuck) {
            startQuestTunnel();
        }
    }

    private void startQuestTunnel() {
        entity.getNavigator().clearPath();
        tunnelSteps = 0;
        tunnelCooldown = 0;
        phase = Phase.TUNNEL;
    }

    /**
     * [鉱石をきれいに掘る] 手が届いて見えていれば掘る。届くが石の陰なら視線上の石だけ掘る。
     * それ以外は幅1×高さ2（下りは3）の階段を掘って自分も進む。
     * 以前は土を掘るとシャベル等に持ち替え、その道具で石が掘れず中止→別の所でまた掘る、
     * を繰り返して地面に浅い穴を散らかしていた。今は掘る前に毎回その石に合う道具へ持ち替える。
     */
    private void tickTunnel() {
        if (mineTargetPos == null || entity.world.getBlockState(mineTargetPos).getBlock() != mineTargetBlock) {
            phase = Phase.RESOLVE;
            return;
        }
        double cx = mineTargetPos.getX() + 0.5;
        double cy = mineTargetPos.getY() + 0.5;
        double cz = mineTargetPos.getZ() + 0.5;
        entity.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
        if (tunnelCooldown-- > 0) {
            return;
        }
        tunnelCooldown = TUNNEL_STEP_TICKS;
        World world = entity.world;

        net.minecraft.util.math.Vec3d eye = new net.minecraft.util.math.Vec3d(entity.posX, entity.posY + 1.6, entity.posZ);
        if (eye.squareDistanceTo(cx, cy, cz) <= REACH_SQ) {
            net.minecraft.util.math.RayTraceResult hit = world.rayTraceBlocks(eye,
                    new net.minecraft.util.math.Vec3d(cx, cy, cz), false, true, false);
            BlockPos blocker = hit == null ? null : hit.getBlockPos();
            if (blocker == null || blocker.equals(mineTargetPos)) {
                mineProgress = 0;
                phase = Phase.MINE;
                return;
            }
            if (digForQuest(world, blocker)) {
                tunnelSteps++;
                return;
            }
        }
        if (++tunnelSteps > MAX_TUNNEL_STEPS) {
            abortCurrentTask();
            return;
        }
        BlockPos cur = new BlockPos(entity);
        int dx = mineTargetPos.getX() - cur.getX();
        int dz = mineTargetPos.getZ() - cur.getZ();
        int dy = mineTargetPos.getY() - cur.getY();
        int sx = 0;
        int sz = 0;
        if (dx != 0 && Math.abs(dx) >= Math.abs(dz)) {
            sx = Integer.signum(dx);
        } else if (dz != 0) {
            sz = Integer.signum(dz);
        } else {
            // 真上/真下: 縦穴は掘らず、前の向きに階段で回り込む
            sx = tunnelLastDx;
            sz = tunnelLastDz;
            if (sx == 0 && sz == 0) {
                sx = 1;
            }
        }
        tunnelLastDx = sx;
        tunnelLastDz = sz;
        int sy = dy < -1 ? -1 : (dy > 1 ? 1 : 0);
        BlockPos next = cur.add(sx, sy, sz);
        // 溶岩・水に掘り抜かない
        for (BlockPos p : new BlockPos[] { next.down(), next, next.up(), next.up(2) }) {
            if (world.getBlockState(p).getMaterial().isLiquid()) {
                abortCurrentTask();
                return;
            }
        }
        BlockPos[] toClear = sy < 0 ? new BlockPos[] { next.up(2), next.up(), next }
                : sy > 0 ? new BlockPos[] { cur.up(2), next, next.up() }
                        : new BlockPos[] { next, next.up() };
        for (BlockPos p : toClear) {
            if (world.isAirBlock(p)) {
                continue;
            }
            if (!digForQuest(world, p)) {
                abortCurrentTask();
                return;
            }
        }
        if (!world.isAirBlock(next) || !world.isAirBlock(next.up())) {
            return; // 砂利が落ちてきた等 -- 次でもう一度
        }
        BlockPos land = next;
        int drop = 0;
        while (drop <= 3 && world.isAirBlock(land.down())) {
            land = land.down();
            drop++;
        }
        if (drop > 3) {
            // 深い空洞に出た -- 歩いて行けるか試す
            gotoTicks = 0;
            phase = Phase.GOTO_BLOCK;
            return;
        }
        entity.swingArm(EnumHand.MAIN_HAND);
        entity.getNavigator().clearPath();
        entity.setPositionAndUpdate(land.getX() + 0.5, land.getY(), land.getZ() + 0.5);
    }

    /** トンネル用に1ブロック掘る（その石に合う道具へ持ち替えてから）。掘れなければ false。 */
    private boolean digForQuest(World world, BlockPos p) {
        IBlockState state = world.getBlockState(p);
        if (world.getTileEntity(p) != null || state.getBlock() == Blocks.BEDROCK
                || state.getBlockHardness(world, p) < 0.0F) {
            return false;
        }
        AllyToolManager.equipForBlock(entity, state, carried);
        if (!AllyAIUtil.canHarvestWith(entity.getHeldItemMainhand(), state)
                && !state.getMaterial().isToolNotRequired()) {
            // 掘れる道具が無い石でも、ドロップ無しで壊すことはできる（プレイヤーと同じ）
            if (state.getBlockHardness(world, p) > 5.0F) {
                return false; // 黒曜石など: 素手では現実的でない
            }
        }
        return breakBlockAt(p);
    }

    private void tickMine() {
        if (mineTargetPos == null || entity.world.getBlockState(mineTargetPos).getBlock() != mineTargetBlock) {
            phase = Phase.RESOLVE;
            return;
        }
        double cx = mineTargetPos.getX() + 0.5;
        double cy = mineTargetPos.getY() + 0.5;
        double cz = mineTargetPos.getZ() + 0.5;
        if (entity.getDistanceSq(cx, cy, cz) > REACH_SQ * 1.3) {
            gotoTicks = 0;
            phase = Phase.GOTO_BLOCK;
            return;
        }
        entity.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
        if (mineProgress % 5 == 0) {
            entity.swingArm(EnumHand.MAIN_HAND);
        }
        int required = Math.max(4, (int) (MINE_TICKS_REQUIRED / AllySkills.speedMultiplier(entity, AllySkills.Skill.MINING)));
        if (++mineProgress < required) {
            return;
        }
        mineProgress = 0;
        int before = countMatching(mineGoal.accepted);
        if (!breakBlockAt(mineTargetPos)) {
            fruitlessMines = MAX_FRUITLESS_MINES;
        }
        if (countMatching(mineGoal.accepted) <= before) {
            if (++fruitlessMines >= MAX_FRUITLESS_MINES) {
                abortCurrentTask();
                return;
            }
        } else {
            fruitlessMines = 0;
        }
        if (countMatching(mineGoal.accepted) >= mineGoal.count) {
            phase = Phase.RESOLVE;
            return;
        }
        BlockPos next = findNearestMatchingBlock(mineTargetBlock);
        if (next == null) {
            phase = Phase.RESOLVE; // 他の手段（クラフト等）を試す
            mineTargetPos = null;
            return;
        }
        TargetRegistry.claim(entity, next, 1200);
        mineTargetPos = next;
        gotoTicks = 0;
        navFailStreak = 0;
        phase = Phase.GOTO_BLOCK;
    }

    /** ブロックを1つ壊してドロップを回収する。保護Mod等に拒否されたら false。 */
    private boolean breakBlockAt(BlockPos pos) {
        World world = entity.world;
        if (AllyAreas.isForbidden(world, pos)) {
            return false; // 立ち入り禁止エリアは壊さない
        }
        IBlockState state = world.getBlockState(pos);
        AllySkills.addXp(entity, AllySkills.Skill.MINING, state.getBlock() == mineTargetBlock ? 2 : 1);
        // 壊すブロックに合った道具（斧/ツルハシ/シャベル）へ自分で持ち替える
        AllyToolManager.equipForBlock(entity, state, carried);
        ItemStack tool = entity.getHeldItemMainhand();
        if (HBMToolSupport.isAbilityTool(tool) && state.getBlock() == mineTargetBlock) {
            List<ItemStack> got = new ArrayList<ItemStack>();
            if (HBMToolSupport.harvest(entity, pos, got)) {
                for (ItemStack g : got) {
                    addToCarried(g);
                }
                return true;
            }
        }
        EntityPlayer contextPlayer = bridge != null ? bridge.getOwnerPlayer(entity) : null;
        if (contextPlayer != null) {
            BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(world, pos, state, contextPlayer);
            MinecraftForge.EVENT_BUS.post(breakEvent);
            if (breakEvent.isCanceled()) {
                return false;
            }
        }
        boolean harvestAllowed = AllyAIUtil.canHarvestWith(tool, state);
        if (contextPlayer != null) {
            harvestAllowed = ForgeEventFactory.doPlayerHarvestCheck(contextPlayer, state, harvestAllowed);
        }
        if (harvestAllowed) {
            Block block = state.getBlock();
            boolean silk = !tool.isEmpty() && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, tool) > 0
                    && block.canSilkHarvest(world, pos, state, contextPlayer);
            int fortune = tool.isEmpty() ? 0 : EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, tool);
            List<ItemStack> drops;
            if (silk) {
                drops = new ArrayList<ItemStack>();
                drops.add(new ItemStack(block, 1, block.getMetaFromState(state)));
            } else {
                drops = block.getDrops(world, pos, state, fortune);
            }
            float chance = ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, fortune, 1.0F, silk, contextPlayer);
            for (ItemStack drop : drops) {
                if (drop != null && !drop.isEmpty() && (chance >= 1.0F || world.rand.nextFloat() < chance)) {
                    addToCarried(drop);
                }
            }
            if (!tool.isEmpty() && tool.isItemStackDamageable()) {
                tool.damageItem(1, entity);
            }
        }
        world.setBlockToAir(pos);
        AxisAlignedBB area = new AxisAlignedBB(pos).grow(2.0);
        for (EntityItem item : world.getEntitiesWithinAABB(EntityItem.class, area)) {
            if (item.isEntityAlive() && !item.getItem().isEmpty()) {
                addToCarried(item.getItem().copy());
                item.setDead();
            }
        }
        return true;
    }

    /** 近い順（水平の殻ごと）に探す。鉱石用に下方向は深く探す。 */
    private BlockPos findNearestMatchingBlock(Block block) {
        BlockPos origin = new BlockPos(entity);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = Math.max(1, origin.getY() - SEARCH_DOWN);
        int maxY = Math.min(255, origin.getY() + SEARCH_UP);
        for (int r = 0; r <= SEARCH_RADIUS_HORIZONTAL; r++) {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    cursor.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                    if (!entity.world.isBlockLoaded(cursor)) {
                        continue;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        cursor.setY(y);
                        if (entity.world.getBlockState(cursor).getBlock() != block) {
                            continue;
                        }
                        // [重複防止・禁止エリア] 他の味方が狙っている所・拠点の中は掘らない
                        if (TargetRegistry.isClaimedByOther(entity, cursor) || AllyAreas.isForbidden(entity.world, cursor)) {
                            continue;
                        }
                        double d = cursor.distanceSq(origin);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = cursor.toImmutable();
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 納品
    // ------------------------------------------------------------------

    private void tickSubmit() {
        if (manualActive) {
            submitManual();
            return;
        }
        if (targetFluidTask != null && targetData != null) {
            submitFluid();
            afterSubmit(targetFluidTask.quest);
            targetFluidTask = null;
            return;
        }
        if (targetTask != null && targetData != null) {
            EntityPlayer player = resolveQuestPlayer();
            try {
                TaskData taskData = targetData.getTaskData(targetTask);
                if (taskData != null && !taskData.isComplete()) {
                    boolean consumes = targetTask.canInsertItem();
                    int acceptedTotal = 0;
                    List<ItemStack> returned = new ArrayList<ItemStack>();
                    for (ItemStack stack : carried) {
                        if (stack.isEmpty() || !targetTask.test(stack) || taskData.isComplete()) {
                            continue;
                        }
                        ItemStack offer = stack.copy();
                        ItemStack leftover = taskData.insertItem(offer, false, false, player);
                        int accepted = offer.getCount() - (leftover == null ? 0 : leftover.getCount());
                        if (accepted > 0) {
                            acceptedTotal += accepted;
                            stack.shrink(accepted);
                            if (!consumes) {
                                ItemStack back = offer.copy();
                                back.setCount(accepted);
                                returned.add(back);
                            }
                        }
                    }
                    carried.removeIf(ItemStack::isEmpty);
                    giveToPlayer(player, returned);
                    if (acceptedTotal == 0) {
                        // 作った物がタスクの条件（NBT等）に合わなかった -- しばらくこのタスクは飛ばす
                        unresolvableUntil.put(targetTask, now() + UNRESOLVABLE_SKIP_TICKS);
                    }
                }
            } catch (Throwable ignored) {
                // クエストファイルのリロード等
            }
            afterSubmit(targetTask.quest);
        }
        targetTask = null;
        cooldown = 1;
    }

    private void abortCurrentTask() {
        if (manualActive) {
            AllyProjectBoard.finish(AllyAreas.ownerId(entity), entity);
            AllyCraftRequests.Request r = manualRequest;
            tellRequester(r, "「" + (r == null ? "?" : r.name) + "」を作る途中で行き詰まりました（材料が見つからない・掘れない等）。依頼を終了します。");
            AllyCraftRequests.remove(entity);
            manualActive = false;
            manualRequest = null;
            return;
        }
        if (targetTask != null) {
            unresolvableUntil.put(targetTask, now() + UNRESOLVABLE_SKIP_TICKS);
        }
        if (targetFluidTask != null) {
            unresolvableUntil.put(targetFluidTask, now() + UNRESOLVABLE_SKIP_TICKS);
        }
        releaseQuestClaim();
        AllyProjectBoard.finish(AllyAreas.ownerId(entity), entity);
        targetTask = null;
        targetFluidTask = null;
    }

    private void handOverLeftovers() {
        List<ItemStack> all = new ArrayList<ItemStack>();
        for (ItemStack stack : carried) {
            if (AllyToolManager.isKeepable(stack)) {
                AllyInventory.insertOrDrop(entity, stack); // 道具・作業台は自分で持っておく
            } else {
                all.add(stack);
            }
        }
        carried.clear();
        giveToPlayer(resolveQuestPlayer(), all);
    }

    // ------------------------------------------------------------------
    // [プロジェクト発注・並列分散]
    // ------------------------------------------------------------------

    private Task lastAnnounced;

    /** クエスト着手の通知と、プロジェクト（採取の発注）の開始。 */
    private void announceTask(EntityPlayer player, Task task, QuestRecipeResolver.Key key, int count) {
        String title;
        try {
            title = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(task.quest.getTitle());
        } catch (Throwable t) {
            title = "?";
        }
        if (task == lastAnnounced) {
            return; // 同じタスクの次の束 -- 発注は出し直さない
        }
        lastAnnounced = task;
        if (player != null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString(
                    "[クエスト] " + entity.getName() + " が「" + title + "」に着手します。"));
        }
        if (key != null) {
            startProject("クエスト: " + title, key, count);
        }
    }

    /** 斧/ツルハシの仕事で、近くに仕事の無い（クラフト役になれる）味方がいるか。 */
    private boolean isSupplierWhileCrafterNearby() {
        if (manualActive || AllyCraftRequests.get(entity) != null) {
            return false;
        }
        String job = bridge == null ? "" : bridge.getJob(entity);
        if (!"axe".equals(job) && !"pickaxe".equals(job)) {
            return false;
        }
        java.util.UUID owner = AllyAreas.ownerId(entity);
        if (owner == null) {
            return false;
        }
        for (EntityFriendlyCreature other : entity.world.getEntitiesWithinAABB(EntityFriendlyCreature.class,
                entity.getEntityBoundingBox().grow(48.0D))) {
            if (other != entity && other.isEntityAlive() && owner.equals(AllyAreas.ownerId(other))
                    && bridge.getJob(other).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** その種類（斧=原木 / ツルハシ=鉱石・石）の作業役が近くにいるか。 */
    private boolean workersAvailable(boolean axe) {
        java.util.UUID owner = AllyAreas.ownerId(entity);
        if (owner == null || bridge == null) {
            return false;
        }
        for (EntityFriendlyCreature other : entity.world.getEntitiesWithinAABB(EntityFriendlyCreature.class,
                entity.getEntityBoundingBox().grow(64.0D))) {
            if (other != entity && other.isEntityAlive() && owner.equals(AllyAreas.ownerId(other))
                    && (axe ? "axe" : "pickaxe").equals(bridge.getJob(other))) {
                return true;
            }
        }
        return false;
    }

    /**
     * レシピツリーを末端まで展開して、必要な採掘ブロックと量・使う機械（電圧）を数える。
     * needs: ブロック → {個数, ドロップのアイテムID, メタ}
     */
    private void expandNeeds(QuestRecipeResolver.Key key, int count, Map<Block, int[]> needs,
            java.util.Set<QuestRecipeResolver.Key> path, Map<String, Integer> machines, int depth) {
        if (key == null || count <= 0 || depth > 14 || path.contains(key) || --expandBudget < 0) {
            return;
        }
        Block b = closure.mineBlock.get(key);
        if (b != null) {
            int[] v = needs.get(b);
            if (v == null) {
                v = new int[] { 0, net.minecraft.item.Item.getIdFromItem(key.item), key.meta };
                needs.put(b, v);
            }
            v[0] = Math.min(4096, v[0] + count);
            return;
        }
        QuestRecipeResolver.ProcRecipe r = closure.producerOf(key);
        if (r == null) {
            return;
        }
        int per = 1;
        for (ItemStack out : r.outputs) {
            if (key.matches(out)) {
                per = Math.max(1, out.getCount());
                break;
            }
        }
        int runs = (count + per - 1) / per;
        if (r.tier >= 0) {
            String m = machineLabel(r);
            Integer old = machines.get(m);
            machines.put(m, old == null ? r.tier : Math.max(old, r.tier));
        }
        path.add(key);
        for (QuestRecipeResolver.Input in : r.inputs) {
            if (in.consumed) {
                expandNeeds(closure.bestAccepted(in.accepted), Math.min(4096, runs * in.count), needs, path, machines, depth + 1);
            }
        }
        path.remove(key);
    }

    private int expandBudget;

    private static String machineLabel(QuestRecipeResolver.ProcRecipe r) {
        String s = r.source == null ? "gt" : r.source;
        if (s.startsWith("gt:")) {
            s = s.substring(3);
        }
        if (s.startsWith("recipemap.")) {
            s = s.substring("recipemap.".length());
        }
        if (s.endsWith(".name")) {
            s = s.substring(0, s.length() - 5);
        }
        return s;
    }

    /** プロジェクトを発注する（採取を斧/ツルハシの作業役へ振り分け）。 */
    private void startProject(String title, QuestRecipeResolver.Key key, int count) {
        java.util.UUID owner = AllyAreas.ownerId(entity);
        if (owner == null || key == null) {
            return;
        }
        Map<Block, int[]> needs = new java.util.LinkedHashMap<Block, int[]>();
        Map<String, Integer> machines = new java.util.LinkedHashMap<String, Integer>();
        expandBudget = 2000;
        expandNeeds(key, Math.max(1, count - countMatching(new ItemStack[] { key.toStack(1) })), needs,
                new java.util.HashSet<QuestRecipeResolver.Key>(), machines, 0);
        boolean axeTeam = workersAvailable(true);
        boolean pickTeam = workersAvailable(false);
        Map<Block, int[]> orders = new java.util.LinkedHashMap<Block, int[]>();
        for (Map.Entry<Block, int[]> e : needs.entrySet()) {
            Block b = e.getKey();
            IBlockState st = b.getDefaultState();
            String tool = b.getHarvestTool(st);
            boolean log = b instanceof net.minecraft.block.BlockLog || "axe".equals(tool);
            if ((log && axeTeam) || ("pickaxe".equals(tool) && pickTeam)) {
                orders.put(b, e.getValue());
            }
        }
        if (!AllyProjectBoard.start(owner, title, entity, orders)) {
            orders.clear(); // 別のクラフト役がプロジェクト中 -- 自分の分は自分で掘る
        }
        EntityPlayer p = resolveQuestPlayer();
        if (p == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("[プロジェクト] ").append(title).append(" ／ クラフト役: ").append(entity.getName());
        if (!machines.isEmpty()) {
            sb.append(" ／ 工程: ");
            int n = 0;
            for (Map.Entry<String, Integer> m : machines.entrySet()) {
                if (n++ > 0) {
                    sb.append("、");
                }
                if (n > 8) {
                    sb.append("…");
                    break;
                }
                sb.append(m.getKey()).append("(").append(QuestRecipeResolver.tierName(m.getValue())).append(")");
            }
        }
        String desc = AllyProjectBoard.describe(owner);
        if (!desc.isEmpty()) {
            sb.append(" ／ 発注: ").append(desc);
        }
        p.sendMessage(new net.minecraft.util.text.TextComponentString(sb.toString()));
    }

    // ---- 作業役からの納品待ち

    private ItemGoal supplyGoal;
    private Block supplyBlock;
    private int supplyWait;
    private int supplyLastCount;
    private final java.util.Set<Block> selfMine = new java.util.HashSet<Block>();

    private boolean shouldWaitForSupply(Block block) {
        if (selfMine.contains(block)) {
            return false;
        }
        java.util.UUID owner = AllyAreas.ownerId(entity);
        return owner != null && AllyProjectBoard.isOrderOpenFor(owner, block, entity);
    }

    /** 作業役が届けてくれるのを待つ（その間に届いた物を持ち物から取り出す）。90秒届かなければ自分で掘る。 */
    private void tickWaitSupply() {
        if (supplyGoal == null) {
            phase = Phase.RESOLVE;
            return;
        }
        supplyWait++;
        if (supplyWait % 20 != 0) {
            return;
        }
        pullFromInventory(supplyGoal.accepted, supplyGoal.count - countMatching(supplyGoal.accepted));
        int have = countMatching(supplyGoal.accepted);
        if (have >= supplyGoal.count) {
            phase = Phase.RESOLVE;
            return;
        }
        if (have > supplyLastCount) {
            supplyLastCount = have;
            supplyWait = 0;
        }
        java.util.UUID owner = AllyAreas.ownerId(entity);
        if (supplyWait > 1800 || owner == null || !AllyProjectBoard.isOrderOpenFor(owner, supplyBlock, entity)) {
            selfMine.add(supplyBlock); // 届かない -- 自分で掘る
            phase = Phase.RESOLVE;
        }
        // 暇な間は持ち主のそばで待つ
        EntityPlayer p = resolveQuestPlayer();
        if (p != null && entity.getDistanceSq(p) > 8 * 8) {
            entity.getNavigator().tryMoveToEntityLiving(p, MOVE_SPEED);
        }
    }

    // ---- 液体納品・自動ループ

    private void submitFluid() {
        try {
            TaskData td = targetData.getTaskData(targetFluidTask);
            if (!(td instanceof FluidTask.Data) || td.isComplete()) {
                return;
            }
            String name = targetFluidTask.fluid.getName();
            int amount = "water".equals(name) ? (int) Math.min(Integer.MAX_VALUE, targetFluidTask.getMaxProgress() - td.progress)
                    : fluidAmount(name);
            if (amount <= 0) {
                return;
            }
            int filled = ((FluidTask.Data) td).fill(targetFluidTask.createFluidStack(amount), true);
            if (!"water".equals(name)) {
                addFluid(name, -filled);
            }
            if (filled <= 0) {
                unresolvableUntil.put(targetFluidTask, now() + UNRESOLVABLE_SKIP_TICKS);
            }
        } catch (Throwable ignored) {
            // クエストファイルのリロード等
        }
    }

    /** 納品後: 達成通知 → 予約・発注を片付けて、すぐ次の解放済みクエストへ（Auto-Quest Loop）。 */
    private void afterSubmit(Quest quest) {
        releaseQuestClaim();
        AllyProjectBoard.finish(AllyAreas.ownerId(entity), entity);
        cooldown = 1;
        try {
            if (quest != null && targetData != null && quest.isComplete(targetData)) {
                EntityPlayer p = resolveQuestPlayer();
                if (p != null) {
                    p.sendMessage(new net.minecraft.util.text.TextComponentString(net.minecraft.util.text.TextFormatting.GREEN
                            + "[クエスト達成] 「" + net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(quest.getTitle())
                            + "」を納品しました。次の解放済みクエストに取りかかります。"));
                }
                AllySkills.addXp(entity, AllySkills.Skill.BUILDING, 10);
            }
        } catch (Throwable ignored) {
            // 無視
        }
    }

    // ------------------------------------------------------------------
    // [仮想・実機ハイブリッド加工]
    // ------------------------------------------------------------------

    private final Map<BlockPos, Long> badMachineMap = new HashMap<BlockPos, Long>();

    private Map<BlockPos, Long> badMachines() {
        return badMachineMap;
    }

    private BlockPos machinePos;
    private boolean machineIsFurnace;
    private ProcessGoal machineGoal;
    private int machineTicks;
    private int machineTimeout;
    private final List<ItemStack> machineInputs = new ArrayList<ItemStack>();
    private final List<String> machineFluidNames = new ArrayList<String>();
    private final List<net.minecraftforge.fluids.FluidStack> machineFluidInputs = new ArrayList<net.minecraftforge.fluids.FluidStack>();
    private final Map<String, Integer> machineGot = new HashMap<String, Integer>();

    private static String keyOf(ItemStack s) {
        return s.getItem().getRegistryName() + "@" + s.getMetadata();
    }

    /** carried から in に合う物を count 個取り出して返す（容器アイテムは carried に戻す）。 */
    private List<ItemStack> takeMatching(QuestRecipeResolver.Input in, int count) {
        List<ItemStack> taken = new ArrayList<ItemStack>();
        int remaining = count;
        for (ItemStack stack : carried) {
            if (remaining <= 0) {
                break;
            }
            if (!in.accepts(stack)) {
                continue;
            }
            int t = Math.min(remaining, stack.getCount());
            taken.add(stack.splitStack(t));
            remaining -= t;
        }
        carried.removeIf(ItemStack::isEmpty);
        return taken;
    }

    private net.minecraft.tileentity.TileEntityFurnace findFurnace() {
        net.minecraft.tileentity.TileEntityFurnace best = null;
        double bestD = 16.0 * 16.0;
        for (net.minecraft.tileentity.TileEntity te : entity.world.loadedTileEntityList) {
            if (te instanceof net.minecraft.tileentity.TileEntityFurnace) {
                double d = te.getDistanceSq(entity.posX, entity.posY, entity.posZ);
                if (d < bestD) {
                    bestD = d;
                    best = (net.minecraft.tileentity.TileEntityFurnace) te;
                }
            }
        }
        return best;
    }

    /** 実機が使えれば材料を取り分けて MACHINE_GOTO へ。使えなければ false（仮想加工へ）。 */
    private boolean tryStartReal(ProcessGoal pg) {
        QuestRecipeResolver.ProcRecipe r = pg.recipe;
        BlockPos pos = null;
        boolean furnace = false;
        if ("furnace".equals(r.source)) {
            net.minecraft.tileentity.TileEntityFurnace f = findFurnace();
            if (f == null) {
                return false;
            }
            pos = f.getPos();
            furnace = true;
        } else if (r.gtMap != null && net.minecraftforge.fml.common.Loader.isModLoaded("gregtech")) {
            for (QuestRecipeResolver.Input in : r.inputs) {
                if (!in.consumed) {
                    return false; // 回路設定などが要るレシピは仮想加工で
                }
            }
            try {
                pos = GTMachineOps.findMachine(entity.world, new BlockPos(entity), 24, r.gtMap, Math.max(0, r.tier), badMachines(), now());
                if (pos == null) {
                    pos = installMachineFor(r);
                }
            } catch (Throwable t) {
                pos = null;
            }
            if (pos == null) {
                return false;
            }
        } else {
            return false;
        }
        // 液体を準備（名前 → FluidStack）
        machineFluidInputs.clear();
        machineFluidNames.clear();
        for (QuestRecipeResolver.FluidAmount f : r.fluidInputs) {
            net.minecraftforge.fluids.FluidStack fs = net.minecraftforge.fluids.FluidRegistry.getFluidStack(f.fluid, f.amount);
            if (fs == null) {
                return false;
            }
            machineFluidInputs.add(fs);
            machineFluidNames.add(f.fluid);
        }
        machineInputs.clear();
        for (QuestRecipeResolver.Input in : r.inputs) {
            if (in.consumed) {
                machineInputs.addAll(takeMatching(in, in.count));
            }
        }
        for (QuestRecipeResolver.FluidAmount f : r.fluidInputs) {
            addFluid(f.fluid, -f.amount);
        }
        machinePos = pos;
        machineIsFurnace = furnace;
        machineGoal = pg;
        machineTicks = 0;
        machineGot.clear();
        machineGotFluid.clear();
        phase = Phase.MACHINE_GOTO;
        return true;
    }

    /** 機械を持っていれば、電源の隣に設置（ケーブルがあれば配線）する。 */
    private BlockPos installMachineFor(QuestRecipeResolver.ProcRecipe r) {
        ItemStack want = GTMachineOps.machineItemFor(r.gtMap, Math.max(0, r.tier));
        if (want.isEmpty()) {
            return null;
        }
        ItemStack[] accepted = { want };
        pullFromInventory(accepted, 1);
        ItemStack machine = null;
        for (ItemStack s : carried) {
            if (ItemStack.areItemsEqual(s, want)) {
                machine = s;
                break;
            }
        }
        if (machine == null) {
            return null;
        }
        List<ItemStack> cables = new ArrayList<ItemStack>();
        net.minecraft.inventory.InventoryBasic inv = AllyInventory.get(entity);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (GTMachineOps.isCableItem(inv.getStackInSlot(i))) {
                cables.add(inv.removeStackFromSlot(i));
            }
        }
        BlockPos placed = GTMachineOps.installMachine(entity, machine, cables);
        for (ItemStack c : cables) {
            if (!c.isEmpty()) {
                AllyInventory.insertOrDrop(entity, c);
            }
        }
        carried.removeIf(ItemStack::isEmpty);
        if (placed != null) {
            EntityPlayer p = resolveQuestPlayer();
            if (p != null) {
                p.sendMessage(new net.minecraft.util.text.TextComponentString("[" + entity.getName() + "] "
                        + want.getDisplayName() + " を電源の近く（" + placed.getX() + ", " + placed.getY() + ", " + placed.getZ()
                        + "）に設置しました。"));
            }
        }
        return placed;
    }

    private void tickMachineGoto() {
        if (machinePos == null || machineGoal == null) {
            phase = Phase.RESOLVE;
            return;
        }
        machineTicks++;
        double d = entity.getDistanceSq(machinePos);
        if (d > 3.0 * 3.0 && machineTicks < 300) {
            if (machineTicks % 20 == 1) {
                entity.getNavigator().tryMoveToXYZ(machinePos.getX() + 0.5, machinePos.getY(), machinePos.getZ() + 0.5, MOVE_SPEED);
            }
            return;
        }
        entity.getNavigator().clearPath();
        entity.getLookHelper().setLookPosition(machinePos.getX() + 0.5, machinePos.getY() + 0.5, machinePos.getZ() + 0.5, 30.0F, 30.0F);
        entity.swingArm(EnumHand.MAIN_HAND);
        boolean loaded;
        if (machineIsFurnace) {
            loaded = loadFurnace();
        } else {
            List<ItemStack> leftovers = new ArrayList<ItemStack>();
            loaded = GTMachineOps.insert(entity.world, machinePos, machineInputs, machineFluidInputs, leftovers);
            for (ItemStack l : leftovers) {
                addToCarried(l); // 入りきらなかった分は手元へ（消さない）
            }
        }
        if (!loaded) {
            fallbackToVirtual(false);
            return;
        }
        machineTicks = 0;
        machineTimeout = Math.max(400, machineGoal.recipe.durationTicks * 3 + 200);
        phase = Phase.MACHINE_WAIT;
    }

    private boolean loadFurnace() {
        net.minecraft.tileentity.TileEntity te = entity.world.getTileEntity(machinePos);
        if (!(te instanceof net.minecraft.tileentity.TileEntityFurnace) || machineInputs.isEmpty()) {
            return false;
        }
        net.minecraft.tileentity.TileEntityFurnace f = (net.minecraft.tileentity.TileEntityFurnace) te;
        ItemStack input = machineInputs.get(0);
        ItemStack slot0 = f.getStackInSlot(0);
        if (!slot0.isEmpty() && !(ItemStack.areItemsEqual(slot0, input) && slot0.getCount() + input.getCount() <= slot0.getMaxStackSize())) {
            return false; // 誰かが使用中
        }
        // 燃料: 燃えていなくて燃料枠が空なら、手持ちの燃料を入れる
        if (!f.isBurning() && f.getStackInSlot(1).isEmpty()) {
            pullFromInventory(FUELS, 1);
            ItemStack fuel = ItemStack.EMPTY;
            for (ItemStack s : carried) {
                if (acceptsAny(FUELS, s)) {
                    fuel = s.splitStack(1);
                    break;
                }
            }
            carried.removeIf(ItemStack::isEmpty);
            if (fuel.isEmpty()) {
                return false;
            }
            f.setInventorySlotContents(1, fuel);
        }
        if (slot0.isEmpty()) {
            f.setInventorySlotContents(0, input.copy());
        } else {
            slot0.grow(input.getCount());
        }
        f.markDirty();
        return true;
    }

    private void collect(ItemStack got) {
        if (got.isEmpty()) {
            return;
        }
        String k = keyOf(got);
        Integer old = machineGot.get(k);
        machineGot.put(k, (old == null ? 0 : old) + got.getCount());
        addToCarried(got);
    }

    private final Map<String, Integer> machineGotFluid = new HashMap<String, Integer>();

    private boolean machineDone() {
        for (ItemStack out : machineGoal.recipe.outputs) {
            Integer got = machineGot.get(keyOf(out));
            if (got == null || got < out.getCount()) {
                return false;
            }
        }
        for (QuestRecipeResolver.FluidAmount f : machineGoal.recipe.fluidOutputs) {
            Integer got = machineGotFluid.get(f.fluid);
            if (got == null || got < f.amount) {
                return false;
            }
        }
        return true;
    }

    private void tickMachineWait() {
        if (machinePos == null || machineGoal == null) {
            phase = Phase.RESOLVE;
            return;
        }
        machineTicks++;
        if (machineTicks % 20 != 0) {
            return;
        }
        if (machineIsFurnace) {
            net.minecraft.tileentity.TileEntity te = entity.world.getTileEntity(machinePos);
            if (te instanceof net.minecraft.tileentity.TileEntityFurnace) {
                net.minecraft.tileentity.TileEntityFurnace f = (net.minecraft.tileentity.TileEntityFurnace) te;
                ItemStack out = f.getStackInSlot(2);
                if (!out.isEmpty() && !machineGoal.recipe.outputs.isEmpty()
                        && ItemStack.areItemsEqual(out, machineGoal.recipe.outputs.get(0))) {
                    Integer got = machineGot.get(keyOf(out));
                    int want = machineGoal.recipe.outputs.get(0).getCount() - (got == null ? 0 : got);
                    if (want > 0) {
                        collect(f.decrStackSize(2, Math.min(want, out.getCount()))); // 自分の分だけ
                    }
                }
            }
        } else {
            List<net.minecraftforge.fluids.FluidStack> fluids = new ArrayList<net.minecraftforge.fluids.FluidStack>();
            List<String> names = new ArrayList<String>();
            for (QuestRecipeResolver.FluidAmount f : machineGoal.recipe.fluidOutputs) {
                names.add(f.fluid);
            }
            for (ItemStack got : GTMachineOps.extractOutputs(entity.world, machinePos, machineGoal.recipe.outputs, names, fluids)) {
                collect(got);
            }
            for (net.minecraftforge.fluids.FluidStack fs : fluids) {
                addFluid(fs.getFluid().getName(), fs.amount);
                Integer old = machineGotFluid.get(fs.getFluid().getName());
                machineGotFluid.put(fs.getFluid().getName(), (old == null ? 0 : old) + fs.amount);
            }
        }
        if (machineDone()) {
            AllySkills.addXp(entity, AllySkills.Skill.BUILDING, 2);
            machinePos = null;
            machineGoal = null;
            phase = Phase.RESOLVE;
            return;
        }
        if (machineTicks > machineTimeout) {
            badMachines().put(machinePos, now() + 6000); // 5分間はこの機械を使わない
            fallbackToVirtual(true); // 電力不足・詰まり等 -- 仮想加工で進める
        }
    }

    /** 実機での加工をやめて、入れた材料を取り戻し、仮想加工でやり直す。 */
    private void fallbackToVirtual(boolean inMachine) {
        ProcessGoal pg = machineGoal;
        if (inMachine) {
            recoverMachine();
        } else {
            for (ItemStack s : machineInputs) {
                addToCarried(s);
            }
            for (net.minecraftforge.fluids.FluidStack fs : machineFluidInputs) {
                addFluid(fs.getFluid().getName(), fs.amount);
            }
        }
        machineInputs.clear();
        machineFluidInputs.clear();
        machinePos = null;
        machineGoal = null;
        if (pg != null) {
            pg.forceVirtual = true;
            if (++pg.attempts <= MAX_PROCESS_ATTEMPTS) {
                goalStack.push(pg);
            }
        }
        phase = Phase.RESOLVE;
    }

    /** 機械に入れたままの材料・出来た物を取り戻す（中断時）。 */
    private void recoverMachine() {
        if (machinePos == null || machineGoal == null) {
            return;
        }
        if (phase == Phase.MACHINE_WAIT) {
            if (machineIsFurnace) {
                net.minecraft.tileentity.TileEntity te = entity.world.getTileEntity(machinePos);
                if (te instanceof net.minecraft.tileentity.TileEntityFurnace) {
                    net.minecraft.tileentity.TileEntityFurnace f = (net.minecraft.tileentity.TileEntityFurnace) te;
                    if (!machineInputs.isEmpty() && ItemStack.areItemsEqual(f.getStackInSlot(0), machineInputs.get(0))) {
                        addToCarried(f.decrStackSize(0, machineInputs.get(0).getCount()));
                    }
                    ItemStack out = f.getStackInSlot(2);
                    if (!out.isEmpty() && !machineGoal.recipe.outputs.isEmpty()
                            && ItemStack.areItemsEqual(out, machineGoal.recipe.outputs.get(0))) {
                        addToCarried(f.decrStackSize(2, out.getCount()));
                    }
                }
            } else {
                List<net.minecraftforge.fluids.FluidStack> fluids = new ArrayList<net.minecraftforge.fluids.FluidStack>();
                for (ItemStack s : GTMachineOps.extractInputs(entity.world, machinePos, machineInputs, machineFluidNames, fluids)) {
                    addToCarried(s);
                }
                List<String> outNames = new ArrayList<String>();
                for (QuestRecipeResolver.FluidAmount f : machineGoal.recipe.fluidOutputs) {
                    outNames.add(f.fluid);
                }
                for (ItemStack s : GTMachineOps.extractOutputs(entity.world, machinePos, machineGoal.recipe.outputs, outNames, fluids)) {
                    addToCarried(s);
                }
                for (net.minecraftforge.fluids.FluidStack fs : fluids) {
                    addFluid(fs.getFluid().getName(), fs.amount);
                }
            }
        } else if (phase == Phase.MACHINE_GOTO) {
            for (ItemStack s : machineInputs) {
                addToCarried(s);
            }
            for (net.minecraftforge.fluids.FluidStack fs : machineFluidInputs) {
                addFluid(fs.getFluid().getName(), fs.amount);
            }
        }
        machineInputs.clear();
        machineFluidInputs.clear();
        machinePos = null;
        machineGoal = null;
    }

    // ------------------------------------------------------------------
    // [/allycraft] 頼まれた物を、レシピツリーを逆引きして作る
    // ------------------------------------------------------------------

    private boolean manualActive;
    private AllyCraftRequests.Request manualRequest;

    private boolean startManualRequest() {
        AllyCraftRequests.Request r = AllyCraftRequests.get(entity);
        if (r == null) {
            return false;
        }
        QuestRecipeResolver.Key key = closure.bestAccepted(r.accepted);
        if (countMatching(r.accepted) + countInInventory(r.accepted) < r.count && key == null) {
            tellRequester(r, "「" + r.name + "」の作り方が見つかりません（今の道具で辿れるレシピ・採掘の範囲に無い）。");
            AllyCraftRequests.remove(entity);
            return false;
        }
        pullFromInventory(r.accepted, r.count - countMatching(r.accepted));
        manualActive = true;
        manualRequest = r;
        startProject("依頼: " + r.name, key, r.count);
        targetTask = null;
        targetData = null;
        taskStartTime = now();
        goalStack.clear();
        goalStack.push(new ItemGoal(r.accepted, r.count));
        phase = Phase.RESOLVE;
        if (key != null) {
            List<String> plan = new ArrayList<String>();
            explain(key, plan, new java.util.HashSet<QuestRecipeResolver.Key>(), 0);
            if (!plan.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < plan.size() && i < 24; i++) {
                    if (i > 0) {
                        sb.append(" → ");
                    }
                    sb.append(plan.get(i));
                }
                if (plan.size() > 24) {
                    sb.append(" → …");
                }
                tellRequester(r, "[手順] " + sb);
            }
        }
        return true;
    }

    /**
     * 手順の説明を作る（実行はしない）。材料を先に、作る物を後に並べる。
     * 採掘に道具が要る時は、その道具づくりも手順に入れる。
     */
    private void explain(QuestRecipeResolver.Key key, List<String> out, java.util.Set<QuestRecipeResolver.Key> seen, int depth) {
        if (key == null || depth > 14 || !seen.add(key) || out.size() > 40) {
            return;
        }
        String name;
        try {
            name = key.toStack(1).getDisplayName();
        } catch (Throwable t) {
            name = String.valueOf(key.item.getRegistryName());
        }
        Block block = closure.mineBlock.get(key);
        if (block != null) {
            ItemStack tool = basicToolFor(block.getDefaultState());
            String cls = block.getHarvestTool(block.getDefaultState());
            if (tool != null && cls != null
                    && AllyToolManager.bestLevel(entity, cls, carried) < block.getHarvestLevel(block.getDefaultState())) {
                explain(closure.bestAccepted(new ItemStack[] { tool }), out, seen, depth + 1);
            }
            out.add(name + "(採掘)");
            return;
        }
        QuestRecipeResolver.ProcRecipe r = closure.producerOf(key);
        if (r == null) {
            return;
        }
        for (QuestRecipeResolver.Input in : r.inputs) {
            if (in.consumed) {
                explain(closure.bestAccepted(in.accepted), out, seen, depth + 1);
            }
        }
        if ("crafting".equals(r.source) && totalConsumed(r) > 4) {
            explain(closure.bestAccepted(new ItemStack[] { new ItemStack(Blocks.CRAFTING_TABLE) }), out, seen, depth + 1);
        }
        if ("furnace".equals(r.source)) {
            explain(closure.bestAccepted(new ItemStack[] { new ItemStack(Blocks.FURNACE) }), out, seen, depth + 1);
            out.add(name + "(精錬)");
        } else if ("crafting".equals(r.source)) {
            out.add(name + "(クラフト)");
        } else {
            out.add(name + "(" + r.source + ")");
        }
    }

    private void submitManual() {
        AllyProjectBoard.finish(AllyAreas.ownerId(entity), entity);
        AllyCraftRequests.Request r = manualRequest;
        manualActive = false;
        manualRequest = null;
        AllyCraftRequests.remove(entity);
        if (r == null) {
            return;
        }
        List<ItemStack> give = new ArrayList<ItemStack>();
        int want = r.count;
        for (ItemStack stack : carried) {
            if (want <= 0) {
                break;
            }
            if (!stack.isEmpty() && acceptsAny(r.accepted, stack)) {
                give.add(stack.splitStack(Math.min(want, stack.getCount())));
                want -= give.get(give.size() - 1).getCount();
            }
        }
        carried.removeIf(ItemStack::isEmpty);
        EntityPlayer p = entity.world.getPlayerEntityByUUID(r.requester);
        if (p == null) {
            p = resolveQuestPlayer();
        }
        giveToPlayer(p, give);
        tellRequester(r, "「" + r.name + "」を " + (r.count - want) + " 個作って渡しました。");
        cooldown = 10;
    }

    private void tellRequester(AllyCraftRequests.Request r, String msg) {
        EntityPlayer p = r == null ? null : entity.world.getPlayerEntityByUUID(r.requester);
        if (p == null) {
            p = resolveQuestPlayer();
        }
        if (p != null) {
            p.sendMessage(new net.minecraft.util.text.TextComponentString("[" + entity.getName() + "] " + msg));
        }
    }

    private ItemStack storeInChestNear(EntityPlayer player, ItemStack stack) {
        for (net.minecraft.tileentity.TileEntity te : player.world.loadedTileEntityList) {
            if (!(te instanceof net.minecraft.tileentity.TileEntityChest)
                    || te.getDistanceSq(player.posX, player.posY, player.posZ) > 16 * 16) {
                continue;
            }
            stack = net.minecraft.tileentity.TileEntityHopper.putStackInInventoryAllSlots(null,
                    (net.minecraft.inventory.IInventory) te, stack, net.minecraft.util.EnumFacing.UP);
            te.markDirty();
            if (stack.isEmpty()) {
                break;
            }
        }
        return stack;
    }

    private void giveToPlayer(EntityPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (player != null) {
                player.inventory.addItemStackToInventory(stack);
            }
            if (!stack.isEmpty() && player != null) {
                // [自動納品] 持ち主の持ち物がいっぱいなら、持ち主の近くのチェストへ格納
                stack = storeInChestNear(player, stack);
            }
            if (!stack.isEmpty()) {
                entity.entityDropItem(stack, 0.5F);
            }
        }
    }

    // ------------------------------------------------------------------
    // 手持ちの出納（アイテムはメタ値まで区別、液体はmB）
    // ------------------------------------------------------------------

    private static boolean acceptsAny(ItemStack[] accepted, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemStack a : accepted) {
            if (a != null && !a.isEmpty() && a.getItem() == stack.getItem()
                    && (a.getMetadata() == OreDictionary.WILDCARD_VALUE || a.getMetadata() == stack.getMetadata())) {
                return true;
            }
        }
        return false;
    }

    private int countMatching(ItemStack[] accepted) {
        int total = 0;
        for (ItemStack stack : carried) {
            if (acceptsAny(accepted, stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void consumeMatching(QuestRecipeResolver.Input in, int amount) {
        int remaining = amount;
        List<ItemStack> containers = new ArrayList<ItemStack>();
        for (ItemStack stack : carried) {
            if (remaining <= 0) {
                break;
            }
            if (!in.accepts(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            if (stack.getItem().hasContainerItem(stack)) {
                ItemStack container = stack.getItem().getContainerItem(stack);
                if (!container.isEmpty()) {
                    container.setCount(container.getCount() * take);
                    containers.add(container);
                }
            }
            stack.shrink(take);
            remaining -= take;
        }
        carried.removeIf(ItemStack::isEmpty);
        for (ItemStack c : containers) {
            addToCarried(c);
        }
    }

    private int fluidAmount(String fluid) {
        Integer v = carriedFluids.get(fluid);
        return v == null ? 0 : v;
    }

    private void addFluid(String fluid, int delta) {
        int v = fluidAmount(fluid) + delta;
        if (v <= 0) {
            carriedFluids.remove(fluid);
        } else {
            carriedFluids.put(fluid, v);
        }
    }

    private void addToCarried(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (ItemStack existing : carried) {
            if (existing.getCount() < existing.getMaxStackSize()
                    && ItemStack.areItemsEqual(existing, stack)
                    && ItemStack.areItemStackTagsEqual(existing, stack)) {
                int move = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
                existing.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
        while (!stack.isEmpty()) {
            ItemStack part = stack.splitStack(Math.max(1, Math.min(stack.getCount(), stack.getMaxStackSize())));
            carried.add(part);
        }
    }

    private int carriedItemCount() {
        int total = 0;
        for (ItemStack stack : carried) {
            total += stack.getCount();
        }
        return total;
    }
}
