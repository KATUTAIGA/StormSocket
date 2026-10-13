package com.example.examplemod;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * 味方の「プレイヤーと同じ」36スロットの持ち物。
 *
 * <p>Engender 本体の {@code basicInventory} は装備の写しで毎Tick上書きされるため使えない。
 * これはエンティティの Forge 永続データに保存され、ワールドを出入りしても残る。
 * スニーク＋素手で味方を右クリックすると、チェストと同じ画面で中身を出し入れできる。
 * 味方はここから道具（斧・ツルハシ・シャベル・銃・剣）を自分で選んで持ち替え、
 * 素材もここから使う。</p>
 */
public final class AllyInventory {

    public static final int SIZE = 36;
    private static final String KEY = "EngenderAllyInventory";

    private static final Map<EntityFriendlyCreature, Inv> LIVE = new WeakHashMap<EntityFriendlyCreature, Inv>();

    private AllyInventory() {
    }

    private static final class Inv extends InventoryBasic {
        private final java.lang.ref.WeakReference<EntityFriendlyCreature> owner;
        private boolean loading;
        private int viewers;

        Inv(EntityFriendlyCreature owner, int size) {
            super(title(owner), true, size);
            this.owner = new java.lang.ref.WeakReference<EntityFriendlyCreature>(owner);
        }

        @Override
        public void markDirty() {
            super.markDirty();
            EntityFriendlyCreature e = owner.get();
            if (!loading && e != null) {
                save(e, this);
            }
        }

        @Override
        public void openInventory(EntityPlayer player) {
            super.openInventory(player);
            viewers++;
        }

        @Override
        public void closeInventory(EntityPlayer player) {
            super.closeInventory(player);
            viewers = Math.max(0, viewers - 1);
        }
    }

    private static String title(EntityFriendlyCreature e) {
        return e.getName() + " の持ち物 (合計Lv" + AllySkills.totalLevel(e) + ")";
    }

    /**
     * [熟練度で持ち物が増える] スキルの合計レベルで 36/45/54 スロット。
     * 大きくする時は中身を移して作り直す（画面を開いている人がいる間は待つ）。
     */
    public static InventoryBasic get(EntityFriendlyCreature entity) {
        Inv inv = LIVE.get(entity);
        int size = AllySkills.inventorySize(entity);
        if (inv == null) {
            inv = new Inv(entity, Math.max(size, savedSize(entity)));
            load(entity, inv);
            LIVE.put(entity, inv);
        } else if (inv.getSizeInventory() < size && inv.viewers == 0) {
            Inv bigger = new Inv(entity, size);
            bigger.loading = true;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                bigger.setInventorySlotContents(i, inv.getStackInSlot(i));
            }
            bigger.loading = false;
            LIVE.put(entity, bigger);
            save(entity, bigger);
            inv = bigger;
        }
        return inv;
    }

    /** レベルアップ時: タイトルと大きさを更新。 */
    public static void refresh(EntityFriendlyCreature entity) {
        InventoryBasic inv = get(entity);
        inv.setCustomName(title(entity));
    }

    /** 保存されている一番大きいスロット番号+1 を 9 の倍数に（縮めて中身を失わないため）。 */
    private static int savedSize(EntityFriendlyCreature entity) {
        NBTTagCompound data = entity.getEntityData();
        int max = 0;
        if (data.hasKey(KEY, Constants.NBT.TAG_LIST)) {
            NBTTagList list = data.getTagList(KEY, Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                max = Math.max(max, (list.getCompoundTagAt(i).getByte("Slot") & 255) + 1);
            }
        }
        int rounded = ((max + 8) / 9) * 9;
        return Math.min(54, Math.max(SIZE, rounded));
    }

    private static void load(EntityFriendlyCreature entity, Inv inv) {
        inv.loading = true;
        try {
            NBTTagCompound data = entity.getEntityData();
            if (data.hasKey(KEY, Constants.NBT.TAG_LIST)) {
                NBTTagList list = data.getTagList(KEY, Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound tag = list.getCompoundTagAt(i);
                    int slot = tag.getByte("Slot") & 255;
                    if (slot < inv.getSizeInventory()) {
                        inv.setInventorySlotContents(slot, new ItemStack(tag));
                    }
                }
            }
        } finally {
            inv.loading = false;
        }
    }

    private static void save(EntityFriendlyCreature entity, InventoryBasic inv) {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setByte("Slot", (byte) i);
                stack.writeToNBT(tag);
                list.appendTag(tag);
            }
        }
        entity.getEntityData().setTag(KEY, list);
    }

    /** 入れられるだけ入れて、入りきらなかった分を返す（引数は変更しない）。 */
    public static ItemStack insert(EntityFriendlyCreature entity, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        InventoryBasic inv = get(entity);
        ItemStack rest = stack.copy();
        for (int i = 0; i < inv.getSizeInventory() && !rest.isEmpty(); i++) {
            ItemStack slot = inv.getStackInSlot(i);
            if (!slot.isEmpty() && ItemStack.areItemsEqual(slot, rest) && ItemStack.areItemStackTagsEqual(slot, rest)
                    && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(slot.getMaxStackSize() - slot.getCount(), rest.getCount());
                slot.grow(move);
                rest.shrink(move);
            }
        }
        for (int i = 0; i < inv.getSizeInventory() && !rest.isEmpty(); i++) {
            if (inv.getStackInSlot(i).isEmpty()) {
                int move = Math.min(rest.getMaxStackSize(), rest.getCount());
                ItemStack part = rest.copy();
                part.setCount(move);
                inv.setInventorySlotContents(i, part);
                rest.shrink(move);
            }
        }
        inv.markDirty();
        return rest;
    }

    /** 持ち物へ。入りきらなければ足元に落とす（絶対に消さない）。 */
    public static void insertOrDrop(EntityFriendlyCreature entity, ItemStack stack) {
        ItemStack rest = insert(entity, stack);
        if (!rest.isEmpty()) {
            EntityItem drop = new EntityItem(entity.world, entity.posX, entity.posY + 0.5, entity.posZ, rest);
            drop.setPickupDelay(20);
            entity.world.spawnEntity(drop);
        }
    }

    /** 味方が倒れた時: 中身を全部その場に落として空にする。 */
    public static void dropAll(EntityFriendlyCreature entity) {
        InventoryBasic inv = get(entity);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty()) {
                net.minecraft.inventory.InventoryHelper.spawnItemStack(entity.world, entity.posX, entity.posY + 0.5, entity.posZ, s.copy());
                inv.setInventorySlotContents(i, ItemStack.EMPTY);
            }
        }
        inv.markDirty();
    }

    /** スニーク右クリックで開く、チェストと同じ画面。 */
    public static void open(EntityPlayer player, EntityFriendlyCreature entity) {
        refresh(entity);
        player.displayGUIChest(get(entity));
    }
}
