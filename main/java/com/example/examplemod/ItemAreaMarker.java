package com.example.examplemod;

import java.util.List;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * [視覚的指示] エリアマーカー。
 *
 * <ul>
 *   <li>何も無い所へ右クリック: 種類を切り替え（採掘 → 伐採 → 立ち入り禁止）</li>
 *   <li>ブロックへ右クリック 1回目: 1点目を記録。2回目: 2点目を記録してエリアを登録</li>
 *   <li>スニーク＋ブロックへ右クリック: そこを含む自分のエリアを削除</li>
 *   <li>手に持っている間、自分のエリアの枠が色付きの粒子で表示される
 *       （青=採掘、緑=伐採、赤=立ち入り禁止）</li>
 * </ul>
 */
public class ItemAreaMarker extends Item {

    private static final String KEY_MODE = "Mode";
    private static final String KEY_P1 = "P1";
    private static final int MAX_EDGE = 128;

    public ItemAreaMarker() {
        this.setUnlocalizedName("ally_area_marker");
        this.setRegistryName("ally_area_marker");
        this.setMaxStackSize(1);
        this.setCreativeTab(CreativeTabs.TOOLS);
    }

    private static NBTTagCompound tag(ItemStack stack) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        return stack.getTagCompound();
    }

    private static AllyAreas.Type mode(ItemStack stack) {
        int m = tag(stack).getInteger(KEY_MODE);
        AllyAreas.Type[] types = AllyAreas.Type.values();
        return types[Math.floorMod(m, types.length)];
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            NBTTagCompound t = tag(stack);
            t.setInteger(KEY_MODE, Math.floorMod(t.getInteger(KEY_MODE) + 1, AllyAreas.Type.values().length));
            t.removeTag(KEY_P1);
            player.sendMessage(new TextComponentString("[エリアマーカー] 種類: " + mode(stack).label
                    + "（ブロックを2か所右クリックで範囲指定）"));
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing facing,
            float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        ItemStack stack = player.getHeldItem(hand);
        NBTTagCompound t = tag(stack);
        AllyAreas data = AllyAreas.get(world);
        int dim = world.provider.getDimension();
        if (player.isSneaking()) {
            int removed = data.removeAt(player.getUniqueID(), dim, pos);
            player.sendMessage(new TextComponentString("[エリアマーカー] " + (removed > 0
                    ? removed + " 個のエリアを削除しました。" : "ここに自分のエリアはありません。")));
            t.removeTag(KEY_P1);
            return EnumActionResult.SUCCESS;
        }
        if (!t.hasKey(KEY_P1)) {
            t.setLong(KEY_P1, pos.toLong());
            player.sendMessage(new TextComponentString("[エリアマーカー] " + mode(stack).label + " の1点目: "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "（もう1点を右クリック）"));
            return EnumActionResult.SUCCESS;
        }
        BlockPos p1 = BlockPos.fromLong(t.getLong(KEY_P1));
        t.removeTag(KEY_P1);
        AllyAreas.Type type = mode(stack);
        // 採掘・伐採エリアは高さ方向を少し広げる（地下の鉱石・木のてっぺんも入るように）
        BlockPos a = p1;
        BlockPos b = pos;
        if (type == AllyAreas.Type.MINE && Math.abs(a.getY() - b.getY()) < 2) {
            a = new BlockPos(a.getX(), Math.max(1, Math.min(a.getY(), b.getY()) - 64), a.getZ());
        } else if (type == AllyAreas.Type.CHOP && Math.abs(a.getY() - b.getY()) < 2) {
            b = new BlockPos(b.getX(), Math.min(255, Math.max(a.getY(), b.getY()) + 40), b.getZ());
            a = new BlockPos(a.getX(), Math.max(1, a.getY() - 4), a.getZ());
        }
        AllyAreas.Area area = new AllyAreas.Area(player.getUniqueID(), dim, type, a, b);
        if (area.max.getX() - area.min.getX() > MAX_EDGE || area.max.getZ() - area.min.getZ() > MAX_EDGE) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "[エリアマーカー] 広すぎます（一辺 "
                    + MAX_EDGE + " ブロックまで）。"));
            return EnumActionResult.SUCCESS;
        }
        data.add(area);
        player.sendMessage(new TextComponentString("[エリアマーカー] " + type.label + " を登録しました: "
                + area.min.getX() + "," + area.min.getY() + "," + area.min.getZ() + " 〜 "
                + area.max.getX() + "," + area.max.getY() + "," + area.max.getZ()
                + (type == AllyAreas.Type.FORBID ? "（味方はここを絶対に壊しません）" : "（味方はこの中だけで作業します）")));
        return EnumActionResult.SUCCESS;
    }

    /** 持っている間、自分のエリアの枠を粒子で見せる（サーバーから本人にだけ送る）。 */
    @Override
    public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world.isRemote || !selected || !(entity instanceof EntityPlayerMP) || entity.ticksExisted % 10 != 0) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) entity;
        WorldServer ws = (WorldServer) world;
        int dim = world.provider.getDimension();
        for (AllyAreas.Area a : AllyAreas.get(world).all()) {
            if (a.dim != dim || !a.owner.equals(player.getUniqueID())
                    || a.distanceSqTo(player.posX, player.posY, player.posZ) > 96 * 96) {
                continue;
            }
            drawBox(ws, player, a);
        }
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey(KEY_P1)) {
            BlockPos p = BlockPos.fromLong(stack.getTagCompound().getLong(KEY_P1));
            ws.spawnParticle(player, EnumParticleTypes.VILLAGER_HAPPY, true, p.getX() + 0.5, p.getY() + 1.2, p.getZ() + 0.5,
                    4, 0.3, 0.3, 0.3, 0.0);
        }
    }

    private static void drawBox(WorldServer ws, EntityPlayerMP player, AllyAreas.Area a) {
        double x0 = a.min.getX();
        double y0 = a.min.getY();
        double z0 = a.min.getZ();
        double x1 = a.max.getX() + 1;
        double y1 = a.max.getY() + 1;
        double z1 = a.max.getZ() + 1;
        // 高さ方向は見やすさのため、プレイヤー付近の高さに輪を描く
        double yMid = Math.max(y0, Math.min(y1, Math.floor(player.posY)));
        double[][] edges = {
                { x0, yMid, z0, x1, yMid, z0 }, { x0, yMid, z1, x1, yMid, z1 },
                { x0, yMid, z0, x0, yMid, z1 }, { x1, yMid, z0, x1, yMid, z1 },
                { x0, y0, z0, x0, y1, z0 }, { x1, y0, z0, x1, y1, z0 },
                { x0, y0, z1, x0, y1, z1 }, { x1, y0, z1, x1, y1, z1 },
        };
        for (double[] e : edges) {
            double len = Math.sqrt((e[3] - e[0]) * (e[3] - e[0]) + (e[4] - e[1]) * (e[4] - e[1]) + (e[5] - e[2]) * (e[5] - e[2]));
            int steps = (int) Math.min(16, Math.max(1, len / 2));
            for (int i = 0; i <= steps; i++) {
                double f = i / (double) steps;
                double x = e[0] + (e[3] - e[0]) * f;
                double y = e[1] + (e[4] - e[1]) * f;
                double z = e[2] + (e[5] - e[2]) * f;
                if (player.getDistanceSq(x, y, z) > 64 * 64) {
                    continue;
                }
                // REDSTONE 粒子は count=0 の時、オフセットが色(RGB)になる
                ws.spawnParticle(player, EnumParticleTypes.REDSTONE, true, x, y, z, 0,
                        Math.max(0.001, a.type.r), a.type.g, a.type.b, 1.0);
            }
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add("種類: " + mode(stack).label);
        tooltip.add("空中で右クリック: 種類を切り替え");
        tooltip.add("ブロックを2回右クリック: 範囲を登録");
        tooltip.add("スニーク+右クリック: そこのエリアを削除");
    }
}
