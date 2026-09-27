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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private static final Logger LOGGER = LogManager.getLogger("examplemod");

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
        // [味方が消える不具合の修正] HBM の能力（鉱脈一括採掘など）を偽プレイヤー経由で
        // 発動するこの下の処理は、HBM 側の内部実装に直接依存している。ここで何らかの
        // 例外（対応していない道具・想定外の内部状態など）が発生すると、以前は
        // 何も捕まえずにそのまま呼び出し元（採掘AI）まで例外が伝播していた。
        // Minecraft/Forge はエンティティの Tick 中に例外が起きると、サーバー全体を
        // 落とさないために「そのエンティティだけを黙って World から取り除く」ため、
        // これが原因でHBMのツルハシ等を持たせた味方が何も言わずに消えていた。
        // 能力発動全体を確実に捕まえ、失敗時は普通の採掘にフォールバックする。
        FakePlayer fake;
        try {
            fake = FakePlayerFactory.getMinecraft((WorldServer) ally.world);
        } catch (Throwable t) {
            LOGGER.warn("HBM ability harvest: could not get a fake player, falling back to normal mining", t);
            return false;
        }
        boolean ok = false;
        try {
            fake.setPosition(ally.posX, ally.posY, ally.posZ);
            fake.rotationYaw = ally.rotationYaw;
            fake.rotationPitch = ally.rotationPitch;
            fake.setHeldItem(EnumHand.MAIN_HAND, tool);
            try {
                ok = fake.interactionManager.tryHarvestBlock(pos);
            } finally {
                fake.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            }
        } catch (Throwable t) {
            LOGGER.warn("HBM ability harvest failed for {} at {}, falling back to normal mining",
                    tool.isEmpty() ? "?" : tool.getItem().getRegistryName(), pos, t);
            return false;
        }
        if (tool.isEmpty()) {
            ally.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY); // 壊れた
        }
        try {
            // 能力で連鎖的に掘られたブロックの分も含めて回収する
            AxisAlignedBB area = new AxisAlignedBB(pos).grow(8.0D).union(ally.getEntityBoundingBox().grow(3.0D));
            for (EntityItem item : ally.world.getEntitiesWithinAABB(EntityItem.class, area)) {
                if (item.isEntityAlive() && !item.getItem().isEmpty() && item.ticksExisted < 5) {
                    into.add(item.getItem().copy());
                    item.setDead();
                }
            }
        } catch (Throwable t) {
            // ブロック自体はもう掘れているので、拾い忘れがあっても採掘自体は成功のまま扱う
            LOGGER.warn("HBM ability harvest: drop pickup failed at {}", pos, t);
        }
        return ok;
    }
}
