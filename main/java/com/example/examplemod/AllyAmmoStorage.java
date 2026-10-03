package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * 味方専用の弾薬ポーチ。
 *
 * <p>[弾薬が1個しか渡らない不具合の修正] 以前は Engender 本体の
 * {@code basicInventory}（8スロット）に弾薬を入れていたが、バイトコードを確認すると
 * 本体は毎Tick、スロット0〜5を頭/胴/脚/足/メインハンド/オフハンドの装備で、
 * スロット6を読書中の本で <b>上書き</b> している（装備の写し鏡であって収納ではない）。
 * 空いている最初のスロット（＝防具を着ていなければスロット0）に入れた64個の
 * マガジンは次のTickで消えていた。</p>
 *
 * <p>ここではエンティティの Forge 永続データ（{@code getEntityData()}、ワールド保存時に
 * 自動で保存される）に弾薬を保持するので、消えず、再ログインしても残る。</p>
 */
public final class AllyAmmoStorage {

    private static final String KEY = "EngenderAllyAmmo";
    /** ポーチの最大スタック数（1スタック64個なら 1728発分）。 */
    private static final int MAX_STACKS = 27;

    private AllyAmmoStorage() {
    }

    private static List<ItemStack> read(EntityFriendlyCreature entity) {
        List<ItemStack> list = new ArrayList<ItemStack>();
        NBTTagCompound data = entity.getEntityData();
        if (!data.hasKey(KEY, Constants.NBT.TAG_LIST)) {
            return list;
        }
        NBTTagList tags = data.getTagList(KEY, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tags.tagCount(); i++) {
            ItemStack stack = new ItemStack(tags.getCompoundTagAt(i));
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    private static void write(EntityFriendlyCreature entity, List<ItemStack> list) {
        NBTTagList tags = new NBTTagList();
        for (ItemStack stack : list) {
            if (stack != null && !stack.isEmpty()) {
                tags.appendTag(stack.writeToNBT(new NBTTagCompound()));
            }
        }
        entity.getEntityData().setTag(KEY, tags);
    }

    /** 入れられた個数を返す（{@code stack} 自体は変更しない）。 */
    public static int add(EntityFriendlyCreature entity, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        List<ItemStack> list = read(entity);
        ItemStack rest = stack.copy();
        int given = 0;
        for (ItemStack existing : list) {
            if (rest.isEmpty()) {
                break;
            }
            if (ItemStack.areItemsEqual(existing, rest) && ItemStack.areItemStackTagsEqual(existing, rest)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(existing.getMaxStackSize() - existing.getCount(), rest.getCount());
                existing.grow(move);
                rest.shrink(move);
                given += move;
            }
        }
        while (!rest.isEmpty() && list.size() < MAX_STACKS) {
            int move = Math.min(rest.getMaxStackSize(), rest.getCount());
            ItemStack part = rest.copy();
            part.setCount(move);
            list.add(part);
            rest.shrink(move);
            given += move;
        }
        write(entity, list);
        return given;
    }

    public static int count(EntityFriendlyCreature entity, ItemStack template) {
        int total = 0;
        for (ItemStack stack : read(entity)) {
            if (ItemStack.areItemsEqual(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** {@code amount} 個取り出す。足りなければ何もせず false。 */
    public static boolean consume(EntityFriendlyCreature entity, ItemStack template, int amount) {
        List<ItemStack> list = read(entity);
        int have = 0;
        for (ItemStack stack : list) {
            if (ItemStack.areItemsEqual(stack, template)) {
                have += stack.getCount();
            }
        }
        if (have < amount) {
            return false;
        }
        int remaining = amount;
        for (ItemStack stack : list) {
            if (remaining <= 0) {
                break;
            }
            if (ItemStack.areItemsEqual(stack, template)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        list.removeIf(ItemStack::isEmpty);
        write(entity, list);
        return true;
    }

    /** 空になったマガジン等、弾薬以外で返ってきた物もポーチへ（入らなければ呼び出し側で処理）。 */
    public static int total(EntityFriendlyCreature entity) {
        int total = 0;
        for (ItemStack stack : read(entity)) {
            total += stack.getCount();
        }
        return total;
    }
}
