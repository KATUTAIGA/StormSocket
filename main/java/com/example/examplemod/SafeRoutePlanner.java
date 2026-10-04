package com.example.examplemod;

import java.util.List;

import net.minecraft.block.material.Material;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * [動的なコスト・経路判定] 移動ルートにリスク評価を入れて、安全なルートを選ぶ。
 *
 * <p>バニラの A* が出した最短ルートに加え、途中で左右へ 8/16 ブロック迂回するルートを
 * 候補として作り、各ルートを「長さ + 重み × 危険度」で比べて一番安い物を選ぶ。</p>
 * <ul>
 *   <li>危険度: 溶岩・火が隣接するマス、2ブロック超の落下、近くの敵（6ブロック以内）</li>
 *   <li>重み: 体力が減っているほど、防具が弱いほど大きくなる
 *       （元気で重装備なら最短の危険地帯を突っ切り、弱っていれば遠回りでも安全な平地を選ぶ）</li>
 *   <li>さらに A* 自体のノードコスト（火・サボテン・水の近く）も体力に応じて上げる</li>
 * </ul>
 */
public final class SafeRoutePlanner {

    private SafeRoutePlanner() {
    }

    /** 危険度の重み。体力が減るほど・防具が弱いほど大きい。 */
    static double riskWeight(EntityFriendlyCreature e) {
        double hp = e.getHealth() / Math.max(1.0F, e.getMaxHealth());
        double armor = Math.min(20, e.getTotalArmorValue()) / 20.0;
        return 0.5 + 4.0 * (1.0 - hp) + 1.5 * (1.0 - armor);
    }

    private static void applyNodePriorities(EntityFriendlyCreature e) {
        double hp = e.getHealth() / Math.max(1.0F, e.getMaxHealth());
        float extra = (float) (1.0 - hp);
        e.setPathPriority(PathNodeType.DANGER_FIRE, 8.0F + 24.0F * extra);
        e.setPathPriority(PathNodeType.DAMAGE_FIRE, 16.0F + 32.0F * extra);
        e.setPathPriority(PathNodeType.DANGER_CACTUS, 8.0F + 16.0F * extra);
        e.setPathPriority(PathNodeType.DAMAGE_CACTUS, 16.0F + 16.0F * extra);
        e.setPathPriority(PathNodeType.DANGER_OTHER, 8.0F + 16.0F * extra);
        e.setPathPriority(PathNodeType.DAMAGE_OTHER, 16.0F + 16.0F * extra);
    }

    /**
     * (x,y,z) へのルートを選んで返す（迂回ルートの時は、その経由地までのルート）。
     * 行けなければ null。
     */
    public static Path plan(EntityFriendlyCreature e, double x, double y, double z) {
        applyNodePriorities(e);
        PathNavigate nav = e.getNavigator();
        Path direct = nav.getPathToXYZ(x, y, z);
        if (direct == null) {
            return null;
        }
        World world = e.world;
        List<EntityMob> mobs = world.getEntitiesWithinAABB(EntityMob.class, e.getEntityBoundingBox().grow(48.0D));
        double w = riskWeight(e);
        double directRisk = pathRisk(world, direct, mobs);
        if (directRisk < 2.0) {
            return direct; // ほぼ安全 -- 最短で行く
        }
        double bestCost = direct.getCurrentPathLength() + w * directRisk;
        Path best = direct;

        double sx = e.posX;
        double sz = e.posZ;
        double dx = x - sx;
        double dz = z - sz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 6.0) {
            return direct;
        }
        double px = -dz / len;
        double pz = dx / len;
        double mx = sx + dx * 0.5;
        double mz = sz + dz * 0.5;
        for (int side = -1; side <= 1; side += 2) {
            for (int off = 8; off <= 16; off += 8) {
                if (len < 2 * off) {
                    continue; // 近すぎる目標への大回りは、かえって遠ざかる
                }
                int wx = MathHelper.floor(mx + px * off * side);
                int wz = MathHelper.floor(mz + pz * off * side);
                BlockPos top = world.getHeight(new BlockPos(wx, 0, wz));
                if (Math.abs(top.getY() - e.posY) > 12) {
                    continue; // 崖の上/下へは迂回しない
                }
                Path leg = nav.getPathToPos(top);
                if (leg == null) {
                    continue;
                }
                PathPoint end = leg.getFinalPathPoint();
                if (end == null || end.distanceTo(new PathPoint(wx, top.getY(), wz)) > 3.0F) {
                    continue; // 経由地にたどり着けない
                }
                double rest = Math.sqrt((x - wx) * (x - wx) + (z - wz) * (z - wz));
                double cost = leg.getCurrentPathLength() + w * pathRisk(world, leg, mobs)
                        + rest + w * lineRisk(world, top, new BlockPos(x, y, z), mobs);
                if (cost < bestCost * 0.85) {
                    bestCost = cost;
                    best = leg;
                }
            }
        }
        return best;
    }

    private static double pathRisk(World world, Path path, List<EntityMob> mobs) {
        double risk = 0;
        int prevY = Integer.MIN_VALUE;
        for (int i = 0; i < path.getCurrentPathLength(); i++) {
            PathPoint p = path.getPathPointFromIndex(i);
            risk += nodeRisk(world, new BlockPos(p.x, p.y, p.z), mobs);
            if (prevY != Integer.MIN_VALUE && prevY - p.y > 2) {
                risk += 2.0 * (prevY - p.y - 2);
            }
            prevY = p.y;
        }
        return risk;
    }

    /** 直線上を2ブロックおきに調べた危険度（経由地から先の見積もり）。 */
    private static double lineRisk(World world, BlockPos from, BlockPos to, List<EntityMob> mobs) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        int steps = (int) Math.min(40, Math.sqrt(dx * dx + dz * dz) / 2);
        double risk = 0;
        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            BlockPos p = world.getHeight(new BlockPos(from.getX() + dx * f, 0, from.getZ() + dz * f));
            risk += nodeRisk(world, p, mobs);
        }
        return risk;
    }

    private static double nodeRisk(World world, BlockPos p, List<EntityMob> mobs) {
        double risk = 0;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = -1; oy <= 0; oy++) {
                    Material m = world.getBlockState(p.add(ox, oy, oz)).getMaterial();
                    if (m == Material.LAVA || m == Material.FIRE) {
                        risk += 3.0;
                    }
                }
            }
        }
        for (EntityMob mob : mobs) {
            double d = mob.getDistanceSq(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d < 9.0) {
                risk += 4.0;
            } else if (d < 36.0) {
                risk += 1.5;
            }
        }
        return risk;
    }
}
