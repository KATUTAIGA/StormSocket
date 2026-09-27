package com.example.examplemod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.oredict.OreDictionary;

/**
 * クエスト自動達成AI用の「何を、どうやって作れるか」の計画器。
 *
 * <p>作業台レシピ・かまど・GregTech の全レシピマップ（GTQT 追加分を含む）を共通形式
 * {@link ProcRecipe} に変換し、「今の道具で採掘できるブロックのドロップ」と「水」から
 * 出発して、到達できる全アイテム/液体を幅優先で求める（閉包計算）。各アイテムには
 * 「最初に作れるようになったレシピ」を記録するので、それを辿るだけで必ず循環しない
 * 生産手順が得られる。</p>
 *
 * <p>数万件のレシピを扱うため、計算は {@link #work} で少しずつ（1Tickあたり上限付きで）
 * 進め、サーバーを止めない。結果は採掘能力（ツルハシの採掘レベル）ごとに共有される。</p>
 */
public final class QuestRecipeResolver {

    private QuestRecipeResolver() {
    }

    // ------------------------------------------------------------------
    // データ型
    // ------------------------------------------------------------------

    /** アイテム＋メタデータ（GT は1つの Item に数千素材をメタ値で載せているため Item だけでは区別できない）。 */
    public static final class Key {
        public final Item item;
        public final int meta;

        public Key(Item item, int meta) {
            this.item = item;
            this.meta = meta == OreDictionary.WILDCARD_VALUE ? 0 : meta;
        }

        public static Key of(ItemStack stack) {
            return new Key(stack.getItem(), stack.getMetadata());
        }

        public ItemStack toStack(int count) {
            return new ItemStack(item, count, meta);
        }

        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() == item && stack.getMetadata() == meta;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return k.item == item && k.meta == meta;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(item) * 31 + meta;
        }
    }

    /** レシピの1入力。accepted のどれでもよい（鉱石辞書）。consumed=false は型・回路など消費しない道具。 */
    public static final class Input {
        public final ItemStack[] accepted;
        public final int count;
        public final boolean consumed;

        public Input(ItemStack[] accepted, int count, boolean consumed) {
            this.accepted = accepted;
            this.count = count;
            this.consumed = consumed;
        }

        public boolean accepts(ItemStack stack) {
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
    }

    public static final class FluidAmount {
        public final String fluid;
        public final int amount;

        public FluidAmount(String fluid, int amount) {
            this.fluid = fluid;
            this.amount = amount;
        }
    }

    /** 作業台/かまど/GT機械のレシピを共通化したもの。 */
    public static final class ProcRecipe {
        public final List<Input> inputs;
        public final List<FluidAmount> fluidInputs;
        public final List<ItemStack> outputs;
        public final List<FluidAmount> fluidOutputs;
        public final int durationTicks;
        public final String source;
        /** GregTech のレシピマップ（GT以外は null）。実機を探す時に使う。 */
        public Object gtMap;
        /** 電圧ランク（0=ULV, 1=LV, 2=MV, ... / GT以外は -1）。 */
        public int tier = -1;
        /** EU/t（GT以外は 0）。 */
        public long eut;

        public ProcRecipe(List<Input> inputs, List<FluidAmount> fluidInputs, List<ItemStack> outputs,
                List<FluidAmount> fluidOutputs, int durationTicks, String source) {
            this.inputs = inputs;
            this.fluidInputs = fluidInputs;
            this.outputs = outputs;
            this.fluidOutputs = fluidOutputs;
            this.durationTicks = durationTicks;
            this.source = source;
        }
    }

    /** 採掘レベルごとの到達可能集合と生産手順。 */
    public static final class Closure {
        public final int level;
        public final Map<Key, Block> mineBlock = new HashMap<Key, Block>();
        public final Map<Key, ProcRecipe> producer = new HashMap<Key, ProcRecipe>();
        public final Map<String, ProcRecipe> fluidProducer = new HashMap<String, ProcRecipe>();
        private final Map<Key, Integer> order = new HashMap<Key, Integer>();
        private final Set<Item> obtainableItems = new HashSet<Item>();
        private final Set<String> obtainableFluids = new HashSet<String>();
        private boolean initialized;
        private boolean done;
        private int cursor;
        private boolean changed;
        private int passes;
        private int nextOrder;

        Closure(int level) {
            this.level = level;
        }

        public boolean isDone() {
            return done;
        }

        private int bestPickaxe = Integer.MIN_VALUE;

        /**
         * この閉包で作れる一番強いツルハシの採掘レベル（計算済みの時だけ意味がある）。
         * 例: レベル0（木のツルハシ）の閉包で丸石→石のツルハシが作れるなら 1。
         */
        public int bestPickaxeLevel() {
            if (!done) {
                return level;
            }
            if (bestPickaxe == Integer.MIN_VALUE) {
                int best = level;
                for (Key k : order.keySet()) {
                    try {
                        ItemStack st = k.toStack(1);
                        best = Math.max(best, st.getItem().getHarvestLevel(st, "pickaxe", null, null));
                    } catch (Throwable ignored) {
                        // 一部Modの道具は引数が null だと例外 -- 無視
                    }
                }
                bestPickaxe = best;
            }
            return bestPickaxe;
        }

        public ProcRecipe producerOf(Key key) {
            return producer.get(key);
        }

        public boolean isObtainable(Key key) {
            return order.containsKey(key);
        }

        public boolean isFluidObtainable(String fluid) {
            return obtainableFluids.contains(fluid);
        }

        /** accepted の中で到達可能なもののうち、最も早く（＝短い手順で）作れるもの。無ければ null。 */
        public Key bestAccepted(ItemStack[] accepted) {
            Key best = null;
            int bestOrder = Integer.MAX_VALUE;
            for (ItemStack a : accepted) {
                if (a == null || a.isEmpty()) {
                    continue;
                }
                Key k = Key.of(a);
                Integer o = order.get(k);
                if (o != null && o < bestOrder) {
                    best = k;
                    bestOrder = o;
                }
            }
            return best;
        }

        private void markObtainable(Key key) {
            if (!order.containsKey(key)) {
                order.put(key, nextOrder++);
                obtainableItems.add(key.item);
            }
        }

        private boolean inputObtainable(Input in) {
            if (!in.consumed) {
                return true; // 型・レンズ・プログラム回路などは「機械の設定」扱い
            }
            for (ItemStack a : in.accepted) {
                if (a == null || a.isEmpty()) {
                    continue;
                }
                if (a.getMetadata() == OreDictionary.WILDCARD_VALUE ? obtainableItems.contains(a.getItem())
                        : order.containsKey(Key.of(a))) {
                    return true;
                }
            }
            return false;
        }

        private void init() {
            Random rand = new Random(0);
            for (Block block : Block.REGISTRY) {
                try {
                    IBlockState state = block.getDefaultState();
                    if (!canMineWithLevel(state, level)) {
                        continue;
                    }
                    Item drop = block.getItemDropped(state, rand, 0);
                    if (drop == null || drop == Items.AIR) {
                        continue;
                    }
                    Key k = new Key(drop, block.damageDropped(state));
                    if (!mineBlock.containsKey(k)) {
                        mineBlock.put(k, block);
                    }
                    markObtainable(k);
                } catch (Throwable ignored) {
                    // 一部Modブロックは既定状態でドロップ計算に失敗する -- 無視
                }
            }
            obtainableFluids.add("water");
            initialized = true;
        }

        /** 最大 budget 件のレシピを調べて閉包計算を進める。 */
        void step(List<ProcRecipe> recipes, int budget) {
            if (done) {
                return;
            }
            if (!initialized) {
                init();
                return;
            }
            int end = Math.min(recipes.size(), cursor + budget);
            for (int i = cursor; i < end; i++) {
                ProcRecipe r = recipes.get(i);
                boolean useful = false;
                for (ItemStack out : r.outputs) {
                    if (!out.isEmpty() && !order.containsKey(Key.of(out))) {
                        useful = true;
                        break;
                    }
                }
                if (!useful) {
                    for (FluidAmount f : r.fluidOutputs) {
                        if (!obtainableFluids.contains(f.fluid)) {
                            useful = true;
                            break;
                        }
                    }
                }
                if (!useful) {
                    continue;
                }
                boolean ok = true;
                for (Input in : r.inputs) {
                    if (!inputObtainable(in)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    for (FluidAmount f : r.fluidInputs) {
                        if (!obtainableFluids.contains(f.fluid)) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (!ok) {
                    continue;
                }
                for (ItemStack out : r.outputs) {
                    if (out.isEmpty()) {
                        continue;
                    }
                    Key k = Key.of(out);
                    if (!order.containsKey(k)) {
                        producer.put(k, r);
                        markObtainable(k);
                        changed = true;
                    }
                }
                for (FluidAmount f : r.fluidOutputs) {
                    if (obtainableFluids.add(f.fluid)) {
                        fluidProducer.put(f.fluid, r);
                        changed = true;
                    }
                }
            }
            cursor = end;
            if (cursor >= recipes.size()) {
                cursor = 0;
                passes++;
                if (!changed || passes >= MAX_PASSES) {
                    done = true;
                }
                changed = false;
            }
        }
    }

    public static boolean canMineWithLevel(IBlockState state, int pickaxeLevel) {
        if (state.getBlock() == Blocks.AIR || state.getBlock() == Blocks.BEDROCK || state.getMaterial().isLiquid()) {
            return false;
        }
        if (state.getMaterial().isToolNotRequired()) {
            return true;
        }
        String tool = state.getBlock().getHarvestTool(state);
        if (tool == null) {
            return false;
        }
        if ("pickaxe".equals(tool)) {
            return pickaxeLevel >= 0 && state.getBlock().getHarvestLevel(state) <= pickaxeLevel;
        }
        // 斧/シャベルが必要な素材は、このAIがその道具を持っている時だけ実際に掘れる
        // （実際の採掘時に AllyAIUtil.canHarvestWith で再確認する）。
        return false;
    }

    // ------------------------------------------------------------------
    // レシピ一覧の構築（段階的）
    // ------------------------------------------------------------------

    private static final int MAX_PASSES = 40;
    private static final List<ProcRecipe> RECIPES = new ArrayList<ProcRecipe>();
    private static final Map<Integer, Closure> CLOSURES = new LinkedHashMap<Integer, Closure>();
    private static int buildStage;
    private static java.util.Iterator<IRecipe> craftingIterator;
    private static List<Object> gtMaps;
    private static int gtMapCursor;
    private static long lastWorkTime = Long.MIN_VALUE;

    /**
     * [完全動的な手順の導出] 今持っている道具のレベルから出発して、「作れるツルハシ」で
     * 採掘レベルを上げていった先のレベル。木のツルハシしか無くても、丸石→石のツルハシ→
     * 鉄鉱石→かまどで精錬→鉄のツルハシ…と自分で辿れる所まで計画に入れる。
     */
    public static synchronized int effectiveLevel(int baseLevel) {
        int level = Math.max(0, baseLevel);
        for (int guard = 0; guard < 8; guard++) {
            Closure c = CLOSURES.get(level);
            if (c == null || !c.isDone()) {
                break;
            }
            int next = c.bestPickaxeLevel();
            if (next <= level) {
                break;
            }
            level = next;
        }
        return level;
    }

    /**
     * [電圧ランクの考慮] 作業台・かまど → GT の低電圧（ULV/LV）→ 高電圧… の順に並べる。
     * 閉包計算は先に見つかったレシピを採用するので、同じ物が作れる時は低い電圧・
     * 短い時間の工程が選ばれる（序盤から HV 機械の工程を組まない）。
     */
    private static void sortRecipesByCost() {
        try {
            java.util.Collections.sort(RECIPES, new java.util.Comparator<ProcRecipe>() {
                @Override
                public int compare(ProcRecipe a, ProcRecipe b) {
                    int c = Integer.compare(a.tier, b.tier);
                    if (c != 0) {
                        return c;
                    }
                    return Integer.compare(a.durationTicks, b.durationTicks);
                }
            });
        } catch (Throwable ignored) {
            // 並べ替えに失敗しても計画はできる
        }
    }

    /** 電圧ランクの表示名（ULV, LV, MV ...）。 */
    public static String tierName(int tier) {
        String[] names = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UXV", "OpV", "MAX" };
        return tier < 0 ? "" : tier < names.length ? names[tier] : "T" + tier;
    }

    public static boolean recipesReady() {
        return buildStage >= 3;
    }

    /**
     * 計画器を少し進める。同じワールドTick内で何度呼ばれても1回分しか働かない。
     * @return その採掘レベルの閉包（計算途中なら isDone()==false）
     */
    public static synchronized Closure work(int pickaxeLevel, long worldTime) {
        Closure closure = CLOSURES.get(pickaxeLevel);
        if (closure == null) {
            closure = new Closure(pickaxeLevel);
            CLOSURES.put(pickaxeLevel, closure);
        }
        if (worldTime == lastWorkTime) {
            return closure;
        }
        lastWorkTime = worldTime;
        if (!recipesReady()) {
            buildStep();
            return closure;
        }
        closure.step(RECIPES, 6000);
        return closure;
    }

    private static void buildStep() {
        switch (buildStage) {
            case 0:
                if (craftingIterator == null) {
                    craftingIterator = CraftingManager.REGISTRY.iterator();
                }
                for (int n = 0; n < 1500 && craftingIterator.hasNext(); n++) {
                    try {
                        ProcRecipe r = fromCrafting(craftingIterator.next());
                        if (r != null) {
                            RECIPES.add(r);
                        }
                    } catch (Throwable ignored) {
                        // 特殊レシピ（花火・地図等）は無視
                    }
                }
                if (!craftingIterator.hasNext()) {
                    craftingIterator = null;
                    buildStage = 1;
                }
                break;
            case 1:
                try {
                    for (Map.Entry<ItemStack, ItemStack> e : FurnaceRecipes.instance().getSmeltingList().entrySet()) {
                        if (e.getKey().isEmpty() || e.getValue().isEmpty()) {
                            continue;
                        }
                        List<Input> in = new ArrayList<Input>();
                        in.add(new Input(new ItemStack[] { e.getKey().copy() }, 1, true));
                        List<ItemStack> out = new ArrayList<ItemStack>();
                        out.add(e.getValue().copy());
                        RECIPES.add(new ProcRecipe(in, new ArrayList<FluidAmount>(), out,
                                new ArrayList<FluidAmount>(), 200, "furnace"));
                    }
                } catch (Throwable ignored) {
                    // かまどレシピが読めなくても続行
                }
                buildStage = 2;
                break;
            case 2:
                if (!Loader.isModLoaded("gregtech")) {
                    buildStage = 3;
                    break;
                }
                try {
                    if (gtMaps == null) {
                        gtMaps = GTRecipeSource.listMaps();
                        gtMapCursor = 0;
                    }
                    if (gtMapCursor < gtMaps.size()) {
                        GTRecipeSource.convertMap(gtMaps.get(gtMapCursor++), RECIPES);
                        break;
                    }
                } catch (Throwable t) {
                    System.err.println("[EngenderQuest] GregTech recipe import failed: " + t);
                }
                buildStage = 3;
                sortRecipesByCost();
                System.out.println("[EngenderQuest] recipe planner ready: " + RECIPES.size() + " recipes");
                break;
            default:
                break;
        }
    }

    private static ProcRecipe fromCrafting(IRecipe recipe) {
        ItemStack output = recipe.getRecipeOutput();
        if (output == null || output.isEmpty() || recipe.getIngredients().isEmpty()) {
            return null;
        }
        // 同じ材料枠をまとめて個数にする
        List<Input> inputs = new ArrayList<Input>();
        List<Ingredient> seen = new ArrayList<Ingredient>();
        List<Integer> counts = new ArrayList<Integer>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == Ingredient.EMPTY) {
                continue;
            }
            ItemStack[] matches = ing.getMatchingStacks();
            if (matches == null || matches.length == 0) {
                return null;
            }
            int found = -1;
            for (int i = 0; i < seen.size(); i++) {
                if (sameIngredient(seen.get(i), ing)) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                counts.set(found, counts.get(found) + 1);
            } else {
                seen.add(ing);
                counts.add(1);
            }
        }
        for (int i = 0; i < seen.size(); i++) {
            ItemStack[] matches = seen.get(i).getMatchingStacks();
            // 道具（GTのハンマー等）はクラフト後に容器アイテムとして戻るので「消費しない」扱い
            boolean consumed = !matches[0].getItem().hasContainerItem(matches[0])
                    || matches[0].getItem().getContainerItem(matches[0]).getItem() != matches[0].getItem();
            inputs.add(new Input(matches, counts.get(i), consumed));
        }
        List<ItemStack> outs = new ArrayList<ItemStack>();
        outs.add(output.copy());
        return new ProcRecipe(inputs, new ArrayList<FluidAmount>(), outs, new ArrayList<FluidAmount>(), 20, "crafting");
    }

    private static boolean sameIngredient(Ingredient a, Ingredient b) {
        if (a == b) {
            return true;
        }
        ItemStack[] ma = a.getMatchingStacks();
        ItemStack[] mb = b.getMatchingStacks();
        if (ma.length != mb.length) {
            return false;
        }
        for (int i = 0; i < ma.length; i++) {
            if (!ItemStack.areItemStacksEqual(ma[i], mb[i])) {
                return false;
            }
        }
        return true;
    }
}
