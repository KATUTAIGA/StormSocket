package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 右クリックでGUIを開き、味方モブ（{@link EntityAIDriveImmersiveVehicle} を持つ
 * {@code EntityFriendlyCreature} 全員）へ「車で向かうべき目的地」を指示できるアイテム。
 *
 * <p>実際の座標入力・送信は {@link GuiVehicleWaypoint}（クライアント側GUI）と
 * {@link PacketSetVehicleWaypoint}（サーバーへの通知パケット）が担う。
 * このクラス自身はクライアント専用コードを {@code @SideOnly(Side.CLIENT)} を付けた
 * 別メソッドに隔離しており、専用サーバー上でこのクラスがロードされても
 * {@link net.minecraft.client.gui.GuiScreen} 等のクライアント専用クラスには触れない
 * （vanilla の {@code ItemWritableBook} と同じパターン）。
 */
public class ItemVehicleWaypoint extends Item {

    public ItemVehicleWaypoint() {
        this.setUnlocalizedName("vehicle_waypoint");
        this.setRegistryName("vehicle_waypoint");
        this.setMaxStackSize(1);
        this.setCreativeTab(CreativeTabs.TOOLS);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) {
            openGui(player);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @SideOnly(Side.CLIENT)
    private void openGui(EntityPlayer player) {
        BlockPos initial;
        RayTraceResult trace = Minecraft.getMinecraft().objectMouseOver;
        if (trace != null && trace.typeOfHit == RayTraceResult.Type.BLOCK && trace.getBlockPos() != null) {
            initial = trace.getBlockPos();
        } else {
            initial = new BlockPos(player);
        }
        Minecraft.getMinecraft().displayGuiScreen(new GuiVehicleWaypoint(initial));
    }
}
