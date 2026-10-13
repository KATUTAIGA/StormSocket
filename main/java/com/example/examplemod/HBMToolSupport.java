package com.example.examplemod;

import java.util.List;

import com.hbm.handler.ability.IToolAreaAbility;
import com.hbm.handler.ability.IToolHarvestAbility;
import com.hbm.handler.ability.ToolPreset;
import com.hbm.items.tool.ItemToolAbility;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * [HBMのツルハシ等の特殊機能を味方も使う]
 *
 * <p>HBM の能力付き道具（{@link ItemToolAbility}：鉱脈一括採掘・範囲採掘・幸運・
 * 自動精錬など）は、能力を {@code onBlockStartBreak(stack, pos, player)} で発動する。
 * 味方はプレイヤーではないので、サーバー側の偽プレイヤーに <b>同じ道具の実物</b>
 * を持たせて {@code tryHarvestBlock} させる（耐久もその道具から減る）。</p>
 *
 * <p>モードは自動選択: 周りを荒らさない「鉱脈一括（RECURSION）」を最優先し、
 * 幸運（LUCK）があれば併用。ハンマー（範囲）・爆発は整地・坑道が汚くなるので使わない。
 * 精錬・粉砕などドロップを別物に変える能力も、クエストの必要品と食い違うので使わない。</p>
 */
final class HBMToolSupport {

    private HBMToolSupport() {
    }

    static boolean isAbilityTool(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (Class<?> c = s.getItem().getClass(); c != null && c != Item.class; c = c.getSuperclass()) {
            if ("com.hbm.items.tool.ItemToolAbility".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 味方向けに一番きれいで効率の良いモードを選んでおく。 */
    static void selectPreset(ItemStack tool) {
        ItemToolAbility item = (ItemToolAbility) tool.getItem();
        ItemToolAbility.Configuration config = item.getConfiguration(tool);
        if (config == null || config.presets == null) {
            return;
        }
        int bestIndex = -1;
        int bestScore = -1;
        for (int i = 0; i < config.presets.size(); i++) {
            ToolPreset p = config.presets.get(i);
            int score = 0;
            if (p.areaAbility == IToolAreaAbility.RECURSION) {
                score += 100 + p.areaAbilityLevel;
            } else if (p.areaAbility != null && p.areaAbility != IToolAreaAbility.NONE) {
                continue; // ハンマー・爆発は使わない
            }
            if (p.harvestAbility == IToolHarvestAbility.LUCK) {
                score += 10 + p.harvestAbilityLevel;
            } else if (p.harvestAbility != null && p.harvestAbility != IToolHarvestAbility.NONE) {
                continue; // 精錬・粉砕・シルク等は使わない
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        if (bestIndex >= 0 && config.currentPreset != bestIndex) {
            config.currentPreset = bestIndex;
            item.setConfiguration(tool, config);
        }
    }

    /**
     * 能力付き道具で pos を掘る（能力が連鎖で周りの鉱石も掘る）。落ちた物は into へ。
     * 掘れなかった（保護Mod等）なら false。
     */
    static boolean harvest(EntityFriendlyCreature ally, BlockPos pos, List<ItemStack> into) {
        if (!(ally.world instanceof WorldServer)) {
            return false;
        }
        ItemStack tool = ally.getHeldItemMainhand();
        try {
            selectPreset(tool);
        } catch (Throwable ignored) {
            // 設定の読めない道具 -- 今のモードのまま使う
        }
        FakePlayer fake = FakePlayerFactory.getMinecraft((WorldServer) ally.world);
        fake.setPosition(ally.posX, ally.posY, ally.posZ);
        fake.rotationYaw = ally.rotationYaw;
        fake.rotationPitch = ally.rotationPitch;
        fake.setHeldItem(EnumHand.MAIN_HAND, tool);
        boolean ok;
        try {
            ok = fake.interactionManager.tryHarvestBlock(pos);
        } finally {
            fake.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        if (tool.isEmpty()) {
            ally.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY); // 壊れた
        }
        // 能力で連鎖的に掘られたブロックの分も含めて回収する
        AxisAlignedBB area = new AxisAlignedBB(pos).grow(8.0D).union(ally.getEntityBoundingBox().grow(3.0D));
        for (EntityItem item : ally.world.getEntitiesWithinAABB(EntityItem.class, area)) {
            if (item.isEntityAlive() && !item.getItem().isEmpty() && item.ticksExisted < 5) {
                into.add(item.getItem().copy());
                item.setDead();
            }
        }
        return ok;
    }
}
