package com.example.examplemod;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.ItemStack;

/**
 * [クラフトの動的解読] {@code /allycraft} で頼まれた「これを作って」の依頼。
 * 味方のクエストAIが、レシピツリーを逆引きして手順を自動で組み立てて実行する。
 */
public final class AllyCraftRequests {

    public static final class Request {
        public final ItemStack[] accepted;
        public final int count;
        public final UUID requester;
        public final String name;

        public Request(ItemStack target, int count, UUID requester) {
            this.accepted = new ItemStack[] { target.copy() };
            this.count = count;
            this.requester = requester;
            this.name = target.getDisplayName();
        }
    }

    private static final Map<EntityFriendlyCreature, Request> REQUESTS = new WeakHashMap<EntityFriendlyCreature, Request>();

    private AllyCraftRequests() {
    }

    public static synchronized void put(EntityFriendlyCreature e, Request r) {
        REQUESTS.put(e, r);
    }

    public static synchronized Request get(EntityFriendlyCreature e) {
        return REQUESTS.get(e);
    }

    public static synchronized void remove(EntityFriendlyCreature e) {
        REQUESTS.remove(e);
    }
}
