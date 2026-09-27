package com.example.examplemod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockVine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

/**
 * [整地と拠点建設・全面作り直し] シャベルの仕事の味方が、きれいに整地してから木の家を建てる。
 *
 * <ol>
 *   <li><b>下見</b>: 持ち主の周り（半径18）で、起伏が一番少なく、機械・チェスト・水・
 *       立ち入り禁止エリアの無い 13x13 の土地を選ぶ。高さは土地の中央値（削る量と盛る量が
 *       釣り合う高さ）。</li>
 *   <li><b>伐採・草刈り</b>: 敷地の木は根元を切って木ごと倒し（原木は建材に）、花・草・雪を取り除く。
 *       背の高い木の上に届かず止まる、ということが無い。</li>
 *   <li><b>切り崩し</b>: 目標の高さより上を、上の段から順に削る（穴だらけにしない）。
 *       石も掘る（持ち物のツルハシに自分で持ち替える）。敷地の外周2マスはなだらかな斜面にする。</li>
 *   <li><b>埋め立て</b>: 低い所を下から順に埋める（削った土・石を使う。足りなければ周りの
 *       高い所を削って取る）。水も埋める。表面は草ブロックにする。</li>
 *   <li><b>建築</b>: 原木を板材に加工し、丸石・板材で 7x7 の家（床・壁3段・屋根）を建て、
 *       ドア・作業台・チェスト・たいまつも自分でクラフトして設置する。材料が足りなければ
 *       近くの木を切りに行く。</li>
 * </ol>
 * <p>どの作業も「届かない・進めない」状態が続けば、足元の草花を片付ける／ジャンプする／
 * 別の場所から作業する、で抜け出し、同じ所で固まり続けない。</p>
 */
public class EntityAIBuildBaseAlly extends EntityAIBase {

    private enum Phase { SURVEY, CLEAR, CUT, FILL, GATHER_WOOD, BUILD, DONE }

    private enum StepKind { FOUNDATION, FLOOR, WALL, WINDOW, ROOF, ROOF_STAIRS, DOOR, LIGHT, CRAFTING, CHEST, FURNACE, BED }

    private static final class BuildStep {
        final BlockPos pos;
        final StepKind kind;
        final EnumFacing facing;
        int retries;

        BuildStep(BlockPos pos, StepKind kind, EnumFacing facing) {
            this.pos = pos;
            this.kind = kind;
            this.facing = facing;
        }
    }

    private static final int SITE_RADIUS = 7;           // 15x15
    private static final int SLOPE_RING = 2;            // 外周のなだらかな斜面
    // [建築の強化] 7x7・壁3段の小屋は狭すぎるとの声を受けて、9x9・壁4段の
    // 一回り大きい拠点に変更。窓・炉・追加の明かり・チェストも合わせて増やした。
    private static final int HOUSE_RADIUS = 4;          // 9x9
    private static final int WALL_HEIGHT = 4;
    // [建築の強化: 切妻屋根] 平らな屋根はいかにも仮設小屋に見えるので、外周から
    // 段々に持ち上げて板/丸石の階段ブロックで斜面を作る「切妻屋根」に変更。
    // 高さは中央に立ったまま届く範囲に収まるよう2段までに抑えている。
    private static final int ROOF_PITCH_LAYERS = 2;
    private static final int SURVEY_RADIUS = 18;
    private static final int MAX_CUT_ABOVE = 24;
    private static final int MAX_FILL_DEPTH = 8;
    private static final double REACH_SQ = 4.8 * 4.8;
    // [建築の強化] 9x9・壁4段・切妻屋根・土台に大きくしたため、中央に立った時に
    // 一番遠い角（屋根の一番高い所、土台の四隅）まで届く距離も合わせて広げる
    // （届かないまま同じ場所で固まるのを防ぐ）。
    private static final double BUILD_REACH_SQ = 8.0 * 8.0;
    private static final double MOVE_SPEED = 1.0;
    private static final int STUCK_UNSTICK_TICKS = 30;
    private static final int STUCK_SKIP_TICKS = 160;
    private static final String NBT_SITE = "EngenderBuildSite";

    private final EntityFriendlyCreature entity;
    private final EngenderGatheringBridge bridge;
    private final List<ItemStack> carried = new ArrayList<ItemStack>();

    private Phase phase = Phase.SURVEY;
    private BlockPos siteOrigin;
    private int targetY;

    private BlockPos workTarget;
    private int workProgress;
    private int workRequired;
    private int stuckTicks;
    private double lastX;
    private double lastZ;
    private int noMoveTicks;
    private int replanCooldown;
    private final Set<BlockPos> skipped = new HashSet<BlockPos>();
    private int rescan;

    private final Deque<BuildStep> buildQueue = new ArrayDeque<BuildStep>();
    private int woodTrips;
    private BlockPos woodTarget;
    private boolean noticedNoFiller;
    private boolean noticedNoMaterial;
    private boolean houseIncomplete;

    public EntityAIBuildBaseAlly(EntityFriendlyCreature entity, EngenderGatheringBridge bridge) {
        this.entity = entity;
        this.bridge = bridge;
        this.setMutexBits(3);
        this.loadSite();
    }

    // ------------------------------------------------------------------
    // 外部から使われる口（手渡し・仕事の交代）
    // ------------------------------------------------------------------

    public void receiveMaterial(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            this.addToCarried(stack.copy());
        }
    }

    public void dropStoredMaterials() {
        for (ItemStack stack : this.carried) {
            if (stack != null && !stack.isEmpty()) {
                AllyInventory.insertOrDrop(this.entity, stack.copy());
            }
        }
        this.carried.clear();
    }

    public boolean acceptsMaterials() {
        return this.phase != Phase.DONE;
    }

    public boolean isDone() {
        return this.phase == Phase.DONE;
    }

    /** もう一度シャベルを渡された: 今いる場所で新しく整地・建築を始める。 */
    public void restart() {
        if (this.houseIncomplete && this.siteOrigin != null && this.entity.getDistanceSq(this.siteOrigin) < 64 * 64) {
            this.houseIncomplete = false;
            this.noticedNoMaterial = false;
            this.woodTrips = 0;
            this.buildQueue.clear();
            this.setPhase(Phase.BUILD); // 同じ場所で続きを仕上げる
            this.say("[拠点建設] 未完成の家の続きを仕上げます。");
            return;
        }
        this.entity.getEntityData().removeTag(NBT_SITE);
        this.siteOrigin = null;
        this.phase = Phase.SURVEY;
        this.buildQueue.clear();
        this.skipped.clear();
        this.woodTrips = 0;
        this.noticedNoFiller = false;
        this.noticedNoMaterial = false;
    }

    private void saveSite() {
        if (this.siteOrigin == null) {
            return;
        }
        NBTTagCompound t = new NBTTagCompound();
        t.setLong("Origin", this.siteOrigin.toLong());
        t.setInteger("Y", this.targetY);
        t.setString("Phase", this.phase.name());
        t.setBoolean("Incomplete", this.houseIncomplete);
        this.entity.getEntityData().setTag(NBT_SITE, t);
    }

    private void loadSite() {
        NBTTagCompound data = this.entity.getEntityData();
        if (!data.hasKey(NBT_SITE)) {
            return;
        }
        NBTTagCompound t = data.getCompoundTag(NBT_SITE);
        BlockPos saved = BlockPos.fromLong(t.getLong("Origin"));
        this.houseIncomplete = t.getBoolean("Incomplete");
        if ((Phase.DONE.name().equals(t.getString("Phase")) && !this.houseIncomplete)
                || this.entity.getDistanceSq(saved) > 64 * 64) {
            // 完成済み・遠く離れた古い現場 -- 新しく始める
            data.removeTag(NBT_SITE);
            return;
        }
        this.siteOrigin = saved;
        this.targetY = t.getInteger("Y");
        try {
            this.phase = Phase.valueOf(t.getString("Phase"));
        } catch (Exception e) {
            this.phase = Phase.CLEAR;
        }
        if (this.phase == Phase.BUILD || this.phase == Phase.GATHER_WOOD) {
            this.phase = Phase.BUILD; // 建築手順は作り直す
        }
    }

    private void setPhase(Phase p) {
        this.phase = p;
        this.workTarget = null;
        this.workProgress = 0;
        this.stuckTicks = 0;
        this.skipped.clear();
        this.rescan = 0;
        this.saveSite();
    }

    // ------------------------------------------------------------------
    // EntityAIBase
    // ------------------------------------------------------------------

    private boolean isBuilderJob() {
        return "shovel".equals(this.bridge.getJob(this.entity));
    }

    @Override
    public boolean shouldExecute() {
        return this.phase != Phase.DONE && this.isBuilderJob() && !this.bridge.isFighting(this.entity);
    }

    @Override
    public boolean shouldContinueExecuting() {
        return this.shouldExecute();
    }

    @Override
    public void resetTask() {
        this.entity.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        World world = this.entity.getEntityWorld();
        switch (this.phase) {
            case SURVEY:
                this.survey(world);
                break;
            case CLEAR:
                this.tickWork(world, Phase.CLEAR);
                break;
            case CUT:
                this.tickWork(world, Phase.CUT);
                break;
            case FILL:
                this.tickWork(world, Phase.FILL);
                break;
            case GATHER_WOOD:
                this.tickGatherWood(world);
                break;
            case BUILD:
                this.tickBuild(world);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // 下見: 一番平らで安全な土地を選ぶ
    // ------------------------------------------------------------------

    /** 幹の上の方に、自然に生えた（落葉する）葉が付いている木か。 */
    static boolean isNaturalTree(World w, BlockPos base) {
        Block log = w.getBlockState(base).getBlock();
        BlockPos top = base;
        for (int i = 0; i < 32 && w.getBlockState(top.up()).getBlock() == log; i++) {
            top = top.up();
        }
        for (BlockPos q : BlockPos.getAllInBoxMutable(top.add(-3, -3, -3), top.add(3, 3, 3))) {
            IBlockState s = w.getBlockState(q);
            if (s.getBlock() instanceof BlockLeaves) {
                try {
                    if (s.getValue(BlockLeaves.DECAYABLE)) {
                        return true;
                    }
                } catch (Exception e) {
                    return true; // Modの葉（性質が読めない）-- 自然とみなす
                }
            }
        }
        return false;
    }

    /** 人が置いた物（板材・丸石・レンガ・ガラス・柵・階段・ハーフ・羊毛など）。整地で壊さない。 */
    static boolean isManMade(IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof net.minecraft.block.BlockFence || b instanceof net.minecraft.block.BlockWall
                || b instanceof net.minecraft.block.BlockGlass || b instanceof net.minecraft.block.BlockPane
                || b instanceof net.minecraft.block.BlockStairs || b instanceof net.minecraft.block.BlockSlab
                || b instanceof BlockDoor || b instanceof net.minecraft.block.BlockBed
                || b instanceof net.minecraft.block.BlockFenceGate || b instanceof net.minecraft.block.BlockTrapDoor
                || b instanceof net.minecraft.block.BlockLadder || b instanceof net.minecraft.block.BlockRailBase
                || b instanceof net.minecraft.block.BlockTorch || b instanceof net.minecraft.block.BlockCarpet) {
            return true;
        }
        return b == Blocks.PLANKS || b == Blocks.COBBLESTONE || b == Blocks.STONEBRICK || b == Blocks.BRICK_BLOCK
                || b == Blocks.WOOL || b == Blocks.STAINED_HARDENED_CLAY || b == Blocks.CONCRETE
                || b == Blocks.QUARTZ_BLOCK || b == Blocks.IRON_BLOCK || b == Blocks.GOLD_BLOCK || b == Blocks.DIAMOND_BLOCK
                || b == Blocks.BOOKSHELF || b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN
                || b instanceof BlockCrops || b instanceof net.minecraft.block.BlockFarmland
                || b == Blocks.CRAFTING_TABLE || b == Blocks.HAY_BLOCK || b == Blocks.GRASS_PATH;
    }

    /** 植物・木・雪を除いた「地面」の高さ。水面なら -1。 */
    private int groundY(World w, int x, int z) {
        BlockPos top = w.getHeight(new BlockPos(x, 0, z));
        BlockPos p = top.down();
        for (int i = 0; i < 64 && p.getY() > 1; i++, p = p.down()) {
            IBlockState s = w.getBlockState(p);
            if (w.isAirBlock(p) || this.isVegetation(s)) {
                continue;
            }
            if (s.getMaterial().isLiquid()) {
                return -1;
            }
            return p.getY();
        }
        return p.getY();
    }

    private void survey(World w) {
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        BlockPos base = owner != null && owner.getDistanceSq(this.entity) < 64 * 64 ? new BlockPos(owner) : new BlockPos(this.entity);
        int span = SURVEY_RADIUS + SITE_RADIUS + 3;
        int size = span * 2 + 1;
        int[][] h = new int[size][size];
        boolean[][] blocked = new boolean[size][size];
        boolean[][] manMade = new boolean[size][size];
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int x = base.getX() + dx;
                int z = base.getZ() + dz;
                BlockPos col = new BlockPos(x, base.getY(), z);
                if (!w.isBlockLoaded(col)) {
                    h[dx + span][dz + span] = -1;
                    continue;
                }
                int gy = this.groundY(w, x, z);
                h[dx + span][dz + span] = gy;
                blocked[dx + span][dz + span] = gy < 0 || AllyAreas.isForbidden(w, new BlockPos(x, gy, z))
                        || AllyAreas.isForbidden(w, new BlockPos(x, gy + 1, z))
                        || (gy > 0 && isManMade(w.getBlockState(new BlockPos(x, gy, z))));
                for (int y = Math.max(1, gy); y <= gy + 12 && gy > 0; y++) {
                    if (isManMade(w.getBlockState(new BlockPos(x, y, z)))) {
                        manMade[dx + span][dz + span] = true;
                        break;
                    }
                }
            }
        }
        // 機械・チェスト等（タイルエンティティ）がある所は選ばない
        for (net.minecraft.tileentity.TileEntity te : w.loadedTileEntityList) {
            BlockPos tp = te.getPos();
            int ix = tp.getX() - base.getX() + span;
            int iz = tp.getZ() - base.getZ() + span;
            if (ix >= 0 && iz >= 0 && ix < size && iz < size && Math.abs(tp.getY() - base.getY()) < 24) {
                blocked[ix][iz] = true;
            }
        }
        long bestCost = Long.MAX_VALUE;
        int bestX = 0;
        int bestZ = 0;
        int bestY = base.getY() - 1;
        for (int cx = -SURVEY_RADIUS; cx <= SURVEY_RADIUS; cx += 3) {
            for (int cz = -SURVEY_RADIUS; cz <= SURVEY_RADIUS; cz += 3) {
                List<Integer> heights = new ArrayList<Integer>();
                boolean bad = false;
                for (int dx = -SITE_RADIUS; dx <= SITE_RADIUS && !bad; dx++) {
                    for (int dz = -SITE_RADIUS; dz <= SITE_RADIUS; dz++) {
                        int ix = cx + dx + span;
                        int iz = cz + dz + span;
                        if (blocked[ix][iz] || h[ix][iz] < 0) {
                            bad = true;
                            break;
                        }
                        heights.add(h[ix][iz]);
                    }
                }
                if (bad || heights.isEmpty()) {
                    continue;
                }
                java.util.Collections.sort(heights);
                int median = heights.get(heights.size() / 2);
                if (heights.get(heights.size() - 1) - median > MAX_CUT_ABOVE - 2 || median - heights.get(0) > MAX_FILL_DEPTH) {
                    continue; // 崖・深い谷 -- きれいに平らにできない
                }
                boolean nearBuild = false;
                for (int dx = -SITE_RADIUS - 3; dx <= SITE_RADIUS + 3 && !nearBuild; dx++) {
                    for (int dz = -SITE_RADIUS - 3; dz <= SITE_RADIUS + 3; dz++) {
                        if (manMade[cx + dx + span][cz + dz + span]) {
                            nearBuild = true;
                            break;
                        }
                    }
                }
                if (nearBuild) {
                    continue; // 人の建てた物が敷地・斜面に掛かる
                }
                long cost = 0;
                for (int y : heights) {
                    cost += Math.abs(y - median) * (y > median ? 2L : 1L); // 削る方が大変
                }
                cost += (long) (Math.abs(cx) + Math.abs(cz)) * 2; // 持ち主の近くを優先
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = cx;
                    bestZ = cz;
                    bestY = median;
                }
            }
        }
        if (bestCost == Long.MAX_VALUE) {
            this.say("[拠点建設] 近くに整地できる土地が見つかりません（水・機械・立ち入り禁止エリアばかり）。場所を変えてシャベルを渡してください。");
            this.setPhase(Phase.DONE);
            return;
        }
        this.siteOrigin = new BlockPos(base.getX() + bestX, bestY, base.getZ() + bestZ);
        this.targetY = bestY;
        this.say("[拠点建設] 整地場所を決めました（" + this.siteOrigin.getX() + ", " + bestY + ", " + this.siteOrigin.getZ()
                + " の周り13x13）。木と草を片付けてから、削って・埋めて平らにします。");
        this.setPhase(Phase.CLEAR);
    }

    // ------------------------------------------------------------------
    // 伐採・切り崩し・埋め立て 共通の作業ループ
    // ------------------------------------------------------------------

    private boolean isVegetation(IBlockState s) {
        Block b = s.getBlock();
        return b instanceof BlockLog || b instanceof BlockLeaves || (b instanceof BlockBush && !(b instanceof BlockCrops))
                || b instanceof BlockVine || b instanceof BlockSnow || b instanceof BlockHugeMushroom
                || b == Blocks.CACTUS || b == Blocks.REEDS || b == Blocks.WEB || b == Blocks.PUMPKIN || b == Blocks.MELON_BLOCK;
    }

    /** 敷地＋外周の斜面を含む、その列の「あるべき高さ」。範囲外は Integer.MIN_VALUE。 */
    private int desiredY(int x, int z) {
        int d = Math.max(Math.abs(x - this.siteOrigin.getX()), Math.abs(z - this.siteOrigin.getZ()));
        if (d <= SITE_RADIUS) {
            return this.targetY;
        }
        if (d <= SITE_RADIUS + SLOPE_RING) {
            return Integer.MIN_VALUE + d; // 斜面: 呼び出し側で扱う
        }
        return Integer.MIN_VALUE;
    }

    private int ringDistance(int x, int z) {
        return Math.max(Math.abs(x - this.siteOrigin.getX()), Math.abs(z - this.siteOrigin.getZ())) - SITE_RADIUS;
    }

    /** 次にやる場所。無ければ null（そのフェーズ完了）。近くて手の届く物を優先、上から/下から順。 */
    private BlockPos findWork(World w, Phase p) {
        int r = SITE_RADIUS + (p == Phase.CLEAR ? 3 : SLOPE_RING);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = this.siteOrigin.getX() + dx;
                int z = this.siteOrigin.getZ() + dz;
                int ring = this.ringDistance(x, z);
                BlockPos candidate = null;
                if (p == Phase.CLEAR) {
                    // 上から: 木は根元（一番下の原木）を狙う → 切れば木ごと倒れる。
                    // 草花・雪・低い葉は手の届く高さの物だけ（頭上の枝葉は木と一緒に消える）。
                    for (int y = this.targetY + MAX_CUT_ABOVE + 8; y >= this.targetY - 3; y--) {
                        BlockPos q = new BlockPos(x, y, z);
                        if (w.isAirBlock(q)) {
                            continue;
                        }
                        IBlockState s = w.getBlockState(q);
                        if (!this.isVegetation(s)) {
                            continue;
                        }
                        if (s.getBlock() instanceof BlockLog) {
                            BlockPos base = q;
                            while (base.getY() > this.targetY - 3 && w.getBlockState(base.down()).getBlock() instanceof BlockLog) {
                                base = base.down();
                            }
                            // 葉の付いた自然の木だけ（ログハウス等の原木は切らない）
                            if (!this.skipped.contains(base) && isNaturalTree(w, base)) {
                                candidate = base;
                            }
                            break;
                        }
                        // 外周の外は、敷地に掛かる木（原木）だけ
                        if (ring > 0 || y > this.targetY + 6 || this.skipped.contains(q)) {
                            continue;
                        }
                        candidate = q;
                        break;
                    }
                } else if (p == Phase.CUT) {
                    int limit = ring <= 0 ? this.targetY : this.targetY + ring;
                    for (int y = this.targetY + MAX_CUT_ABOVE; y > limit; y--) {
                        BlockPos q = new BlockPos(x, y, z);
                        if (w.isAirBlock(q)) {
                            continue;
                        }
                        if (this.skipped.contains(q)) {
                            break; // 削らない物（人の建てた物等）の下は掘らない（浮かせない）
                        }
                        candidate = q;
                        break;
                    }
                } else {
                    int limit = ring <= 0 ? this.targetY : this.targetY - ring;
                    // 上から下へ最初の固い地面を探し、その真上から埋める（洞窟の空洞や宙には置かない）
                    for (int y = limit; y >= Math.max(1, this.targetY - MAX_FILL_DEPTH); y--) {
                        BlockPos q = new BlockPos(x, y, z);
                        IBlockState s = w.getBlockState(q);
                        if (w.isAirBlock(q) || s.getMaterial().isLiquid() || this.isVegetation(s)) {
                            continue;
                        }
                        if (y < limit) {
                            BlockPos f = q.up();
                            if (!this.skipped.contains(f)) {
                                candidate = f;
                            }
                        }
                        break;
                    }
                }
                if (candidate == null || AllyAreas.isForbidden(w, candidate) || w.getTileEntity(candidate) != null) {
                    continue;
                }
                if (p == Phase.CUT && (w.getBlockState(candidate).getBlockHardness(w, candidate) < 0
                        || isManMade(w.getBlockState(candidate)))) {
                    this.skipped.add(candidate); // 岩盤・人の建てた物は削らない
                    continue;
                }
                double d = this.eyeDistSq(candidate);
                // CUT は上の段を先に、FILL は下の段を先に。近さはその次。
                double layer = p == Phase.CUT ? -candidate.getY() * 1000.0 : p == Phase.FILL ? candidate.getY() * 1000.0
                        : -candidate.getY() * 1000.0;
                double score = layer + d;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private double eyeDistSq(BlockPos p) {
        double dx = p.getX() + 0.5 - this.entity.posX;
        double dy = p.getY() + 0.5 - (this.entity.posY + this.entity.getEyeHeight());
        double dz = p.getZ() + 0.5 - this.entity.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void tickWork(World w, Phase p) {
        if (this.workTarget == null || !this.stillNeeded(w, p, this.workTarget)) {
            this.workTarget = null;
            this.workProgress = 0;
            if (--this.rescan > 0) {
                return;
            }
            this.rescan = 4;
            BlockPos next = this.findWork(w, p);
            if (next == null) {
                this.finishPhase(w, p);
                return;
            }
            this.workTarget = next;
            this.stuckTicks = 0;
        }
        BlockPos t = this.workTarget;
        this.entity.getLookHelper().setLookPosition(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5, 30.0F, 30.0F);
        boolean reachable = this.eyeDistSq(t) <= REACH_SQ
                || (this.stuckTicks > STUCK_UNSTICK_TICKS * 2 && this.eyeDistSq(t) <= 7.0 * 7.0);
        if (!reachable) {
            this.walkNear(t);
            if (++this.stuckTicks > STUCK_SKIP_TICKS) {
                // どうしても近づけない -- 後回しにして別の場所から
                this.skipped.add(t);
                this.workTarget = null;
                this.stuckTicks = 0;
            }
            return;
        }
        this.entity.getNavigator().clearPath();
        if (p == Phase.FILL) {
            if (++this.workProgress < Math.max(2, 6 - AllySkills.level(this.entity, AllySkills.Skill.BUILDING) / 5)) {
                return;
            }
            this.fillAt(w, t);
            this.workTarget = null;
            return;
        }
        if (p == Phase.CUT && w.getBlockState(t).getMaterial().isLiquid()) {
            // 敷地より上の水・溶岩: まず埋めて流れを止め、次の回でその土を削る
            ItemStack plug = this.takeFiller(false);
            if (plug.isEmpty()) {
                this.skipped.add(t);
            } else {
                w.setBlockState(t, Block.getBlockFromItem(plug.getItem()).getStateFromMeta(plug.getMetadata()), 3);
            }
            this.workTarget = null;
            return;
        }
        if (this.workProgress == 0) {
            IBlockState s = w.getBlockState(t);
            AllyToolManager.equipForBlock(this.entity, s, null);
            this.workRequired = this.breakTicks(w, t, s);
        }
        this.workProgress++;
        if (this.workProgress % 5 == 0) {
            this.entity.swingArm(EnumHand.MAIN_HAND);
            w.playEvent(2001, t, Block.getStateId(w.getBlockState(t)));
        }
        if (this.workProgress < this.workRequired) {
            return;
        }
        IBlockState s = w.getBlockState(t);
        if (s.getBlock() instanceof BlockLog) {
            this.fellTree(w, t);
        } else {
            this.breakAt(w, t);
        }
        this.workTarget = null;
        this.workProgress = 0;
    }

    private boolean stillNeeded(World w, Phase p, BlockPos t) {
        IBlockState s = w.getBlockState(t);
        if (p == Phase.FILL) {
            return w.isAirBlock(t) || s.getMaterial().isLiquid() || this.isVegetation(s);
        }
        if (p == Phase.CLEAR) {
            return !w.isAirBlock(t) && this.isVegetation(s);
        }
        return !w.isAirBlock(t);
    }

    private int phaseRetries;

    private void finishPhase(World w, Phase p) {
        this.vacuum(w, this.siteOrigin, SITE_RADIUS + 4);
        if (!this.skipped.isEmpty() && this.phaseRetries < 2) {
            // 後回しにした所を、もう一度だけやり直す（別の方向から届くかもしれない）
            this.phaseRetries++;
            this.skipped.clear();
            return;
        }
        this.phaseRetries = 0;
        if (p == Phase.CLEAR) {
            this.setPhase(Phase.CUT);
        } else if (p == Phase.CUT) {
            this.setPhase(Phase.FILL);
        } else {
            this.grassTop(w);
            this.say("[拠点建設] 整地が完了しました。続けて家を建てます。");
            this.setPhase(Phase.BUILD);
        }
    }

    /** 掘る時間（ブロックの硬さと道具から。建築スキルで速くなる）。草花は一瞬。 */
    private int breakTicks(World w, BlockPos p, IBlockState s) {
        float hardness = s.getBlockHardness(w, p);
        if (hardness <= 0.0F) {
            return 1;
        }
        ItemStack tool = this.entity.getHeldItemMainhand();
        float speed = tool.isEmpty() ? 1.0F : Math.max(1.0F, tool.getDestroySpeed(s));
        boolean can = AllyAIUtil.canHarvestWith(tool, s);
        int ticks = (int) Math.ceil(hardness * (can ? 30.0F : 100.0F) / speed);
        ticks = (int) Math.ceil(ticks / AllySkills.speedMultiplier(this.entity, AllySkills.Skill.BUILDING));
        return Math.max(1, Math.min(ticks, 200));
    }

    private void breakAt(World w, BlockPos p) {
        if (AllyAreas.isForbidden(w, p) || w.getTileEntity(p) != null) {
            this.skipped.add(p);
            return;
        }
        IBlockState s = w.getBlockState(p);
        if (s.getMaterial().isLiquid()) {
            w.setBlockToAir(p); // 敷地より上の水は抜く
            return;
        }
        boolean harvest = AllyAIUtil.canHarvestWith(this.entity.getHeldItemMainhand(), s);
        if (harvest) {
            for (ItemStack d : s.getBlock().getDrops(w, p, s, 0)) {
                if (d != null && !d.isEmpty()) {
                    this.addToCarried(d);
                }
            }
        }
        w.setBlockToAir(p);
        ItemStack tool = this.entity.getHeldItemMainhand();
        if (!tool.isEmpty() && tool.isItemStackDamageable() && s.getBlockHardness(w, p) > 0) {
            tool.damageItem(1, this.entity);
        }
        AllySkills.addXp(this.entity, AllySkills.Skill.BUILDING, 1);
    }

    /** 根元の原木を切ったら、つながった原木を全部倒す（葉は周りの分を片付ける）。 */
    private void fellTree(World w, BlockPos base) {
        Block logBlock = w.getBlockState(base).getBlock();
        Deque<BlockPos> frontier = new ArrayDeque<BlockPos>();
        Set<BlockPos> logs = new HashSet<BlockPos>();
        frontier.add(base);
        logs.add(base);
        while (!frontier.isEmpty() && logs.size() < 256) {
            BlockPos c = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = c.add(dx, dy, dz);
                        if (!logs.contains(n) && w.getBlockState(n).getBlock() == logBlock
                                && n.distanceSq(base) < 20 * 20 && !AllyAreas.isForbidden(w, n)) {
                            logs.add(n);
                            frontier.add(n);
                        }
                    }
                }
            }
        }
        for (BlockPos l : logs) {
            IBlockState s = w.getBlockState(l);
            for (ItemStack d : s.getBlock().getDrops(w, l, s, 0)) {
                this.addToCarried(d);
            }
            w.setBlockToAir(l);
        }
        // 倒した木の葉（自然に生えた葉だけ）
        for (BlockPos l : logs) {
            for (BlockPos q : BlockPos.getAllInBox(l.add(-3, -1, -3), l.add(3, 3, 3))) {
                IBlockState s = w.getBlockState(q);
                if (s.getBlock() instanceof BlockLeaves && !AllyAreas.isForbidden(w, q)) {
                    boolean natural = true;
                    try {
                        natural = s.getValue(BlockLeaves.DECAYABLE);
                    } catch (Exception ignored) {
                        // Modの葉 -- 自然な物とみなす
                    }
                    if (natural) {
                        for (ItemStack d : s.getBlock().getDrops(w, q, s, 0)) {
                            this.addToCarried(d);
                        }
                        w.setBlockToAir(q);
                    }
                }
            }
        }
        w.playSound(null, base, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.0F, 0.8F);
        AllySkills.addXp(this.entity, AllySkills.Skill.WOODCUTTING, logs.size());
        ItemStack tool = this.entity.getHeldItemMainhand();
        if (!tool.isEmpty() && tool.isItemStackDamageable()) {
            tool.damageItem(Math.min(logs.size(), 8), this.entity);
        }
    }

    private boolean occupied(World w, BlockPos p) {
        return !w.getEntitiesWithinAABB(net.minecraft.entity.EntityLivingBase.class, new AxisAlignedBB(p)).isEmpty();
    }

    private void fillAt(World w, BlockPos p) {
        if (AllyAreas.isForbidden(w, p)) {
            this.skipped.add(p);
            return;
        }
        if (this.occupied(w, p)) {
            // 自分や誰かが立っている -- 一歩どいてから（自分なら別の立ち位置へ）
            if (this.entity.getEntityBoundingBox().intersects(new AxisAlignedBB(p))) {
                BlockPos away = this.standSpotNear(p.add(2, 0, 0));
                this.entity.getNavigator().tryMoveToXYZ(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, MOVE_SPEED);
            }
            return;
        }
        IBlockState s = w.getBlockState(p);
        if (this.isVegetation(s)) {
            this.breakAt(w, p);
        }
        ItemStack filler = this.takeFiller(p.getY() == this.targetY);
        if (filler.isEmpty()) {
            if (!this.noticedNoFiller) {
                this.noticedNoFiller = true;
                this.say("[拠点建設] 埋め立て用の土・石が足りません。土か丸石をスニーク右クリックで渡してもらえれば続きを埋めます。");
            }
            this.skipped.add(p);
            return;
        }
        Block b = Block.getBlockFromItem(filler.getItem());
        w.setBlockState(p, b.getStateFromMeta(filler.getMetadata()), 3);
        w.playSound(null, p, b.getSoundType().getPlaceSound(), SoundCategory.BLOCKS, 0.7F, 1.0F);
        this.entity.swingArm(EnumHand.MAIN_HAND);
        AllySkills.addXp(this.entity, AllySkills.Skill.BUILDING, 1);
    }

    private static boolean isFiller(ItemStack s, boolean top) {
        if (s.isEmpty() || !(s.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block b = Block.getBlockFromItem(s.getItem());
        if (top) {
            return b == Blocks.DIRT || b == Blocks.GRASS;
        }
        return b == Blocks.DIRT || b == Blocks.GRASS || b == Blocks.COBBLESTONE || b == Blocks.STONE
                || b == Blocks.NETHERRACK || b == Blocks.CLAY; // 砂・砂利は崩れるので使わない
    }

    /** 埋め立て材: 表面は土、下は土・石・砂利。手持ち→持ち物の順。 */
    private ItemStack takeFiller(boolean top) {
        for (int pass = 0; pass < 2; pass++) {
            boolean wantTop = top && pass == 0;
            for (ItemStack s : this.carried) {
                if (pass == 0 ? isFiller(s, wantTop) : isFiller(s, false)) {
                    return this.takeOne(s);
                }
            }
            InventoryBasic inv = AllyInventory.get(this.entity);
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (pass == 0 ? isFiller(s, wantTop) : isFiller(s, false)) {
                    return inv.decrStackSize(i, 1);
                }
            }
            if (!top) {
                break;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 表面の土を草ブロックに（見た目を整える）。 */
    private void grassTop(World w) {
        for (int dx = -SITE_RADIUS - SLOPE_RING; dx <= SITE_RADIUS + SLOPE_RING; dx++) {
            for (int dz = -SITE_RADIUS - SLOPE_RING; dz <= SITE_RADIUS + SLOPE_RING; dz++) {
                BlockPos col = new BlockPos(this.siteOrigin.getX() + dx, 0, this.siteOrigin.getZ() + dz);
                BlockPos top = w.getHeight(col).down();
                if (w.getBlockState(top).getBlock() == Blocks.DIRT && w.isAirBlock(top.up())
                        && !AllyAreas.isForbidden(w, top)) {
                    w.setBlockState(top, Blocks.GRASS.getDefaultState(), 3);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 移動（詰まり対策付き）
    // ------------------------------------------------------------------

    /** 対象の近くの立てる場所へ歩く。止まったら足元の草花を片付けて跳ぶ。 */
    private void walkNear(BlockPos t) {
        double moved = (this.entity.posX - this.lastX) * (this.entity.posX - this.lastX)
                + (this.entity.posZ - this.lastZ) * (this.entity.posZ - this.lastZ);
        this.lastX = this.entity.posX;
        this.lastZ = this.entity.posZ;
        this.noMoveTicks = moved < 0.0025 ? this.noMoveTicks + 1 : 0;
        if (this.noMoveTicks > 0 && this.noMoveTicks % STUCK_UNSTICK_TICKS == 0) {
            AllyAIUtil.unstick(this.entity, this.carried, this.phase == Phase.CLEAR || this.phase == Phase.CUT
                    || this.phase == Phase.GATHER_WOOD);
            this.entity.getNavigator().clearPath();
        }
        if (--this.replanCooldown <= 0 && (this.entity.getNavigator().noPath() || this.noMoveTicks % 20 == 19)) {
            this.replanCooldown = 10;
            BlockPos stand = this.standSpotNear(t);
            net.minecraft.pathfinding.Path path = SafeRoutePlanner.plan(this.entity, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            if (path != null) {
                this.entity.getNavigator().setPath(path, MOVE_SPEED);
            }
        }
    }

    /** t の周りで、立てる（足元が固く、2マス空いている）場所。無ければ t の上。 */
    private BlockPos standSpotNear(BlockPos t) {
        World w = this.entity.world;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    BlockPos f = t.add(dx, dy, dz);
                    if (!AllyAIUtil.isPassable(w, f) || !AllyAIUtil.isPassable(w, f.up())) {
                        continue;
                    }
                    IBlockState below = w.getBlockState(f.down());
                    if (!below.getMaterial().isSolid() || below.getMaterial().isLiquid()) {
                        continue;
                    }
                    double d = f.distanceSq(t) + this.entity.getDistanceSq(f) * 0.05;
                    if (d < bestD && d > 0.5) {
                        bestD = d;
                        best = f;
                    }
                }
            }
        }
        return best != null ? best : t.up();
    }

    // ------------------------------------------------------------------
    // 建築
    // ------------------------------------------------------------------

    private static boolean hasOre(ItemStack s, String ore) {
        if (s.isEmpty()) {
            return false;
        }
        int id = OreDictionary.getOreID(ore);
        for (int i : OreDictionary.getOreIDs(s)) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    /** 手持ち・持ち物の資材をまとめて数える。 */
    private int countAll(ItemStackMatcher m) {
        int n = 0;
        for (ItemStack s : this.carried) {
            if (m.matches(s)) {
                n += s.getCount();
            }
        }
        InventoryBasic inv = AllyInventory.get(this.entity);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (m.matches(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private ItemStack takeAny(ItemStackMatcher m) {
        for (ItemStack s : this.carried) {
            if (m.matches(s)) {
                return this.takeOne(s);
            }
        }
        InventoryBasic inv = AllyInventory.get(this.entity);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (m.matches(inv.getStackInSlot(i))) {
                return inv.decrStackSize(i, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    private interface ItemStackMatcher {
        boolean matches(ItemStack s);
    }

    private static final ItemStackMatcher PLANKS = new ItemStackMatcher() {
        @Override
        public boolean matches(ItemStack s) {
            return hasOre(s, "plankWood");
        }
    };
    private static final ItemStackMatcher LOGS = new ItemStackMatcher() {
        @Override
        public boolean matches(ItemStack s) {
            return hasOre(s, "logWood");
        }
    };
    private static boolean isStoneBlock(Block b) {
        return b == Blocks.COBBLESTONE || b == Blocks.STONEBRICK || b == Blocks.BRICK_BLOCK || b == Blocks.STONE
                || b == Blocks.SANDSTONE || b == Blocks.MOSSY_COBBLESTONE;
    }

    private static final ItemStackMatcher STONE = new ItemStackMatcher() {
        @Override
        public boolean matches(ItemStack s) {
            return !s.isEmpty() && isStoneBlock(Block.getBlockFromItem(s.getItem()));
        }
    };

    /** 原木を板材にする（原木1 → 板材4、作業台と同じ）。 */
    private void craftPlanks(int wanted) {
        while (this.countAll(PLANKS) < wanted) {
            ItemStack log = this.takeAny(LOGS);
            if (log.isEmpty()) {
                return;
            }
            int meta = 0;
            if (log.getItem() == Item.getItemFromBlock(Blocks.LOG)) {
                meta = log.getMetadata() & 3;
            } else if (log.getItem() == Item.getItemFromBlock(Blocks.LOG2)) {
                meta = 4 + (log.getMetadata() & 1);
            }
            this.addToCarried(new ItemStack(Blocks.PLANKS, 4, meta));
        }
    }

    private boolean takePlanks(int n) {
        this.craftPlanks(n);
        if (this.countAll(PLANKS) < n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            this.takeAny(PLANKS);
        }
        return true;
    }

    private int structuralNeeded() {
        int n = 0;
        for (BuildStep s : this.buildQueue) {
            if ((s.kind == StepKind.FLOOR || s.kind == StepKind.WALL || s.kind == StepKind.ROOF
                    || s.kind == StepKind.ROOF_STAIRS)
                    && !this.isPlacedStructural(this.entity.world, s)) {
                n++;
            }
        }
        return n;
    }

    private boolean isPlacedStructural(World w, BuildStep s) {
        IBlockState st = w.getBlockState(s.pos);
        return !w.isAirBlock(s.pos) && st.getMaterial().isSolid() && !(st.getBlock() instanceof BlockLeaves)
                && (s.kind != StepKind.FLOOR || st.getBlock() != Blocks.GRASS && st.getBlock() != Blocks.DIRT);
    }

    private void planHouse() {
        this.buildQueue.clear();
        int ox = this.siteOrigin.getX();
        int oz = this.siteOrigin.getZ();
        int floorY = this.targetY;
        int doorX = ox;
        int doorZ = oz - HOUSE_RADIUS;
        // [建築の強化: 土台] 床の下に丸石類の土台を敷く（土の上に直接床が乗っている
        // 「仮設小屋」感を無くす）。材料が足りない分はそのまま地面を活かすだけで、
        // 家の完成自体は妨げない。
        for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
            for (int dz = -HOUSE_RADIUS; dz <= HOUSE_RADIUS; dz++) {
                this.buildQueue.add(new BuildStep(new BlockPos(ox + dx, floorY - 1, oz + dz), StepKind.FOUNDATION, null));
            }
        }
        for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
            for (int dz = -HOUSE_RADIUS; dz <= HOUSE_RADIUS; dz++) {
                this.buildQueue.add(new BuildStep(new BlockPos(ox + dx, floorY, oz + dz), StepKind.FLOOR, null));
            }
        }
        // [建築の強化: 窓] 東西北の壁の中央（南は扉があるので対象外）に採光用の窓を開ける。
        int windowY = floorY + 2;
        for (int y = floorY + 1; y <= floorY + WALL_HEIGHT; y++) {
            for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
                for (int dz = -HOUSE_RADIUS; dz <= HOUSE_RADIUS; dz++) {
                    if (Math.abs(dx) != HOUSE_RADIUS && Math.abs(dz) != HOUSE_RADIUS) {
                        continue;
                    }
                    int x = ox + dx;
                    int z = oz + dz;
                    if (x == doorX && z == doorZ && y <= floorY + 2) {
                        continue;
                    }
                    boolean windowSpot = y == windowY
                            && ((dx == 0 && Math.abs(dz) == HOUSE_RADIUS) || (dz == 0 && Math.abs(dx) == HOUSE_RADIUS));
                    this.buildQueue.add(new BuildStep(new BlockPos(x, y, z), windowSpot ? StepKind.WINDOW : StepKind.WALL, null));
                }
            }
        }
        // [建築の強化: 切妻屋根] 南北方向へ段々に狭めながら積み、外側の縁は階段ブロックで
        // 斜面に、内側（次の段の下に隠れる部分と一番上の段）は平らな板/丸石で埋める。
        int roofBaseY = floorY + WALL_HEIGHT + 1;
        for (int layer = 0; layer <= ROOF_PITCH_LAYERS; layer++) {
            int y = roofBaseY + layer;
            int zMin = oz - HOUSE_RADIUS + layer;
            int zMax = oz + HOUSE_RADIUS - layer;
            boolean cap = layer == ROOF_PITCH_LAYERS;
            for (int dx = -HOUSE_RADIUS; dx <= HOUSE_RADIUS; dx++) {
                int x = ox + dx;
                for (int z = zMin; z <= zMax; z++) {
                    if (!cap && z == zMin) {
                        this.buildQueue.add(new BuildStep(new BlockPos(x, y, z), StepKind.ROOF_STAIRS, EnumFacing.SOUTH));
                    } else if (!cap && z == zMax) {
                        this.buildQueue.add(new BuildStep(new BlockPos(x, y, z), StepKind.ROOF_STAIRS, EnumFacing.NORTH));
                    } else {
                        this.buildQueue.add(new BuildStep(new BlockPos(x, y, z), StepKind.ROOF, null));
                    }
                }
            }
        }
        this.buildQueue.add(new BuildStep(new BlockPos(doorX, floorY + 1, doorZ), StepKind.DOOR, EnumFacing.SOUTH));
        // [建築の強化: ベッド] 拠点なのに寝る場所が無かったので追加。扉・家具から離れた
        // 南西側の空きスペースに置く（北側は作業台/炉/チェスト、扉は南中央で埋まっている）。
        this.buildQueue.add(new BuildStep(new BlockPos(ox - 3, floorY + 1, oz - 1), StepKind.BED, EnumFacing.NORTH));
        // [建築の強化: 明かり] 以前は東西の壁だけだったので、北側にも1本追加して部屋全体を照らす。
        this.buildQueue.add(new BuildStep(new BlockPos(ox - HOUSE_RADIUS + 1, floorY + 2, oz), StepKind.LIGHT, EnumFacing.EAST));
        this.buildQueue.add(new BuildStep(new BlockPos(ox + HOUSE_RADIUS - 1, floorY + 2, oz), StepKind.LIGHT, EnumFacing.WEST));
        this.buildQueue.add(new BuildStep(new BlockPos(ox, floorY + 2, oz + HOUSE_RADIUS - 1), StepKind.LIGHT, EnumFacing.SOUTH));
        this.buildQueue.add(new BuildStep(new BlockPos(ox - 2, floorY + 1, oz + HOUSE_RADIUS - 1), StepKind.CRAFTING, null));
        this.buildQueue.add(new BuildStep(new BlockPos(ox, floorY + 1, oz + HOUSE_RADIUS - 1), StepKind.FURNACE, null));
        this.buildQueue.add(new BuildStep(new BlockPos(ox + 2, floorY + 1, oz + HOUSE_RADIUS - 1), StepKind.CHEST, null));
        // [建築の強化: 収納] チェストをもう1つ、扉のそば（出入り時に使いやすい場所）に増設。
        this.buildQueue.add(new BuildStep(new BlockPos(ox + 2, floorY + 1, oz - HOUSE_RADIUS + 1), StepKind.CHEST, null));
    }

    private void tickBuild(World w) {
        if (this.buildQueue.isEmpty()) {
            this.planHouse();
            // 材料の見込み: 丸石類＋板材（原木は板材に換算）で足りなければ木を切りに行く
            int need = this.structuralNeeded() + 30; // ドア・ベッド・作業台・炉・チェスト2つ・明かり3本・窓の分
            int have = this.countAll(STONE) + this.countAll(PLANKS) + this.countAll(LOGS) * 4;
            if (have < need && this.woodTrips < 4) {
                this.woodTrips++;
                this.say("[拠点建設] 建材が " + (need - have) + " 個ほど足りないので、近くの木を切ってきます。");
                this.setPhase(Phase.GATHER_WOOD);
                this.buildQueue.clear();
                return;
            }
        }
        BuildStep step = this.buildQueue.peek();
        if (step == null) {
            return;
        }
        // 家の中央に立てば、床・壁・屋根すべてに手が届く
        BlockPos center = new BlockPos(this.siteOrigin.getX(), this.targetY + 1, this.siteOrigin.getZ());
        if (this.entity.getDistanceSq(center.getX() + 0.5, center.getY(), center.getZ() + 0.5) > 1.2 * 1.2
                && this.eyeDistSq(step.pos) > BUILD_REACH_SQ) {
            this.walkNear(center);
            if (++this.stuckTicks > STUCK_SKIP_TICKS * 2 && this.entity.getDistanceSq(center) < 20 * 20) {
                this.entity.setPositionAndUpdate(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
                this.stuckTicks = 0;
            }
            return;
        }
        this.stuckTicks = 0;
        this.entity.getNavigator().clearPath();
        this.entity.getLookHelper().setLookPosition(step.pos.getX() + 0.5, step.pos.getY() + 0.5, step.pos.getZ() + 0.5, 30.0F, 30.0F);
        if (++this.workProgress < Math.max(2, 5 - AllySkills.level(this.entity, AllySkills.Skill.BUILDING) / 6)) {
            return;
        }
        this.workProgress = 0;
        this.buildQueue.poll();
        this.placeStep(w, step);
        if (this.buildQueue.isEmpty()) {
            this.vacuum(w, this.siteOrigin, SITE_RADIUS + 2);
            this.dropStoredMaterials();
            if (this.houseIncomplete) {
                this.say("[拠点建設] 材料が足りず、家の一部が未完成です。建材を渡してシャベルをもう一度渡すと、続きを仕上げます。");
            } else {
                this.say("[拠点建設] 拠点（" + this.siteOrigin.getX() + ", " + (this.targetY + 1) + ", " + this.siteOrigin.getZ()
                        + "）が完成しました！ 石の土台・切妻屋根・窓・ベッド・作業台・炉・チェスト2つ・明かり3本付きの、"
                        + "一回り大きくて本格的な拠点です。");
            }
            this.setPhase(Phase.DONE);
        }
    }

    private void placeStep(World w, BuildStep step) {
        if (AllyAreas.isForbidden(w, step.pos)) {
            return;
        }
        if (step.kind != StepKind.FLOOR && this.occupied(w, step.pos) && step.retries < 20) {
            step.retries++;
            this.buildQueue.addLast(step); // 誰かが立っている -- 後で置く
            return;
        }
        switch (step.kind) {
            case FLOOR:
            case WALL:
            case ROOF:
                this.placeStructural(w, step);
                break;
            case FOUNDATION:
                this.placeFoundation(w, step);
                break;
            case ROOF_STAIRS:
                this.placeRoofStairs(w, step);
                break;
            case WINDOW:
                this.placeWindow(w, step);
                break;
            case DOOR:
                this.placeDoor(w, step);
                break;
            case LIGHT:
                this.placeTorch(w, step);
                break;
            case FURNACE:
                this.placeFurnace(w, step);
                break;
            case BED:
                this.placeBed(w, step);
                break;
            case CRAFTING:
                if (w.isAirBlock(step.pos) && (this.takeAny(new ItemStackMatcher() {
                    @Override
                    public boolean matches(ItemStack s) {
                        return s.getItem() == Item.getItemFromBlock(Blocks.CRAFTING_TABLE);
                    }
                }).isEmpty() ? this.takePlanks(4) : true)) {
                    this.setBlock(w, step.pos, Blocks.CRAFTING_TABLE.getDefaultState());
                }
                break;
            case CHEST:
                if (w.isAirBlock(step.pos) && (this.takeAny(new ItemStackMatcher() {
                    @Override
                    public boolean matches(ItemStack s) {
                        return s.getItem() == Item.getItemFromBlock(Blocks.CHEST);
                    }
                }).isEmpty() ? this.takePlanks(8) : true)) {
                    this.setBlock(w, step.pos, Blocks.CHEST.getDefaultState());
                }
                break;
            default:
                break;
        }
    }

    private void setBlock(World w, BlockPos p, IBlockState s) {
        w.setBlockState(p, s, 3);
        w.playSound(null, p, s.getBlock().getSoundType().getPlaceSound(), SoundCategory.BLOCKS, 0.8F, 1.0F);
        this.entity.swingArm(EnumHand.MAIN_HAND);
        AllySkills.addXp(this.entity, AllySkills.Skill.BUILDING, 1);
    }

    /** 床・屋根は板材を優先、壁は丸石類を優先（無ければもう一方）。 */
    private void placeStructural(World w, BuildStep step) {
        if (this.isPlacedStructural(w, step)) {
            return;
        }
        IBlockState cur = w.getBlockState(step.pos);
        if (!w.isAirBlock(step.pos) && !this.isVegetation(cur) && !cur.getMaterial().isReplaceable()
                && step.kind != StepKind.FLOOR) {
            return; // 何か別の物がある（誰かの設置物など）-- 壊さない
        }
        ItemStack mat;
        if (step.kind == StepKind.WALL) {
            mat = this.takeAny(STONE);
            if (mat.isEmpty() && this.takePlanksOne()) {
                mat = new ItemStack(Blocks.PLANKS);
            }
        } else {
            mat = this.takePlanksOne() ? new ItemStack(Blocks.PLANKS) : this.takeAny(STONE);
        }
        if (mat.isEmpty()) {
            this.houseIncomplete = true;
            return;
        }
        if (step.kind == StepKind.FLOOR && !w.isAirBlock(step.pos)) {
            // 床は地面の土を板材に張り替える（土は埋め立て材として持っておく）
            for (ItemStack d : cur.getBlock().getDrops(w, step.pos, cur, 0)) {
                this.addToCarried(d);
            }
        } else if (!w.isAirBlock(step.pos)) {
            w.setBlockToAir(step.pos);
        }
        Block b = Block.getBlockFromItem(mat.getItem());
        this.setBlock(w, step.pos, b.getStateFromMeta(mat.getMetadata()));
    }

    private boolean takePlanksOne() {
        this.craftPlanks(1);
        return !this.takeAny(PLANKS).isEmpty();
    }

    /** [建築の強化: 窓] 板ガラスがあれば窓を、無ければ壁材で塞ぐ（雨風を防ぐ方を優先）。 */
    private void placeWindow(World w, BuildStep step) {
        if (this.isPlacedStructural(w, step)) {
            return;
        }
        IBlockState cur = w.getBlockState(step.pos);
        if (!w.isAirBlock(step.pos) && !this.isVegetation(cur) && !cur.getMaterial().isReplaceable()) {
            return; // 何か別の物がある -- 壊さない
        }
        if (!w.isAirBlock(step.pos)) {
            w.setBlockToAir(step.pos);
        }
        ItemStack glass = this.takeAny(new ItemStackMatcher() {
            @Override
            public boolean matches(ItemStack s) {
                return !s.isEmpty() && Block.getBlockFromItem(s.getItem()) == Blocks.GLASS;
            }
        });
        if (!glass.isEmpty()) {
            this.setBlock(w, step.pos, Blocks.GLASS.getDefaultState());
            return;
        }
        ItemStack mat = this.takeAny(STONE);
        if (mat.isEmpty() && this.takePlanksOne()) {
            mat = new ItemStack(Blocks.PLANKS);
        }
        if (mat.isEmpty()) {
            this.houseIncomplete = true;
            return;
        }
        Block b = Block.getBlockFromItem(mat.getItem());
        this.setBlock(w, step.pos, b.getStateFromMeta(mat.getMetadata()));
    }

    /** [建築の強化: 炉] 丸石類8個（持ち物に完成品があればそれ）で炉を設置する。 */
    private void placeFurnace(World w, BuildStep step) {
        if (this.isPlacedStructural(w, step) || !w.isAirBlock(step.pos)) {
            return;
        }
        ItemStack furnace = this.takeAny(new ItemStackMatcher() {
            @Override
            public boolean matches(ItemStack s) {
                return s.getItem() == Item.getItemFromBlock(Blocks.FURNACE);
            }
        });
        if (furnace.isEmpty()) {
            if (this.countAll(STONE) < 8) {
                return; // 丸石類が足りない -- 材料が集まったら次のTickで置く
            }
            for (int i = 0; i < 8; i++) {
                this.takeAny(STONE);
            }
        }
        this.setBlock(w, step.pos, Blocks.FURNACE.getDefaultState());
    }

    /**
     * [建築の強化: 土台] 床の真下を丸石類に張り替える。すでに丸石/石系の地面なら
     * 何もしない（せっかくの地形をわざわざ壊さない）。材料が無ければ地面をそのまま
     * 活かすだけで、家の完成自体は妨げない。
     */
    private void placeFoundation(World w, BuildStep step) {
        IBlockState cur = w.getBlockState(step.pos);
        if (isStoneBlock(cur.getBlock()) || cur.getMaterial().isLiquid()) {
            return;
        }
        ItemStack mat = this.takeAny(STONE);
        if (mat.isEmpty()) {
            return;
        }
        Block b = Block.getBlockFromItem(mat.getItem());
        this.setBlock(w, step.pos, b.getStateFromMeta(mat.getMetadata()));
    }

    /**
     * [建築の強化: 切妻屋根] 屋根の斜面（縁）を板/丸石の階段ブロックで作る。
     * 板材が無ければ丸石類の階段で代用する。
     */
    private void placeRoofStairs(World w, BuildStep step) {
        if (this.isPlacedStructural(w, step)) {
            return;
        }
        IBlockState cur = w.getBlockState(step.pos);
        if (!w.isAirBlock(step.pos) && !this.isVegetation(cur) && !cur.getMaterial().isReplaceable()) {
            return;
        }
        Block stairsBlock;
        if (this.takePlanksOne()) {
            stairsBlock = Blocks.OAK_STAIRS;
        } else if (!this.takeAny(STONE).isEmpty()) {
            stairsBlock = Blocks.STONE_STAIRS;
        } else {
            this.houseIncomplete = true;
            return;
        }
        if (!w.isAirBlock(step.pos)) {
            w.setBlockToAir(step.pos);
        }
        IBlockState state = stairsBlock.getDefaultState()
                .withProperty(BlockStairs.FACING, step.facing)
                .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.BOTTOM);
        this.setBlock(w, step.pos, state);
    }

    /**
     * [建築の強化: ベッド] 拠点に寝る場所を用意する。持ち物にベッドがあればそれを、
     * 無ければ板材3個で代用して置く。置く場所が塞がっていれば諦める（家の完成は妨げない）。
     */
    private void placeBed(World w, BuildStep step) {
        // [色付きベッド対応] 1.12はベッドが色ごとに別ブロック/別アイテムなので、
        // 特定の色を決め打ちで判定すると持ち物にある別色のベッドを見逃す。
        // 検出は instanceof で色を問わず判定し、無ければ白ベッドを標準で置く。
        BlockPos foot = step.pos;
        BlockPos head = foot.offset(step.facing);
        if (w.getBlockState(foot).getBlock() instanceof BlockBed || w.getBlockState(head).getBlock() instanceof BlockBed) {
            return;
        }
        if (!w.isAirBlock(foot) || !w.isAirBlock(head)) {
            return;
        }
        ItemStack bed = this.takeAny(new ItemStackMatcher() {
            @Override
            public boolean matches(ItemStack s) {
                return !s.isEmpty() && s.getItem() instanceof net.minecraft.item.ItemBed;
            }
        });
        Block bedBlock = Blocks.WHITE_BED;
        if (!bed.isEmpty()) {
            Block fromItem = Block.getBlockFromItem(bed.getItem());
            if (fromItem instanceof BlockBed) {
                bedBlock = fromItem;
            }
        } else if (!this.takePlanks(3)) {
            return;
        }
        IBlockState footState = bedBlock.getDefaultState()
                .withProperty(BlockBed.FACING, step.facing)
                .withProperty(BlockBed.PART, BlockBed.EnumPartType.FOOT);
        IBlockState headState = bedBlock.getDefaultState()
                .withProperty(BlockBed.FACING, step.facing)
                .withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD);
        w.setBlockState(foot, footState, 3);
        w.setBlockState(head, headState, 3);
        w.playSound(null, foot, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.8F, 1.0F);
        this.entity.swingArm(EnumHand.MAIN_HAND);
        AllySkills.addXp(this.entity, AllySkills.Skill.BUILDING, 1);
    }

    private void placeDoor(World w, BuildStep step) {
        if (!w.isAirBlock(step.pos) || !w.isAirBlock(step.pos.up())) {
            return;
        }
        ItemStack door = this.takeAny(new ItemStackMatcher() {
            @Override
            public boolean matches(ItemStack s) {
                return s.getItem() instanceof net.minecraft.item.ItemDoor;
            }
        });
        Block doorBlock = Blocks.OAK_DOOR;
        if (!door.isEmpty() && door.getItem().getRegistryName() != null) {
            Block b = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS.getValue(door.getItem().getRegistryName());
            if (b instanceof BlockDoor) {
                doorBlock = b;
            }
        } else if (this.takePlanks(6)) {
            // 板材6 → 木のドア3（1つ使い、残りは持っておく）
            this.addToCarried(new ItemStack(Items.OAK_DOOR, 2));
        } else {
            return; // 出入り口は開けたまま
        }
        net.minecraft.item.ItemDoor.placeDoor(w, step.pos, step.facing, doorBlock, false);
        w.playSound(null, step.pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.8F, 1.0F);
        this.entity.swingArm(EnumHand.MAIN_HAND);
    }

    private void placeTorch(World w, BuildStep step) {
        if (!w.isAirBlock(step.pos)) {
            return;
        }
        ItemStack torch = this.takeAny(new ItemStackMatcher() {
            @Override
            public boolean matches(ItemStack s) {
                return s.getItem() == Item.getItemFromBlock(Blocks.TORCH);
            }
        });
        if (torch.isEmpty()) {
            // 石炭/木炭＋棒 → たいまつ4
            ItemStack coal = this.takeAny(new ItemStackMatcher() {
                @Override
                public boolean matches(ItemStack s) {
                    return s.getItem() == Items.COAL;
                }
            });
            if (coal.isEmpty()) {
                return;
            }
            ItemStack stick = this.takeAny(new ItemStackMatcher() {
                @Override
                public boolean matches(ItemStack s) {
                    return s.getItem() == Items.STICK;
                }
            });
            if (stick.isEmpty()) {
                if (!this.takePlanks(2)) {
                    this.addToCarried(coal);
                    return;
                }
                this.addToCarried(new ItemStack(Items.STICK, 3));
            }
            this.addToCarried(new ItemStack(Blocks.TORCH, 3));
        }
        IBlockState st = Blocks.TORCH.getDefaultState().withProperty(BlockTorch.FACING, step.facing);
        this.setBlock(w, step.pos, st);
    }

    // ------------------------------------------------------------------
    // 建材が足りない時: 近くの木を切りに行く
    // ------------------------------------------------------------------

    private void tickGatherWood(World w) {
        if (this.woodTarget == null || !(w.getBlockState(this.woodTarget).getBlock() instanceof BlockLog)) {
            this.woodTarget = this.findTree(w);
            this.workProgress = 0;
            this.stuckTicks = 0;
            if (this.woodTarget == null) {
                this.say("[拠点建設] 近くに切れる木がありません。今ある材料で建てます。");
                this.woodTrips = 99;
                this.setPhase(Phase.BUILD);
                return;
            }
        }
        BlockPos t = this.woodTarget;
        this.entity.getLookHelper().setLookPosition(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5, 30.0F, 30.0F);
        if (this.eyeDistSq(t) > REACH_SQ) {
            this.walkNear(t);
            if (++this.stuckTicks > STUCK_SKIP_TICKS * 2) {
                this.skipped.add(t);
                this.woodTarget = null;
            }
            return;
        }
        this.entity.getNavigator().clearPath();
        if (this.workProgress == 0) {
            IBlockState s = w.getBlockState(t);
            AllyToolManager.equipForBlock(this.entity, s, null);
            this.workRequired = this.breakTicks(w, t, s);
        }
        if (++this.workProgress % 5 == 0) {
            this.entity.swingArm(EnumHand.MAIN_HAND);
        }
        if (this.workProgress < this.workRequired) {
            return;
        }
        this.fellTree(w, t);
        this.woodTarget = null;
        this.workProgress = 0;
        int need = this.structuralNeededEstimate();
        int have = this.countAll(STONE) + this.countAll(PLANKS) + this.countAll(LOGS) * 4;
        if (have >= need) {
            this.say("[拠点建設] 建材が揃いました。建築に戻ります。");
            this.setPhase(Phase.BUILD);
        }
    }

    private int structuralNeededEstimate() {
        int side = HOUSE_RADIUS * 2 + 1;
        int floor = side * side;
        int roof = 0;
        for (int layer = 0; layer <= ROOF_PITCH_LAYERS; layer++) {
            roof += side * (2 * (HOUSE_RADIUS - layer) + 1);
        }
        int walls = (side * 4 - 4) * WALL_HEIGHT;
        return floor + roof + walls + 30;
    }

    /** 近くの木の根元（一番下の原木）。敷地の外、禁止エリア外、伐採エリアの指定があればその中。 */
    private BlockPos findTree(World w) {
        List<AllyAreas.Area> areas = AllyAreas.workAreas(this.entity, AllyAreas.Type.CHOP);
        BlockPos c = new BlockPos(this.entity);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -32; dx <= 32; dx++) {
            for (int dz = -32; dz <= 32; dz++) {
                BlockPos top = w.getHeight(new BlockPos(c.getX() + dx, 0, c.getZ() + dz));
                for (int y = top.getY(); y > top.getY() - 24 && y > 1; y--) {
                    BlockPos p = new BlockPos(top.getX(), y, top.getZ());
                    if (!(w.getBlockState(p).getBlock() instanceof BlockLog)
                            || w.getBlockState(p.down()).getBlock() instanceof BlockLog) {
                        continue;
                    }
                    if (!isNaturalTree(w, p)) {
                        break; // 人の建てた原木（ログハウス等）
                    }
                    if (this.skipped.contains(p) || !AllyAreas.allowed(this.entity, p, AllyAreas.Type.CHOP, areas)
                            || TargetRegistry.isClaimedByOther(this.entity, p)) {
                        break;
                    }
                    double d = p.distanceSq(c);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                    break;
                }
            }
        }
        if (best != null) {
            TargetRegistry.claim(this.entity, best, 1200);
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    private void vacuum(World w, BlockPos center, int r) {
        if (center == null) {
            return;
        }
        AxisAlignedBB area = new AxisAlignedBB(center).grow(r, 16, r);
        for (EntityItem item : w.getEntitiesWithinAABB(EntityItem.class, area)) {
            if (item.isEntityAlive() && !item.getItem().isEmpty() && item.ticksExisted > 5 && item.getThrower() == null) {
                this.addToCarried(item.getItem().copy());
                item.setDead();
            }
        }
    }

    private void say(String message) {
        EntityPlayer owner = this.bridge.getOwnerPlayer(this.entity);
        if (owner != null && owner.isEntityAlive()) {
            owner.sendMessage(new TextComponentString("[" + this.entity.getName() + "] " + message));
        }
    }

    private ItemStack takeOne(ItemStack fromStack) {
        ItemStack single = fromStack.splitStack(1);
        if (fromStack.isEmpty()) {
            this.carried.remove(fromStack);
        }
        return single;
    }

    private void addToCarried(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // 集めた物はまず味方の持ち物へ（保存されるので、ワールドを出入りしても消えない）。
        // ただし道具の持ち替え用に空きを4つは残す。
        InventoryBasic inv = AllyInventory.get(this.entity);
        int free = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (inv.getStackInSlot(i).isEmpty()) {
                free++;
            }
        }
        if (free > 4) {
            stack = AllyInventory.insert(this.entity, stack);
            if (stack.isEmpty()) {
                return;
            }
        }
        for (ItemStack existing : this.carried) {
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
        // 大量になったら持ち物へ（手持ちは24スタックまで）
        if (this.carried.size() >= 24) {
            ItemStack rest = AllyInventory.insert(this.entity, stack);
            if (rest.isEmpty()) {
                return;
            }
            stack = rest;
        }
        this.carried.add(stack);
    }
}
