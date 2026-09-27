package com.example.examplemod;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * {@link EngenderVehicleBridge}（Flan's Mod 用）と同じパターンで、ワールドに出現した
 * {@code EntityFriendlyCreature} に Immersive Vehicles 用の2つのAIタスクを常時追加する。
 *
 * <ul>
 *   <li>{@link EntityAIMountImmersiveVehicle}（優先度 {@value #MOUNT_PRIORITY}）:
 *       最優先。空いている車両の運転席があれば真っ先に乗り込む。</li>
 *   <li>{@link EntityAIDriveImmersiveVehicle}（優先度 {@value #DRIVE_PRIORITY}）:
 *       乗車後、エンジンを掛けて「敵がいれば突撃、いなければ目的地へ移動、
 *       どちらも無ければ待機」を行う。</li>
 * </ul>
 *
 * どちらも移動+視線のミューテックス(3)を占有するため、同時に両方が実行されることはない
 * （乗車していない間は Mount 側が、乗車済みなら Drive 側だけが shouldExecute() を通す）。
 *
 * 導入手順:
 *   1. このファイルと EntityAIMountImmersiveVehicle.java / EntityAIDriveImmersiveVehicle.java を
 *      com.example.examplemod パッケージに追加。
 *   2. ExampleMod#init() の中で、他のブリッジ登録と同様に以下を追加:
 *        MinecraftForge.EVENT_BUS.register(new EngenderImmersiveVehicleBridge());
 */
public class EngenderImmersiveVehicleBridge {

    /** 最優先: 空いている車両を見つけたら真っ先に乗り込む。数値が小さいほど優先。 */
    private static final int MOUNT_PRIORITY = 0;
    /** 乗車後の運転（エンジン始動・突撃・目的地移動・待機）。 */
    private static final int DRIVE_PRIORITY = 1;

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (!(event.getEntity() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntity();

        boolean hasMountTask = entity.tasks.taskEntries.stream()
                .anyMatch(e -> e.action instanceof EntityAIMountImmersiveVehicle);
        if (!hasMountTask) {
            entity.tasks.addTask(MOUNT_PRIORITY, new EntityAIMountImmersiveVehicle(entity));
        }

        boolean hasDriveTask = entity.tasks.taskEntries.stream()
                .anyMatch(e -> e.action instanceof EntityAIDriveImmersiveVehicle);
        if (!hasDriveTask) {
            entity.tasks.addTask(DRIVE_PRIORITY, new EntityAIDriveImmersiveVehicle(entity));
        }
    }
}
