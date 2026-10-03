package com.example.examplemod;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * [スキル・熟練度] 味方ごとの「採掘」「伐採」「建築」「戦闘」の経験値とレベル。
 *
 * <p>経験値はエンティティの永続データに保存。レベル = floor(sqrt(経験値 / 20))（最大30）。
 * レベルに応じて:</p>
 * <ul>
 *   <li>採掘/伐採: 掘る速さ（1レベルごとに +8%）→ 鉱脈・木の一括破壊が速くなる</li>
 *   <li>建築: 足場の設置間隔が短くなる、整地・建築が速くなる</li>
 *   <li>合計レベル: 持ち物が 36 → 45（合計15）→ 54（合計30）スロットに増える、
 *       一度に運べる量が増える</li>
 * </ul>
 */
public final class AllySkills {

    public enum Skill {
        MINING("採掘"),
        WOODCUTTING("伐採"),
        BUILDING("建築"),
        COMBAT("戦闘");

        public final String label;

        Skill(String label) {
            this.label = label;
        }
    }

    private static final String KEY = "EngenderSkills";
    private static final int MAX_LEVEL = 30;

    private AllySkills() {
    }

    private static NBTTagCompound tag(EntityFriendlyCreature e) {
        NBTTagCompound data = e.getEntityData();
        if (!data.hasKey(KEY)) {
            data.setTag(KEY, new NBTTagCompound());
        }
        return data.getCompoundTag(KEY);
    }

    public static int xp(EntityFriendlyCreature e, Skill s) {
        return tag(e).getInteger(s.name());
    }

    public static int level(EntityFriendlyCreature e, Skill s) {
        return Math.min(MAX_LEVEL, (int) Math.floor(Math.sqrt(xp(e, s) / 20.0)));
    }

    public static int totalLevel(EntityFriendlyCreature e) {
        int n = 0;
        for (Skill s : Skill.values()) {
            n += level(e, s);
        }
        return n;
    }

    public static void addXp(EntityFriendlyCreature e, Skill s, int amount) {
        if (e == null || e.world == null || e.world.isRemote || amount <= 0) {
            return;
        }
        int before = level(e, s);
        int beforeSize = inventorySize(e);
        tag(e).setInteger(s.name(), xp(e, s) + amount);
        int after = level(e, s);
        if (after > before) {
            e.world.playSound(null, e.posX, e.posY, e.posZ, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, 0.6F, 1.2F);
            EntityPlayer owner = AllyAIUtil.resolveOwnerPlayer(e, null);
            if (owner != null) {
                String extra = inventorySize(e) > beforeSize ? "（持ち物が " + inventorySize(e) + " スロットに増えました）" : "";
                owner.sendMessage(new TextComponentString(TextFormatting.GOLD + "[" + e.getName() + "] "
                        + s.label + "スキルが Lv" + after + " に上がりました！" + extra));
            }
            AllyInventory.refresh(e);
        }
    }

    /** 掘る・切る速さの倍率（1.0 = 通常）。 */
    public static float speedMultiplier(EntityFriendlyCreature e, Skill s) {
        return 1.0F + 0.08F * level(e, s);
    }

    /** 持ち物のスロット数（チェスト画面の都合で 9 の倍数、最大 54）。 */
    public static int inventorySize(EntityFriendlyCreature e) {
        int total = totalLevel(e);
        return total >= 30 ? 54 : total >= 15 ? 45 : 36;
    }

    /** 一度に運べるスタック数のボーナス。 */
    public static int carryBonus(EntityFriendlyCreature e) {
        return totalLevel(e) / 3;
    }

    public static String summary(EntityFriendlyCreature e) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : Skill.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.label).append("Lv").append(level(e, s));
        }
        return sb.toString();
    }
}
