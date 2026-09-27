package com.example.examplemod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockOre;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Gives an Engender ally that is holding a pickaxe or an axe the ability to
 * go find matching resources on its own, gather them, and bring them back
 * to the player who equipped it.
 *
 * <p>Resource detection is entirely generic: it goes through
 * {@link Item#getToolClasses(ItemStack)}, {@link Block#getHarvestTool(IBlockState)},
 * {@link Block#getHarvestLevel(IBlockState)} and the Forge Ore Dictionary
 * instead of any hardcoded item/block list, so any modded pickaxe/axe and
 * any modded ore or log block (this mod's own included) is picked up
 * automatically as long as it follows normal Forge conventions.</p>
 *
 * <p>On top of the basic "find one block, mine it, bring it back" loop this
 * also implements:</p>
 * <ul>
 *   <li>Whole-tree / whole-vein clearing: the first log/ore found is used as
 *       a seed for a flood fill over connected matching blocks, queued up
 *       and worked through before the ally heads home.</li>
 *   <li>Self-built scaffolding: when a tree target is too high to path to,
 *       the ally digs up dirt/gravel/cobblestone/stone from around its feet,
 *       pillars up on placed blocks to reach the canopy, and tears the
 *       tower back down (recovering the blocks) once the tree is cleared.</li>
 *   <li>Tunnelling: when an ore target can't be reached by normal
 *       pathfinding, the ally digs a straight 1x2 tunnel toward it through
 *       breakable terrain instead of giving up immediately.</li>
 *   <li>A basic safety check that skips ore blocks sitting directly above
 *       lava rather than approaching them from above.</li>
 * </ul>
 */
public class EntityAIGatherResourceAlly extends EntityAIBase {

    private static final int SEARCH_RADIUS_HORIZONTAL = 36; // widened search area for maximum work-finding
    private static final int SEARCH_RADIUS_VERTICAL = 16;
    private static final int MAX_BLOCKS_SCANNED_PER_SEARCH = 8000;
    private static final int RESCAN_COOLDOWN_TICKS = 5; // near-zero: don't sit around between searches
    private static final int EMPTY_AREA_RESCAN_COOLDOWN_TICKS = 60;
    private static final int MINE_TICKS_REQUIRED = 40; // fallback only, used if the realistic calc can't run
    private static final int UTILITY_MINE_TICKS = 20; // scaffold-material gathering, tunnel digging
    private static final double ARRIVE_AT_BLOCK_DIST_SQ = 3.0 * 3.0;

    /**
     * [採掘リーチ] a real player can only interact with a block within
     * ~4.5 blocks of their eyes; this ally is held to the same limit before
     * it's allowed to start (or keep) mining a target block.
     */
    private static final double MINING_REACH_DISTANCE = 4.5;
    private static final double MINING_REACH_DISTANCE_SQ = MINING_REACH_DISTANCE * MINING_REACH_DISTANCE;
    /** Safety cap so a very hard block + a very weak tool can't stall the AI forever. */
    private static final int MAX_REALISTIC_MINE_TICKS = 1200; // 60s
    private static final double DELIVER_DIST_SQ = 2.5 * 2.5;
    private static final double DELIVER_GIVE_UP_DIST_SQ = 5.0 * 5.0;
    private static final int STUCK_TIMEOUT_TICKS = 100; // give up on a truly dead end faster
    private static final double MOVE_SPEED = 1.0;
    private static final double RETURN_MOVE_SPEED = 1.1;
    private static final float DELIVERY_SEARCH_RANGE = 48.0f;

    /** Requirement 1: don't head back until the ally is actually carrying a full load. */
    private static final int MAX_CARRIED_STACKS = 8;
    private static final int MAX_CARRIED_TOTAL_ITEMS = 128;
    /** 木・鉱脈を途中で投げ出して配達に戻らないための上限（これを超えたら流石に一度戻る）。 */
    private static final int HARD_MAX_CARRIED_STACKS = 24;
    /** 目標へ近づけない（経路が途中までしか無い・進まない）と判断するまでのTick。 */
    private static final int NAV_NO_PROGRESS_LIMIT = 50;
    /** 葉を何ブロックまで越えて、同じ木の枝（原木）を探すか。 */
    private static final int TREE_LEAF_BRIDGE = 3;

    /** How many consecutive "no path" failures before trying scaffolding/tunnelling. */
    private static final int STUCK_TRIGGER_FAILS = 1;
    private static final double SCAFFOLD_TRIGGER_HEIGHT = 1.5;

    /** Requirement 5: clear leaves/bushes blocking the path without stopping, and vacuum nearby loose drops. */
    private static final int OBSTRUCTION_CHECK_INTERVAL_TICKS = 4;
    private static final double DROP_VACUUM_RANGE = 2.0;

    /** Requirement 8: how many consecutive blocks of open air below a step counts as an unsafe cliff. */
    private static final int UNSAFE_FALL_HEIGHT = 4;

    /** Requirement (RayTrace): rough eye height used to decide whether a candidate block is actually visible. */
    private static final double EYE_HEIGHT = 1.6;

    private static final int SCAFFOLD_SEARCH_RADIUS = 5;
    private static final int SCAFFOLD_SEARCH_DOWN = 4;
    private static final int MAX_SCAFFOLD_HEIGHT = 48;
    private static final int SCAFFOLD_STEP_INTERVAL_TICKS = 4;

    private static final int MAX_TUNNEL_BLOCKS = 96;
    private static final int TUNNEL_STEP_INTERVAL_TICKS = 4;

    private static final int MAX_TREE_BLOCKS = 256;
    private static final int MAX_ORE_CLUSTER_BLOCKS = 64;
    private static final int CLUSTER_SEARCH_RADIUS = 32;

    /** How many blocks of "couldn't path there" history to remember, and for how long. */
    private static final int MAX_FAILED_BLOCKS_TRACKED = 64;
    private static final long FAILED_BLOCK_EXPIRY_TICKS = 600; // 30s

    private enum Phase { GOTO_BLOCK, TUNNEL, SCAFFOLD_GATHER, SCAFFOLD_BASE, SCAFFOLD_UP, MINE, SCAFFOLD_DOWN, GOTO_PLAYER, DELIVER }

    private enum NavResult { ARRIVED, MOVING, FAILED }

    private enum BreakResult { OK, TOOL_BROKE, BLOCKED }

    private final EntityFriendlyCreature entity;
    private final EngenderGatheringBridge bridge;
    private final String toolClass;

    private Phase phase = Phase.GOTO_BLOCK;
    private BlockPos targetBlock;
    private int mineProgress;
    /** [採掘速度のリアル化] recomputed for every new target from block hardness + tool speed; see {@link #computeMineTicksRequired}. */
    private int mineTicksRequired = MINE_TICKS_REQUIRED;
    private int idleTicks;
    private int rescanCooldown;
    private int navFailStreak;
    private int obstructionCheckCooldown;
    private final List<ItemStack> carried = new ArrayList<ItemStack>();

    /** Requirement 1 (tree/vein clearing): the rest of the current cluster, queued up behind {@link #targetBlock}. */
    private final Deque<BlockPos> plannedTargets = new ArrayDeque<BlockPos>();
    private final Set<BlockPos> clusterVisited = new HashSet<BlockPos>();

    /** [苗木の植え直し] 今切っている木の根元（一番下の原木）の位置。木を切り終えたら苗木を植える。 */
    private BlockPos treeBaseStump;

    /** Requirement 2 (scaffolding). */
    private final Deque<BlockPos> scaffoldStack = new ArrayDeque<BlockPos>();
    private final List<ItemStack> scaffoldMaterial = new ArrayList<ItemStack>();
    private BlockPos scaffoldSourceBlock;
    private int scaffoldMineProgress;
    private int scaffoldNeeded;
    private int scaffoldPlacedCount;
    private int scaffoldStepCooldown;

    /** Requirement 3c (tunnelling toward buried ore). */
    private BlockPos tunnelCursor;
    private int tunnelStepsUsed;
    private int tunnelStepCooldown;

    /** Bounded, time-expiring blacklist of blocks the ally failed to path to / gave up on. */
    private final Map<BlockPos, Long> lastFailedBlocks = new LinkedHashMap<BlockPos, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, Long> eldest) {
            return this.size() > MAX_FAILED_BLOCKS_TRACKED;
        }
    };

    public EntityAIGatherResourceAlly(EntityFriendlyCreature entity, EngenderGatheringBridge bridge, String toolClass) {
        this.entity = entity;
        this.bridge = bridge;
        this.toolClass = toolClass;
        // Same mutex bits (move + look) as the combat AIs in this mod, so a
        // higher priority combat task always wins the tie and this errand
        // never fights the ally for control mid-fight.
        this.setMutexBits(3);
    }

    /**
     * [道具が無い時の自力調達] 仕事の道具がどこにも無く作る材料も無い間は、
     * 素手で原木を集める（木こりモード）。原木が集まったら作業台→最低限の道具を作って持つ。
     */
    private boolean bootstrapping;
    private int toolCheckCooldown;

    // ------------------------------------------------------------------
    // [チーム・エリア・熟練度] 共通ヘルパー
    // ------------------------------------------------------------------

    private boolean isWorkerRole() {
        return AllyTeamManager.roleOf(this.entity) == AllyTeamManager.Role.WORKER;
    }

    private AllySkills.Skill currentSkill() {
        return "axe".equals(this.activeClass()) ? AllySkills.Skill.WOODCUTTING : AllySkills.Skill.MINING;
    }

    private int scaffoldInterval() {
        return Math.max(1, SCAFFOLD_STEP_INTERVAL_TICKS - AllySkills.level(this.entity, AllySkills.Skill.BUILDING) / 8);
    }

    private AllyAreas.Type areaType() {
        return "axe".equals(this.activeClass()) ? AllyAreas.Type.CHOP : AllyAreas.Type.MINE;
    }

    private List<AllyAreas.Area> workAreaCache;
    private long workAreaCacheTime = -1000;
    private AllyAreas.Type workAreaCacheType;

    private List<AllyAreas.Area> cachedWorkAreas() {
        long now = this.entity.world.getTotalWorldTime();
        if (this.workAreaCache == null || now - this.workAreaCacheTime > 100 || this.workAreaCacheType != this.areaType()) {
            this.workAreaCacheType = this.areaType();
            this.workAreaCache = AllyAreas.workAreas(this.entity, this.workAreaCacheType);
            this.workAreaCacheTime = now;
        }
        return this.workAreaCache;
    }

    private int areaTravelCooldown;
    private boolean travelling;

    /**
     * [エリア指定] 作業エリアが指定されていて、自分がどれからも遠い（24ブロック超）なら
     * 一番近いエリア。作業の途中（狙っている物・足場・荷物がある）なら null。
     */
    private AllyAreas.Area farWorkArea() {
        if (this.targetBlock != null || !this.plannedTargets.isEmpty() || !this.scaffoldStack.isEmpty()
                || !this.carried.isEmpty() || this.bootstrapping) {
            return null;
        }
        List<AllyAreas.Area> areas = this.cachedWorkAreas();
        if (areas.isEmpty()) {
            return null;
        }
        AllyAreas.Area nearest = AllyAreas.nearest(this.entity, areas);
        if (nearest == null || nearest.distanceSqTo(this.entity.posX, this.entity.posY, this.entity.posZ) < 24 * 24) {
            return null;
        }
        return nearest;
    }

    private void steerTowardArea(AllyAreas.Area area) {
        this.idleTicks = 0;
        if (--this.areaTravelCooldown > 0 && !this.entity.getNavigator().noPath()) {
            return;
        }
        this.areaTravelCooldown = 40;
        BlockPos c = area.center();
        double dx = c.getX() - this.entity.posX;
        double dz = c.getZ() - this.entity.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        // 遠い時は、エリアの方向へ32ブロックずつ進む（経路探索の届く範囲で）
        BlockPos goal = len > 32
                ? new BlockPos(this.entity.posX + dx / len * 32, 0, this.entity.posZ + dz / len * 32)
                : c;
        BlockPos top = this.entity.world.getHeight(goal);
        net.minecraft.pathfinding.Path path = SafeRoutePlanner.plan(this.entity, top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
        if (path != null) {
            this.entity.getNavigator().setPath(path, MOVE_SPEED);
        }
    }

    private void stashCarriedToInventory() {
        if (this.carried.isEmpty() || !this.scaffoldStack.isEmpty()) {
            return;
        }
        for (ItemStack s : this.carried) {
            AllyInventory.insertOrDrop(this.entity, s);
        }
        this.carried.clear();
    }

    /** [チーム・回収係] 集めた物を回収係の持ち物へ渡す。渡した個数。 */
    public int giveCarriedTo(EntityFriendlyCreature receiver) {
        int moved = 0;
        java.util.Iterator<ItemStack> it = this.carried.iterator();
        while (it.hasNext()) {
            ItemStack s = it.next();
            if (s.isEmpty()) {
                it.remove();
                continue;
            }
            int before = s.getCount();
            ItemStack rest = AllyInventory.insert(receiver, s);
            moved += before - rest.getCount();
            if (rest.isEmpty()) {
                it.remove();
            } else {
                s.setCount(rest.getCount());
            }
        }
        return moved;
    }

    /** [チーム・トンネル支援] 掘った丸石/石/土を壁補強用に分ける。渡した個数。 */
    public int shareFillBlocks(EntityFriendlyCreature receiver, int max) {
        int moved = 0;
        java.util.Iterator<ItemStack> it = this.carried.iterator();
        while (it.hasNext() && moved < max) {
            ItemStack s = it.next();
            Block b = s.isEmpty() ? null : Block.getBlockFromItem(s.getItem());
            if (b != Blocks.COBBLESTONE && b != Blocks.STONE && b != Blocks.DIRT && b != Blocks.NETHERRACK) {
                continue;
            }
            int take = Math.min(max - moved, s.getCount());
            ItemStack part = s.splitStack(take);
            ItemStack rest = AllyInventory.insert(receiver, part);
            s.grow(rest.getCount());
            moved += take - rest.getCount();
            if (s.isEmpty()) {
                it.remove();
            }
        }
        return moved;
    }

    /** 配達に向かっている最中か（回収係が先回りして運ぶ合図）。 */
    public boolean isHeadingHome() {
        return this.phase == Phase.GOTO_PLAYER || this.phase == Phase.DELIVER;
    }

    /** 実際に今探して掘る対象の種類（素手で原木集め中は "axe"）。 */
    private String activeClass() {
        return this.bootstrapping ? "axe" : this.toolClass;
    }

    private boolean handMatches(String cls) {
        ItemStack stack = this.entity.getHeldItemMainhand();
        return !stack.isEmpty() && stack.getItem().getToolClasses(stack).contains(cls);
    }

    private ItemStack getHeldTool() {
        ItemStack stack = this.entity.getHeldItemMainhand();
        if (this.handMatches(this.toolClass)) {
            return stack;
        }
        if (this.bootstrapping && (stack.isEmpty() || this.handMatches("axe"))) {
            return stack; // 素手（または斧）で原木を取る
        }
        return null;
    }

    /**
     * [仕事に合わせて自分で持ち替える] 手に仕事の道具が無ければ、
     * 1) 持ち物から一番良い物に持ち替え、2) 無ければ持ち物の木材/丸石から作業台で作り、
     * 3) それも無理なら素手で原木集めを始める。戦闘中は銃/剣を持っているので触らない。
     */
    private void ensureTool() {
        if (this.handMatches(this.toolClass)) {
            this.finishBootstrap();
            return;
        }
        if (this.bridge.isFighting(this.entity)) {
            return;
        }
        if (this.toolCheckCooldown > 0) {
            this.toolCheckCooldown--;
            return;
        }
        this.toolCheckCooldown = 20;
        if (this.bootstrapping) {
            this.stashLogsForCrafting();
        }
        try {
            AllyToolManager.equipToolClass(this.entity, this.toolClass, null);
            if (!this.handMatches(this.toolClass) && AllyToolManager.craftBasicTool(this.entity, this.toolClass)) {
                AllyToolManager.equipToolClass(this.entity, this.toolClass, null);
                if (this.handMatches(this.toolClass)) {
                    this.tellOwner("作業台で " + this.entity.getHeldItemMainhand().getDisplayName() + " を作りました。");
                }
            }
        } catch (Throwable ignored) {
            // 道具の判定に失敗 -- 素手で続ける
        }
        if (this.handMatches(this.toolClass)) {
            this.finishBootstrap();
            return;
        }
        if (!this.bootstrapping) {
            this.bootstrapping = true;
            this.clearTargetsKeepingScaffold();
            this.tellOwner("道具が無いので、素手で木を切って作業台と道具を作ります。");
        }
        ItemStack hand = this.entity.getHeldItemMainhand();
        if (!hand.isEmpty() && !this.handMatches("axe")) {
            AllyToolManager.stowMainhand(this.entity); // 関係ない物は持ち物へしまって素手に
        }
    }

    private void finishBootstrap() {
        if (this.bootstrapping) {
            this.bootstrapping = false;
            this.stashLogsForCrafting();
            if (!"axe".equals(this.toolClass)) {
                this.clearTargetsKeepingScaffold();
            }
        }
    }

    private void clearTargetsKeepingScaffold() {
        if (this.scaffoldStack.isEmpty()) {
            this.targetBlock = null;
            this.plannedTargets.clear();
            this.clusterVisited.clear();
            this.phase = Phase.GOTO_BLOCK;
        }
    }

    /** 集めた原木・板材・丸石は、道具作り用に自分の持ち物へ移す（プレイヤーへ配達しない）。 */
    private void stashLogsForCrafting() {
        java.util.Iterator<ItemStack> it = this.carried.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack.isEmpty()) {
                it.remove();
                continue;
            }
            boolean wood = false;
            for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack)) {
                String name = net.minecraftforge.oredict.OreDictionary.getOreName(id);
                if ("logWood".equals(name) || "plankWood".equals(name)) {
                    wood = true;
                    break;
                }
            }
            if (wood) {
                AllyInventory.insertOrDrop(this.entity, stack);
                it.remove();
            }
        }
    }

    private void tellOwner(String message) {
        EntityPlayer owner = this.findKnownOwnerPlayer();
        if (owner != null) {
            owner.sendMessage(new net.minecraft.util.text.TextComponentString("[" + this.entity.getName() + "] " + message));
        }
    }

    @Override
    public boolean shouldExecute() {
        // [木こり・採掘が止まる不具合の修正] This used to bail out completely
        // whenever entity.getAttackTarget() was non-null. That made sense
        // back when the only thing that could ever set an attack target was
        // reactive (the player being hit), but EngenderGatheringBridge now
        // gives every ally an always-on EntityAINearestAttackableTarget
        // radar that sets attackTarget the instant ANY hostile mob is
        // visible nearby -- extremely common while mining underground or
        // near a tree line at night. A pickaxe/axe-holding ally has no
        // combat AI at all (applyWeaponAI only attaches one for guns/swords),
        // so nothing was ever consuming or clearing that target: gathering
        // simply froze for as long as some mob merely existed in view,
        // which is exactly what "doesn't cut wood"/"mining isn't proactive"
        // looked like in practice. It's also redundant: EntityAITasks' own
        // priority/mutex system already lets the survival AI (priority 1)
        // and a real combat AI (priority 2, only ever present when armed)
        // preempt this one (priority 4) whenever they actually need to, with
        // or without this extra check.
        if (!this.isWorkerRole()) {
            // [チーム] 回収係・支援・護衛になった: 集めた物は自分の持ち物へ（回収係として運ぶ）
            this.stashCarriedToInventory();
            return false;
        }
        this.ensureTool();
        if (this.getHeldTool() == null) {
            return false;
        }
        // [エリア指定] 作業エリアが遠ければ、まずそこへ向かう（このタスクとして移動する）
        this.travelling = this.farWorkArea() != null;
        if (this.travelling) {
            return true;
        }
        if (!this.carried.isEmpty()) {
            return true;
        }
        if (this.targetBlock != null || !this.plannedTargets.isEmpty() || !this.scaffoldStack.isEmpty()) {
            return true;
        }
        this.targetBlock = this.pickNextTarget(true);
        return this.targetBlock != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        // See shouldExecute(): deliberately NOT gated on getAttackTarget()
        // anymore -- see the comment there.
        if (this.getHeldTool() == null) {
            return false;
        }
        if (!this.isWorkerRole() && this.scaffoldStack.isEmpty()) {
            return false; // 足場の上なら降りてから役割を替える
        }
        if (this.travelling) {
            return this.farWorkArea() != null;
        }
        return this.idleTicks <= STUCK_TIMEOUT_TICKS;
    }

    @Override
    public void startExecuting() {
        this.idleTicks = 0;
        // `phase` is deliberately left as-is when there's mid-block state to
        // resume (targetBlock/plannedTargets/scaffoldStack): resetTask()
        // never clears it, so this picks the errand back up exactly where
        // combat interrupted it instead of restarting the trip home.
        if (this.targetBlock == null && this.plannedTargets.isEmpty() && this.scaffoldStack.isEmpty()) {
            // Requirement 1: only make a special trip home if the bag is
            // actually full -- otherwise get straight back to gathering.
            this.phase = this.isCarriedFull() ? Phase.GOTO_PLAYER : Phase.GOTO_BLOCK;
        }
    }

    @Override
    public void resetTask() {
        this.entity.getNavigator().clearPath();
        this.idleTicks = 0;
        this.mineProgress = 0;
        // `carried`, `targetBlock`, `plannedTargets` and any in-progress
        // scaffold/tunnel state are deliberately kept so the ally resumes
        // its errand (instead of losing gathered goods, a half-built tower,
        // or a half-dug tunnel) the next time this task is picked, e.g.
        // right after a combat interruption ends.
    }

    @Override
    public void updateTask() {
        this.ensureTool();
        ItemStack tool = this.getHeldTool();
        if (tool == null) {
            return;
        }
        if (this.travelling) {
            AllyAreas.Area far = this.farWorkArea();
            if (far != null) {
                this.steerTowardArea(far);
                return;
            }
            this.travelling = false;
            this.entity.getNavigator().clearPath();
        }
        World world = this.entity.getEntityWorld();
        // 暗い所（坑道・夜間）では、たいまつを作って置いていく。
        if (this.phase != Phase.SCAFFOLD_UP && this.phase != Phase.SCAFFOLD_DOWN) {
            this.refillTorchSupplies();
            AllyAIUtil.placeTorchIfDark(this.entity, this.torchSupplies);
        }
        switch (this.phase) {
            case GOTO_BLOCK:
                this.updateGoToBlock(world);
                break;
            case TUNNEL:
                this.updateTunnel(world);
                break;
            case SCAFFOLD_GATHER:
                this.updateScaffoldGather(world);
                break;
            case SCAFFOLD_BASE:
                this.updateScaffoldBase(world);
                break;
            case SCAFFOLD_UP:
                this.updateScaffoldUp(world);
                break;
            case MINE:
                this.updateMine(world);
                break;
            case SCAFFOLD_DOWN:
                this.updateScaffoldDown(world);
                break;
            case GOTO_PLAYER:
                this.updateGoToPlayer();
                break;
            case DELIVER:
                this.updateDeliver();
                break;
        }
    }

    // ------------------------------------------------------------------
    // Movement helper shared by every phase that just needs to walk somewhere
    // ------------------------------------------------------------------

    private BlockPos navGoal;
    private double navLastX;
    private double navLastZ;
    private int navFrozenTicks;
    private double navBestDistSq;
    private int navNoProgressTicks;

    private NavResult navigateTowards(BlockPos pos, double speed) {
        this.maybeClearObstructions();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        this.entity.getLookHelper().setLookPosition(cx, cy, cz, 30.0f, 30.0f);
        double distSq = this.entity.getDistanceSq(cx, cy, cz);
        if (distSq <= ARRIVE_AT_BLOCK_DIST_SQ) {
            this.entity.getNavigator().clearPath();
            this.navGoal = null;
            return NavResult.ARRIVED;
        }
        // [ツルハシ・斧でまともに仕事しない不具合の根本原因]
        // 地中の鉱石や高い枝へは、バニラの経路探索は「行ける所まで」の途中経路を返す。
        // 以前はそれを「移動中」とみなし、行き止まりで立ち尽くし→タイムアウト→再開…を
        // 延々と繰り返して、トンネルも足場も始めなかった。近づけなくなったら FAILED を返す。
        // [障害物で動けない] 経路はあるのに体が動いていない → 草花・葉を片付けて跳ぶ
        double mv = (this.entity.posX - this.navLastX) * (this.entity.posX - this.navLastX)
                + (this.entity.posZ - this.navLastZ) * (this.entity.posZ - this.navLastZ);
        this.navLastX = this.entity.posX;
        this.navLastZ = this.entity.posZ;
        if (!this.entity.getNavigator().noPath() && mv < 0.0025) {
            if (++this.navFrozenTicks % 25 == 0) {
                AllyAIUtil.unstick(this.entity, this.carried);
                this.entity.getNavigator().clearPath();
            }
        } else {
            this.navFrozenTicks = 0;
        }
        if (!pos.equals(this.navGoal)) {
            this.navGoal = pos;
            this.navBestDistSq = distSq;
            this.navNoProgressTicks = 0;
        } else if (distSq < this.navBestDistSq - 0.25) {
            this.navBestDistSq = distSq;
            this.navNoProgressTicks = 0;
        } else if (++this.navNoProgressTicks > NAV_NO_PROGRESS_LIMIT) {
            this.entity.getNavigator().clearPath();
            this.navGoal = null;
            return NavResult.FAILED;
        }
        if (this.entity.getNavigator().noPath()) {
            World world = this.entity.getEntityWorld();
            // Requirement 8: never even start a path whose very first step
            // would walk into lava or off an unguarded ledge -- treat it the
            // same as any other unreachable target so the normal blacklist /
            // scaffold / tunnel fallback in each phase takes over instead.
            if (this.isImmediateStepHazardous(world, new BlockPos(this.entity), pos)) {
                return NavResult.FAILED;
            }
            // [安全なルート選択] 危険度（溶岩・落下・敵）と体力から最短か迂回かを選ぶ
            net.minecraft.pathfinding.Path planned = SafeRoutePlanner.plan(this.entity, cx, pos.getY(), cz);
            boolean started = planned != null && this.entity.getNavigator().setPath(planned, speed);
            if (!started) {
                this.navGoal = null;
                return NavResult.FAILED;
            }
            // 途中までしか無い経路で、しかもその終点が今いる場所 = もう近づけない。
            net.minecraft.pathfinding.Path path = this.entity.getNavigator().getPath();
            net.minecraft.pathfinding.PathPoint end = path == null ? null : path.getFinalPathPoint();
            if (end != null) {
                double ex = end.x + 0.5 - cx;
                double ey = end.y + 0.5 - cy;
                double ez = end.z + 0.5 - cz;
                boolean partial = ex * ex + ey * ey + ez * ez > ARRIVE_AT_BLOCK_DIST_SQ;
                double selfSq = this.entity.getDistanceSq(end.x + 0.5, end.y, end.z + 0.5);
                if (partial && selfSq < 2.25) {
                    this.entity.getNavigator().clearPath();
                    this.navGoal = null;
                    return NavResult.FAILED;
                }
            }
        }
        return NavResult.MOVING;
    }

    /** Requirement 8: a cheap, defensive re-check on top of whatever hazard-avoidance the navigator already does. */
    private boolean isImmediateStepHazardous(World world, BlockPos from, BlockPos to) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        if (dx == 0 && dz == 0) {
            return false;
        }
        BlockPos step = from.add(dx, 0, dz);
        Material belowMaterial = world.getBlockState(step.down()).getMaterial();
        if (belowMaterial == Material.LAVA) {
            return true;
        }
        int airBelow = 0;
        for (int i = 1; i <= UNSAFE_FALL_HEIGHT; i++) {
            if (world.isAirBlock(step.down(i))) {
                airBelow++;
            } else {
                break;
            }
        }
        return airBelow >= UNSAFE_FALL_HEIGHT;
    }

    /** Requirement 5: break leaves/bushes in the ally's way instead of stopping for them. */
    private void maybeClearObstructions() {
        if (this.obstructionCheckCooldown > 0) {
            this.obstructionCheckCooldown--;
            return;
        }
        this.obstructionCheckCooldown = OBSTRUCTION_CHECK_INTERVAL_TICKS;
        World world = this.entity.getEntityWorld();
        BlockPos base = new BlockPos(this.entity);
        // [木こり: 葉が残る不具合の修正] 幹を切り倒した直後、頭上に残る葉の塊は
        // 従来ここでは足元と頭上1マスしか片付けなかったため、切った木のほとんどの
        // 葉が回収されずに浮いたまま残っていた（苗木・りんごも一切落ちない）。
        // 頭上方向へ数マス分の縦の列も片付けるようにして、通常の高さの木なら
        // 幹を切り終えた時点でその場から届く葉がほぼ回収される。
        int[][] offsets = {
                {0, 0, 0}, {0, 1, 0},
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {0, 2, 0}, {0, 3, 0}, {0, 4, 0}, {0, 5, 0},
                {1, 2, 0}, {-1, 2, 0}, {0, 2, 1}, {0, 2, -1},
                {1, 3, 0}, {-1, 3, 0}, {0, 3, 1}, {0, 3, -1},
        };
        for (int[] offset : offsets) {
            BlockPos pos = base.add(offset[0], offset[1], offset[2]);
            if (world.isAirBlock(pos)) {
                continue;
            }
            IBlockState state = world.getBlockState(pos);
            if (!this.isClearableObstruction(state)) {
                continue;
            }
            // Cosmetic/cheap clearing: no durability cost, no mining timer,
            // just don't let leaves or grass slow the errand down.
            this.collectDropsInto(world, pos, state, ItemStack.EMPTY, this.carried, this.findKnownOwnerPlayer());
            world.setBlockToAir(pos);
        }
    }

    private boolean isClearableObstruction(IBlockState state) {
        Block block = state.getBlock();
        // Only leaves and wild grass -- plain BlockBush also matched crops,
        // saplings and flowers, so the ally trampled the player's farm.
        return block instanceof BlockLeaves
                || block instanceof net.minecraft.block.BlockTallGrass
                || block instanceof net.minecraft.block.BlockDeadBush
                || block instanceof net.minecraft.block.BlockDoublePlant;
    }

    // ------------------------------------------------------------------
    // GOTO_BLOCK / MINE -- the main "walk to it and dig it" loop
    // ------------------------------------------------------------------

    private void updateGoToBlock(World world) {
        if (this.targetBlock == null || !this.isStillValidTarget(world, this.targetBlock)) {
            this.targetBlock = this.pickNextTarget(true);
            if (this.targetBlock == null) {
                if (!this.scaffoldStack.isEmpty()) {
                    this.phase = Phase.SCAFFOLD_DOWN;
                    return;
                }
                // [配達されない不具合の修正] Nothing left to gather nearby but
                // the bag isn't full: before, the task just ended here, then
                // shouldExecute() restarted it (carried != empty), it ended
                // again... forever, and a part-full load never reached the
                // player. Take whatever we have home instead.
                if (!this.carried.isEmpty()) {
                    this.phase = Phase.GOTO_PLAYER;
                    this.idleTicks = 0;
                    return;
                }
                this.idleTicks = STUCK_TIMEOUT_TICKS + 1;
                return;
            }
        }

        NavResult nav = this.navigateTowards(this.targetBlock, MOVE_SPEED);
        if (nav == NavResult.ARRIVED) {
            // Requirement 8/9: the world can change between when this block
            // was queued and now (e.g. lava flowed in) -- re-verify safety
            // right before committing to stand here and mine.
            if ("pickaxe".equals(this.activeClass()) && this.isDangerousBelow(world, this.targetBlock)) {
                this.blacklistBlock(this.targetBlock);
                this.targetBlock = this.pickNextTarget(false);
                this.idleTicks += 5;
                return;
            }
            this.entity.getNavigator().clearPath();
            if ("pickaxe".equals(this.activeClass()) && (!this.hasLineOfSight(world, this.targetBlock)
                    || this.eyeDistSq(this.targetBlock) > MINING_REACH_DISTANCE_SQ)) {
                // 石の向こうの鉱石を「壁越しに」掘らない。視線上の石を掘り進めてから掘る。
                this.startTunnel();
                return;
            }
            this.phase = Phase.MINE;
            this.mineProgress = 0;
            ItemStack toolInHand = this.getHeldTool();
            this.mineTicksRequired = this.computeMineTicksRequired(world, this.targetBlock, toolInHand);
            this.idleTicks = 0;
            this.navFailStreak = 0;
            return;
        }
        if (nav == NavResult.MOVING) {
            this.navFailStreak = 0;
            this.idleTicks++;
            return;
        }

        // nav == FAILED: normal pathfinding can't get us there.
        this.navFailStreak++;
        if (this.navFailStreak < STUCK_TRIGGER_FAILS) {
            this.idleTicks += 5;
            return;
        }

        double verticalGap = this.targetBlock.getY() - this.entity.posY;
        if ("axe".equals(this.activeClass())) {
            // 近づけなくても手が届く所にある原木はそのまま切る（以前はここで諦めて取り残した）。
            double ex = this.targetBlock.getX() + 0.5 - this.entity.posX;
            double ey = this.targetBlock.getY() + 0.5 - (this.entity.posY + EYE_HEIGHT);
            double ez = this.targetBlock.getZ() + 0.5 - this.entity.posZ;
            if (ex * ex + ey * ey + ez * ez <= MINING_REACH_DISTANCE_SQ) {
                this.entity.getNavigator().clearPath();
                this.phase = Phase.MINE;
                this.mineProgress = 0;
                this.mineTicksRequired = this.computeMineTicksRequired(world, this.targetBlock, this.getHeldTool());
                this.navFailStreak = 0;
                return;
            }
        }
        if ("axe".equals(this.activeClass()) && verticalGap > SCAFFOLD_TRIGGER_HEIGHT) {
            // Requirement 2: too high to walk to -- build a tower instead of giving up.
            // Gather enough for the tallest log of THIS tree up front, so the
            // ally never runs out halfway up and has to tear the tower down.
            int topY = this.targetBlock.getY();
            for (BlockPos p : this.plannedTargets) {
                if (Math.abs(p.getX() - this.targetBlock.getX()) <= 3 && Math.abs(p.getZ() - this.targetBlock.getZ()) <= 3) {
                    topY = Math.max(topY, p.getY());
                }
            }
            this.scaffoldNeeded = Math.min(MAX_SCAFFOLD_HEIGHT, (int) Math.ceil(topY - this.entity.posY) + 1);
            this.scaffoldPlacedCount = 0;
            this.navFailStreak = 0;
            this.phase = Phase.SCAFFOLD_GATHER;
            return;
        }
        if ("pickaxe".equals(this.activeClass())) {
            // Requirement 3c: dig a tunnel toward the ore instead of giving up.
            this.startTunnel();
            return;
        }

        // Nothing else to try for this block: blacklist it and immediately move on to the next one.
        this.blacklistBlock(this.targetBlock);
        this.navFailStreak = 0;
        this.targetBlock = this.pickNextTarget(false);
        if (this.targetBlock == null) {
            if (!this.scaffoldStack.isEmpty()) {
                this.phase = Phase.SCAFFOLD_DOWN;
                return;
            }
            this.idleTicks += 10;
        }
    }

    /** Requirement 1: keep gathering until the bag is actually full; requirement 2: no cooldown in between. */
    private void continueGatheringOrGoHome() {
        if (this.isCarriedFull()) {
            this.phase = Phase.GOTO_PLAYER;
            return;
        }
        BlockPos next = this.pickNextTarget(false);
        if (next != null) {
            this.targetBlock = next;
            this.phase = Phase.GOTO_BLOCK;
            return;
        }
        // Truly nothing left nearby: head home with whatever we have, even if not full.
        this.phase = this.carried.isEmpty() ? Phase.GOTO_BLOCK : Phase.GOTO_PLAYER;
    }

    private boolean isCarriedFull() {
        if (this.carried.size() >= MAX_CARRIED_STACKS + AllySkills.carryBonus(this.entity)) {
            return true;
        }
        return this.carriedItemCount() >= MAX_CARRIED_TOTAL_ITEMS;
    }

    private void updateMine(World world) {
        if (this.targetBlock == null || !this.isStillValidTarget(world, this.targetBlock)) {
            this.targetBlock = null;
            this.mineProgress = 0;
            this.phase = Phase.GOTO_BLOCK;
            return;
        }
        double cx = this.targetBlock.getX() + 0.5;
        double cy = this.targetBlock.getY() + 0.5;
        double cz = this.targetBlock.getZ() + 0.5;
        // [採掘リーチ] never keep "mining" a block the ally has drifted away
        // from (knockback, pushed by another mob, etc.) -- re-approach
        // instead of digging from an unrealistic distance.
        if (this.eyeDistSq(this.targetBlock) > MINING_REACH_DISTANCE_SQ) {
            this.mineProgress = 0;
            // Up on a tower, walking toward it means stepping off the tower.
            // Come down properly instead (the block stays queued).
            if (!this.scaffoldStack.isEmpty()) {
                this.plannedTargets.addFirst(this.targetBlock);
                this.targetBlock = null;
                this.phase = Phase.SCAFFOLD_DOWN;
                return;
            }
            this.phase = Phase.GOTO_BLOCK;
            return;
        }
        this.entity.getLookHelper().setLookPosition(cx, cy, cz, 30.0f, 30.0f);
        if (this.mineProgress % 20 == 0) {
            TargetRegistry.claim(this.entity, this.targetBlock, 600);
        }
        this.mineProgress++;
        this.idleTicks = 0;
        if (this.mineProgress % 8 == 0) {
            IBlockState state = world.getBlockState(this.targetBlock);
            world.playEvent(2001, this.targetBlock, Block.getStateId(state));
        }
        if (this.mineProgress < this.mineTicksRequired) {
            return;
        }
        this.harvestTargetBlock(world);
    }

    /**
     * [採掘速度のリアル化] mirrors the vanilla break-time formula
     * ({@code Block#getPlayerRelativeBlockHardness}) instead of the old
     * fixed-tick timer: block hardness, the tool's own destroy speed
     * ({@link ItemStack#getDestroySpeed}), Efficiency, Haste/Mining Fatigue
     * (when the ally happens to have either) and the same water/off-ground
     * penalties a real player gets all feed into how long this specific
     * block actually takes.
     */
    private int computeMineTicksRequired(World world, BlockPos pos, ItemStack tool) {
        IBlockState state = world.getBlockState(pos);
        float hardness = state.getBlockHardness(world, pos);
        if (hardness < 0.0f) {
            // Unbreakable (e.g. bedrock) -- shouldn't normally be a chosen
            // target since canHarvest() already filters these out, but
            // never hang forever if one slips through.
            return MAX_REALISTIC_MINE_TICKS;
        }
        if (hardness <= 0.0f) {
            return 1;
        }

        float speed = (tool == null || tool.isEmpty()) ? 1.0f : tool.getDestroySpeed(state);
        if (speed <= 0.0f) {
            speed = 1.0f;
        }
        if (speed > 1.0f && tool != null && !tool.isEmpty()) {
            int efficiencyLevel = EnchantmentHelper.getEnchantmentLevel(Enchantments.EFFICIENCY, tool);
            if (efficiencyLevel > 0) {
                speed += (float) (efficiencyLevel * efficiencyLevel + 1);
            }
        }
        if (this.entity.isPotionActive(MobEffects.HASTE)) {
            PotionEffect haste = this.entity.getActivePotionEffect(MobEffects.HASTE);
            speed *= 1.0f + (float) (haste.getAmplifier() + 1) * 0.2f;
        }
        if (this.entity.isPotionActive(MobEffects.MINING_FATIGUE)) {
            PotionEffect fatigue = this.entity.getActivePotionEffect(MobEffects.MINING_FATIGUE);
            float fatigueMultiplier;
            switch (Math.min(fatigue.getAmplifier(), 3)) {
                case 0: fatigueMultiplier = 0.3f; break;
                case 1: fatigueMultiplier = 0.09f; break;
                case 2: fatigueMultiplier = 0.0027f; break;
                default: fatigueMultiplier = 0.00081f; break;
            }
            speed *= fatigueMultiplier;
        }
        boolean hasAquaAffinity = tool != null && !tool.isEmpty()
                && EnchantmentHelper.getEnchantmentLevel(Enchantments.AQUA_AFFINITY, tool) > 0;
        if (this.entity.isInsideOfMaterial(Material.WATER) && !hasAquaAffinity) {
            speed /= 5.0f;
        }
        if (!this.entity.onGround) {
            speed /= 5.0f;
        }

        boolean canHarvest = this.canHarvest(tool, state);
        float damagePerTick = speed / hardness / (canHarvest ? 30.0f : 100.0f);
        if (damagePerTick <= 0.0f) {
            return MINE_TICKS_REQUIRED;
        }
        int ticks = (int) Math.ceil(1.0f / damagePerTick);
        // [熟練度] 採掘/伐採スキルのレベルで速くなる
        ticks = (int) Math.ceil(ticks / AllySkills.speedMultiplier(this.entity, this.currentSkill()));
        return Math.max(1, Math.min(ticks, MAX_REALISTIC_MINE_TICKS));
    }

    private void harvestTargetBlock(World world) {
        BlockPos minedPos = this.targetBlock;
        BreakResult result = this.breakBlock(world, minedPos, this.carried);
        this.targetBlock = null;
        this.mineProgress = 0;
        this.tunnelCursor = null;

        if (result == BreakResult.TOOL_BROKE) {
            // Everything carried/held has already been safely dropped inside
            // breakBlock(); the bridge tears this task down on its own once
            // it notices the main hand went empty.
            return;
        }
        if (result == BreakResult.BLOCKED) {
            // A protection/claim mod (or similar) vetoed this specific break
            // -- don't waste time coming back to it, just move on.
            this.blacklistBlock(minedPos);
        }

        if (!this.scaffoldStack.isEmpty()) {
            // [足場の後にバグる不具合の修正] Used to climb straight back down
            // after ONE log, then rebuild the whole tower for the next canopy
            // log, forever. While up on the tower, first clear every queued
            // block that's within reach from here; only then come down.
            BlockPos reachable = this.pollReachablePlannedTarget(world);
            if (reachable != null) {
                this.targetBlock = reachable;
                this.phase = Phase.MINE;
                this.mineProgress = 0;
                this.mineTicksRequired = this.computeMineTicksRequired(world, reachable, this.getHeldTool());
                return;
            }
            // More of this tree ABOVE us: keep building up instead of coming
            // down and rebuilding the whole tower for it.
            BlockPos higher = this.lowestPlannedTargetAbove();
            if (higher != null && !this.scaffoldMaterial.isEmpty()) {
                this.plannedTargets.remove(higher);
                this.targetBlock = higher;
                this.scaffoldNeeded = Math.max(this.scaffoldNeeded,
                        this.scaffoldPlacedCount + (int) Math.ceil(higher.getY() - this.entity.posY) + 1);
                this.phase = Phase.SCAFFOLD_UP;
                return;
            }
            this.phase = Phase.SCAFFOLD_DOWN;
            return;
        }

        // Requirement 1: stop only once the bag is actually full (or the
        // tool broke, handled above) -- otherwise keep working through the
        // rest of this tree/vein before deciding the trip is over.
        // [大きい木を途中で投げ出す不具合] 木・鉱脈が残っている間は、上限いっぱいまで続ける。
        java.util.UUID ordOwner = this.ownerIdCached();
        int ordered = ordOwner == null ? 0 : AllyProjectBoard.orderedCount(ordOwner, this.carried);
        if (ordered >= 32 || (ordered > 0 && this.plannedTargets.isEmpty())) {
            this.phase = Phase.GOTO_PLAYER; // まず発注品をクラフト役へ（deliverToCrafter）
            return;
        }
        if (this.plannedTargets.isEmpty() ? this.isCarriedFull() : this.carried.size() >= HARD_MAX_CARRIED_STACKS) {
            this.phase = Phase.GOTO_PLAYER;
            return;
        }
        BlockPos next = this.pollPlannedTarget(world);
        if (next != null) {
            this.targetBlock = next;
            this.phase = Phase.GOTO_BLOCK;
            return;
        }

        if ("axe".equals(this.activeClass())) {
            // [苗木の植え直し] この木の原木は全部切り終えた -- 根元に苗木を植える。
            this.tryReplantSapling(world);
        }
        this.continueGatheringOrGoHome();
    }

    /**
     * [苗木の植え直し] 木を根元から切り終えた直後に呼ばれる。切った根元の場所が
     * まだ空気で、その下が草/土のままなら、持ち物の中の苗木（葉を片付けた時に
     * 落ちた物）を1つ使って植え直す。苗木が無ければ何もしない。
     */
    private void tryReplantSapling(World world) {
        BlockPos stump = this.treeBaseStump;
        this.treeBaseStump = null;
        if (stump == null || !world.isAirBlock(stump)) {
            return;
        }
        Block below = world.getBlockState(stump.down()).getBlock();
        if (below != Blocks.GRASS && below != Blocks.DIRT) {
            return;
        }
        java.util.Iterator<ItemStack> it = this.carried.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack.isEmpty()) {
                it.remove();
                continue;
            }
            Block block = Block.getBlockFromItem(stack.getItem());
            if (!(block instanceof net.minecraft.block.BlockSapling)) {
                continue;
            }
            world.setBlockState(stump, block.getStateFromMeta(stack.getMetadata()), 3);
            stack.shrink(1);
            if (stack.isEmpty()) {
                it.remove();
            }
            this.tellOwner("木を切った後に苗木を植えておきました。");
            return;
        }
    }

    // ------------------------------------------------------------------
    // Requirement 2: self-built scaffolding to reach high trees
    // ------------------------------------------------------------------

    private void updateScaffoldGather(World world) {
        this.idleTicks = 0;
        // Count ITEMS, not stacks: drops are merged into stacks, so size()
        // stayed at 1 and this gathered forever.
        int stock = 0;
        for (ItemStack s : this.scaffoldMaterial) {
            stock += s.getCount();
        }
        if (stock + this.scaffoldPlacedCount >= this.scaffoldNeeded) {
            if (this.scaffoldStack.isEmpty()) {
                // [大きい木] 幹の横ではなく、狙う原木の真下（近く）に足場を建てる。
                this.scaffoldBase = this.findScaffoldBase(world);
                this.scaffoldBaseTicks = 0;
                this.phase = Phase.SCAFFOLD_BASE;
            } else {
                this.phase = Phase.SCAFFOLD_UP;
            }
            return;
        }
        // 持ち物に土・丸石・石があれば先にそれを使う（地面に穴を掘らずに済む）。
        if (this.pullScaffoldFromInventory(this.scaffoldNeeded - this.scaffoldPlacedCount - stock)) {
            return;
        }
        if (this.scaffoldSourceBlock == null || !this.isScaffoldSource(world.getBlockState(this.scaffoldSourceBlock))) {
            this.scaffoldSourceBlock = this.findScaffoldSource(world);
            this.scaffoldMineProgress = 0;
            if (this.scaffoldSourceBlock == null) {
                // Nothing to build a tower with nearby: give up on this particular tree.
                this.abandonScaffoldClimb();
                return;
            }
        }
        NavResult nav = this.navigateTowards(this.scaffoldSourceBlock, MOVE_SPEED);
        if (nav == NavResult.FAILED) {
            this.blacklistBlock(this.scaffoldSourceBlock);
            this.scaffoldSourceBlock = null;
            return;
        }
        if (nav == NavResult.MOVING) {
            return;
        }
        this.scaffoldMineProgress++;
        if (this.scaffoldMineProgress < UTILITY_MINE_TICKS) {
            return;
        }
        BreakResult result = this.breakBlock(world, this.scaffoldSourceBlock, this.scaffoldMaterial);
        if (result == BreakResult.BLOCKED) {
            // Vetoed (e.g. a claim mod) -- don't keep circling back to it.
            this.blacklistBlock(this.scaffoldSourceBlock);
        }
        this.scaffoldSourceBlock = null;
        this.scaffoldMineProgress = 0;
        // If the tool broke here, breakBlock() already flushed everything and
        // the bridge will remove this task shortly; nothing more to do.
    }

    private BlockPos scaffoldBase;
    private int scaffoldBaseTicks;

    /** 狙う原木の真下付近で、立てる地面（足元が固く、頭上2マス空き）を探す。 */
    private boolean airOrJunk(World world, BlockPos p) {
        return world.isAirBlock(p) || AllyAIUtil.isPassableJunk(world.getBlockState(p));
    }

    private BlockPos findScaffoldBase(World world) {
        if (this.targetBlock == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos column = new BlockPos(this.targetBlock.getX() + dx, this.targetBlock.getY() - 1, this.targetBlock.getZ() + dz);
                // 上から下へ: 最初に見つかる「空気の上の固い地面」
                for (int y = column.getY(); y >= this.targetBlock.getY() - MAX_SCAFFOLD_HEIGHT && y > 1; y--) {
                    BlockPos feet = new BlockPos(column.getX(), y, column.getZ());
                    IBlockState below = world.getBlockState(feet.down());
                    // [花・草で足場が建てられない不具合] 花や草のある所も「空き」とみなす（登る前に片付ける）
                    if (!this.airOrJunk(world, feet)) {
                        if (!this.isClearableObstruction(world.getBlockState(feet))) {
                            break; // 原木や地面の中 -- この列は使えない
                        }
                        continue;
                    }
                    if (below.getMaterial().isSolid() && !(below.getBlock() instanceof BlockLeaves)
                            && !below.getMaterial().isLiquid() && this.airOrJunk(world, feet.up())) {
                        double score = dx * dx + dz * dz + Math.abs(this.entity.posY - y) * 0.1;
                        if (score < bestScore) {
                            bestScore = score;
                            best = feet;
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    private void updateScaffoldBase(World world) {
        this.idleTicks = 0;
        BlockPos base = this.scaffoldBase;
        if (base == null) {
            this.phase = Phase.SCAFFOLD_UP; // 探せなかった: 今いる所から建てる
            return;
        }
        double hx = this.entity.posX - (base.getX() + 0.5);
        double hz = this.entity.posZ - (base.getZ() + 0.5);
        boolean there = hx * hx + hz * hz < 0.8 * 0.8 && Math.abs(this.entity.posY - base.getY()) < 1.2;
        if (!there && ++this.scaffoldBaseTicks < 200) {
            if (this.entity.getNavigator().noPath()) {
                if (!this.entity.getNavigator().tryMoveToXYZ(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, MOVE_SPEED)) {
                    this.scaffoldBaseTicks = 200;
                }
            }
            return;
        }
        this.entity.getNavigator().clearPath();
        if (!there && this.entity.getDistanceSq(base.getX() + 0.5, base.getY(), base.getZ() + 0.5) < 2.5 * 2.5) {
            this.entity.setPositionAndUpdate(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        }
        // ここから必要な高さを数え直す
        if (this.targetBlock != null) {
            int topY = this.targetBlock.getY();
            for (BlockPos p : this.plannedTargets) {
                if (Math.abs(p.getX() - this.targetBlock.getX()) <= 3 && Math.abs(p.getZ() - this.targetBlock.getZ()) <= 3) {
                    topY = Math.max(topY, p.getY());
                }
            }
            this.scaffoldNeeded = Math.max(this.scaffoldNeeded,
                    Math.min(MAX_SCAFFOLD_HEIGHT, (int) Math.ceil(topY - this.entity.posY) + 1));
        }
        this.phase = Phase.SCAFFOLD_UP;
    }

    private boolean pullScaffoldFromInventory(int wanted) {
        if (wanted <= 0) {
            return false;
        }
        net.minecraft.inventory.InventoryBasic inv = AllyInventory.get(this.entity);
        boolean moved = false;
        for (int i = 0; i < inv.getSizeInventory() && wanted > 0; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block == Blocks.DIRT || block == Blocks.COBBLESTONE || block == Blocks.STONE) {
                int take = Math.min(wanted, stack.getCount());
                ItemStack part = inv.decrStackSize(i, take);
                this.addToList(this.scaffoldMaterial, part);
                wanted -= take;
                moved = true;
            }
        }
        return moved;
    }

    private void updateScaffoldUp(World world) {
        this.idleTicks = 0;
        if (this.targetBlock != null) {
            double sameColumnDistSq = squaredDistance(
                    new BlockPos(this.entity.posX, this.targetBlock.getY(), this.entity.posZ), this.targetBlock);
            if (this.entity.posY >= this.targetBlock.getY() - 0.5 && sameColumnDistSq <= ARRIVE_AT_BLOCK_DIST_SQ) {
                this.entity.getNavigator().clearPath();
                this.phase = Phase.MINE;
                this.mineProgress = 0;
                this.mineTicksRequired = this.computeMineTicksRequired(world, this.targetBlock, this.getHeldTool());
                return;
            }
        }
        if (this.entity.posY > 255.0) {
            // Safety net: never climb past the world height limit.
            this.abandonScaffoldClimb();
            return;
        }
        if (this.scaffoldMaterial.isEmpty() && !this.scaffoldStack.isEmpty() && this.scaffoldPlacedCount < this.scaffoldNeeded) {
            // Ran out halfway up: walking off to fetch more used to leave the
            // tower behind and start a second one from the ground. Climb down
            // (recovering the blocks as stock) and gather from the bottom.
            this.phase = Phase.SCAFFOLD_DOWN;
            return;
        }
        if (this.scaffoldMaterial.isEmpty()) {
            if (this.scaffoldPlacedCount >= this.scaffoldNeeded) {
                // Used everything we planned for and still not there: bail out cleanly.
                this.phase = Phase.SCAFFOLD_DOWN;
                return;
            }
            this.phase = Phase.SCAFFOLD_GATHER;
            return;
        }
        if (this.scaffoldStepCooldown > 0) {
            this.scaffoldStepCooldown--;
            return;
        }
        // [足場が伸びない不具合の修正] The old code took a block out of the
        // stock, tried to place it UNDER the ally's feet (where the ground
        // already is, so nothing was placed and the block was simply
        // deleted), and then teleported the ally up into the air. Proper
        // pillaring: step up one block first, then fill the space the feet
        // just left. Non-placeable junk (flint from gravel etc.) goes to the
        // carried loot instead of being thrown away.
        this.scaffoldMaterial.removeIf(ItemStack::isEmpty);
        if (this.scaffoldMaterial.isEmpty()) {
            return;
        }
        ItemStack material = this.scaffoldMaterial.get(this.scaffoldMaterial.size() - 1);
        Block placeBlock = Block.getBlockFromItem(material.getItem());
        if (placeBlock == null || placeBlock.equals(Blocks.AIR) || placeBlock instanceof net.minecraft.block.BlockFalling) {
            // Falling blocks (sand/gravel) would drop straight out of the tower.
            this.scaffoldMaterial.remove(this.scaffoldMaterial.size() - 1);
            this.addToList(this.carried, material);
            return;
        }
        // ハーフブロック等の上（posY が n+0.5）でも、足のある「空きマス」を正しく取る
        BlockPos feet = new BlockPos(this.entity.posX, Math.ceil(this.entity.posY - 0.05), this.entity.posZ);
        BlockPos above = feet.up(2);
        if (!world.isAirBlock(above)) {
            // [木の足場がすぐ消える不具合の本当の原因] Pillaring next to a trunk,
            // the block above the ally's head is almost always the tree's own
            // LEAVES -- this used to count as "a ceiling", abandon the climb
            // and tear the tower straight back down. Leaves/grass are cleared;
            // a log of the tree is simply mined from here; only a genuinely
            // solid, unrelated block stops the climb.
            IBlockState aboveState = world.getBlockState(above);
            ItemStack tool = this.getHeldTool();
            if (this.isClearableObstruction(aboveState) || AllyAIUtil.isPassableJunk(aboveState)) {
                this.collectDropsInto(world, above, aboveState, ItemStack.EMPTY, this.carried, this.findKnownOwnerPlayer());
                world.setBlockToAir(above);
                return;
            }
            if (tool != null && this.isDesiredBlock(aboveState) && this.canHarvest(tool, aboveState)) {
                if (this.targetBlock != null && !this.targetBlock.equals(above)) {
                    this.plannedTargets.addFirst(this.targetBlock);
                }
                this.targetBlock = above;
                this.phase = Phase.MINE;
                this.mineProgress = 0;
                this.mineTicksRequired = this.computeMineTicksRequired(world, above, tool);
                return;
            }
            this.abandonScaffoldClimb();
            return;
        }
        if (!world.isAirBlock(feet)) {
            IBlockState feetState = world.getBlockState(feet);
            if (AllyAIUtil.isPassableJunk(feetState) || this.isClearableObstruction(feetState)) {
                // [花・草の上で永久に止まる不具合] 以前は花の「上」へ持ち上げて、落ちて、また
                // 持ち上げて…を繰り返していた。通り抜けられる物は片付けてから積む。
                this.collectDropsInto(world, feet, feetState, ItemStack.EMPTY, this.carried, this.findKnownOwnerPlayer());
                world.setBlockToAir(feet);
                return;
            }
            // 苗木・たいまつ・レール等（誰かの設置物）の上では足場を積まない
            this.abandonScaffoldClimb();
            return;
        }
        if (AllyAreas.isForbidden(world, feet)) {
            this.abandonScaffoldClimb(); // 立ち入り禁止エリアには置かない
            return;
        }
        // Stock is merged into stacks: use ONE item, not the whole stack.
        int meta = material.getMetadata();
        material.shrink(1);
        if (material.isEmpty()) {
            this.scaffoldMaterial.remove(this.scaffoldMaterial.size() - 1);
        }
        this.entity.setPositionAndUpdate(this.entity.posX, feet.getY() + 1.0, this.entity.posZ);
        IBlockState placeState = placeBlock.getStateFromMeta(meta);
        world.setBlockState(feet, placeState, 3);
        AllySkills.addXp(this.entity, AllySkills.Skill.BUILDING, 1);
        this.scaffoldStack.push(feet);
        this.scaffoldPlacedCount++;
        this.entity.getNavigator().clearPath();
        this.scaffoldStepCooldown = this.scaffoldInterval();
    }

    private void updateScaffoldDown(World world) {
        this.idleTicks = 0;
        if (this.scaffoldStack.isEmpty()) {
            this.scaffoldPlacedCount = 0;
            this.scaffoldNeeded = 0;
            if (this.isCarriedFull()) {
                this.phase = Phase.GOTO_PLAYER;
                return;
            }
            BlockPos next = this.pollPlannedTarget(world);
            if (next != null) {
                this.targetBlock = next;
                this.phase = Phase.GOTO_BLOCK;
                return;
            }
            this.continueGatheringOrGoHome();
            return;
        }
        if (this.scaffoldStepCooldown > 0) {
            this.scaffoldStepCooldown--;
            return;
        }
        BlockPos top = this.scaffoldStack.peek();
        boolean standingOnTop = Math.abs(this.entity.posX - (top.getX() + 0.5)) < 1.0
                && Math.abs(this.entity.posZ - (top.getZ() + 0.5)) < 1.0
                && this.entity.posY >= top.getY() + 0.5 && this.entity.posY <= top.getY() + 2.5;
        if (standingOnTop) {
            // Step down onto the block below the top one, centred on the
            // column. The old code moved "posY - 1" from wherever the ally
            // happened to be -- after it had walked/fallen off the tower, that
            // teleported it into the ground (suffocation, stuck in blocks).
            this.entity.setPositionAndUpdate(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
            this.entity.getNavigator().clearPath();
            this.recoverScaffoldBlock(world, this.scaffoldStack.pop());
        } else if (this.entity.getDistanceSq(top.getX() + 0.5, top.getY(), top.getZ() + 0.5) < 6.0 * 6.0) {
            // Knocked/pushed off (or the follow AI moved it): take it down one
            // block at a time from the top as usual, never all at once.
            this.recoverScaffoldBlock(world, this.scaffoldStack.pop());
        } else {
            // Wandered far from the tower: remove what's left.
            while (!this.scaffoldStack.isEmpty()) {
                this.recoverScaffoldBlock(world, this.scaffoldStack.pop());
            }
        }
        this.scaffoldStepCooldown = this.scaffoldInterval();
    }

    private void recoverScaffoldBlock(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (this.isScaffoldSource(state)) {
            // Grass that grew on top of a tower block comes back as dirt.
            Block block = state.getBlock() == Blocks.GRASS ? Blocks.DIRT : state.getBlock();
            ItemStack recovered = new ItemStack(block, 1, block == Blocks.DIRT ? 0 : block.getMetaFromState(state));
            world.setBlockToAir(pos);
            if (!recovered.isEmpty()) {
                // Back into the scaffold stock, so the next climb reuses it.
                this.addToList(this.scaffoldMaterial, recovered);
            }
        }
        if (this.scaffoldStack.isEmpty()) {
            this.scaffoldPlacedCount = 0;
        }
    }

    /** Lowest queued block above the ally within 3 blocks horizontally (same tree). */
    private BlockPos lowestPlannedTargetAbove() {
        BlockPos best = null;
        for (BlockPos p : this.plannedTargets) {
            if (p.getY() <= this.entity.posY) {
                continue;
            }
            if (Math.abs(p.getX() + 0.5 - this.entity.posX) > 3.5 || Math.abs(p.getZ() + 0.5 - this.entity.posZ) > 3.5) {
                continue;
            }
            if (best == null || p.getY() < best.getY()) {
                best = p;
            }
        }
        return best;
    }

    /** Next queued cluster block that can be mined from where the ally stands right now. */
    private BlockPos pollReachablePlannedTarget(World world) {
        ItemStack tool = this.getHeldTool();
        if (tool == null) {
            return null;
        }
        java.util.Iterator<BlockPos> it = this.plannedTargets.iterator();
        while (it.hasNext()) {
            BlockPos candidate = it.next();
            IBlockState state = world.getBlockState(candidate);
            if (!this.isDesiredBlock(state) || !this.canHarvest(tool, state)) {
                it.remove();
                continue;
            }
            if (this.eyeDistSq(candidate) <= MINING_REACH_DISTANCE_SQ) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    private void abandonScaffoldClimb() {
        if (this.targetBlock != null) {
            this.blacklistBlock(this.targetBlock);
        }
        this.targetBlock = null;
        this.scaffoldSourceBlock = null;
        this.scaffoldNeeded = 0;
        this.scaffoldPlacedCount = 0;
        this.phase = this.scaffoldStack.isEmpty() ? Phase.GOTO_BLOCK : Phase.SCAFFOLD_DOWN;
    }

    /**
     * [足場がすぐ消える不具合の修正] GRAVEL used to be a scaffold source --
     * placed gravel is a falling block and dropped straight back down, so the
     * tower "vanished" the moment it was built. Only solid, non-falling blocks.
     */
    private boolean isScaffoldSource(IBlockState state) {
        Block block = state.getBlock();
        return block == Blocks.DIRT || block == Blocks.GRASS || block == Blocks.COBBLESTONE || block == Blocks.STONE;
    }

    private BlockPos findScaffoldSource(World world) {
        BlockPos base = new BlockPos(this.entity);
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -SCAFFOLD_SEARCH_RADIUS; dx <= SCAFFOLD_SEARCH_RADIUS; dx++) {
            for (int dz = -SCAFFOLD_SEARCH_RADIUS; dz <= SCAFFOLD_SEARCH_RADIUS; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    // Never dig the ground the tower stands on / right next to it.
                    continue;
                }
                for (int dy = -SCAFFOLD_SEARCH_DOWN; dy <= 1; dy++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    if (this.isBlacklisted(pos)) {
                        continue;
                    }
                    // [自分の足場を掘り崩す不具合の修正] The nearest dirt/stone
                    // was very often a block of the tower it had just built.
                    if (this.scaffoldStack.contains(pos)) {
                        continue;
                    }
                    // Surface blocks only (air above) -- no pits dug under trees.
                    if (!world.isAirBlock(pos.up())) {
                        continue;
                    }
                    IBlockState state = world.getBlockState(pos);
                    if (!this.isScaffoldSource(state)) {
                        continue;
                    }
                    double distSq = squaredDistance(pos, base);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Requirement 3c: tunnelling toward ore that normal pathing can't reach
    // ------------------------------------------------------------------

    private void startTunnel() {
        this.entity.getNavigator().clearPath();
        this.navGoal = null;
        this.tunnelCursor = new BlockPos(this.entity);
        this.tunnelStepsUsed = 0;
        this.tunnelStepCooldown = 0;
        this.navFailStreak = 0;
        this.phase = Phase.TUNNEL;
    }

    private void abandonTunnelTarget() {
        this.blacklistBlock(this.targetBlock);
        this.tunnelCursor = null;
        this.targetBlock = this.pickNextTarget(false);
        this.phase = Phase.GOTO_BLOCK;
    }

    /**
     * [鉱石をきれいに・確実に掘る] 以前は1ブロック掘るたびに経路探索をやり直し、
     * 途中経路で地上へ歩いて戻っては別の所を掘る、を繰り返して地面に浅い穴を
     * 散らかしていた。今は一度トンネルに入ったら経路探索はせず、
     * 1) 鉱石が手の届く所に見えたら掘る、2) 手は届くが石の陰なら視線上の石だけ掘る、
     * 3) それ以外は幅1×高さ2の階段を鉱石に向けて掘り進み、自分もその中を進む。
     */
    private void updateTunnel(World world) {
        this.idleTicks = 0;
        if (this.targetBlock == null || !this.isStillValidTarget(world, this.targetBlock)) {
            this.tunnelCursor = null;
            this.targetBlock = this.pollPlannedTarget(world);
            this.phase = Phase.GOTO_BLOCK;
            return;
        }
        double tx = this.targetBlock.getX() + 0.5;
        double ty = this.targetBlock.getY() + 0.5;
        double tz = this.targetBlock.getZ() + 0.5;
        this.entity.getLookHelper().setLookPosition(tx, ty, tz, 30.0f, 30.0f);
        if (this.tunnelStepCooldown > 0) {
            this.tunnelStepCooldown--;
            return;
        }
        this.tunnelStepCooldown = TUNNEL_STEP_INTERVAL_TICKS;

        Vec3d eye = new Vec3d(this.entity.posX, this.entity.posY + EYE_HEIGHT, this.entity.posZ);
        if (eye.squareDistanceTo(tx, ty, tz) <= MINING_REACH_DISTANCE_SQ) {
            RayTraceResult hit = world.rayTraceBlocks(eye, new Vec3d(tx, ty, tz), false, true, false);
            BlockPos blocker = hit == null ? null : hit.getBlockPos();
            if (blocker == null || blocker.equals(this.targetBlock)) {
                this.tunnelCursor = null;
                this.phase = Phase.MINE;
                this.mineProgress = 0;
                this.mineTicksRequired = this.computeMineTicksRequired(world, this.targetBlock, this.getHeldTool());
                return;
            }
            // 手は届くが石の陰: 視線をさえぎる1ブロックだけ掘る（坑道を広げすぎない）。
            if (this.digTunnelBlock(world, blocker)) {
                this.tunnelStepsUsed++;
                return;
            }
            // 掘れない物（機械・岩盤など）の陰 -- 普通の階段掘りで回り込む
        }

        if (this.tunnelStepsUsed >= MAX_TUNNEL_BLOCKS) {
            this.abandonTunnelTarget();
            return;
        }
        BlockPos step = this.nextTunnelStep();
        if (step == null) {
            // 真上/真下に並んだが手が届かない: 階段を1段進めて回り込む
            this.tunnelLastDx = this.tunnelLastDx == 0 && this.tunnelLastDz == 0 ? 1 : this.tunnelLastDx;
            step = new BlockPos(this.entity).add(this.tunnelLastDx, 0, this.tunnelLastDz);
        }
        if (this.isTunnelStepLavaAdjacent(world, step)) {
            this.abandonTunnelTarget();
            return;
        }
        BlockPos from = new BlockPos(this.entity);
        BlockPos[] toClear = step.getY() < from.getY()
                ? new BlockPos[] { step.up(2), step.up(), step }
                : step.getY() > from.getY()
                        ? new BlockPos[] { from.up(2), step, step.up() }
                        : new BlockPos[] { step, step.up() };
        for (BlockPos pos : toClear) {
            if (world.isAirBlock(pos)) {
                continue;
            }
            if (!this.digTunnelBlock(world, pos)) {
                this.abandonTunnelTarget();
                return;
            }
        }
        this.tunnelStepsUsed++;
        // 掘った所へ自分も進む。足場が無ければ（洞窟に出た）浅い段差なら降りる。
        if (!world.isAirBlock(step) || !world.isAirBlock(step.up())) {
            return; // 砂利が落ちてきた等 -- 次のTickでもう一度掘る
        }
        BlockPos land = step;
        int drop = 0;
        while (drop <= 3 && world.isAirBlock(land.down())) {
            land = land.down();
            drop++;
        }
        if (drop > 3) {
            // 深い空洞に出た: 普通に歩いて行けるか試す（行けなければ別の鉱石へ）
            this.tunnelCursor = null;
            this.navFailStreak = 0;
            this.phase = Phase.GOTO_BLOCK;
            return;
        }
        if (world.getBlockState(land.down()).getMaterial().isLiquid()) {
            this.abandonTunnelTarget();
            return;
        }
        this.entity.getNavigator().clearPath();
        this.entity.setPositionAndUpdate(land.getX() + 0.5, land.getY(), land.getZ() + 0.5);
        // [チーム] 支援役・レール役がたどれるように、掘った通路を記録
        AllyTeamManager.logTunnelStep(this.entity, land);
    }

    /** トンネル用に1ブロック掘る。掘ってはいけない/掘れない物なら false。 */
    private boolean digTunnelBlock(World world, BlockPos pos) {
        if (world.getTileEntity(pos) != null) {
            return false; // 誰かのチェスト・機械
        }
        IBlockState state = world.getBlockState(pos);
        if (!this.isTunnelable(world, pos, state)) {
            return false;
        }
        BreakResult result = this.breakBlock(world, pos, this.carried);
        return result != BreakResult.BLOCKED;
    }

    /**
     * [鉱石の掘り方をきれいに] A tidy 1x2 staircase that the ally walks as it
     * digs. The old version stepped one axis at a time (zig-zag), dug plain
     * vertical shafts, and ran its cursor up to 40 blocks ahead of the ally
     * -- which left a messy scatter of holes. Now: straight corridor along X,
     * then along Z, dropping/climbing one block per horizontal step; when
     * already lined up horizontally but still far above/below, it keeps
     * stair-stepping in the last corridor direction instead of digging a shaft.
     */
    private BlockPos nextTunnelStep() {
        BlockPos from = new BlockPos(this.entity);
        int dxTotal = this.targetBlock.getX() - from.getX();
        int dzTotal = this.targetBlock.getZ() - from.getZ();
        int dyTotal = this.targetBlock.getY() - from.getY();
        int sx = 0;
        int sz = 0;
        if (dxTotal != 0 && Math.abs(dxTotal) >= Math.abs(dzTotal)) {
            sx = Integer.signum(dxTotal);
        } else if (dzTotal != 0) {
            sz = Integer.signum(dzTotal);
        } else if (Math.abs(dyTotal) > 1) {
            sx = this.tunnelLastDx;
            sz = this.tunnelLastDz;
            if (sx == 0 && sz == 0) {
                sx = 1;
            }
        } else {
            return null; // lined up and within reach height
        }
        int sy = dyTotal < -1 ? -1 : (dyTotal > 1 ? 1 : 0);
        this.tunnelLastDx = sx;
        this.tunnelLastDz = sz;
        BlockPos next = from.add(sx, sy, sz);
        this.tunnelCursor = next;
        return next;
    }

    private int tunnelLastDx;
    private int tunnelLastDz;

    /** Requirement 8: is the floor, the side walls, or the ceiling of this tunnel step lava? */
    private boolean isTunnelStepLavaAdjacent(World world, BlockPos step) {
        BlockPos[] toCheck = { step.down(), step, step.up(), step.up(2) };
        for (BlockPos pos : toCheck) {
            if (world.getBlockState(pos).getMaterial() == Material.LAVA) {
                return true;
            }
        }
        return false;
    }

    private boolean isTunnelable(World world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.BEDROCK) {
            return false;
        }
        if (state.getMaterial().isLiquid()) {
            return false;
        }
        float hardness = block.getBlockHardness(state, world, pos);
        return hardness >= 0.0f;
    }

    // ------------------------------------------------------------------
    // Requirement 2 (part 2): apply Fortune / Silk Touch + tool durability
    // ------------------------------------------------------------------

    /**
     * Breaks one block, routing its drops into `destination`, and damages
     * the held tool.
     *
     * <p>Requirement (Mod effect compatibility): this now goes through the
     * same Forge events a real player's break would fire, attributed to
     * whichever player this ally is currently working for (when known):</p>
     * <ul>
     *   <li>{@link BlockEvent.BreakEvent} first, so claim/protection mods
     *       and similar listeners can veto the break exactly as they would
     *       for the owner themselves.</li>
     *   <li>{@link ForgeEventFactory#doPlayerHarvestCheck} to decide whether
     *       drops are actually granted (mirrors vanilla's own "wrong tool
     *       still breaks the block but yields nothing" rule).</li>
     *   <li>{@link ForgeEventFactory#fireBlockHarvesting} inside
     *       {@link #collectDropsInto} so mod-added drop conversions
     *       (auto-smelt, custom loot, fortune scaling, etc.) apply exactly
     *       as they would for a player.</li>
     * </ul>
     * <p>With no owner player currently known, these three are skipped
     * (there's no real player identity to safely attribute them to) and the
     * block simply breaks the old way.</p>
     */
    private BreakResult breakBlock(World world, BlockPos pos, List<ItemStack> destination) {
        // [立ち入り禁止エリア] 拠点・建築物は絶対に壊さない
        if (AllyAreas.isForbidden(world, pos)) {
            return BreakResult.BLOCKED;
        }
        IBlockState state = world.getBlockState(pos);
        if (this.isDesiredBlock(state)) {
            AllySkills.addXp(this.entity, this.currentSkill(), "pickaxe".equals(this.activeClass()) ? 3 : 2);
        } else {
            AllySkills.addXp(this.entity, AllySkills.Skill.MINING, 1);
        }
        ItemStack tool = this.entity.getHeldItemMainhand();
        // HBM の能力付き道具は、その能力（鉱脈一括採掘・幸運など）込みで掘る。
        if (HBMToolSupport.isAbilityTool(tool) && this.isDesiredBlock(state)) {
            List<ItemStack> got = new ArrayList<ItemStack>();
            boolean ok = HBMToolSupport.harvest(this.entity, pos, got);
            for (ItemStack g : got) {
                this.addToList(destination, g);
            }
            if (this.entity.getHeldItemMainhand().isEmpty()) {
                return BreakResult.TOOL_BROKE;
            }
            if (ok) {
                return BreakResult.OK;
            }
            // 掘れなかった（保護等）→ 通常の方法で判定し直す
        }
        EntityPlayer contextPlayer = this.findKnownOwnerPlayer();

        if (contextPlayer != null) {
            BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(world, pos, state, contextPlayer);
            MinecraftForge.EVENT_BUS.post(breakEvent);
            if (breakEvent.isCanceled()) {
                // A protection/claim mod (or similar) says no -- the block
                // stays put, nothing is gathered, nothing is damaged.
                return BreakResult.BLOCKED;
            }
        }

        boolean harvestAllowed = this.canHarvest(tool, state);
        if (contextPlayer != null) {
            harvestAllowed = ForgeEventFactory.doPlayerHarvestCheck(contextPlayer, state, harvestAllowed);
        }
        if (harvestAllowed) {
            this.collectDropsInto(world, pos, state, tool, destination, contextPlayer);
        }
        world.setBlockToAir(pos);
        // Requirement 5: the instant the block is gone, pull in anything
        // that dropped nearby (this block's own drops as loose entities from
        // a previous partial pickup, or drops from a neighbour) instead of
        // leaving it to despawn or need a detour later.
        this.vacuumNearbyDrops(world, pos, destination);
        if (this.damageHeldToolAndCheckBroken(tool)) {
            return BreakResult.TOOL_BROKE;
        }
        return BreakResult.OK;
    }

    /**
     * A real, alive {@link EntityPlayer} to attribute Forge break/harvest
     * events to, or {@code null} when none is currently known -- a fabricated
     * player would crash any listener that isn't defensive, so those events
     * are simply skipped rather than risk passing a placeholder in.
     */
    private EntityPlayer findKnownOwnerPlayer() {
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        if (owner != null && owner.isEntityAlive() && owner.getEntityWorld() == this.entity.getEntityWorld()) {
            return owner;
        }
        return null;
    }

    /** Requirement 5: sweep up loose dropped items around `pos` straight into `destination`. */
    private void vacuumNearbyDrops(World world, BlockPos pos, List<ItemStack> destination) {
        AxisAlignedBB area = new AxisAlignedBB(pos).grow(DROP_VACUUM_RANGE, DROP_VACUUM_RANGE, DROP_VACUUM_RANGE);
        List<EntityItem> drops = world.getEntitiesWithinAABB(EntityItem.class, area);
        for (EntityItem itemEntity : drops) {
            if (!itemEntity.isEntityAlive()) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            this.addToList(destination, stack.copy());
            itemEntity.setDead();
        }
    }

    private void collectDropsInto(World world, BlockPos pos, IBlockState state, ItemStack tool,
            List<ItemStack> destination, EntityPlayer contextPlayer) {
        Block block = state.getBlock();
        boolean hasSilkTouch = !tool.isEmpty() && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, tool) > 0
                && block.canSilkHarvest(world, pos, state, contextPlayer);
        int fortune = tool.isEmpty() ? 0 : EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, tool);

        List<ItemStack> drops;
        if (hasSilkTouch) {
            drops = new ArrayList<ItemStack>(1);
            ItemStack silkDrop = new ItemStack(block, 1, block.getMetaFromState(state));
            if (!silkDrop.isEmpty()) {
                drops.add(silkDrop);
            }
        } else {
            drops = block.getDrops(world, pos, state, fortune);
        }

        // Requirement (Mod effect compatibility): route the raw drops through
        // the same event a real player's harvest fires, so mods that convert
        // or add to drops here (auto-smelt, custom loot, fortune-scaling
        // add-ons, etc.) take effect exactly as they would for a player.
        float dropChance = ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, fortune, 1.0f, hasSilkTouch, contextPlayer);
        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty() && (dropChance >= 1.0f || world.rand.nextFloat() < dropChance)) {
                this.addToList(destination, drop);
            }
        }
    }

    /** Merges into an existing stack in `list` when possible instead of growing it forever. */
    private void addToList(List<ItemStack> list, ItemStack stack) {
        for (ItemStack existing : list) {
            if (existing.getCount() < existing.getMaxStackSize()
                    && ItemStack.areItemsEqual(existing, stack)
                    && ItemStack.areItemStackTagsEqual(existing, stack)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int move = Math.min(room, stack.getCount());
                existing.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
        list.add(stack);
    }

    private int carriedItemCount() {
        int total = 0;
        for (ItemStack stack : this.carried) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * Reduces the held tool's durability by one. Returns true if this broke
     * the tool (durability reached its max), in which case the break
     * sound/particles are played here, the tool is removed from the ally's
     * hand, and everything currently carried (gathered goods + any spare
     * scaffold material) is safely dropped at the ally's feet rather than
     * lost -- the bridge notices the empty main hand and tears this AI task
     * down on the next tick.
     */
    private boolean damageHeldToolAndCheckBroken(ItemStack tool) {
        if (tool.isEmpty() || !tool.isItemStackDamageable()) {
            return false;
        }
        int newDamage = tool.getItemDamage() + 1;
        if (newDamage >= tool.getMaxDamage()) {
            this.breakHeldTool(tool);
            return true;
        }
        tool.setItemDamage(newDamage);
        return false;
    }

    private void breakHeldTool(ItemStack tool) {
        World world = this.entity.getEntityWorld();
        world.playSound(null, this.entity.posX, this.entity.posY, this.entity.posZ,
                SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.NEUTRAL, 1.0f, 1.0f);
        for (int i = 0; i < 5; i++) {
            world.spawnParticle(EnumParticleTypes.ITEM_CRACK,
                    this.entity.posX, this.entity.posY + 1.0, this.entity.posZ,
                    (world.rand.nextDouble() - 0.5) * 0.3,
                    world.rand.nextDouble() * 0.3,
                    (world.rand.nextDouble() - 0.5) * 0.3,
                    Item.getIdFromItem(tool.getItem()), tool.getMetadata());
        }
        tool.shrink(1);
        if (tool.isEmpty()) {
            this.entity.setHeldItem(net.minecraft.util.EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        // 集めた物は捨てない。次のTickで持ち物の予備に持ち替えるか、新しく作る。
        this.toolCheckCooldown = 0;
    }

    /**
     * たいまつ用の資材（たいまつ・棒・石炭/木炭・少量の原木）。採った物の中から
     * ここへ移しておくことで、プレイヤーへの配達で持ち帰られてしまわない。
     */
    private final List<ItemStack> torchSupplies = new ArrayList<ItemStack>();

    /** プレイヤーが手渡したたいまつ/棒/石炭をたいまつ資材として受け取る。 */
    public void receiveTorchSupplies(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            this.addToList(this.torchSupplies, stack.copy());
        }
    }

    public static boolean isTorchSupply(ItemStack stack) {
        Item item = stack.getItem();
        return item == Item.getItemFromBlock(Blocks.TORCH) || item == net.minecraft.init.Items.STICK
                || item == net.minecraft.init.Items.COAL;
    }

    private void refillTorchSupplies() {
        int coal = 0;
        int logs = 0;
        for (ItemStack s : this.torchSupplies) {
            if (s.getItem() == net.minecraft.init.Items.COAL) {
                coal += s.getCount();
            }
            if (s.getItem() == Item.getItemFromBlock(Blocks.LOG) || s.getItem() == Item.getItemFromBlock(Blocks.LOG2)) {
                logs += s.getCount();
            }
        }
        java.util.Iterator<ItemStack> it = this.carried.iterator();
        List<ItemStack> moved = new ArrayList<ItemStack>();
        while (it.hasNext()) {
            ItemStack s = it.next();
            Item item = s.getItem();
            if (item == Item.getItemFromBlock(Blocks.TORCH) || item == net.minecraft.init.Items.STICK) {
                moved.add(s);
                it.remove();
            } else if (item == net.minecraft.init.Items.COAL && coal < 8) {
                int take = Math.min(8 - coal, s.getCount());
                moved.add(s.splitStack(take));
                coal += take;
                if (s.isEmpty()) {
                    it.remove();
                }
            } else if ((item == Item.getItemFromBlock(Blocks.LOG) || item == Item.getItemFromBlock(Blocks.LOG2)) && logs < 2) {
                int take = Math.min(2 - logs, s.getCount());
                moved.add(s.splitStack(take));
                logs += take;
                if (s.isEmpty()) {
                    it.remove();
                }
            }
        }
        for (ItemStack s : moved) {
            this.addToList(this.torchSupplies, s);
        }
    }

    /** Also called by the bridge when this task is removed (tool swap), so nothing is lost. */
    public void dropEverythingCarried() {
        for (ItemStack stack : this.torchSupplies) {
            this.spawnItemAtAlly(stack);
        }
        this.torchSupplies.clear();
        for (ItemStack stack : this.carried) {
            this.spawnItemAtAlly(stack);
        }
        this.carried.clear();
        for (ItemStack stack : this.scaffoldMaterial) {
            this.spawnItemAtAlly(stack);
        }
        this.scaffoldMaterial.clear();
        // Any blocks already placed for the tower are left standing -- with
        // no tool left there's no safe way to reclaim them.
        this.scaffoldStack.clear();
        this.plannedTargets.clear();
    }

    private void spawnItemAtAlly(ItemStack stack) {
        // 足元に捨てず、味方自身の持ち物へ（入りきらない分だけ落とす）。
        AllyInventory.insertOrDrop(this.entity, stack);
    }

    // ------------------------------------------------------------------
    // GOTO_PLAYER / DELIVER -- requirement 4: never lose gathered items
    // ------------------------------------------------------------------

    private int crafterDeliverTicks;
    private long crafterRetryAt;

    /**
     * [並列分散] 発注品を持っていれば、先にクラフト役へ届ける。届けに向かっている間は true。
     */
    private boolean deliverToCrafter() {
        java.util.UUID owner = this.ownerIdCached();
        AllyProjectBoard.Project pj = owner == null ? null : AllyProjectBoard.get(owner);
        long now = this.entity.world.getTotalWorldTime();
        if (pj == null || pj.crafter == this.entity || now < this.crafterRetryAt
                || pj.crafter.world != this.entity.world || AllyProjectBoard.orderedCount(owner, this.carried) <= 0) {
            this.crafterDeliverTicks = 0;
            return false;
        }
        if (++this.crafterDeliverTicks > 600) {
            this.crafterDeliverTicks = 0;
            this.crafterRetryAt = now + 1200; // たどり着けない -- 1分は持ち主へ届ける
            return false;
        }
        EntityFriendlyCreature crafter = pj.crafter;
        this.idleTicks = 0;
        if (this.entity.getDistanceSq(crafter) > 3.0 * 3.0) {
            if (this.crafterDeliverTicks % 20 == 1 || this.entity.getNavigator().noPath()) {
                this.entity.getNavigator().tryMoveToEntityLiving(crafter, RETURN_MOVE_SPEED);
            }
            return true;
        }
        this.entity.getNavigator().clearPath();
        int n = AllyProjectBoard.deliver(owner, this.carried);
        this.crafterDeliverTicks = 0;
        if (n <= 0) {
            this.crafterRetryAt = now + 1200; // クラフト役の持ち物がいっぱい等
            return false;
        }
        if (n > 0) {
            this.entity.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
        }
        if (this.carried.isEmpty()) {
            this.phase = Phase.GOTO_BLOCK;
            return true;
        }
        return false;
    }

    private java.util.UUID ownerIdCache;
    private long ownerIdCacheTime = -1000;
    private boolean orderedOnlyScan;

    private java.util.UUID ownerIdCached() {
        long now = this.entity.world.getTotalWorldTime();
        if (now - this.ownerIdCacheTime > 100) {
            this.ownerIdCache = AllyAreas.ownerId(this.entity);
            this.ownerIdCacheTime = now;
        }
        return this.ownerIdCache;
    }

    private void updateGoToPlayer() {
        if (this.deliverToCrafter()) {
            return;
        }
        EntityPlayer player = this.findDeliveryPlayer();
        if (player == null || !player.isEntityAlive()) {
            this.idleTicks++;
            return;
        }
        double distSq = this.entity.getDistanceSq(player.posX, player.posY, player.posZ);
        if (distSq <= DELIVER_DIST_SQ) {
            this.entity.getNavigator().clearPath();
            this.phase = Phase.DELIVER;
            this.idleTicks = 0;
            this.deliverBestDist = Double.MAX_VALUE; // 次の配達の進み具合判定をリセット
            this.deliverNoProgressTicks = 0;
            return;
        }
        this.entity.getLookHelper().setLookPositionWithEntity(player, 30.0f, 30.0f);
        // [配達されない不具合の修正] idleTicks used to grow every tick even
        // while happily walking home, so any trip longer than 5 s timed out
        // and the load was never handed over. Only count ticks where we have
        // no path and can't get one.
        boolean noRoute = this.entity.getNavigator().noPath()
                && !this.entity.getNavigator().tryMoveToEntityLiving(player, RETURN_MOVE_SPEED);

        // [配達時に障害物で止まる不具合の修正] No route, or walking but not
        // getting any closer (stuck against a wall / fence / partial path):
        // dig through whatever is directly in the way toward the player.
        double dist = Math.sqrt(distSq);
        if (dist < this.deliverBestDist - 0.5) {
            this.deliverBestDist = dist;
            this.deliverNoProgressTicks = 0;
        } else {
            this.deliverNoProgressTicks++;
        }
        if (noRoute || this.deliverNoProgressTicks > 40) {
            if (this.breakObstacleToward(player)) {
                this.deliverNoProgressTicks = 0;
                this.idleTicks = 0;
                this.entity.getNavigator().clearPath();
                return;
            }
            this.idleTicks++;
        } else {
            this.idleTicks = 0;
        }
    }

    private double deliverBestDist = Double.MAX_VALUE;
    private int deliverNoProgressTicks;
    private int obstacleBreakCooldown;

    /**
     * プレイヤー方向の目の前（足元の高さと頭の高さ）にある邪魔なブロックを1つ壊す。
     * チェスト等のタイルエンティティ・岩盤・液体・採取不能なブロックは壊さない。
     * 壊した（または壊す途中）なら true。
     */
    private boolean breakObstacleToward(EntityPlayer player) {
        if (this.obstacleBreakCooldown > 0) {
            this.obstacleBreakCooldown--;
            return true;
        }
        World world = this.entity.getEntityWorld();
        BlockPos feet = new BlockPos(this.entity);
        double dx = player.posX - this.entity.posX;
        double dz = player.posZ - this.entity.posZ;
        int sx = 0;
        int sz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) {
            sx = dx > 0 ? 1 : -1;
        } else {
            sz = dz > 0 ? 1 : -1;
        }
        BlockPos ahead = feet.add(sx, 0, sz);
        // Player above: also clear the block over our head so we can step up.
        BlockPos[] candidates = player.posY > this.entity.posY + 1.0
                ? new BlockPos[] { ahead.up(), ahead, feet.up(2), ahead.up(2) }
                : new BlockPos[] { ahead.up(), ahead };
        for (BlockPos pos : candidates) {
            IBlockState state = world.getBlockState(pos);
            if (world.isAirBlock(pos) || !state.getMaterial().blocksMovement()) {
                continue;
            }
            if (world.getTileEntity(pos) != null || state.getMaterial().isLiquid()
                    || state.getBlock() == Blocks.BEDROCK || state.getBlockHardness(world, pos) < 0.0F) {
                return false; // 壊してはいけない物 -- 経路探索に任せる
            }
            this.entity.getLookHelper().setLookPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30.0f, 30.0f);
            this.entity.swingArm(net.minecraft.util.EnumHand.MAIN_HAND);
            BreakResult result = this.breakBlock(world, pos, this.carried);
            this.obstacleBreakCooldown = 8;
            return result != BreakResult.BLOCKED;
        }
        // Nothing solid in the way at this spot: nudge forward so the path can re-form.
        if (world.isAirBlock(ahead) && world.isAirBlock(ahead.up()) && !world.isAirBlock(ahead.down())) {
            this.entity.getMoveHelper().setMoveTo(ahead.getX() + 0.5, ahead.getY(), ahead.getZ() + 0.5, RETURN_MOVE_SPEED);
            return true;
        }
        return false;
    }

    private void updateDeliver() {
        EntityPlayer player = this.findDeliveryPlayer();
        if (player == null || !player.isEntityAlive()) {
            // Nobody to hand off to right now; keep the goods and go find them again.
            this.phase = Phase.GOTO_PLAYER;
            this.idleTicks++;
            return;
        }
        double distSq = this.entity.getDistanceSq(player.posX, player.posY, player.posZ);
        if (distSq > DELIVER_GIVE_UP_DIST_SQ) {
            // Player wandered off mid-handoff: don't drop anything, just chase them again.
            this.phase = Phase.GOTO_PLAYER;
            return;
        }

        // Requirement (delivery fix): addItemStackToInventory mutates `stack`
        // in place, shrinking it as items are placed, and only returns false
        // when NOTHING was added at all -- a partial fill (some of a stack
        // placed, some left over) still returns true. Checking the return
        // value alone silently lost that leftover portion; checking
        // `!stack.isEmpty()` afterward catches both the total-failure and
        // partial-failure cases correctly.
        for (ItemStack stack : this.carried) {
            player.inventory.addItemStackToInventory(stack);
            if (!stack.isEmpty()) {
                // Player's inventory is full: drop the rest at their feet.
                // Keeping it used to bounce the ally between DELIVER and
                // GOTO_BLOCK -> GOTO_PLAYER every tick without ever finishing.
                EntityItem dropped = new EntityItem(player.world, player.posX, player.posY + 0.5, player.posZ, stack.copy());
                dropped.setNoPickupDelay();
                player.world.spawnEntity(dropped);
                stack.setCount(0);
            }
        }
        this.carried.clear();

        // Requirement 4: never sit still waiting on a full player inventory.
        // Whatever couldn't be handed off (still in `carried`) is kept and
        // brought right back out to work instead of idling in place.
        this.idleTicks = 0;
        this.phase = Phase.GOTO_BLOCK;
    }

    // ------------------------------------------------------------------
    // Target selection: requirement 1/3a (cluster flood fill) + 3b (safety)
    // ------------------------------------------------------------------

    private boolean isStillValidTarget(World world, BlockPos pos) {
        ItemStack tool = this.getHeldTool();
        if (tool == null) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        return this.isDesiredBlock(state) && this.canHarvest(tool, state);
    }

    /** Drains only the already-planned cluster queue; never starts a brand-new area scan. */
    private BlockPos pollPlannedTarget(World world) {
        ItemStack tool = this.getHeldTool();
        if (tool == null) {
            return null;
        }
        while (!this.plannedTargets.isEmpty()) {
            BlockPos candidate = this.plannedTargets.pollFirst();
            if (this.isBlacklisted(candidate) || TargetRegistry.isClaimedByOther(this.entity, candidate)) {
                continue;
            }
            IBlockState state = world.getBlockState(candidate);
            if (this.isDesiredBlock(state) && this.canHarvest(tool, state)) {
                return candidate;
            }
        }
        return null;
    }

    /** Pulls the next block to work on: first from the current cluster queue, otherwise a fresh area scan. */
    private BlockPos pickNextTarget(boolean respectCooldown) {
        World world = this.entity.getEntityWorld();
        BlockPos fromQueue = this.pollPlannedTarget(world);
        if (fromQueue != null) {
            return fromQueue;
        }
        ItemStack tool = this.getHeldTool();
        if (tool == null) {
            return null;
        }
        BlockPos seed = this.findTargetBlock(respectCooldown);
        if (seed != null) {
            this.planCluster(world, tool, seed);
            // 木は見えた枝からではなく、一番下の原木から切り始める（下から順に全部）。
            if ("axe".equals(this.activeClass()) && !this.plannedTargets.isEmpty()
                    && this.plannedTargets.peekFirst().getY() < seed.getY()) {
                BlockPos lowest = this.plannedTargets.pollFirst();
                List<BlockPos> rest = new ArrayList<BlockPos>(this.plannedTargets);
                rest.add(seed);
                java.util.Collections.sort(rest, new java.util.Comparator<BlockPos>() {
                    @Override
                    public int compare(BlockPos a, BlockPos b) {
                        return Integer.compare(a.getY(), b.getY());
                    }
                });
                this.plannedTargets.clear();
                this.plannedTargets.addAll(rest);
                seed = lowest;
            }
            if ("axe".equals(this.activeClass())) {
                // [苗木の植え直し] この木を根元（一番下の原木）から切り終えたら、
                // 切った場所に苗木を植え直すための位置として覚えておく。
                this.treeBaseStump = seed;
            }
            // [重複防止] この木/鉱脈は自分の担当として予約（他の味方は別の所へ）
            TargetRegistry.claim(this.entity, seed, 1200);
            for (BlockPos p : this.plannedTargets) {
                TargetRegistry.claim(this.entity, p, 1200);
            }
        }
        return seed;
    }

    /**
     * Requirement 1 (whole tree) / 3a (whole ore vein): flood-fills every
     * connected matching block starting from `seed` and queues the rest up
     * in {@link #plannedTargets}, so the ally works through the entire
     * tree/vein before it's considered done.
     */
    private void planCluster(World world, ItemStack tool, BlockPos seed) {
        this.plannedTargets.clear();
        this.clusterVisited.clear();
        boolean tree = "axe".equals(this.activeClass());
        int cap = tree ? MAX_TREE_BLOCKS : MAX_ORE_CLUSTER_BLOCKS;
        double maxDistSq = (double) CLUSTER_SEARCH_RADIUS * CLUSTER_SEARCH_RADIUS;

        // [大きい木を全部切る] 大木・ジャングルの木の枝は幹と葉でしか繋がっていないことが
        // 多く、原木どうしの隣接だけで辿ると枝や上の方を取り残していた。
        // 木の場合は葉を最大 TREE_LEAF_BRIDGE ブロック越えて原木を辿る（葉自体は掘らない）。
        Deque<BlockPos> frontier = new ArrayDeque<BlockPos>();
        Map<BlockPos, Integer> leafSteps = new java.util.HashMap<BlockPos, Integer>();
        frontier.add(seed);
        this.clusterVisited.add(seed);
        leafSteps.put(seed, 0);
        List<BlockPos> ordered = new ArrayList<BlockPos>();
        int visits = 0;

        while (!frontier.isEmpty() && ordered.size() < cap && visits++ < 6000) {
            BlockPos current = frontier.poll();
            int steps = leafSteps.get(current);
            if (steps == 0) {
                ordered.add(current);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.add(dx, dy, dz);
                        if (this.clusterVisited.contains(neighbor)) {
                            continue;
                        }
                        if (TargetRegistry.isClaimedByOther(this.entity, neighbor)
                                || !AllyAreas.allowed(this.entity, neighbor, this.areaType(), this.cachedWorkAreas())) {
                            continue;
                        }
                        if (squaredDistance(neighbor, seed) > maxDistSq) {
                            continue;
                        }
                        IBlockState state = world.getBlockState(neighbor);
                        if (this.isDesiredBlock(state) && this.canHarvest(tool, state)) {
                            if ("pickaxe".equals(this.activeClass()) && this.isDangerousBelow(world, neighbor)) {
                                // Requirement 3b: don't queue up ore sitting right above lava.
                                continue;
                            }
                            this.clusterVisited.add(neighbor);
                            leafSteps.put(neighbor, 0);
                            frontier.add(neighbor);
                        } else if (tree && steps < TREE_LEAF_BRIDGE && state.getBlock() instanceof BlockLeaves
                                && neighbor.getY() >= seed.getY() - 1) {
                            this.clusterVisited.add(neighbor);
                            leafSteps.put(neighbor, steps + 1);
                            frontier.add(neighbor);
                        }
                    }
                }
            }
        }

        if (!ordered.isEmpty()) {
            ordered.remove(0); // the seed itself becomes `targetBlock`, not part of the queue
        }
        if (tree) {
            // 下から順に: 地上から届く物を先に、足場は低い所から積み上げる
            java.util.Collections.sort(ordered, new java.util.Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos a, BlockPos b) {
                    return Integer.compare(a.getY(), b.getY());
                }
            });
        }
        this.plannedTargets.addAll(ordered);
    }

    /** Requirement 3b: a very small, cheap safety net -- don't approach ore with lava right underneath it. */
    private boolean isDangerousBelow(World world, BlockPos pos) {
        Block below = world.getBlockState(pos.down()).getBlock();
        return below == Blocks.LAVA || below == Blocks.FLOWING_LAVA;
    }

    /**
     * Requirement (RayTrace line-of-sight): picks a brand-new seed target.
     * This deliberately runs in two passes -- first requiring a clear line
     * of sight (an exposed face the ally can actually see), and only falling
     * back to any matching block in range (visible or not) if nothing
     * visible turned up. That keeps the common case from beelining straight
     * at ore buried deep behind unbroken stone (which used to trigger an
     * immediate forced tunnel dig for every non-exposed block in range),
     * while still preserving the older "tunnel to genuinely walled-off ore"
     * behaviour as a fallback once nothing visible is left nearby.
     */
    private BlockPos findTargetBlock(boolean respectCooldown) {
        if (respectCooldown) {
            if (this.rescanCooldown > 0) {
                this.rescanCooldown--;
                return null;
            }
        }
        ItemStack tool = this.getHeldTool();
        if (tool == null) {
            return null;
        }
        this.rescanCooldown = RESCAN_COOLDOWN_TICKS;
        World world = this.entity.getEntityWorld();
        // [並列分散] 発注が出ていれば、まず発注のブロックだけを探す
        java.util.UUID owner = this.ownerIdCached();
        if (owner != null && AllyProjectBoard.hasOpenOrders(owner, "axe".equals(this.activeClass()))) {
            this.orderedOnlyScan = true;
            try {
                BlockPos ordered = this.scanForTargetBlock(world, tool, true);
                if (ordered == null) {
                    ordered = this.scanForTargetBlock(world, tool, false);
                }
                if (ordered != null) {
                    return ordered;
                }
            } finally {
                this.orderedOnlyScan = false;
            }
        }
        BlockPos visible = this.scanForTargetBlock(world, tool, true);
        if (visible != null) {
            return visible;
        }
        BlockPos any = this.scanForTargetBlock(world, tool, false);
        if (any == null) {
            // Nothing in range at all: a full-area scan is not cheap, so
            // don't repeat it every few ticks while there's nothing to find.
            this.rescanCooldown = EMPTY_AREA_RESCAN_COOLDOWN_TICKS;
        }
        return any;
    }

    /**
     * [探索範囲の不具合の修正] The old loop went x = -36..36, then z, then y,
     * and stopped after {@link #MAX_BLOCKS_SCANNED_PER_SEARCH} (8000) blocks.
     * One x-slice is 73*33 = 2409 blocks, so the cap was hit after x = -36..-33:
     * it only ever looked at a thin strip 33-36 blocks to the WEST and never
     * saw ore/logs right next to the ally.
     *
     * Now it walks outward in square shells (radius 0, 1, 2, ...) and returns
     * as soon as a shell contains a match, so the nearest target is found
     * first and a nearby target costs only a handful of lookups. The block
     * budget is kept, but now it trims the far edge instead of everything
     * except the far edge.
     */
    private BlockPos scanForTargetBlock(World world, ItemStack tool, boolean requireLineOfSight) {
        BlockPos center = new BlockPos(this.entity);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int budget = requireLineOfSight ? MAX_BLOCKS_SCANNED_PER_SEARCH * 6 : MAX_BLOCKS_SCANNED_PER_SEARCH * 12;
        // 鉱石は地表から深い所にある（GTの鉱脈は特に）。ツルハシの時は下を深く探す。
        boolean ore = "pickaxe".equals(this.activeClass());
        int down = ore ? 40 : SEARCH_RADIUS_VERTICAL;
        int up = ore ? 8 : SEARCH_RADIUS_VERTICAL;
        int scanned = 0;
        for (int r = 0; r <= SEARCH_RADIUS_HORIZONTAL; r++) {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue; // only the outer ring of this shell
                    }
                    for (int dy = -down; dy <= up; dy++) {
                        if (++scanned > budget) {
                            return best;
                        }
                        cursor.setPos(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!world.isBlockLoaded(cursor)) {
                            continue;
                        }
                        IBlockState state = world.getBlockState(cursor);
                        if (!this.isDesiredBlock(state) || !this.canHarvest(tool, state)) {
                            continue;
                        }
                        BlockPos pos = cursor.toImmutable();
                        if (this.isBlacklisted(pos)) {
                            continue;
                        }
                        // [重複防止・エリア指定] 他の味方が狙っている物、エリア外・禁止エリアの物は除く
                        if (TargetRegistry.isClaimedByOther(this.entity, pos)
                                || !AllyAreas.allowed(this.entity, pos, this.areaType(), this.cachedWorkAreas())) {
                            continue;
                        }
                        if ("pickaxe".equals(this.activeClass()) && this.isDangerousBelow(world, pos)) {
                            continue;
                        }
                        if (requireLineOfSight && !this.hasLineOfSight(world, pos)) {
                            continue;
                        }
                        double distSq = squaredDistance(pos, center);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = pos;
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

    /**
     * Requirement (RayTrace): a straight line from the ally's eyes to the
     * block's center hits nothing solid before reaching it -- i.e. it has an
     * exposed, actually-visible face toward the ally right now, rather than
     * being sealed behind other undiscovered blocks.
     */
    private boolean hasLineOfSight(World world, BlockPos pos) {
        Vec3d eye = new Vec3d(this.entity.posX, this.entity.posY + EYE_HEIGHT, this.entity.posZ);
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        RayTraceResult result = world.rayTraceBlocks(eye, target, false, true, false);
        if (result == null) {
            // Nothing solid anywhere along the line (open air the whole way) -- clearly visible.
            return true;
        }
        return pos.equals(result.getBlockPos());
    }

    /** プレイヤーと同じく「目から」ブロック中心までの距離の二乗。 */
    private double eyeDistSq(BlockPos pos) {
        double ex = pos.getX() + 0.5 - this.entity.posX;
        double ey = pos.getY() + 0.5 - (this.entity.posY + EYE_HEIGHT);
        double ez = pos.getZ() + 0.5 - this.entity.posZ;
        return ex * ex + ey * ey + ez * ez;
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return (double) (dx * dx + dy * dy + dz * dz);
    }

    /** Requirement 3 (stuck avoidance): bounded, time-expiring "don't retry this one for a while" memory. */
    private void blacklistBlock(BlockPos pos) {
        this.lastFailedBlocks.put(pos, this.entity.getEntityWorld().getTotalWorldTime());
    }

    private boolean isBlacklisted(BlockPos pos) {
        Long blacklistedAt = this.lastFailedBlocks.get(pos);
        if (blacklistedAt == null) {
            return false;
        }
        long now = this.entity.getEntityWorld().getTotalWorldTime();
        if (now - blacklistedAt > FAILED_BLOCK_EXPIRY_TICKS) {
            this.lastFailedBlocks.remove(pos);
            return false;
        }
        return true;
    }

    /**
     * Mod-agnostic "is this the kind of block I should gather" check.
     * Vanilla ore/log blocks are recognised directly; anything else
     * (this mod's own ores, other mods' ores/logs) is recognised through
     * its Ore Dictionary entry, which is the standard way mods advertise
     * "this is an ore" / "this is a log" to other mods.
     */
    private boolean isDesiredBlock(IBlockState state) {
        // [並列分散] クラフト役からの発注があるブロック（石・特定の鉱石・原木）も掘る対象
        java.util.UUID owner = this.ownerIdCached();
        if (owner != null && AllyProjectBoard.wants(owner, state, "axe".equals(this.activeClass()))) {
            return true;
        }
        if (this.orderedOnlyScan) {
            return false;
        }
        // Block states are singletons, so the (allocation + Ore Dictionary
        // lookup) result can be cached -- the area scan asks this for every
        // block it passes over, mostly plain stone/dirt.
        if (!this.activeClass().equals(this.desiredCacheClass)) {
            // 素手の木こり⇔本来の仕事で判定が変わるので作り直す（以前はツルハシで原木を狙った）
            this.desiredBlockCache.clear();
            this.desiredCacheClass = this.activeClass();
        }
        Boolean cached = this.desiredBlockCache.get(state);
        if (cached == null) {
            cached = this.computeIsDesiredBlock(state);
            this.desiredBlockCache.put(state, cached);
        }
        return cached;
    }

    private final Map<IBlockState, Boolean> desiredBlockCache = new java.util.HashMap<IBlockState, Boolean>();
    private String desiredCacheClass;

    private boolean computeIsDesiredBlock(IBlockState state) {
        Block block = state.getBlock();
        if ("pickaxe".equals(this.activeClass()) && block instanceof BlockOre) {
            return true;
        }
        if ("axe".equals(this.activeClass()) && block instanceof BlockLog) {
            return true;
        }
        ItemStack asStack = new ItemStack(block, 1, block.getMetaFromState(state));
        if (asStack.isEmpty()) {
            return false;
        }
        for (int oreId : OreDictionary.getOreIDs(asStack)) {
            String name = OreDictionary.getOreName(oreId);
            if (name == null) {
                continue;
            }
            if ("pickaxe".equals(this.activeClass()) && name.startsWith("ore")) {
                return true;
            }
            if ("axe".equals(this.activeClass()) && name.startsWith("log")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Requirement (Mod tool compatibility): delegates to the tool's own
     * {@code ItemStack#canHarvestBlock(IBlockState)}, the same Forge/vanilla
     * entry point a real player's held item goes through. That method
     * already walks through the block's declared harvest tool/level for a
     * plain {@code ItemTool}, and -- crucially -- falls through to
     * {@code Item#canHarvestBlock(IBlockState, ItemStack)} for anything that
     * isn't one, which is exactly how mod hammers, paxels and other custom
     * multi-tools declare their own harvest rules. This also automatically
     * enforces "bare hands / the wrong tool never mines stone-like blocks",
     * since that method returns false unless the material doesn't need a
     * tool at all.
     */
    private boolean canHarvest(ItemStack tool, IBlockState state) {
        // [斧で木を切らない不具合の修正] vanilla ItemAxe/ItemTool don't override
        // canHarvestBlock, so tool.canHarvestBlock(log) was false and axe mode
        // could never find a single target. Use the same order as a player's
        // harvest check: tool-not-required material, then the tool's own
        // verdict, then harvest tool class + level.
        return AllyAIUtil.canHarvestWith(tool, state);
    }

    private EntityPlayer findDeliveryPlayer() {
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        if (owner != null && owner.isEntityAlive() && owner.getEntityWorld() == this.entity.getEntityWorld()) {
            return owner;
        }
        World world = this.entity.getEntityWorld();
        AxisAlignedBB area = this.entity.getEntityBoundingBox().grow(DELIVERY_SEARCH_RANGE, DELIVERY_SEARCH_RANGE, DELIVERY_SEARCH_RANGE);
        List<EntityPlayer> nearby = world.getEntitiesWithinAABB(EntityPlayer.class, area);
        EntityPlayer closest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityPlayer candidate : nearby) {
            double distSq = this.entity.getDistanceSq(candidate.posX, candidate.posY, candidate.posZ);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                closest = candidate;
            }
        }
        return closest;
    }
}
