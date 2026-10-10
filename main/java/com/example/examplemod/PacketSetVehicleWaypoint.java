package com.example.examplemod;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.Entity;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * {@link GuiVehicleWaypoint} で入力された座標をクライアントからサーバーへ送るパケット。
 * サーバー側では、ワールド内の全ての {@code EntityFriendlyCreature} が持つ
 * {@link EntityAIDriveImmersiveVehicle} タスクへ目的地を反映する。
 *
 * <p>登録は {@link ModNetwork#init()} で行う。
 */
public class PacketSetVehicleWaypoint implements IMessage {

    /** 全ての味方モブに目的地を配る際の既定の到達半径（ブロック）。 */
    private static final double DEFAULT_ARRIVAL_RADIUS = 4.0D;

    private int x;
    private int y;
    private int z;
    private double radius;

    /** リフレクションでインスタンス化されるためのデフォルトコンストラクタ（IMessage仕様）。 */
    public PacketSetVehicleWaypoint() {
    }

    public PacketSetVehicleWaypoint(BlockPos pos, double radius) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.radius = radius;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(radius);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        radius = buf.readDouble();
    }

    /** サーバー側ハンドラ。ネットワークスレッドで呼ばれるため、実処理はメインスレッドへ回す。 */
    public static class Handler implements IMessageHandler<PacketSetVehicleWaypoint, IMessage> {
        @Override
        public IMessage onMessage(PacketSetVehicleWaypoint message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            if (player == null) {
                return null;
            }
            int x = message.x;
            int y = message.y;
            int z = message.z;
            double radius = message.radius > 0 ? message.radius : DEFAULT_ARRIVAL_RADIUS;

            player.getServerWorld().addScheduledTask(() -> applyWaypoint(player, new BlockPos(x, y, z), radius));
            return null;
        }

        /** 持ち主が未設定の味方でも、この距離以内なら指示を受け付ける。 */
        private static final double UNOWNED_ALLY_RANGE = 64.0D;

        private static void applyWaypoint(EntityPlayerMP player, BlockPos pos, double radius) {
            World world = player.world;
            if (world == null) {
                return;
            }
            for (Entity entity : new java.util.ArrayList<Entity>(world.loadedEntityList)) {
                if (!(entity instanceof EntityFriendlyCreature)) {
                    continue;
                }
                EntityFriendlyCreature ally = (EntityFriendlyCreature) entity;
                // Only this player's allies (plus ownerless ones nearby). It
                // used to redirect every ally in the dimension, including
                // other players' creatures.
                boolean mine = ally.isOwner(player);
                boolean nearbyUnowned = ally.isWild() && ally.getDistanceSq(player) <= UNOWNED_ALLY_RANGE * UNOWNED_ALLY_RANGE;
                if (!mine && !nearbyUnowned) {
                    continue;
                }
                ally.tasks.taskEntries.stream()
                        .filter(e -> e.action instanceof EntityAIDriveImmersiveVehicle)
                        .map(e -> (EntityAIDriveImmersiveVehicle) e.action)
                        .forEach(ai -> ai.setDestination(pos, radius));
            }
        }
    }
}
