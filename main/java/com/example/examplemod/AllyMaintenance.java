package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.block.BlockAnvil;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * [自己メンテナンス] 道具の手入れと、空腹・回復。
 *
 * <p>道具（手に持っている斧・ツルハシ・シャベル等）の残り耐久が少なくなったら、順に:</p>
 * <ol>
 *   <li>金床（近く6ブロック以内に設置、または持ち物に金床）＋修理素材（鉄インゴット等、
 *       その道具が受け付ける物）で修理。素材1個で最大耐久の25%回復（金床と同じ）</li>
 *   <li>持ち物に同じ道具がもう1本あれば、2本を合成して修理（作業台の修理と同じ +5%）</li>
 *   <li>持ち物の予備（同じ種類の道具で耐久が多い物）に持ち替え</li>
 *   <li>近く（10ブロック）のチェストから同じ種類の道具を取り出して持ち替え
 *       （使い古しはチェストへ戻す）</li>
 *   <li>それも無ければ、壊れる直前に木材/丸石から予備を作っておく</li>
 * </ol>
 *
 * <p>空腹: 味方ごとに満腹度（0〜20）を持ち、仕事中・戦闘中に少しずつ減る。
 * 満腹度が減ったり体力が6割を切ると、持ち物の食べ物を自分で食べる。
 * 満腹度0では動きが鈍くなる。満腹度が高い間はゆっくり体力が回復する。</p>
 */
public final class AllyMaintenance {

    private static final String KEY_FOOD = "EngenderFood";
    private static final String KEY_EAT_AT = "EngenderEatAt";
    private static final String KEY_TOOL_MSG = "EngenderToolMsgAt";
    private static final int MAX_FOOD = 20;

    private AllyMaintenance() {
    }

    /** 20Tickに1回、ブリッジから呼ばれる。 */
    public static void tick(EntityFriendlyCreature e, EngenderGatheringBridge bridge) {
        try {
            tickHunger(e, bridge);
        } catch (Throwable ignored) {
            // 食べ物Modの特殊な実装など -- 無視して続行
        }
        if (!bridge.isFighting(e)) {
            try {
                tickTool(e, bridge);
            } catch (Throwable ignored) {
                // 道具Modの特殊な実装など
            }
        }
    }

    // ------------------------------------------------------------------
    // 空腹・回復
    // ------------------------------------------------------------------

    public static int food(EntityFriendlyCreature e) {
        if (!e.getEntityData().hasKey(KEY_FOOD)) {
            e.getEntityData().setInteger(KEY_FOOD, MAX_FOOD);
        }
        return e.getEntityData().getInteger(KEY_FOOD);
    }

    private static void setFood(EntityFriendlyCreature e, int v) {
        e.getEntityData().setInteger(KEY_FOOD, Math.max(0, Math.min(MAX_FOOD, v)));
    }

    private static void tickHunger(EntityFriendlyCreature e, EngenderGatheringBridge bridge) {
        long now = e.world.getTotalWorldTime();
        boolean working = !bridge.getJob(e).isEmpty() || bridge.isFighting(e);
        // 仕事中は30秒ごと、休んでいる間は2分ごとに満腹度が1減る
        long interval = working ? 600 : 2400;
        if (now % interval < 20) {
            setFood(e, food(e) - 1);
        }
        boolean hurt = e.getHealth() < e.getMaxHealth() * 0.6F;
        if ((food(e) <= 14 || hurt) && now - e.getEntityData().getLong(KEY_EAT_AT) >= 40) {
            eat(e);
        }
        if (food(e) <= 0) {
            e.addPotionEffect(new PotionEffect(MobEffects.SLOWNESS, 40, 0, false, false));
            e.addPotionEffect(new PotionEffect(MobEffects.WEAKNESS, 40, 0, false, false));
        } else if (food(e) >= 18 && e.getHealth() < e.getMaxHealth() && now % 80 < 20) {
            e.heal(1.0F);
        }
    }

    /** 持ち物の食べ物を1つ食べる。食べたら true。 */
    public static boolean eat(EntityFriendlyCreature e) {
        InventoryBasic inv = AllyInventory.get(e);
        int bestSlot = -1;
        int bestHeal = -1;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !(s.getItem() instanceof ItemFood)) {
                continue;
            }
            int heal = ((ItemFood) s.getItem()).getHealAmount(s);
            // 腐った肉・毒のある物は、他に何も無い時だけ
            if (s.getItem() == net.minecraft.init.Items.ROTTEN_FLESH || s.getItem() == net.minecraft.init.Items.SPIDER_EYE
                    || s.getItem() == net.minecraft.init.Items.POISONOUS_POTATO) {
                heal -= 10;
            }
            if (heal > bestHeal) {
                bestHeal = heal;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack food = inv.getStackInSlot(bestSlot);
        ItemFood item = (ItemFood) food.getItem();
        int heal = item.getHealAmount(food);
        float saturation = item.getSaturationModifier(food);
        inv.decrStackSize(bestSlot, 1);
        setFood(e, food(e) + heal);
        e.heal(Math.max(1.0F, heal * (1.0F + saturation)));
        e.getEntityData().setLong(KEY_EAT_AT, e.world.getTotalWorldTime());
        e.world.playSound(null, e.posX, e.posY, e.posZ, SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL,
                0.6F, 0.9F + e.world.rand.nextFloat() * 0.2F);
        e.world.playSound(null, e.posX, e.posY, e.posZ, SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.NEUTRAL, 0.4F, 1.0F);
        return true;
    }

    // ------------------------------------------------------------------
    // 道具の手入れ
    // ------------------------------------------------------------------

    private static String toolClassOf(ItemStack s) {
        Set<String> classes = s.getItem().getToolClasses(s);
        for (String c : new String[] { "pickaxe", "axe", "shovel" }) {
            if (classes.contains(c)) {
                return c;
            }
        }
        return classes.isEmpty() ? null : classes.iterator().next();
    }

    private static int remaining(ItemStack s) {
        return s.getMaxDamage() - s.getItemDamage();
    }

    private static void tickTool(EntityFriendlyCreature e, EngenderGatheringBridge bridge) {
        ItemStack tool = e.getHeldItemMainhand();
        if (tool.isEmpty() || !tool.isItemStackDamageable() || tool.getMaxDamage() <= 0) {
            return;
        }
        // 銃（Techguns等は耐久値を残弾に使う物がある）や道具でない物は触らない
        if (AllyToolManager.isRangedGun(tool)
                || (toolClassOf(tool) == null && !(tool.getItem() instanceof net.minecraft.item.ItemSword))) {
            return;
        }
        int threshold = Math.max(4, tool.getMaxDamage() / 12);
        if (remaining(tool) > threshold) {
            return;
        }
        String cls = toolClassOf(tool);
        if (repairWithMaterial(e, tool)) {
            say(e, tool.getDisplayName() + " を修理しました（耐久 " + remaining(tool) + "/" + tool.getMaxDamage() + "）。");
            return;
        }
        if (combineDuplicate(e, tool)) {
            say(e, "2本の " + tool.getDisplayName() + " を合わせて修理しました。");
            return;
        }
        if (cls != null && swapToSpare(e, cls, tool)) {
            say(e, "傷んだ道具を予備の " + e.getHeldItemMainhand().getDisplayName() + " に持ち替えました。");
            return;
        }
        if (cls != null && takeFromChest(e, cls, tool)) {
            say(e, "近くのチェストから " + e.getHeldItemMainhand().getDisplayName() + " を取り出して持ち替えました。");
            return;
        }
        if (cls != null && remaining(tool) <= 2 && !hasSpare(e, cls)) {
            if (AllyToolManager.craftBasicTool(e, cls)) {
                say(e, "道具が壊れそうなので、予備を作っておきました。");
            }
        }
    }

    private static boolean hasSpare(EntityFriendlyCreature e, String cls) {
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && s.getItem().getToolClasses(s).contains(cls)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anvilAvailable(EntityFriendlyCreature e) {
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && net.minecraft.block.Block.getBlockFromItem(s.getItem()) instanceof BlockAnvil) {
                return true;
            }
        }
        BlockPos c = new BlockPos(e);
        for (BlockPos p : BlockPos.getAllInBoxMutable(c.add(-6, -3, -6), c.add(6, 3, 6))) {
            if (e.world.getBlockState(p).getBlock() instanceof BlockAnvil) {
                return true;
            }
        }
        return false;
    }

    private static boolean repairWithMaterial(EntityFriendlyCreature e, ItemStack tool) {
        if (!anvilAvailable(e)) {
            return false;
        }
        InventoryBasic inv = AllyInventory.get(e);
        boolean repaired = false;
        int perItem = Math.max(1, tool.getMaxDamage() / 4);
        for (int i = 0; i < inv.getSizeInventory() && tool.getItemDamage() > 0; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !tool.getItem().getIsRepairable(tool, s)) {
                continue;
            }
            while (!s.isEmpty() && tool.getItemDamage() > 0) {
                tool.setItemDamage(Math.max(0, tool.getItemDamage() - perItem));
                inv.decrStackSize(i, 1);
                s = inv.getStackInSlot(i);
                repaired = true;
            }
        }
        if (repaired) {
            e.world.playSound(null, e.posX, e.posY, e.posZ, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.NEUTRAL, 0.5F, 1.0F);
        }
        return repaired;
    }

    private static boolean combineDuplicate(EntityFriendlyCreature e, ItemStack tool) {
        if (tool.isItemEnchanted()) {
            return false; // エンチャントが消えるので合成しない
        }
        InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || s.getItem() != tool.getItem() || s.isItemEnchanted() || !s.isItemStackDamageable()) {
                continue;
            }
            int total = remaining(tool) + remaining(s) + tool.getMaxDamage() * 5 / 100;
            tool.setItemDamage(Math.max(0, tool.getMaxDamage() - total));
            inv.setInventorySlotContents(i, ItemStack.EMPTY);
            inv.markDirty();
            return true;
        }
        return false;
    }

    private static boolean swapToSpare(EntityFriendlyCreature e, String cls, ItemStack tool) {
        InventoryBasic inv = AllyInventory.get(e);
        int bestSlot = -1;
        int best = remaining(tool);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty() || !s.getItem().getToolClasses(s).contains(cls)) {
                continue;
            }
            int r = s.isItemStackDamageable() ? remaining(s) : Integer.MAX_VALUE;
            if (r > best) {
                best = r;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack chosen = inv.getStackInSlot(bestSlot);
        inv.setInventorySlotContents(bestSlot, tool);
        e.setHeldItem(EnumHand.MAIN_HAND, chosen);
        inv.markDirty();
        return true;
    }

    private static boolean takeFromChest(EntityFriendlyCreature e, String cls, ItemStack tool) {
        World world = e.world;
        List<TileEntity> nearby = new ArrayList<TileEntity>();
        for (TileEntity te : world.loadedTileEntityList) {
            if (te instanceof TileEntityChest && te.getDistanceSq(e.posX, e.posY, e.posZ) <= 10 * 10
                    && !AllyAreas.isForbidden(world, te.getPos())) {
                nearby.add(te);
            }
        }
        for (TileEntity te : nearby) {
            IInventory chest = (IInventory) te;
            for (int i = 0; i < chest.getSizeInventory(); i++) {
                ItemStack s = chest.getStackInSlot(i);
                if (s.isEmpty() || !s.getItem().getToolClasses(s).contains(cls)) {
                    continue;
                }
                if (s.isItemStackDamageable() && remaining(s) <= remaining(tool)) {
                    continue;
                }
                ItemStack taken = chest.decrStackSize(i, 1);
                ItemStack rest = net.minecraft.tileentity.TileEntityHopper.putStackInInventoryAllSlots(null, chest, tool,
                        net.minecraft.util.EnumFacing.UP);
                if (!rest.isEmpty()) {
                    AllyInventory.insertOrDrop(e, rest);
                }
                e.setHeldItem(EnumHand.MAIN_HAND, taken);
                chest.markDirty();
                return true;
            }
        }
        return false;
    }

    private static void say(EntityFriendlyCreature e, String msg) {
        long now = e.world.getTotalWorldTime();
        if (now - e.getEntityData().getLong(KEY_TOOL_MSG) < 100) {
            return;
        }
        e.getEntityData().setLong(KEY_TOOL_MSG, now);
        EntityPlayer owner = AllyAIUtil.resolveOwnerPlayer(e, null);
        if (owner != null) {
            owner.sendMessage(new TextComponentString("[" + e.getName() + "] " + msg));
        }
    }

    /** 持ち物からたいまつ（または材料）を出して、暗ければ足元に置く。 */
    public static void placeTorchFromInventory(EntityFriendlyCreature e) {
        InventoryBasic inv = AllyInventory.get(e);
        List<ItemStack> temp = new ArrayList<ItemStack>();
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            Item it = s.getItem();
            if (it == Item.getItemFromBlock(net.minecraft.init.Blocks.TORCH) || it == net.minecraft.init.Items.COAL
                    || it == net.minecraft.init.Items.STICK) {
                temp.add(inv.removeStackFromSlot(i));
            }
        }
        if (temp.isEmpty()) {
            return;
        }
        AllyAIUtil.placeTorchIfDark(e, temp);
        for (ItemStack s : temp) {
            AllyInventory.insertOrDrop(e, s);
        }
    }
}
