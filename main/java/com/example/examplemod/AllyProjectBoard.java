package com.example.examplemod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLog;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * [タスクの自動並列分散] 持ち主ごとの「クラフトプロジェクト」掲示板。
 *
 * <p>クエスト/依頼を担当するクラフト役（{@link EntityAIQuestSupplyAlly}）が、作る物の
 * レシピツリーを末端（掘る必要のある原木・鉱石・石など）まで展開して、ここへ
 * 「採取の発注」を出す。斧の仕事の味方は原木、ツルハシの仕事の味方は鉱石・石の発注を
 * 優先して掘り、クラフト役へ直接届ける。クラフト役はその間、精錬・加工・組立を進める。</p>
 */
public final class AllyProjectBoard {

    /** 1件の採取発注。 */
    public static final class Order {
        public final Block block;
        public final Item dropItem;
        public final int dropMeta;
        public int remaining;
        public final boolean forAxe;

        Order(Block block, Item dropItem, int dropMeta, int remaining, boolean forAxe) {
            this.block = block;
            this.dropItem = dropItem;
            this.dropMeta = dropMeta;
            this.remaining = remaining;
            this.forAxe = forAxe;
        }

        public boolean matchesDrop(ItemStack s) {
            return !s.isEmpty() && s.getItem() == dropItem && (dropMeta < 0 || s.getMetadata() == dropMeta);
        }
    }

    /** 1件のプロジェクト（持ち主ごとに同時に1件）。 */
    public static final class Project {
        public final String title;
        public final EntityFriendlyCreature crafter;
        public final List<Order> orders = new ArrayList<Order>();
        public final long started;

        Project(String title, EntityFriendlyCreature crafter, long started) {
            this.title = title;
            this.crafter = crafter;
            this.started = started;
        }
    }

    private static final Map<UUID, Project> PROJECTS = new HashMap<UUID, Project>();

    private AllyProjectBoard() {
    }

    /** 発注する。別のクラフト役のプロジェクトが進行中なら上書きせず false。 */
    public static synchronized boolean start(UUID owner, String title, EntityFriendlyCreature crafter, Map<Block, int[]> needs) {
        if (owner == null) {
            return false;
        }
        Project existing = get(owner);
        if (existing != null && existing.crafter != crafter) {
            return false;
        }
        Project p = new Project(title, crafter, crafter.world.getTotalWorldTime());
        for (Map.Entry<Block, int[]> e : needs.entrySet()) {
            Block b = e.getKey();
            int[] v = e.getValue(); // {count, dropItemId, dropMeta}
            Item drop = Item.getItemById(v[1]);
            if (drop == null || v[0] <= 0) {
                continue;
            }
            p.orders.add(new Order(b, drop, v[2], v[0], b instanceof BlockLog || isLogLike(b)));
        }
        PROJECTS.put(owner, p);
        return true;
    }

    private static boolean isLogLike(Block b) {
        try {
            ItemStack s = new ItemStack(b);
            for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(s)) {
                if (net.minecraftforge.oredict.OreDictionary.getOreName(id).startsWith("log")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 無視
        }
        return false;
    }

    public static synchronized void finish(UUID owner, EntityFriendlyCreature crafter) {
        Project p = PROJECTS.get(owner);
        if (p != null && p.crafter == crafter) {
            PROJECTS.remove(owner);
        }
    }

    /** 有効なプロジェクト（クラフト役が生きている）。無ければ null。 */
    public static synchronized Project get(UUID owner) {
        if (owner == null) {
            return null;
        }
        Project p = PROJECTS.get(owner);
        if (p != null && (!p.crafter.isEntityAlive() || p.crafter.world != null
                && p.crafter.world.getTotalWorldTime() - p.started > 48000)) {
            PROJECTS.remove(owner);
            return null;
        }
        return p;
    }

    /** この味方（斧/ツルハシ）が掘るべき発注のブロックか。 */
    public static synchronized boolean wants(UUID owner, IBlockState state, boolean axe) {
        Project p = get(owner);
        if (p == null) {
            return false;
        }
        for (Order o : p.orders) {
            if (o.remaining > 0 && o.forAxe == axe && o.block == state.getBlock()) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean hasOpenOrders(UUID owner, boolean axe) {
        Project p = get(owner);
        if (p == null) {
            return false;
        }
        for (Order o : p.orders) {
            if (o.remaining > 0 && o.forAxe == axe) {
                return true;
            }
        }
        return false;
    }

    /** 発注に合う物を何個持っているか（届けに行くかの判断用）。 */
    public static synchronized int orderedCount(UUID owner, List<ItemStack> carried) {
        Project p = get(owner);
        if (p == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : carried) {
            for (Order o : p.orders) {
                if (o.remaining > 0 && o.matchesDrop(s)) {
                    n += s.getCount();
                    break;
                }
            }
        }
        return n;
    }

    /** 持っている発注品をクラフト役の持ち物へ渡す。渡した個数。 */
    public static synchronized int deliver(UUID owner, List<ItemStack> carried) {
        Project p = get(owner);
        if (p == null) {
            return 0;
        }
        int total = 0;
        Iterator<ItemStack> it = carried.iterator();
        while (it.hasNext()) {
            ItemStack s = it.next();
            for (Order o : p.orders) {
                if (o.remaining <= 0 || !o.matchesDrop(s)) {
                    continue;
                }
                int before = s.getCount();
                ItemStack rest = AllyInventory.insert(p.crafter, s);
                int moved = before - rest.getCount();
                o.remaining -= moved;
                total += moved;
                s.setCount(rest.getCount());
                break;
            }
            if (s.isEmpty()) {
                it.remove();
            }
        }
        return total;
    }

    /** そのクラフト役自身の発注で、このブロックがまだ残っているか。 */
    public static synchronized boolean isOrderOpenFor(UUID owner, Block block, EntityFriendlyCreature crafter) {
        Project p = get(owner);
        return p != null && p.crafter == crafter && isOrderOpen(owner, block);
    }

    /** クラフト役: このブロックの発注がまだ残っていて、担当できる作業役がいるか。 */
    public static synchronized boolean isOrderOpen(UUID owner, Block block) {
        Project p = get(owner);
        if (p == null) {
            return false;
        }
        for (Order o : p.orders) {
            if (o.block == block && o.remaining > 0) {
                return true;
            }
        }
        return false;
    }

    public static synchronized String describe(UUID owner) {
        Project p = get(owner);
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Order o : p.orders) {
            if (o.remaining <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(new ItemStack(o.dropItem, 1, Math.max(0, o.dropMeta)).getDisplayName()).append(" x").append(o.remaining)
                    .append(o.forAxe ? "(伐採班)" : "(掘削班)");
        }
        return sb.toString();
    }
}
