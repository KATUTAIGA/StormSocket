package com.example.examplemod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/**
 * [タスクの重複防止] 全味方共通の「ターゲット管理マネージャー」。
 *
 * <p>ある味方が狙っている鉱石・原木・クエスト用の採掘ブロックを「予約」し、
 * 他の味方が同じブロックへ二重に向かわないようにする。予約には期限があり、
 * 予約した味方が死んだ・諦めた・ワールドを出た時も自然に解放される。</p>
 */
public final class TargetRegistry {

    private static final class Claim {
        final UUID owner;
        long until;

        Claim(UUID owner, long until) {
            this.owner = owner;
            this.until = until;
        }
    }

    private static final Map<String, Claim> CLAIMS = new HashMap<String, Claim>();
    private static long lastCleanup;

    private TargetRegistry() {
    }

    private static String key(Entity e, BlockPos pos) {
        return e.dimension + ":" + pos.toLong();
    }

    private static long now(Entity e) {
        return e.world.getTotalWorldTime();
    }

    /** 予約する（自分の予約なら期限を延ばす）。他の味方が予約中なら false。 */
    public static synchronized boolean claim(Entity e, BlockPos pos, long ttlTicks) {
        long now = now(e);
        cleanup(now);
        String k = key(e, pos);
        Claim c = CLAIMS.get(k);
        if (c != null && c.until > now && !c.owner.equals(e.getUniqueID())) {
            return false;
        }
        if (c == null || !c.owner.equals(e.getUniqueID())) {
            CLAIMS.put(k, new Claim(e.getUniqueID(), now + ttlTicks));
        } else {
            c.until = now + ttlTicks;
        }
        return true;
    }

    /** 他の味方が（期限内で）予約しているか。 */
    public static synchronized boolean isClaimedByOther(Entity e, BlockPos pos) {
        Claim c = CLAIMS.get(key(e, pos));
        return c != null && c.until > now(e) && !c.owner.equals(e.getUniqueID());
    }

    /** サーバー停止時（別のワールドを開き直す時に古い予約が残らないように）。 */
    public static synchronized void clear() {
        CLAIMS.clear();
        lastCleanup = 0;
    }

    public static synchronized void release(Entity e, BlockPos pos) {
        String k = key(e, pos);
        Claim c = CLAIMS.get(k);
        if (c != null && c.owner.equals(e.getUniqueID())) {
            CLAIMS.remove(k);
        }
    }

    public static synchronized void releaseAll(Entity e) {
        UUID id = e.getUniqueID();
        Iterator<Claim> it = CLAIMS.values().iterator();
        while (it.hasNext()) {
            if (it.next().owner.equals(id)) {
                it.remove();
            }
        }
    }

    private static void cleanup(long now) {
        if (now - lastCleanup < 200 && CLAIMS.size() < 8192) {
            return;
        }
        lastCleanup = now;
        Iterator<Claim> it = CLAIMS.values().iterator();
        while (it.hasNext()) {
            if (it.next().until <= now) {
                it.remove();
            }
        }
    }
}
