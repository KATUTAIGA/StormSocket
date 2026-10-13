package com.example.examplemod;

import net.minecraft.init.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = ExampleMod.MODID, name = ExampleMod.NAME, version = ExampleMod.VERSION)
public class ExampleMod {
    public static final String MODID = "examplemod";
    public static final String NAME = "Example Mod";
    public static final String VERSION = "1.0";

    private static Logger logger;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        ModNetwork.init();
    }

    /** [/allycraft] 味方に「これを作って」と頼むコマンド。 */
    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandAllyCraft());
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        TargetRegistry.clear();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        logger.info("DIRT BLOCK >> {}", Blocks.DIRT.getRegistryName());

        MinecraftForge.EVENT_BUS.register(new EngenderTechgunsBridge());
	MinecraftForge.EVENT_BUS.register(new EngenderFlanBridge());
	MinecraftForge.EVENT_BUS.register(new EngenderVehicleBridge());
	EngenderGatheringBridge gatheringBridge = new EngenderGatheringBridge();
	MinecraftForge.EVENT_BUS.register(gatheringBridge);
	// [アイテム受け渡し] the actual "hand an item to the ally" system --
	// nothing else in this mod or the base Engender mod implements it.
	MinecraftForge.EVENT_BUS.register(new EngenderItemHandoffBridge(gatheringBridge));
	// [Immersive Vehicles] 味方モブが車両へ最優先で乗り込み、エンジンを掛けて
	// 敵に突撃したり、指定した目的地へ向かったりできるようにする。
	MinecraftForge.EVENT_BUS.register(new EngenderImmersiveVehicleBridge());
	// [GregTech Quantum Transition クエスト自動達成] 未達成のFTB Questsアイテム
	// タスクを、採掘とバニラクラフトが届く範囲で自律的に集めて納品する。
	MinecraftForge.EVENT_BUS.register(new EngenderQuestSupplyBridge(gatheringBridge));
    }
}
