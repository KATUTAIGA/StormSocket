package com.example.examplemod;

import java.util.List;
import java.util.Set;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.EnumHand;
import net.minecraftforge.oredict.OreDictionary;

/**
 * 味方の道具の自動持ち替え。
 *
 * <ul>
 *   <li>戦闘 → 銃（Techguns/Flan's/HBM の遠距離武器）、無ければ剣</li>
 *   <li>原木 → 斧、鉱石・石 → ツルハシ、土・砂 → シャベル（壊すブロックに一番速い道具）</li>
 *   <li>何も持っていなければ、持ち物の原木/板材/丸石から作業台を作り、
 *       最低限の道具（木 or 石の道具）をクラフトして使う</li>
 * </ul>
 * 探す場所は「手」「{@link AllyInventory}」と、呼び出し側が渡す追加リスト
 * （クエストAIが集めた素材など）。持ち替えた元の道具は持ち物へ戻す（消さない）。
 */
public final class AllyToolManager {

    private AllyToolManager() {
    }

    // ------------------------------------------------------------------
    // 採掘用
    // ------------------------------------------------------------------

    private static float blockScore(ItemStack s, IBlockState state) {
        if (!AllyAIUtil.canHarvestWith(s, state)) {
            return -1.0F;
        }
        if (s.isEmpty()) {
            return 0.5F;
        }
        float speed = s.getDestroySpeed(state);
        if (HBMToolSupport.isAbilityTool(s)) {
            speed += 2.0F; // 特殊能力つきの道具を優先
        }
        return speed;
    }

    /**
     * そのブロックを壊すのに一番良い道具を手に持つ。採取可能な状態になれば true
     * （道具不要ブロックなら素手でも true）。
     */
    public static boolean equipForBlock(EntityFriendlyCreature e, IBlockState state, List<ItemStack> extra) {
        ItemStack hand = e.getHeldItemMainhand();
        float best = blockScore(hand, state);
        int bestSlot = -1;
        int bestExtra = -1;
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !isTool(s)) {
                continue;
            }
            float sc = blockScore(s, state);
            if (sc > best + 0.01F) {
                best = sc;
                bestSlot = i;
                bestExtra = -1;
            }
        }
        if (extra != null) {
            for (int i = 0; i < extra.size(); i++) {
                ItemStack s = extra.get(i);
                if (s.isEmpty() || !isTool(s)) {
                    continue;
                }
                float sc = blockScore(s, state);
                if (sc > best + 0.01F) {
                    best = sc;
                    bestSlot = -1;
                    bestExtra = i;
                }
            }
        }
        if (bestSlot >= 0) {
            swapFromInventory(e, bestSlot);
        } else if (bestExtra >= 0) {
            swapFromList(e, extra, bestExtra);
        } else if (best < 0.0F && state.getMaterial().isToolNotRequired()) {
            return true;
        }
        return best >= 0.0F;
    }

    /** 味方が自分の持ち物に残しておくべき物（道具・武器・作業台）。 */
    public static boolean isKeepable(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return isTool(s) || weaponScore(s) >= 0 || s.getItem() == Item.getItemFromBlock(Blocks.CRAFTING_TABLE);
    }

    private static boolean isTool(ItemStack s) {
        return !s.getItem().getToolClasses(s).isEmpty() || HBMToolSupport.isAbilityTool(s);
    }

    /** 指定の種類（pickaxe/axe/shovel）の道具で一番強いものを持つ。 */
    public static boolean equipToolClass(EntityFriendlyCreature e, String toolClass, List<ItemStack> extra) {
        ItemStack hand = e.getHeldItemMainhand();
        int best = levelOf(hand, toolClass);
        int bestSlot = -1;
        int bestExtra = -1;
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            int lv = levelOf(inv.getStackInSlot(i), toolClass);
            if (lv > best) {
                best = lv;
                bestSlot = i;
                bestExtra = -1;
            }
        }
        if (extra != null) {
            for (int i = 0; i < extra.size(); i++) {
                int lv = levelOf(extra.get(i), toolClass);
                if (lv > best) {
                    best = lv;
                    bestSlot = -1;
                    bestExtra = i;
                }
            }
        }
        if (bestSlot >= 0) {
            swapFromInventory(e, bestSlot);
        } else if (bestExtra >= 0) {
            swapFromList(e, extra, bestExtra);
        }
        return best >= 0;
    }

    public static int levelOf(ItemStack s, String toolClass) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        Set<String> classes = s.getItem().getToolClasses(s);
        if (!classes.contains(toolClass)) {
            return -1;
        }
        return Math.max(0, s.getItem().getHarvestLevel(s, toolClass, null, null));
    }

    /** 手・持ち物・追加リスト中で一番強いツルハシの採掘レベル（無ければ -1）。 */
    public static int bestLevel(EntityFriendlyCreature e, String toolClass, List<ItemStack> extra) {
        int best = levelOf(e.getHeldItemMainhand(), toolClass);
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            best = Math.max(best, levelOf(inv.getStackInSlot(i), toolClass));
        }
        if (extra != null) {
            for (ItemStack s : extra) {
                best = Math.max(best, levelOf(s, toolClass));
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 戦闘用
    // ------------------------------------------------------------------

    public static boolean isRangedGun(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Item item = s.getItem();
        if (item instanceof techguns.items.guns.GenericGun && !(item instanceof techguns.items.guns.IGenericGunMelee)) {
            return true;
        }
        return EngenderFlanBridge.isFlanGun(item) || EngenderHBMBridge.isHbmGun(item);
    }

    private static int weaponScore(ItemStack s) {
        if (isRangedGun(s)) {
            return 100;
        }
        if (!s.isEmpty() && (s.getItem() instanceof ItemSword || s.getItem() instanceof techguns.items.guns.IGenericGunMelee)) {
            return 50;
        }
        return -1;
    }

    /** 戦闘用に銃（無ければ剣）を持つ。武器を持てたら true。 */
    public static boolean equipWeapon(EntityFriendlyCreature e) {
        int best = weaponScore(e.getHeldItemMainhand());
        int bestSlot = -1;
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            int sc = weaponScore(inv.getStackInSlot(i));
            if (sc > best) {
                best = sc;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            swapFromInventory(e, bestSlot);
        }
        return best >= 0;
    }

    // ------------------------------------------------------------------
    // 入れ替え
    // ------------------------------------------------------------------

    private static void swapFromInventory(EntityFriendlyCreature e, int slot) {
        InventoryBasic inv = AllyInventory.get(e);
        ItemStack chosen = inv.getStackInSlot(slot);
        ItemStack hand = e.getHeldItemMainhand();
        inv.setInventorySlotContents(slot, hand.isEmpty() ? ItemStack.EMPTY : hand);
        e.setHeldItem(EnumHand.MAIN_HAND, chosen);
    }

    private static void swapFromList(EntityFriendlyCreature e, List<ItemStack> list, int index) {
        ItemStack chosen = list.remove(index);
        ItemStack hand = e.getHeldItemMainhand();
        e.setHeldItem(EnumHand.MAIN_HAND, chosen);
        if (!hand.isEmpty()) {
            AllyInventory.insertOrDrop(e, hand);
        }
    }

    /** 手に持っている物を持ち物へしまって素手にする（原木を素手で取る時など）。 */
    public static void stowMainhand(EntityFriendlyCreature e) {
        ItemStack hand = e.getHeldItemMainhand();
        if (!hand.isEmpty()) {
            e.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            AllyInventory.insertOrDrop(e, hand);
        }
    }

    // ------------------------------------------------------------------
    // 最低限の道具のクラフト（作業台込み）
    // ------------------------------------------------------------------

    /**
     * 持ち物の原木/板材/丸石から、作業台（無ければ作って持つ）を使って
     * pickaxe/axe/shovel/sword の最低限の道具を作り、持ち物に入れる。
     * 丸石が3つ以上あれば石の道具、無ければ木の道具。
     */
    public static boolean craftBasicTool(EntityFriendlyCreature e, String toolClass) {
        InventoryBasic inv = AllyInventory.get(e);
        int heads = "shovel".equals(toolClass) ? 1 : "sword".equals(toolClass) ? 2 : 3;
        int sticksNeeded = "sword".equals(toolClass) ? 1 : 2;
        boolean stone = count(inv, new ItemStack(Blocks.COBBLESTONE), false) >= heads;

        int planksNeeded = (stone ? 0 : heads) + (count(inv, new ItemStack(Items.STICK), false) >= sticksNeeded ? 0 : 2)
                + (count(inv, new ItemStack(Blocks.CRAFTING_TABLE), false) > 0 ? 0 : 4);
        while (countOre(inv, "plankWood") < planksNeeded) {
            if (!takeOre(inv, "logWood", 1)) {
                return false; // 木材が足りない
            }
            AllyInventory.insertOrDrop(e, new ItemStack(Blocks.PLANKS, 4, 0));
        }
        if (count(inv, new ItemStack(Blocks.CRAFTING_TABLE), false) == 0) {
            takeOre(inv, "plankWood", 4);
            AllyInventory.insertOrDrop(e, new ItemStack(Blocks.CRAFTING_TABLE));
        }
        if (count(inv, new ItemStack(Items.STICK), false) < sticksNeeded) {
            takeOre(inv, "plankWood", 2);
            AllyInventory.insertOrDrop(e, new ItemStack(Items.STICK, 4));
        }
        take(inv, new ItemStack(Items.STICK), sticksNeeded);
        if (stone) {
            take(inv, new ItemStack(Blocks.COBBLESTONE), heads);
        } else {
            takeOre(inv, "plankWood", heads);
        }
        Item result;
        if ("pickaxe".equals(toolClass)) {
            result = stone ? Items.STONE_PICKAXE : Items.WOODEN_PICKAXE;
        } else if ("axe".equals(toolClass)) {
            result = stone ? Items.STONE_AXE : Items.WOODEN_AXE;
        } else if ("shovel".equals(toolClass)) {
            result = stone ? Items.STONE_SHOVEL : Items.WOODEN_SHOVEL;
        } else {
            result = stone ? Items.STONE_SWORD : Items.WOODEN_SWORD;
        }
        AllyInventory.insertOrDrop(e, new ItemStack(result));
        return true;
    }

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

    private static int countOre(InventoryBasic inv, String ore) {
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (hasOre(inv.getStackInSlot(i), ore)) {
                n += inv.getStackInSlot(i).getCount();
            }
        }
        return n;
    }

    private static boolean takeOre(InventoryBasic inv, String ore, int amount) {
        if (countOre(inv, ore) < amount) {
            return false;
        }
        for (int i = 0; i < inv.getSizeInventory() && amount > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (hasOre(s, ore)) {
                int t = Math.min(amount, s.getCount());
                inv.decrStackSize(i, t);
                amount -= t;
            }
        }
        return true;
    }

    private static int count(InventoryBasic inv, ItemStack template, boolean anyMeta) {
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == template.getItem() && (anyMeta || s.getMetadata() == template.getMetadata())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static boolean take(InventoryBasic inv, ItemStack template, int amount) {
        if (count(inv, template, false) < amount) {
            return false;
        }
        for (int i = 0; i < inv.getSizeInventory() && amount > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem() == template.getItem() && s.getMetadata() == template.getMetadata()) {
                int t = Math.min(amount, s.getCount());
                inv.decrStackSize(i, t);
                amount -= t;
            }
        }
        return true;
    }
}
