package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

/**
 * [エリア指定] マーカーアイテムで指定した「採掘エリア」「伐採エリア」「立ち入り禁止エリア」。
 *
 * <ul>
 *   <li>採掘/伐採エリア: 持ち主がそのエリアを1つでも持っていれば、その味方は
 *       エリアの中だけで鉱石/原木を探す（エリアの外の物には手を出さない）。</li>
 *   <li>立ち入り禁止エリア: 誰の味方であっても、その中のブロックは絶対に壊さず、置かない。
 *       拠点や建築物の誤採掘・誤伐採を防ぐ。</li>
 * </ul>
 * <p>ワールドのセーブデータに保存されるので、再起動しても残る。</p>
 */
public final class AllyAreas extends WorldSavedData {

    public static final String NAME = "examplemod_ally_areas";

    public enum Type {
        MINE("採掘エリア", 0.3F, 0.6F, 1.0F),
        CHOP("伐採エリア", 0.2F, 1.0F, 0.2F),
        FORBID("立ち入り禁止エリア", 1.0F, 0.1F, 0.1F);

        public final String label;
        public final float r;
        public final float g;
        public final float b;

        Type(String label, float r, float g, float b) {
            this.label = label;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    public static final class Area {
        public final UUID owner;
        public final int dim;
        public final Type type;
        public final BlockPos min;
        public final BlockPos max;

        public Area(UUID owner, int dim, Type type, BlockPos a, BlockPos b) {
            this.owner = owner;
            this.dim = dim;
            this.type = type;
            this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        public boolean contains(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX()
                    && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }

        public BlockPos center() {
            return new BlockPos((min.getX() + max.getX()) / 2, (min.getY() + max.getY()) / 2, (min.getZ() + max.getZ()) / 2);
        }

        public double distanceSqTo(double x, double y, double z) {
            double dx = Math.max(0, Math.max(min.getX() - x, x - (max.getX() + 1)));
            double dy = Math.max(0, Math.max(min.getY() - y, y - (max.getY() + 1)));
            double dz = Math.max(0, Math.max(min.getZ() - z, z - (max.getZ() + 1)));
            return dx * dx + dy * dy + dz * dz;
        }

        public int volume() {
            return (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        }
    }

    private final List<Area> areas = new ArrayList<Area>();

    public AllyAreas(String name) {
        super(name);
    }

    public AllyAreas() {
        super(NAME);
    }

    public static AllyAreas get(World world) {
        MapStorage storage = world.getMapStorage();
        AllyAreas data = (AllyAreas) storage.getOrLoadData(AllyAreas.class, NAME);
        if (data == null) {
            data = new AllyAreas(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    public List<Area> all() {
        return areas;
    }

    public void add(Area area) {
        areas.add(area);
        markDirty();
    }

    /** pos を含む、その持ち主のエリアを全部消す。消した数。 */
    public int removeAt(UUID owner, int dim, BlockPos pos) {
        int n = 0;
        for (int i = areas.size() - 1; i >= 0; i--) {
            Area a = areas.get(i);
            if (a.dim == dim && a.contains(pos) && a.owner.equals(owner)) {
                areas.remove(i);
                n++;
            }
        }
        if (n > 0) {
            markDirty();
        }
        return n;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        areas.clear();
        NBTTagList list = nbt.getTagList("Areas", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            try {
                Type type = Type.valueOf(t.getString("Type"));
                areas.add(new Area(t.getUniqueId("Owner"), t.getInteger("Dim"), type,
                        BlockPos.fromLong(t.getLong("Min")), BlockPos.fromLong(t.getLong("Max"))));
            } catch (Exception ignored) {
                // 壊れたエントリは捨てる
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Area a : areas) {
            NBTTagCompound t = new NBTTagCompound();
            t.setUniqueId("Owner", a.owner);
            t.setInteger("Dim", a.dim);
            t.setString("Type", a.type.name());
            t.setLong("Min", a.min.toLong());
            t.setLong("Max", a.max.toLong());
            list.appendTag(t);
        }
        nbt.setTag("Areas", list);
        return nbt;
    }

    // ------------------------------------------------------------------
    // 判定ヘルパー（AI から使う）
    // ------------------------------------------------------------------

    /** 立ち入り禁止エリアの中か（誰のエリアでも）。ここは壊さない・置かない。 */
    public static boolean isForbidden(World world, BlockPos pos) {
        if (world == null || world.isRemote) {
            return false;
        }
        int dim = world.provider.getDimension();
        for (Area a : get(world).areas) {
            if (a.type == Type.FORBID && a.dim == dim && a.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 味方の持ち主の UUID（オフラインでも分かる）。分からなければ null。 */
    public static UUID ownerId(EntityFriendlyCreature e) {
        try {
            UUID id = ((IEntityOwnable) e).getOwnerId();
            if (id != null) {
                return id;
            }
        } catch (Throwable ignored) {
            // フォールバックへ
        }
        EntityPlayer p = AllyAIUtil.resolveOwnerPlayer(e, null);
        return p == null ? null : p.getUniqueID();
    }

    /** この味方の持ち主が作った、指定種類の作業エリア。 */
    public static List<Area> workAreas(EntityFriendlyCreature e, Type type) {
        List<Area> out = new ArrayList<Area>();
        UUID owner = ownerId(e);
        if (owner == null) {
            return out;
        }
        int dim = e.world.provider.getDimension();
        for (Area a : get(e.world).areas) {
            if (a.type == type && a.dim == dim && a.owner.equals(owner)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * この味方が pos で type の作業をしてよいか。禁止エリアは常に不可。
     * 持ち主に type の作業エリアが1つも無ければ制限なし、あればその中だけ。
     */
    public static boolean allowed(EntityFriendlyCreature e, BlockPos pos, Type type, List<Area> workAreas) {
        if (isForbidden(e.world, pos)) {
            return false;
        }
        if (workAreas == null || workAreas.isEmpty()) {
            return true;
        }
        for (Area a : workAreas) {
            if (a.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 作業エリアのうち一番近いもの（無ければ null）。 */
    public static Area nearest(EntityFriendlyCreature e, List<Area> areas) {
        Area best = null;
        double bestD = Double.MAX_VALUE;
        for (Area a : areas) {
            double d = a.distanceSqTo(e.posX, e.posY, e.posZ);
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }
}
