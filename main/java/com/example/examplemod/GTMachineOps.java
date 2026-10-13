package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.IEnergyContainer;
import gregtech.api.capability.IMultipleTankHandler;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

/**
 * [仮想・実機ハイブリッド加工] GregTech の実機を探して、搬入・加工待ち・搬出する。
 * 機械が無ければ、持っている機械を電源（ケーブル・発電機・バッテリーバッファ）の隣に
 * 設置し、ケーブルを持っていれば電源から機械まで配線する。
 *
 * <p>GregTech が読み込まれている時だけ使う（呼び出し側で {@code Loader.isModLoaded} 済み）。
 * 位置は BlockPos でやりとりし、クエストAI本体には GT のクラスを持ち込まない。</p>
 */
final class GTMachineOps {

    private GTMachineOps() {
    }

    // MetaTileEntity は ModularUI のクラスを継承しているため、コンパイル時に型として
    // 触れると ModularUI の jar まで必要になる。GT 自身のメソッド名（難読化されない）を
    // リフレクションで呼んで扱う。

    private static Object call(Object o, String name) {
        if (o == null) {
            return null;
        }
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isGTHolder(TileEntity te) {
        for (Class<?> c = te.getClass(); c != null; c = c.getSuperclass()) {
            if ("gregtech.api.metatileentity.MetaTileEntityHolder".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Object mteOf(TileEntity te) {
        return te != null && isGTHolder(te) ? call(te, "getMetaTileEntity") : null;
    }

    private static Object mteAt(World w, BlockPos pos) {
        return mteOf(w.getTileEntity(pos));
    }

    /**
     * 機械の電圧ランク。単体機械は getTier()、マルチブロックはレシピ処理の最大電圧から。
     * 分からなければ 0（ULV扱い: 蒸気機械など）。
     */
    private static int tierOf(Object mte) {
        Object t = call(mte, "getTier");
        if (t instanceof Integer) {
            return (Integer) t;
        }
        Object v = call(call(mte, "getRecipeLogic"), "getMaxVoltage");
        if (v instanceof Long && (Long) v > 0) {
            return gregtech.api.util.GTUtility.getTierByVoltage((Long) v);
        }
        return 0;
    }

    private static IItemHandler handler(Object mte, String name) {
        Object h = call(mte, name);
        return h instanceof IItemHandler ? (IItemHandler) h : null;
    }

    private static IMultipleTankHandler tanks(Object mte, String name) {
        Object h = call(mte, name);
        return h instanceof IMultipleTankHandler ? (IMultipleTankHandler) h : null;
    }

    /** 電力があるか（エネルギー容器を持たない蒸気機械等は「ある」とみなす）。 */
    private static boolean powered(TileEntity te) {
        try {
            IEnergyContainer ec = te.getCapability(GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER, null);
            return ec == null || ec.getEnergyStored() > 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 半径 r 以内で、そのレシピマップ・電圧以上・電力のある実機。無ければ null。 */
    static BlockPos findMachine(World w, BlockPos center, int r, Object map, int tier,
            java.util.Map<BlockPos, Long> bad, long now) {
        BlockPos best = null;
        double bestD = (double) r * r;
        for (TileEntity te : w.loadedTileEntityList) {
            double d = te.getPos().distanceSq(center);
            if (d > bestD || !isGTHolder(te)) {
                continue;
            }
            Long badUntil = bad == null ? null : bad.get(te.getPos());
            if (badUntil != null && badUntil > now) {
                continue; // 最近うまく動かなかった機械
            }
            Object mte = mteOf(te);
            if (mte == null || call(mte, "getRecipeMap") != map || tierOf(mte) < tier || !powered(te)) {
                continue;
            }
            if (Boolean.FALSE.equals(call(mte, "isStructureFormed"))) {
                continue; // 組み上がっていないマルチブロック
            }
            bestD = d;
            best = te.getPos();
        }
        return best;
    }

    /** 機械へ1回分の材料を搬入。全部入る時だけ入れて true。入りきらなかった物は leftovers へ。 */
    static boolean insert(World w, BlockPos pos, List<ItemStack> items, List<FluidStack> fluids, List<ItemStack> leftovers) {
        Object mte = mteAt(w, pos);
        IItemHandler imp = handler(mte, "getImportItems");
        IMultipleTankHandler tanks = tanks(mte, "getImportFluids");
        if (imp == null) {
            return false;
        }
        // まず試しに入れてみる
        for (ItemStack s : items) {
            if (!ItemHandlerHelper.insertItemStacked(imp, s.copy(), true).isEmpty()) {
                return false;
            }
        }
        for (FluidStack f : fluids) {
            if (tanks == null || tanks.fill(f.copy(), false) < f.amount) {
                return false;
            }
        }
        for (ItemStack s : items) {
            ItemStack rest = ItemHandlerHelper.insertItemStacked(imp, s.copy(), false);
            if (!rest.isEmpty()) {
                leftovers.add(rest);
            }
        }
        for (FluidStack f : fluids) {
            tanks.fill(f.copy(), true);
        }
        return true;
    }

    private static boolean matchesAny(ItemStack s, List<ItemStack> wanted) {
        for (ItemStack w : wanted) {
            if (!w.isEmpty() && w.getItem() == s.getItem() && w.getMetadata() == s.getMetadata()) {
                return true;
            }
        }
        return false;
    }

    /** wanted の各アイテムを、その個数までだけ取り出す（プレイヤーの物・別の加工の物には触らない）。 */
    private static List<ItemStack> take(IItemHandler h, List<ItemStack> wanted) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        if (h == null) {
            return out;
        }
        for (ItemStack w : wanted) {
            if (w.isEmpty()) {
                continue;
            }
            int remaining = w.getCount();
            for (int i = 0; i < h.getSlots() && remaining > 0; i++) {
                ItemStack in = h.getStackInSlot(i);
                if (in.isEmpty() || in.getItem() != w.getItem() || in.getMetadata() != w.getMetadata()) {
                    continue;
                }
                ItemStack got = h.extractItem(i, Math.min(remaining, in.getCount()), false);
                if (!got.isEmpty()) {
                    remaining -= got.getCount();
                    out.add(got);
                }
            }
        }
        return out;
    }

    private static void drainNamed(IMultipleTankHandler tanks, List<String> names, List<FluidStack> fluidsOut) {
        if (tanks == null || fluidsOut == null) {
            return;
        }
        for (String n : names) {
            FluidStack want = net.minecraftforge.fluids.FluidRegistry.getFluidStack(n, Integer.MAX_VALUE);
            if (want == null) {
                continue;
            }
            FluidStack got = tanks.drain(want, true);
            if (got != null && got.amount > 0) {
                fluidsOut.add(got);
            }
        }
    }

    /** 搬出口から、このレシピの出力だけを取り出す（液体は fluidsOut へ）。 */
    static List<ItemStack> extractOutputs(World w, BlockPos pos, List<ItemStack> wanted, List<String> wantedFluids,
            List<FluidStack> fluidsOut) {
        Object mte = mteAt(w, pos);
        List<ItemStack> out = take(handler(mte, "getExportItems"), wanted);
        drainNamed(tanks(mte, "getExportFluids"), wantedFluids, fluidsOut);
        return out;
    }

    /** 加工できなかった時に、自分が入れた材料だけを搬入口から取り戻す。 */
    static List<ItemStack> extractInputs(World w, BlockPos pos, List<ItemStack> inserted, List<String> insertedFluids,
            List<FluidStack> fluidsOut) {
        Object mte = mteAt(w, pos);
        List<ItemStack> out = take(handler(mte, "getImportItems"), inserted);
        drainNamed(tanks(mte, "getImportFluids"), insertedFluids, fluidsOut);
        return out;
    }

    /** そのレシピマップを扱える、電圧が足りる一番低い電圧の機械（アイテム）。無ければ EMPTY。 */
    private static final java.util.Map<Object, java.util.Map<Integer, ItemStack>> MACHINE_ITEM_CACHE =
            new java.util.IdentityHashMap<Object, java.util.Map<Integer, ItemStack>>();

    static synchronized ItemStack machineItemFor(Object map, int tier) {
        java.util.Map<Integer, ItemStack> byTier = MACHINE_ITEM_CACHE.get(map);
        if (byTier == null) {
            byTier = new java.util.HashMap<Integer, ItemStack>();
            MACHINE_ITEM_CACHE.put(map, byTier);
        }
        ItemStack cached = byTier.get(tier);
        if (cached == null) {
            cached = machineItemForUncached(map, tier);
            byTier.put(tier, cached);
        }
        return cached.copy();
    }

    private static ItemStack machineItemForUncached(Object map, int tier) {
        ItemStack best = ItemStack.EMPTY;
        int bestTier = Integer.MAX_VALUE;
        try {
            Class<?> mgr = Class.forName("gregtech.api.metatileentity.registry.MTEManager");
            Object inst = mgr.getMethod("getInstance").invoke(null);
            Object regs = mgr.getMethod("getRegistries").invoke(inst);
            for (Object reg : (Iterable<?>) regs) {
                for (Object mte : (Iterable<?>) reg) {
                    if (mte == null || call(mte, "getRecipeMap") != map) {
                        continue;
                    }
                    int t = tierOf(mte);
                    if (t >= tier && t < bestTier) {
                        Object st = call(mte, "getStackForm");
                        if (st instanceof ItemStack && !((ItemStack) st).isEmpty()) {
                            bestTier = t;
                            best = (ItemStack) st;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // レジストリの形が違う版 -- 設置はしない
        }
        return best;
    }

    private static boolean isCable(TileEntity te) {
        for (Class<?> c = te.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().contains("Cable")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEnergySource(TileEntity te) {
        try {
            for (EnumFacing f : EnumFacing.values()) {
                IEnergyContainer ec = te.getCapability(GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER, f);
                if (ec != null && ec.getOutputVoltage() > 0 && ec.outputsEnergy(f)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 無視
        }
        return false;
    }

    /**
     * 機械を置く場所: 通電しているケーブル（無ければ発電機・バッテリーバッファ）の隣の空き。
     * out[0] = 置く場所、戻り値 true。見つからなければ false。
     */
    static boolean findPowerSpot(World w, BlockPos center, int r, BlockPos[] out) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (TileEntity te : w.loadedTileEntityList) {
                if (te.getPos().distanceSq(center) > (double) r * r) {
                    continue;
                }
                boolean ok = pass == 0 ? isCable(te) : isEnergySource(te);
                if (!ok) {
                    continue;
                }
                for (EnumFacing f : EnumFacing.HORIZONTALS) {
                    BlockPos p = te.getPos().offset(f);
                    if (!w.isAirBlock(p) || AllyAreas.isForbidden(w, p)) {
                        continue;
                    }
                    double d = p.distanceSq(center);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        out[0] = best;
        return best != null;
    }

    /** 偽プレイヤーにアイテムを持たせ、target の空きマスへ設置する（隣のブロックをクリック）。 */
    static boolean placeItemAt(EntityFriendlyCreature ally, ItemStack stack, BlockPos target) {
        if (!(ally.world instanceof WorldServer) || stack.isEmpty() || !ally.world.isAirBlock(target)
                || AllyAreas.isForbidden(ally.world, target)) {
            return false;
        }
        World w = ally.world;
        for (EnumFacing f : EnumFacing.values()) {
            BlockPos against = target.offset(f);
            if (w.isAirBlock(against) || w.getBlockState(against).getBlock().isReplaceable(w, against)) {
                continue; // 草・雪などをクリックすると、そこに置かれてしまう
            }
            FakePlayer fake = FakePlayerFactory.getMinecraft((WorldServer) w);
            fake.setPosition(ally.posX, ally.posY, ally.posZ);
            ItemStack one = stack.copy();
            one.setCount(1);
            fake.setHeldItem(EnumHand.MAIN_HAND, one);
            try {
                EnumActionResult res = one.onItemUse(fake, w, against, EnumHand.MAIN_HAND, f.getOpposite(), 0.5F, 0.5F, 0.5F);
                if (res == EnumActionResult.SUCCESS && !w.isAirBlock(target)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // この向きでは置けない -- 次の面
            } finally {
                fake.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        return false;
    }

    /** GT のケーブル/電線アイテムか。 */
    static boolean isCableItem(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        for (Class<?> c = s.getItem().getClass(); c != null; c = c.getSuperclass()) {
            if ("ItemBlockCable".equals(c.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 電源の近くに機械を置き、必要ならケーブルで配線する。
     * 置けたら機械の位置、置けなければ null。machine/cables は消費する（呼び出し側の持ち物）。
     */
    static BlockPos installMachine(EntityFriendlyCreature ally, ItemStack machine, List<ItemStack> cables) {
        World w = ally.world;
        BlockPos[] spot = new BlockPos[1];
        BlockPos center = new BlockPos(ally);
        if (findPowerSpot(w, center, 16, spot) && placeItemAt(ally, machine, spot[0])) {
            machine.shrink(1);
            return spot[0];
        }
        // 電源はあるが隣が空いていない/遠い: ケーブルを持っていれば、電源から2〜6ブロック離して配線
        if (cables == null || cables.isEmpty()) {
            return null;
        }
        for (TileEntity te : w.loadedTileEntityList) {
            if (te.getPos().distanceSq(center) > 16 * 16 || !(isEnergySource(te) || isCable(te))) {
                continue;
            }
            for (EnumFacing f : EnumFacing.HORIZONTALS) {
                List<BlockPos> path = new ArrayList<BlockPos>();
                BlockPos p = te.getPos().offset(f);
                boolean clear = true;
                for (int i = 0; i < 4; i++) {
                    if (!w.isAirBlock(p) || AllyAreas.isForbidden(w, p)) {
                        clear = false;
                        break;
                    }
                    path.add(p);
                    p = p.offset(f);
                }
                if (!clear || !w.isAirBlock(p)) {
                    continue;
                }
                int cableCount = 0;
                for (ItemStack c : cables) {
                    cableCount += c.getCount();
                }
                if (cableCount < path.size()) {
                    continue;
                }
                for (BlockPos cp : path) {
                    ItemStack use = null;
                    for (ItemStack c : cables) {
                        if (!c.isEmpty()) {
                            use = c;
                            break;
                        }
                    }
                    if (use == null || !placeItemAt(ally, use, cp)) {
                        return null;
                    }
                    use.shrink(1);
                }
                if (placeItemAt(ally, machine, p)) {
                    machine.shrink(1);
                    return p;
                }
                return null;
            }
        }
        return null;
    }
}
