package com.example.examplemod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/**
 * Standalone addon mod: lets Engender's EntityFriendlyCreature allies use
 * HBM's NTM Reforged guns for ranged combat, the same way
 * EngenderTechgunsBridge does for Techguns.
 *
 * Requires: Engender (for net.minecraft.entity.helpful.EntityFriendlyCreature)
 *           and hbm (HBM's NTM Reforged) both loaded.
 *
 * Adjust "dependencies" below to match your real mod ids / versions.
 */
@Mod(modid = EngenderHBMAddon.MODID, name = EngenderHBMAddon.NAME, version = EngenderHBMAddon.VERSION,
        dependencies = "required-after:hbm;after:examplemod")
public class EngenderHBMAddon {

    public static final String MODID = "engenderhbmbridge";
    public static final String NAME = "Engender HBM Bridge";
    public static final String VERSION = "1.0";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new EngenderHBMBridge());
    }
}
