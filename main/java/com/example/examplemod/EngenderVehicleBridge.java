package com.example.examplemod;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * EngenderFlanBridge / EngenderHBMBridge と同じパターンで、
 * ワールドに出現した EntityFriendlyCreature に
 * 「戦車の銃座に乗って攻撃する」AIタスクを常時追加する。
 *
 * 既存の武器AI(EntityAIAttackRangedFlanGun 等)とは別枠のタスクなので、
 * 「銃を持っている間は素手で戦い、近くに空いてる戦車があれば乗り込む」
 * という両方の挙動が共存できるように、優先度(数値)は他の攻撃AIより
 * 少し低くして「手持ち武器がなければ戦車を探す」くらいの重みにしています。
 * 優先度の数値は環境に合わせて調整してください。
 *
 * 導入手順:
 *   1. このファイルと EntityAIMountAndOperateVehicleGun.java を
 *      com.example.examplemod パッケージに追加。
 *   2. ExampleMod#init() の中で、他のブリッジ登録と同様に以下を追加:
 *        MinecraftForge.EVENT_BUS.register(new EngenderVehicleBridge());
 */
public class EngenderVehicleBridge {

    /** タスクリスト内での優先度。数値が小さいほど優先。 */
    private static final int TASK_PRIORITY = 3;

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (!(event.getEntity() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntity();

        // 既に同種のタスクを持っていないか簡易チェック(多重登録防止)
        boolean alreadyHas = entity.tasks.taskEntries.stream()
                .anyMatch(e -> e.action instanceof EntityAIMountAndOperateVehicleGun);
        if (!alreadyHas) {
            entity.tasks.addTask(TASK_PRIORITY, new EntityAIMountAndOperateVehicleGun(entity));
        }
    }
}
