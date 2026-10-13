package com.example.examplemod;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * GregTech Quantum Transition (FTB Quests) 対応: 全ての {@code EntityFriendlyCreature}
 * へ、無条件で {@link EntityAIQuestSupplyAlly} を追加する。手に道具を持っているか
 * どうかに関係なく常時バックグラウンドで動作し、未達成のクエストが要求する
 * アイテムを（採掘・バニラクラフトで届く範囲で）自律的に集めて納品する。
 *
 * <p>優先度は{@value #QUEST_SUPPLY_PRIORITY}。生存AI(1)・武器AI(2)には必ず譲り、
 * 採取/建築AI(4)よりは上。以前は6で、採掘に道具が必要なのに道具を持たせると
 * 採取AIが常に走って一度も出番が来ない、という矛盾があった。クエストAIは実際に
 * 解決可能なクエストを見つけた時しか制御を取らないので、採取を奪うのはその間だけ。</p>
 */
public class EngenderQuestSupplyBridge {

    private static final int QUEST_SUPPLY_PRIORITY = 3;

    /** 道具の受け渡し等で既に判明している「持ち主」を流用し、Forgeイベントの文脈やクエストデータ解決に使う。 */
    private final EngenderGatheringBridge gatheringBridge;

    public EngenderQuestSupplyBridge(EngenderGatheringBridge gatheringBridge) {
        this.gatheringBridge = gatheringBridge;
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (!(event.getEntity() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntity();

        boolean hasTask = entity.tasks.taskEntries.stream()
                .anyMatch(e -> e.action instanceof EntityAIQuestSupplyAlly);
        if (!hasTask) {
            EntityAIBase task = new EntityAIQuestSupplyAlly(entity, this.gatheringBridge);
            entity.tasks.addTask(QUEST_SUPPLY_PRIORITY, task);
        }
    }
}
