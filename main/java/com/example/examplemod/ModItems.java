package com.example.examplemod;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * このMod独自アイテムの登録。{@code @Mod.EventBusSubscriber} により
 * {@code MinecraftForge.EVENT_BUS} へ自動登録されるため、{@code ExampleMod} 側での
 * 追加登録は不要（{@code new ModItems()} 等は書かなくてよい）。
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID)
public class ModItems {

    public static final ItemVehicleWaypoint VEHICLE_WAYPOINT = new ItemVehicleWaypoint();
    /** [エリア指定] 採掘/伐採/立ち入り禁止エリアを2点で指定するマーカー。 */
    public static final ItemAreaMarker AREA_MARKER = new ItemAreaMarker();

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(VEHICLE_WAYPOINT);
        event.getRegistry().register(AREA_MARKER);
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        // 注意: ModelRegistryEvent の発火時点ではまだ Minecraft.getRenderItem() が
        // 初期化されていない（特にOptiFine環境ではNPEになる）ため、
        // Minecraft.getMinecraft().getRenderItem().getItemModelMesher().register(...) は使わない。
        // ModelLoader 経由での登録が正しい/安全な方法。
        ModelLoader.setCustomModelResourceLocation(VEHICLE_WAYPOINT, 0,
                new ModelResourceLocation(ExampleMod.MODID + ":vehicle_waypoint", "inventory"));
        ModelLoader.setCustomModelResourceLocation(AREA_MARKER, 0,
                new ModelResourceLocation(ExampleMod.MODID + ":ally_area_marker", "inventory"));
    }
}
