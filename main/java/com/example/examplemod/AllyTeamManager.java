package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * [集団AI・チーム連携] 同じ持ち主の味方が近く（24ブロック以内）にいると、自動でチームを組み、
 * 役割を分担する。
 *
 * <ul>
 *   <li><b>伐採チーム</b>（斧の仕事の味方が2体以上）: 1体目=伐採、2体目=回収係
 *       （伐採役が集めた物と落ちている物を受け取り、近くのチェスト→無ければ持ち主へ運ぶ）、
 *       3体目=護衛（周りの敵を見張って撃つ）</li>
 *   <li><b>掘削チーム</b>（ツルハシの仕事の味方が2体以上）: 先頭=掘削、2体目=支援
 *       （掘られたトンネルに松明を置き、壁・天井・床の穴や砂利・水を丸石でふさぐ）、
 *       3体目=レール敷設、4体目=護衛</li>
 *   <li>仕事の無い（武器だけの）味方は、チームの護衛になる</li>
 * </ul>
 * <p>役割は40Tickごとに見直され、メンバーが減れば自動で作業役へ戻る。
 * 編成が変わった時は持ち主のチャットへ通知する。</p>
 */
public final class AllyTeamManager {

    public enum Role {
        WORKER("作業"),
        COLLECTOR("回収係"),
        GUARD("護衛"),
        TUNNEL_SUPPORT("トンネル支援（松明・壁補強）"),
        RAIL_LAYER("レール敷設");

        public final String label;

        Role(String label) {
            this.label = label;
        }
    }

    private static final class Info {
        final Role role;
        final EntityFriendlyCreature leader;
        final long until;

        Info(Role role, EntityFriendlyCreature leader, long until) {
            this.role = role;
            this.leader = leader;
            this.until = until;
        }
    }

    /** 掘削役が掘り進んだ階段の位置（支援・レール役がたどる）。 */
    public static final class TunnelLog {
        public final List<BlockPos> steps = new ArrayList<BlockPos>();
        /** 先頭から捨てた数（絶対インデックス = removed + i）。 */
        public int removed;

        public int end() {
            return removed + steps.size();
        }

        public BlockPos at(int absolute) {
            int i = absolute - removed;
            return i >= 0 && i < steps.size() ? steps.get(i) : null;
        }
    }

    private static final double TEAM_RANGE = 24.0;
    private static final int MAX_LOG = 512;

    private static final Map<EntityFriendlyCreature, Info> ROLES = new WeakHashMap<EntityFriendlyCreature, Info>();
    private static final Map<EntityFriendlyCreature, TunnelLog> TUNNELS = new WeakHashMap<EntityFriendlyCreature, TunnelLog>();
    private static final Map<EntityFriendlyCreature, String> LAST_ANNOUNCE = new WeakHashMap<EntityFriendlyCreature, String>();

    private AllyTeamManager() {
    }

    public static synchronized Role roleOf(EntityFriendlyCreature e) {
        Info i = ROLES.get(e);
        if (i == null || i.until < e.world.getTotalWorldTime()) {
            return Role.WORKER;
        }
        return i.role;
    }

    public static synchronized EntityFriendlyCreature leaderOf(EntityFriendlyCreature e) {
        Info i = ROLES.get(e);
        if (i == null || i.until < e.world.getTotalWorldTime() || i.leader == null || !i.leader.isEntityAlive()) {
            return null;
        }
        return i.leader;
    }

    public static synchronized void logTunnelStep(EntityFriendlyCreature digger, BlockPos pos) {
        TunnelLog log = TUNNELS.get(digger);
        if (log == null) {
            log = new TunnelLog();
            TUNNELS.put(digger, log);
        }
        if (!log.steps.isEmpty() && log.steps.get(log.steps.size() - 1).equals(pos)) {
            return;
        }
        log.steps.add(pos.toImmutable());
        while (log.steps.size() > MAX_LOG) {
            log.steps.remove(0);
            log.removed++;
        }
    }

    public static synchronized TunnelLog tunnelLog(EntityFriendlyCreature digger) {
        return TUNNELS.get(digger);
    }

    public static boolean hasWeapon(EntityFriendlyCreature e) {
        if (AllyToolManager.isRangedGun(e.getHeldItemMainhand())
                || e.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemSword) {
            return true;
        }
        net.minecraft.inventory.InventoryBasic inv = AllyInventory.get(e);
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            net.minecraft.item.ItemStack s = inv.getStackInSlot(i);
            if (AllyToolManager.isRangedGun(s) || s.getItem() instanceof net.minecraft.item.ItemSword) {
                return true;
            }
        }
        return false;
    }

    /** 40Tickごとに各味方から呼ばれ、周りの味方と一緒に役割を決め直す。 */
    public static synchronized void update(EntityFriendlyCreature self, EngenderGatheringBridge bridge) {
        UUID owner = AllyAreas.ownerId(self);
        long until = self.world.getTotalWorldTime() + 100;
        if (owner == null) {
            ROLES.remove(self);
            return;
        }
        List<EntityFriendlyCreature> team = new ArrayList<EntityFriendlyCreature>();
        for (EntityFriendlyCreature other : self.world.getEntitiesWithinAABB(EntityFriendlyCreature.class,
                self.getEntityBoundingBox().grow(TEAM_RANGE))) {
            if (other.isEntityAlive() && !other.isRiding() && owner.equals(AllyAreas.ownerId(other))) {
                team.add(other);
            }
        }
        if (!team.contains(self)) {
            team.add(self);
        }
        Collections.sort(team, new Comparator<EntityFriendlyCreature>() {
            @Override
            public int compare(EntityFriendlyCreature a, EntityFriendlyCreature b) {
                return Integer.compare(a.getEntityId(), b.getEntityId());
            }
        });
        // 編成を決めるのは、見えている仲間の中で一番IDの小さい味方（まとめ役）だけ。
        // 各自がばらばらに決めると、見えている範囲の違いで役割が毎回入れ替わってしまう。
        if (team.get(0) != self) {
            return;
        }

        List<EntityFriendlyCreature> axe = new ArrayList<EntityFriendlyCreature>();
        List<EntityFriendlyCreature> pick = new ArrayList<EntityFriendlyCreature>();
        List<EntityFriendlyCreature> idleArmed = new ArrayList<EntityFriendlyCreature>();
        for (EntityFriendlyCreature m : team) {
            String job = bridge.getJob(m);
            if ("axe".equals(job)) {
                axe.add(m);
            } else if ("pickaxe".equals(job)) {
                pick.add(m);
            } else if (job.isEmpty() && hasWeapon(m)) {
                idleArmed.add(m);
            }
        }
        for (EntityFriendlyCreature m : team) {
            ROLES.put(m, new Info(Role.WORKER, null, until));
        }
        boolean guardTaken = !idleArmed.isEmpty();
        EntityFriendlyCreature mainLeader = null;
        if (axe.size() >= 2) {
            EntityFriendlyCreature lead = axe.get(0);
            mainLeader = lead;
            ROLES.put(axe.get(1), new Info(Role.COLLECTOR, lead, until));
            if (axe.size() >= 3 && !guardTaken) {
                ROLES.put(axe.get(2), new Info(Role.GUARD, lead, until));
                guardTaken = true;
            }
        }
        if (pick.size() >= 2) {
            EntityFriendlyCreature lead = pick.get(0);
            if (mainLeader == null) {
                mainLeader = lead;
            }
            ROLES.put(pick.get(1), new Info(Role.TUNNEL_SUPPORT, lead, until));
            if (pick.size() >= 3) {
                ROLES.put(pick.get(2), new Info(Role.RAIL_LAYER, lead, until));
            }
            if (pick.size() >= 4 && !guardTaken) {
                ROLES.put(pick.get(3), new Info(Role.GUARD, lead, until));
                guardTaken = true;
            }
        }
        if (mainLeader == null) {
            for (EntityFriendlyCreature m : team) {
                if (!bridge.getJob(m).isEmpty()) {
                    mainLeader = m;
                    break;
                }
            }
        }
        if (mainLeader != null) {
            for (EntityFriendlyCreature g : idleArmed) {
                ROLES.put(g, new Info(Role.GUARD, mainLeader, until));
            }
        }
        announce(team, bridge);
    }

    private static void announce(List<EntityFriendlyCreature> team, EngenderGatheringBridge bridge) {
        if (team.size() < 2) {
            return;
        }
        StringBuilder sig = new StringBuilder();
        int special = 0;
        for (EntityFriendlyCreature m : team) {
            Info i = ROLES.get(m);
            Role r = i == null ? Role.WORKER : i.role;
            if (r != Role.WORKER) {
                special++;
            }
            if (sig.length() > 0) {
                sig.append("、");
            }
            String job = bridge.getJob(m);
            AllyProjectBoard.Project pj = AllyProjectBoard.get(AllyAreas.ownerId(m));
            String label = pj != null && pj.crafter == m ? "精錬・クラフト担当"
                    : r == Role.WORKER ? ("axe".equals(job) ? "伐採" : "pickaxe".equals(job) ? "掘削"
                    : "shovel".equals(job) ? "整地・建築" : "待機") : r.label;
            sig.append(m.getName()).append("=").append(label);
        }
        if (special == 0) {
            return;
        }
        EntityFriendlyCreature first = team.get(0);
        String text = sig.toString();
        if (text.equals(LAST_ANNOUNCE.get(first))) {
            return;
        }
        LAST_ANNOUNCE.put(first, text);
        EntityPlayer owner = AllyAIUtil.resolveOwnerPlayer(first, bridge);
        if (owner != null) {
            owner.sendMessage(new TextComponentString(TextFormatting.AQUA + "[チーム編成] " + text));
        }
    }
}
