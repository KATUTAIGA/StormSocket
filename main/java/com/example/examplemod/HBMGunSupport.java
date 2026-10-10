package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBase;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.handler.BulletConfiguration;
import com.hbm.handler.GunConfiguration;
import com.hbm.handler.GunConfigurationSedna;
import com.hbm.items.weapon.ItemGunBase;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.ItemGunBaseSedna;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * [HBMの銃が実弾を撃たない・全部同じ弾になる不具合の修正]
 *
 * <p>以前は銃の種類に関係なく、汎用の {@code EntityBullet} を固定ダメージで撃っていた
 * （＝どの銃でも同じ弾・弾薬も不要）。ここでは HBM 自身の弾道定義を使う:</p>
 * <ul>
 *   <li>旧式銃（{@link ItemGunBase}）: {@code mainConfig.config} の弾設定IDから
 *       {@link BulletConfiguration} を取り、{@link EntityBulletBase}（その銃の本物の弾・
 *       散弾数・弾速・ダメージ）を撃つ。</li>
 *   <li>新式銃（{@link ItemGunBaseNT}）: レシーバー → マガジンが受け付ける
 *       {@link BulletConfig} から {@link EntityBulletBaseMK4} を撃つ。</li>
 * </ul>
 * <p>いずれも弾設定が指す「実際の弾薬アイテム」を味方の弾薬ポーチ
 * （{@link AllyAmmoStorage}）から消費し、マガジン容量ぶん装填・リロードする。
 * 弾薬が無ければ撃たない。</p>
 */
final class HBMGunSupport {

    private static final String KEY_ROUNDS = "EngenderRounds";
    private static final String KEY_TYPE = "EngenderAmmoType";

    /** fire() の結果: 次に撃てるまでの待ちTick。NO_AMMO なら弾切れ。 */
    static final int NO_AMMO = -1;

    private HBMGunSupport() {
    }

    // ------------------------------------------------------------------
    // 銃ごとの「弾の種類」一覧
    // ------------------------------------------------------------------

    /** 1種類の弾: 必要な弾薬アイテム、1アイテムあたりの装填数、撃ち方。 */
    private static final class AmmoKind {
        final ItemStack ammoItem;
        final int roundsPerItem;
        final int oldConfigId;          // 旧式: 弾設定ID（新式は -1）
        final BulletConfig newConfig;   // 新式

        AmmoKind(ItemStack ammoItem, int roundsPerItem, int oldConfigId, BulletConfig newConfig) {
            this.ammoItem = ammoItem;
            this.roundsPerItem = Math.max(1, roundsPerItem);
            this.oldConfigId = oldConfigId;
            this.newConfig = newConfig;
        }
    }

    /**
     * 旧式の銃設定。{@link ItemGunBase}（GunConfiguration）と、別系統の
     * {@link ItemGunBaseSedna}（GunConfigurationSedna）の両方をここで同じ形にする。
     * [HBMの一部の銃が装備・使用できない不具合] Sedna 系の銃は以前どちらにも該当せず、
     * 銃として認識されていなかった。
     */
    private static final class OldCfg {
        List<Integer> config;
        int ammoCap;
        int rateOfFire;
        int reloadDuration;
        net.minecraft.util.SoundEvent firingSound;
        float firingPitch;
    }

    private static OldCfg oldCfg(ItemStack gun) {
        OldCfg o = null;
        if (gun.getItem() instanceof ItemGunBase) {
            GunConfiguration c = ((ItemGunBase) gun.getItem()).mainConfig;
            if (c != null) {
                o = new OldCfg();
                o.config = c.config;
                o.ammoCap = c.ammoCap;
                o.rateOfFire = c.rateOfFire;
                o.reloadDuration = c.reloadDuration;
                o.firingSound = c.firingSound;
                o.firingPitch = c.firingPitch;
            }
        } else if (gun.getItem() instanceof ItemGunBaseSedna) {
            GunConfigurationSedna c = ((ItemGunBaseSedna) gun.getItem()).mainConfig;
            if (c != null) {
                o = new OldCfg();
                o.config = c.config;
                o.ammoCap = c.ammoCap;
                o.rateOfFire = c.rateOfFire;
                o.reloadDuration = c.reloadDuration;
                o.firingSound = c.firingSound;
                o.firingPitch = c.firingPitch;
            }
        }
        return o;
    }

    private static boolean isOldStyle(ItemStack gun) {
        return gun.getItem() instanceof ItemGunBase || gun.getItem() instanceof ItemGunBaseSedna;
    }

    private static List<AmmoKind> kinds(ItemStack gun) {
        List<AmmoKind> list = new ArrayList<AmmoKind>();
        if (isOldStyle(gun)) {
            OldCfg cfg = oldCfg(gun);
            if (cfg != null && cfg.config != null) {
                for (Integer id : cfg.config) {
                    BulletConfiguration bc = BulletConfigSyncingUtil.pullConfig(id);
                    if (bc != null && bc.ammo != null) {
                        list.add(new AmmoKind(bc.ammo.toStack(), bc.ammoCount, id, null));
                    }
                }
            }
        } else if (gun.getItem() instanceof ItemGunBaseNT) {
            Receiver receiver = primaryReceiver(gun);
            if (receiver != null) {
                IMagazine<?> mag = receiver.getMagazine(gun);
                if (mag instanceof MagazineSingleTypeBase) {
                    for (BulletConfig bc : ((MagazineSingleTypeBase) mag).acceptedBullets) {
                        if (bc != null && bc.ammo != null) {
                            list.add(new AmmoKind(bc.ammo.toStack(), bc.ammoReloadCount, -1, bc));
                        }
                    }
                }
            }
        }
        return list;
    }

    private static Receiver primaryReceiver(ItemStack gun) {
        GunConfig cfg = ((ItemGunBaseNT) gun.getItem()).getConfig(gun, 0);
        if (cfg == null) {
            return null;
        }
        Receiver[] receivers = cfg.getReceivers(gun);
        return receivers == null || receivers.length == 0 ? null : receivers[0];
    }

    static boolean isAmmoFor(ItemStack gun, ItemStack candidate) {
        try {
            for (AmmoKind k : kinds(gun)) {
                if (ItemStack.areItemsEqual(k.ammoItem, candidate)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 未知の銃構成
        }
        return false;
    }

    /** 足りない弾薬の例（通知用）。 */
    static ItemStack exampleAmmo(ItemStack gun) {
        try {
            List<AmmoKind> k = kinds(gun);
            return k.isEmpty() ? ItemStack.EMPTY : k.get(0).ammoItem;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------
    // 装填・射撃
    // ------------------------------------------------------------------

    private static NBTTagCompound tag(ItemStack gun) {
        if (!gun.hasTagCompound()) {
            gun.setTagCompound(new NBTTagCompound());
        }
        return gun.getTagCompound();
    }

    private static int capacity(ItemStack gun) {
        if (isOldStyle(gun)) {
            OldCfg cfg = oldCfg(gun);
            return cfg == null ? 1 : Math.max(1, cfg.ammoCap);
        }
        Receiver r = primaryReceiver(gun);
        if (r == null || r.getMagazine(gun) == null) {
            return 1;
        }
        return Math.max(1, r.getMagazine(gun).getCapacity(gun));
    }

    /** 弾が空ならポーチから装填する。装填できた弾数（0なら弾切れ）。 */
    private static int reloadIfEmpty(EntityFriendlyCreature shooter, ItemStack gun, List<AmmoKind> kinds) {
        NBTTagCompound tag = tag(gun);
        int rounds = tag.getInteger(KEY_ROUNDS);
        if (rounds > 0 && tag.getInteger(KEY_TYPE) < kinds.size()) {
            return rounds;
        }
        int cap = capacity(gun);
        for (int i = 0; i < kinds.size(); i++) {
            AmmoKind k = kinds.get(i);
            int loaded = 0;
            while (loaded < cap && AllyAmmoStorage.consume(shooter, k.ammoItem, 1)) {
                loaded += k.roundsPerItem;
            }
            if (loaded > 0) {
                tag.setInteger(KEY_ROUNDS, loaded);
                tag.setInteger(KEY_TYPE, i);
                return loaded;
            }
        }
        tag.setInteger(KEY_ROUNDS, 0);
        return 0;
    }

    /**
     * 1回撃つ（散弾銃なら複数発）。撃つ前に射手の向きを標的へ合わせておくこと
     * （HBMの弾は射手の rotationYaw/rotationPitch で飛ぶ）。
     * @return 次に撃てるまでの待ちTick（リロードした時はリロード時間を含む）。弾切れなら {@link #NO_AMMO}
     */
    static int fire(EntityFriendlyCreature shooter, ItemStack gun) {
        List<AmmoKind> kinds = kinds(gun);
        if (kinds.isEmpty()) {
            return NO_AMMO;
        }
        NBTTagCompound tag = tag(gun);
        boolean wasEmpty = tag.getInteger(KEY_ROUNDS) <= 0;
        if (reloadIfEmpty(shooter, gun, kinds) <= 0) {
            return NO_AMMO;
        }
        if (wasEmpty) {
            return reloadTicks(gun); // 装填動作ぶん待ってから撃つ
        }
        AmmoKind kind = kinds.get(tag.getInteger(KEY_TYPE));
        World world = shooter.world;
        int delay;
        if (kind.oldConfigId >= 0) {
            BulletConfiguration bc = BulletConfigSyncingUtil.pullConfig(kind.oldConfigId);
            if (bc == null) {
                return NO_AMMO;
            }
            int pellets = bc.bulletsMin + (bc.bulletsMax > bc.bulletsMin ? world.rand.nextInt(bc.bulletsMax - bc.bulletsMin + 1) : 0);
            for (int i = 0; i < Math.max(1, pellets); i++) {
                world.spawnEntity(new EntityBulletBase(world, kind.oldConfigId, shooter));
            }
            OldCfg cfg = oldCfg(gun);
            delay = cfg == null ? 10 : Math.max(1, cfg.rateOfFire);
        } else {
            Receiver r = primaryReceiver(gun);
            BulletConfig bc = kind.newConfig;
            float damage = (r == null ? 5.0F : r.getBaseDamage(gun));
            float spread = (r == null ? 0.0F : r.getInnateSpread(gun));
            int pellets = bc.projectilesMin + (bc.projectilesMax > bc.projectilesMin
                    ? world.rand.nextInt(bc.projectilesMax - bc.projectilesMin + 1) : 0);
            for (int i = 0; i < Math.max(1, pellets); i++) {
                world.spawnEntity(new EntityBulletBaseMK4(shooter, bc, damage, spread, 0.0D, 0.0D, 0.0D));
            }
            delay = r == null ? 10 : Math.max(1, r.getDelayAfterFire(gun));
        }
        tag.setInteger(KEY_ROUNDS, tag.getInteger(KEY_ROUNDS) - 1);
        playFireSound(shooter, gun);
        return delay;
    }

    /** [発射音] その銃自身に設定された発射音を鳴らす（無ければ汎用の銃声）。 */
    private static void playFireSound(EntityFriendlyCreature shooter, ItemStack gun) {
        net.minecraft.util.SoundEvent sound = null;
        float pitch = 1.0F;
        try {
            if (isOldStyle(gun)) {
                OldCfg cfg = oldCfg(gun);
                if (cfg != null) {
                    sound = cfg.firingSound;
                    pitch = cfg.firingPitch > 0 ? cfg.firingPitch : 1.0F;
                }
            } else {
                Receiver r = primaryReceiver(gun);
                if (r != null) {
                    sound = r.getFireSound(gun);
                }
            }
        } catch (Throwable ignored) {
            // 設定の読めない銃
        }
        if (sound == null) {
            sound = net.minecraft.init.SoundEvents.ENTITY_GENERIC_EXPLODE;
            pitch = 1.8F;
        }
        shooter.world.playSound(null, shooter.posX, shooter.posY, shooter.posZ, sound,
                net.minecraft.util.SoundCategory.HOSTILE, 1.0F, pitch);
    }

    private static int reloadTicks(ItemStack gun) {
        if (isOldStyle(gun)) {
            OldCfg cfg = oldCfg(gun);
            return cfg == null ? 30 : Math.max(10, cfg.reloadDuration);
        }
        Receiver r = primaryReceiver(gun);
        return r == null ? 30 : Math.max(10, r.getReloadBeginDuration(gun) + r.getReloadCycleDuration(gun));
    }
}
