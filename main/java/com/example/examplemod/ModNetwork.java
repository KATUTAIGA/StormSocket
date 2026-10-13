package com.example.examplemod;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * このMod用のネットワークチャンネル。{@code ExampleMod#preInit} から {@link #init()} を
 * 呼び出して登録する。
 */
public final class ModNetwork {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(ExampleMod.MODID + "_veh");

    private ModNetwork() {
    }

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(PacketSetVehicleWaypoint.Handler.class, PacketSetVehicleWaypoint.class, id++, Side.SERVER);
    }
}
