package com.example.examplemod;

import java.util.List;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartSeat;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;

import mcinterface1122.WrapperEntity;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

/**
 * Engender Mod 系の味方モブに、Immersive Vehicles（旧 Minecraft Transport Simulator, IV）の
 * 車両（自動車・トラック等）を「本格的に」自律運転させるための {@link EntityAIBase} 実装。
 *
 * <p>v2 での変更点（{@link EntityAIMountImmersiveVehicle} とセットで運用する前提）:
 * <ul>
 *   <li>乗車済みであれば、目的地が未設定でも自動でエンジンを始動し、以後は常に
 *       「敵性クリーチャーが近くにいれば車両ごと突撃する（ラム攻撃）」→
 *       「目的地が設定されていればそこへ向かう」→「どちらも無ければその場で待機」
 *       の優先順位で行動する。</li>
 *   <li>敵性クリーチャーの検出は、既存の {@code EntityAISurviveAndCombatAlly} と同じ基準
 *       （リベンジターゲット優先、次点で周囲の {@link EntityMob} のうち最も近いもの）を用いる。</li>
 *   <li>降車するのは「GUIアイテム等で指定した目的地に到達した」場合のみで、
 *       敵を轢き終わった後は自動では降りず、次の脅威 or 目的地 or 待機へ戻る。</li>
 * </ul>
 *
 * <h2>1. 車両の判定 / API アダプト</h2>
 * IV は座席を専用の透明エンティティ（内部的には {@code mcinterface1122.BuilderEntityLinkedSeat}）
 * として実装しており、{@code entity.getRidingEntity()} が返すのはこの座席エンティティであって
 * 車両本体ではない。そこで {@link IWrapperEntity#getEntityRiding()}（IV 公式の
 * ラッパー越しアクセサ）を使い、座席（{@link APart}）→ {@link APart#vehicleOn} を辿って
 * 実体である {@link EntityVehicleF_Physics} を取得する。
 *
 * <h2>2. 自動運転物理制御アルゴリズム</h2>
 * <ul>
 *   <li>ステアリング: 目的地/敵ベクトルと車両進行方向（yaw）との角度差（yawDelta）に対する
 *       比例制御（P制御）で目標ステア角を求め、IV 標準の可動範囲・変化レート
 *       （実測値 ±45°）でなめらかに追従させる。</li>
 *   <li>アクセル & ブレーキ: 通常走行時は直進で加速、カーブや目的地手前では減速しつつ
 *       フットブレーキを作動。突撃(charge)時はブレーキをほぼ使わず勢いを保つ。</li>
 *   <li>スタック検出 & 脱出: 速度がほぼ0の状態が一定時間続いたら、ギアをバックへ入れ、
 *       ハンドルを逆に切って後退したのち、前進ギアへ戻して走行を再開する。</li>
 * </ul>
 *
 * <h2>3. 目的地到達判定 & 降車</h2>
 * {@link #setDestination(BlockPos)} で指定した地点の半径内（既定4ブロック）に入ったら
 * 完全停止（スロットル0 & ブレーキON）させ、パーキングブレーキを掛けたうえで
 * {@code dismountRidingEntity()} により降車させる。
 *
 * <p>ビルド時の注意: このクラスをコンパイルするには、プロジェクトの {@code build.gradle} の
 * 依存関係に Immersive Vehicles の deobf jar が含まれている必要がある
 * （このプロジェクトは {@code libs/} フォルダを丸ごと {@code compileOnly} しているので追加設定は不要）。
 */
public class EntityAIDriveImmersiveVehicle extends EntityAIBase {

    // ------------------------------------------------------------------
    // 調整用定数
    // ------------------------------------------------------------------

    /** 目的地到達とみなす半径（ブロック）。仕様上 3〜5 ブロックを推奨。 */
    private static final double DEFAULT_ARRIVAL_RADIUS = 4.0D;

    /** ステアリングのP制御ゲイン（度 → 度）。値が大きいほど鋭く切り返す。 */
    private static final double STEERING_KP = 1.15D;
    /** IV のステアリング入力可動範囲（度）。ControlSystem 実装（CAR_TURN, ±45°）に準拠。 */
    private static final double MAX_STEERING_ANGLE = 45.0D;
    /** 1 Tick あたりのステアリング最大変化量（度）。実車のハンドル操作速度に相当。 */
    private static final double MAX_STEERING_RATE_PER_TICK = 6.0D;

    /** この角度差（度）を超えたら「ゆるいカーブ」として減速する。 */
    private static final double SLOWDOWN_YAW_THRESHOLD = 25.0D;
    /** この角度差（度）を超えたら「急カーブ」としてブレーキも併用する。 */
    private static final double SHARP_TURN_YAW_THRESHOLD = 55.0D;
    /**
     * 急カーブ中にこの速度（block/tick）を超えていたらブレーキで減速する上限。
     * 実車と同じく、停止したままではハンドルを切っても車体は絶対に曲がらない
     * （速度0のときは {@link #applySteering} でどれだけステア角を付けても旋回が発生しない）ため、
     * 急カーブだからといってスロットルを0にはせず、この速度以下なら {@link #SLOW_THROTTLE} で
     * 低速前進を続けさせて実際に曲がれるようにする。
     */
    private static final double SHARP_TURN_MAX_SPEED = 0.20D;

    /** 最大スロットル（突撃時に使用）。 */
    private static final double MAX_THROTTLE = 1.0D;
    /**
     * 巡航スロットル（通常の目的地移動、直進時）。
     * 以前は 0.65 に抑えていたため、ギアが上まで入っていても最高速に達しづらかった。
     * モブの運転に人間的な慎重さは不要なので、直進時はフルスロットルにする
     * （カーブ・目的地手前では別途 {@link #SLOW_THROTTLE} 等に絞られる）。
     */
    private static final double CRUISE_THROTTLE = MAX_THROTTLE;
    /** カーブ / 接近時の低速スロットル。 */
    private static final double SLOW_THROTTLE = 0.30D;
    /** スロットルの 1 Tick あたり最大変化量（急発進防止。突撃時は無視して即全開にする）。 */
    private static final double THROTTLE_STEP_PER_TICK = 0.05D;
    /** 突撃時のスロットル立ち上がり（急加速OK）。 */
    private static final double CHARGE_THROTTLE_STEP_PER_TICK = 0.12D;
    /** ブレーキの 1 Tick あたり最大変化量。 */
    private static final double BRAKE_STEP_PER_TICK = 0.15D;
    /** 目的地手前、この距離（ブロック）から減速を開始する。 */
    private static final double BRAKE_DISTANCE = 8.0D;
    /** 完全停止とみなす速度（block/tick）。 */
    private static final double STOP_SPEED_THRESHOLD = 0.02D;

    /** これ未満の速度が続いたら「スタックしている」と判定する閾値（block/tick）。 */
    private static final double STUCK_SPEED_THRESHOLD = 0.02D;
    /** スタック判定に必要な低速継続 Tick 数（2秒）。 */
    private static final int STUCK_DETECT_TICKS = 40;
    /** バックで脱出する Tick 数（1.5秒）。 */
    private static final int REVERSE_ESCAPE_TICKS = 30;
    /** 脱出後、再度スタック判定を行うまでのクールダウン（3秒）。 */
    private static final int STUCK_COOLDOWN_TICKS = 60;
    /** エンジン始動を待つ最大 Tick 数（10秒）。燃料切れ等での無限待機を防ぐ。 */
    private static final int ENGINE_START_TIMEOUT_TICKS = 200;
    /** 走行中にエンストを検知して再始動を試みる最短間隔（燃料切れで往復し続けないよう）。 */
    private static final int ENGINE_RESTART_COOLDOWN_TICKS = 400;
    /** 目的地接近時の許容速度 = APPROACH_MIN_SPEED + APPROACH_SPEED_PER_BLOCK * 残り距離（ブロック/Tick）。 */
    private static final double APPROACH_MIN_SPEED = 0.08D;
    private static final double APPROACH_SPEED_PER_BLOCK = 0.05D;
    /** 突撃対象にする敵との高低差の上限（洞窟の下の敵を追って上空を旋回し続けないため）。 */
    private static final double THREAT_MAX_HEIGHT_DIFF = 5.0D;

    /**
     * マニュアル変速車両で自力変速した直後、次の変速判定を行うまでのクールダウン（Tick）。
     * 無いと rpm が閾値付近で細かく上下するたびアップ/ダウンシフトを連発してしまう。
     */
    private static final int MANUAL_SHIFT_COOLDOWN_TICKS = 10;

    /** 敵性クリーチャーを捜索する半径（ブロック）。 */
    private static final double COMBAT_SEARCH_RADIUS = 24.0D;

    /**
     * 前方の障害物（村の建物の壁など）を検知する距離（ブロック）。
     * 目的地に向けてフルスロットルで走らせるようにした結果、村などに突っ込んで
     * 事故る問題が出たため、進行方向のブロックをレイキャストで先読みし、
     * 近ければスロットルを絞り、直前ならブレーキで止める。
     */
    private static final double OBSTACLE_LOOKAHEAD_DISTANCE = 10.0D;
    /** この距離（ブロック）まで障害物が迫ったら完全停止させる。 */
    private static final double OBSTACLE_STOP_DISTANCE = 3.0D;
    /** 障害物回避のためのレイキャストを行う高さ（車両中心からのY方向オフセット）。 */
    private static final double OBSTACLE_RAY_HEIGHT_OFFSET = 1.0D;

    // ------------------------------------------------------------------
    // フィールド
    // ------------------------------------------------------------------

    private final EntityFriendlyCreature driver;

    private BlockPos destination;
    private double arrivalRadius = DEFAULT_ARRIVAL_RADIUS;

    /** 現在操作対象としている IV 車両。乗車していない間は null。 */
    private EntityVehicleF_Physics vehicle;

    private double currentSteering;
    private int lowSpeedTicks;
    private int stuckCooldownTicks;
    private int stateTimer;
    private int manualShiftCooldownTicks;
    private int engineRestartCooldown;
    private boolean loggedUpdateError;

    private enum DriveState {
        /** エンジン始動待ち。 */
        STARTING_ENGINES,
        /** 通常稼働中（毎Tick、突撃/目的地移動/待機のいずれかを内部で選択する）。 */
        ACTIVE,
        /** スタックからの脱出でバック中。 */
        REVERSING,
        /** 目的地付近で停止処理中。 */
        ARRIVING,
        /** 完全停止・降車済み。タスク終了。 */
        STOPPED
    }

    private DriveState state = DriveState.STARTING_ENGINES;

    /**
     * @param driver 車両を運転させたいモブ。{@link EntityAIMountImmersiveVehicle} 等により
     *               IV の座席（運転席）へ既に乗車済みであること。
     */
    public EntityAIDriveImmersiveVehicle(EntityFriendlyCreature driver) {
        this.driver = driver;
        // 移動(1)・見た目/視線(2) の両方を占有し、通常の徒歩移動系AIと競合しないようにする。
        this.setMutexBits(3);
    }

    // ------------------------------------------------------------------
    // 外部から目的地を設定するための公開API（GUIアイテム等から呼び出す）
    // ------------------------------------------------------------------

    /** 目的地を設定する。null を設定すると目的地指示は解除される（敵がいなければ待機になる）。 */
    public void setDestination(BlockPos destination) {
        this.destination = destination;
        PARKED.remove(driver);
    }

    /** 目的地と到達判定半径（ブロック、既定4。仕様上 3〜5 を推奨）を設定する。 */
    public void setDestination(BlockPos destination, double arrivalRadius) {
        this.destination = destination;
        this.arrivalRadius = Math.max(1.0D, arrivalRadius);
        PARKED.remove(driver);
    }

    public BlockPos getDestination() {
        return destination;
    }

    /** 現在このAIが車両を掴んで運転制御を行っているかどうか。 */
    public boolean isDriving() {
        return vehicle != null && state != DriveState.STOPPED;
    }

    // ------------------------------------------------------------------
    // EntityAIBase
    // ------------------------------------------------------------------

    @Override
    public boolean shouldExecute() {
        if (driver == null || !driver.isEntityAlive()) {
            return false;
        }
        if (driver.world != null && driver.world.isRemote) {
            // AIロジックはサーバー権威で行う。クライアント側では絶対に実行しない。
            return false;
        }
        if (driver.getRidingEntity() == null) {
            return false;
        }
        // 乗車さえしていれば、目的地未指定でも即座に運転(エンジン始動→待機/突撃)を開始する。
        return resolveVehicle(driver) != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (driver == null || !driver.isEntityAlive()) {
            return false;
        }
        if (driver.getRidingEntity() == null) {
            return false;
        }
        vehicle = resolveVehicle(driver);
        return vehicle != null && state != DriveState.STOPPED;
    }

    @Override
    public void startExecuting() {
        vehicle = resolveVehicle(driver);
        currentSteering = 0.0D;
        lowSpeedTicks = 0;
        stuckCooldownTicks = 0;
        stateTimer = 0;
        manualShiftCooldownTicks = 0;
        state = DriveState.STARTING_ENGINES;
    }

    @Override
    public void resetTask() {
        if (vehicle != null) {
            safeFullStop(vehicle);
        }
        vehicle = null;
        state = DriveState.STARTING_ENGINES;
    }

    @Override
    public void updateTask() {
        if (vehicle == null) {
            vehicle = resolveVehicle(driver);
            if (vehicle == null) {
                return;
            }
        }

        try {
            switch (state) {
                case STARTING_ENGINES:
                    tickStartEngines();
                    break;
                case ACTIVE:
                    tickActive();
                    break;
                case REVERSING:
                    tickReverse();
                    break;
                case ARRIVING:
                    tickArrive();
                    break;
                case STOPPED:
                default:
                    break;
            }
        } catch (Throwable t) {
            // IV側APIの想定外状態（車両破壊・座席消失など）でAI全体やサーバーを
            // 落とさないためのフェイルセーフ。安全側（停止）に倒す。
            // 以前は例外を完全に握りつぶしていたため原因調査ができなかった。
            // 初回だけログに残す。
            if (!loggedUpdateError) {
                loggedUpdateError = true;
                System.err.println("[EngenderIV] drive AI error (logged once): " + t);
                t.printStackTrace();
            }
            safeFullStop(vehicle);
            state = DriveState.STOPPED;
        }
    }

    // ------------------------------------------------------------------
    // 1. 車両判定 / Immersive Vehicles API アダプト
    // ------------------------------------------------------------------

    /**
     * モブが騎乗しているエンティティを辿り、Immersive Vehicles の車両本体
     * （{@link EntityVehicleF_Physics}）を取得する。
     *
     * <p>IV は座席を専用の非表示エンティティ（{@code BuilderEntityLinkedSeat}）として実装して
     * いるため、{@code entity.getRidingEntity()}（vanilla の Entity API）が返すのはこの座席
     * エンティティであり、車両本体ではない。そこで IV 公式のラッパー API
     * （{@link IWrapperEntity#getEntityRiding()}）を経由して IV 内部エンティティ
     * （{@link AEntityB_Existing}）を取得し、それが座席パーツ（{@link APart}）であれば
     * {@link APart#vehicleOn} を辿って車両本体を得る。
     */
    static EntityVehicleF_Physics resolveVehicle(EntityFriendlyCreature mob) {
        if (mob == null || mob.getRidingEntity() == null) {
            return null;
        }
        try {
            IWrapperEntity wrapper = WrapperEntity.getWrapperFor(mob);
            if (wrapper == null) {
                return null;
            }
            AEntityB_Existing riding = wrapper.getEntityRiding();
            if (riding == null) {
                return null;
            }
            if (riding instanceof EntityVehicleF_Physics) {
                return (EntityVehicleF_Physics) riding;
            }
            // [プレイヤーが運転できなくなる不具合の修正] Only the DRIVER's seat.
            // Any seat used to count, so an ally riding as a passenger took over
            // throttle/brake/steering every tick (and tickIdle forced the
            // parking brake on), making the car undrivable for the player.
            if (riding instanceof PartSeat) {
                PartSeat seat = (PartSeat) riding;
                if (seat.placementDefinition == null || !seat.placementDefinition.isController) {
                    return null;
                }
                EntityVehicleF_Physics vehicleOn = seat.vehicleOn;
                if (vehicleOn != null && vehicleOn.isValid) {
                    return vehicleOn;
                }
            }
        } catch (Throwable t) {
            // 座席が乗車直後でまだ内部リンクが確立していない等、一時的な不整合は無視する。
        }
        return null;
    }

    /**
     * 運転手を座席から降ろす。
     *
     * <p>単純に vanilla の {@code Entity#dismountRidingEntity()} を呼ぶと、IV 側の
     * {@code PartSeat}（座席）の内部状態（{@code rider} フィールド等）の更新は
     * {@code BuilderEntityLinkedSeat} 側の検知任せになる。この経路とタイミングが
     * 重なると、座席のエンティティが破棄される際に {@code PartSeat#removeRider()} が
     * 既に {@code rider == null} の状態で再度呼ばれ、
     * {@code NullPointerException}（{@code PartSeat.removeRider} ←
     * {@code BuilderEntityLinkedSeat.setDead}）でサーバーログを汚す不具合が確認された。
     *
     * <p>乗車時に {@link EntityAIMountImmersiveVehicle} が {@code seat.setRider(...)} を
     * 直接呼んでいるのと対称に、降車時もこちらから同じ権威を持つ
     * {@link PartSeat#removeRider()} を直接呼び、IV 側の状態更新を一度だけ確実に
     * 行わせることで、上記の二重クリーンアップによる NPE を避ける。
     * 座席が特定できない場合のみ、保険として vanilla の dismount にフォールバックする。
     */
    private void dismountFromVehicle() {
        try {
            IWrapperEntity wrapper = WrapperEntity.getWrapperFor(driver);
            if (wrapper != null) {
                AEntityB_Existing riding = wrapper.getEntityRiding();
                if (riding instanceof PartSeat) {
                    PartSeat seat = (PartSeat) riding;
                    if (seat.rider != null) {
                        seat.removeRider();
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 座席が既に破棄されている等の一時的な不整合。フォールバックへ。
        }
        if (driver.getRidingEntity() != null) {
            driver.dismountRidingEntity();
        }
    }

    // ------------------------------------------------------------------
    // エンジン始動フェーズ
    // ------------------------------------------------------------------

    private void tickStartEngines() {
        stateTimer++;

        if (vehicle.engines.isEmpty()) {
            // エンジンを持たない車両（被牽引車など）はそのまま稼働フェーズへ。
            state = DriveState.ACTIVE;
            stateTimer = 0;
            return;
        }

        boolean allRunning = true;
        for (PartEngine engine : vehicle.engines) {
            if (engine.running) {
                continue;
            }
            allRunning = false;
            // プレイヤーが行うのと同じ操作経路（マグネトON → セルモーターON）でエンジン始動を試みる。
            if (!engine.magnetoVar.isActive) {
                engine.magnetoVar.setActive(true, true);
            }
            if (!engine.electricStarterVar.isActive && !engine.handStarterVar.isActive) {
                engine.electricStarterVar.setActive(true, true);
            }
        }

        if (allRunning) {
            // 始動が完了したらセルモーターを切っておく（回しっぱなし防止）。
            for (PartEngine engine : vehicle.engines) {
                if (engine.electricStarterVar.isActive) {
                    engine.electricStarterVar.setActive(false, true);
                }
            }
            state = DriveState.ACTIVE;
            stateTimer = 0;
            return;
        }

        if (stateTimer > ENGINE_START_TIMEOUT_TICKS) {
            // 燃料切れ等でいつまでも始動しない場合はタイムアウトし、
            // そのまま稼働フェーズへ進む（惰性走行・牽引されている車両等を考慮）。
            state = DriveState.ACTIVE;
            stateTimer = 0;
        }
    }

    // ------------------------------------------------------------------
    // 2. 自動運転物理制御アルゴリズム（毎Tickの行動選択）
    // ------------------------------------------------------------------

    /**
     * 乗車・エンジン始動が済んだ後の通常稼働。優先順位は
     * 「近くの敵性クリーチャーへ突撃」 &gt; 「設定された目的地へ移動」 &gt; 「待機」。
     */
    private void tickActive() {
        // [エンストしたまま動かない不具合の修正] Nothing ever went back to
        // STARTING_ENGINES once ACTIVE, so a stalled engine left the car dead
        // until the ally re-boarded. (Rate-limited so an out-of-fuel car
        // doesn't bounce between the two states.)
        if (engineRestartCooldown > 0) {
            engineRestartCooldown--;
        } else if (!vehicle.engines.isEmpty()) {
            for (PartEngine engine : vehicle.engines) {
                if (!engine.running) {
                    engineRestartCooldown = ENGINE_RESTART_COOLDOWN_TICKS;
                    state = DriveState.STARTING_ENGINES;
                    stateTimer = 0;
                    return;
                }
            }
        }

        EntityLivingBase threat = findNearestThreat();
        if (threat != null && threat.isEntityAlive()) {
            tickCharge(threat);
            return;
        }

        if (destination != null) {
            double distSq = horizontalDistanceSq(vehicle, destination.getX() + 0.5D, destination.getZ() + 0.5D);
            if (Math.sqrt(distSq) <= arrivalRadius) {
                state = DriveState.ARRIVING;
                return;
            }
            tickWaypoint();
            return;
        }

        tickIdle();
    }

    /** 敵性クリーチャー(EntityMob)のうち、リベンジターゲット優先で最も近いものを探す。 */
    private EntityLivingBase findNearestThreat() {
        EntityLivingBase revenge = driver.getRevengeTarget();
        // Never ram the player (friendly fire makes them the revenge target)
        // or another Engender creature.
        if (revenge != null && revenge.isEntityAlive()
                && !(revenge instanceof net.minecraft.entity.player.EntityPlayer)
                && !(revenge instanceof EntityFriendlyCreature)) {
            return revenge;
        }
        if (driver.world == null) {
            return null;
        }
        AxisAlignedBB area = new AxisAlignedBB(vehicle.position.x, vehicle.position.y, vehicle.position.z,
                vehicle.position.x, vehicle.position.y, vehicle.position.z)
                .grow(COMBAT_SEARCH_RADIUS, COMBAT_SEARCH_RADIUS, COMBAT_SEARCH_RADIUS);
        List<EntityMob> mobs = driver.world.getEntitiesWithinAABB(EntityMob.class, area);
        if (mobs == null || mobs.isEmpty()) {
            return null;
        }
        EntityMob closest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityMob mob : mobs) {
            if (!mob.isEntityAlive()) {
                continue;
            }
            // A zombie in a cave below (or on a cliff above) can't be rammed;
            // chasing it made the car circle overhead forever instead of
            // ever reaching the waypoint.
            if (Math.abs(mob.posY - vehicle.position.y) > THREAT_MAX_HEIGHT_DIFF || !driver.canEntityBeSeen(mob)) {
                continue;
            }
            double dx = mob.posX - vehicle.position.x;
            double dy = mob.posY - vehicle.position.y;
            double dz = mob.posZ - vehicle.position.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                closest = mob;
            }
        }
        return closest;
    }

    /** 敵に向かって「突撃」する。ブレーキはほぼ使わず、勢いを保ったまま体当たりする。 */
    private void tickCharge(EntityLivingBase threat) {
        manageGears();
        if (checkAndHandleStuck()) {
            return;
        }

        double yawDelta = computeYawDeltaToTarget(vehicle, threat.posX, threat.posZ);
        applySteering(yawDelta);

        double absYaw = Math.abs(yawDelta);
        double targetThrottle;
        boolean wantBrake;
        if (absYaw > SHARP_TURN_YAW_THRESHOLD) {
            // 急に向きを変える必要がある場合のみ、コントロールを失わないよう少し緩める。
            targetThrottle = SLOW_THROTTLE;
            wantBrake = false;
        } else {
            // 全開で突撃。
            targetThrottle = MAX_THROTTLE;
            wantBrake = false;
        }

        ObstacleAvoidance obstacle = computeObstacleAvoidance(targetThrottle, wantBrake);
        setParkingBrake(false);
        stepThrottleAndBrake(obstacle.targetThrottle, obstacle.wantBrake, CHARGE_THROTTLE_STEP_PER_TICK);
    }

    /** 設定された目的地へ向けて通常走行する。 */
    private void tickWaypoint() {
        manageGears();
        if (checkAndHandleStuck()) {
            return;
        }

        double dist = Math.sqrt(horizontalDistanceSq(vehicle, destination.getX() + 0.5D, destination.getZ() + 0.5D));

        double yawDelta = computeYawDeltaToTarget(vehicle, destination.getX() + 0.5D, destination.getZ() + 0.5D);
        applySteering(yawDelta);

        double absYaw = Math.abs(yawDelta);
        double targetThrottle;
        boolean wantBrake;

        double remaining = dist - arrivalRadius;
        // [目的地を大きく行き過ぎる不具合の修正] Braking used to start only
        // at dist < 4 -- already inside the default 4-block arrival radius, so
        // it never fired and the car hit the radius at cruise speed. Now the
        // allowed speed shrinks linearly with the distance left to the radius
        // edge, and we brake whenever we're faster than that.
        double allowedSpeed = APPROACH_MIN_SPEED + APPROACH_SPEED_PER_BLOCK * Math.max(0.0D, remaining);
        if (vehicle.velocity > allowedSpeed) {
            targetThrottle = 0.0D;
            wantBrake = true;
        } else if (remaining <= BRAKE_DISTANCE) {
            targetThrottle = SLOW_THROTTLE;
            wantBrake = false;
        } else if (absYaw > SHARP_TURN_YAW_THRESHOLD) {
            // スロットル0にすると、目的地が真後ろ等で最初から急な向き直しが必要なケースで
            // 「停止→旋回不能→ヨー角が縮まらない→ずっとスロットル0」のデッドロックに陥り、
            // ハンドルを切るだけで一切加速しなくなってしまう（実際に報告されていた不具合）。
            // 低速なりに前進は続けさせ、曲がりながら加速できるようにする。
            targetThrottle = SLOW_THROTTLE;
            wantBrake = vehicle.velocity > SHARP_TURN_MAX_SPEED;
        } else if (absYaw > SLOWDOWN_YAW_THRESHOLD) {
            targetThrottle = SLOW_THROTTLE;
            wantBrake = false;
        } else {
            targetThrottle = CRUISE_THROTTLE;
            wantBrake = false;
        }

        ObstacleAvoidance obstacle = computeObstacleAvoidance(targetThrottle, wantBrake);
        setParkingBrake(false);
        stepThrottleAndBrake(obstacle.targetThrottle, obstacle.wantBrake, THROTTLE_STEP_PER_TICK);
    }

    /** 敵も目的地もない場合、その場でパーキングブレーキを掛けて待機する（エンジンは掛けたまま）。 */
    private void tickIdle() {
        lowSpeedTicks = 0;
        setParkingBrake(true);
        stepThrottleAndBrake(0.0D, true, THROTTLE_STEP_PER_TICK);
        applySteering(0.0D);
    }

    /**
     * ギアを適切に管理する。{@link #tickWaypoint()} / {@link #tickCharge} の
     * 前進走行フェーズの冒頭で必ず呼び出す。
     *
     * <p><b>発進（ニュートラル→1速）:</b> ギアがニュートラル（0）のままだと、
     * いくらスロットルを踏んでも（エンジンが空回りするだけで）車両は絶対に
     * 発進しない。IV はプレイヤーがシフトレバー/パドルを操作した時と同じ
     * {@code PartEngine#shiftUp()} を呼ぶことでしかギアを変えられないため、
     * ニュートラルなら毎Tickこれを呼んで1速へ入れる。
     *
     * <p><b>2速以降への変速（オートマ車両）:</b> {@code isAutomaticVar} が
     * 有効な車両は、IV 本体の {@code PartEngine#update()} が rpm を見て毎Tick
     * 自動で {@code shiftUp()}/{@code shiftDown()} を呼んでくれるため、ここでは
     * 何もしない。二重に呼ぶと変速タイミングがずれてバッドシフト
     * （{@code badShiftEngine()}）を誘発しかねないため、必ずスキップする。
     *
     * <p><b>2速以降への変速（マニュアル車両）:</b> マニュアル設定の車両は
     * IV 側が一切自動変速してくれず、以前の実装では1速に入れたきり放置していた
     * ため最高速に達しないまま走り続けてしまっていた（＝「目的地を指定しても
     * スピードが出ない」不具合の主因）。ここでは IV 本体がオートマ車両に対して
     * 行っている変速判定式
     * （{@code rpm > shiftRpm * 0.5 * (1 + throttle)} で1段アップ、
     * {@code rpm < shiftRpm * 0.25 * (1 + throttle)} で1段ダウン。
     * {@code shiftRpm} は車両定義の {@code upShiftRPM}/{@code downShiftRPM}
     * （ギアごとのRPM表）があればその値、無ければ {@code maxSafeRPM * 0.9}）
     * と同じ式を自前で評価し、{@code shiftUp()}/{@code shiftDown()} を呼ぶ。
     * rpm が閾値付近でばたつくと変速を連発してしまうため、
     * {@link #MANUAL_SHIFT_COOLDOWN_TICKS} の間は次の変速判定を行わない。
     */
    private void manageGears() {
        if (manualShiftCooldownTicks > 0) {
            manualShiftCooldownTicks--;
        }

        for (PartEngine engine : vehicle.engines) {
            if (!engine.running) {
                continue;
            }

            double gear = engine.currentGearVar.currentValue;
            if (gear == 0.0D) {
                // ニュートラル: まず1速へ。
                engine.shiftUp();
                continue;
            }
            if (gear < 0.0D) {
                // 前進走行中なのにリバースに入っている（オートマのIVがリバース中に
                // さらに -2 速へ落とし、脱出時の shiftUp() 1回では -1 までしか
                // 戻らなかった等）。以前はここで何もせず、永久にバックで走っていた。
                engine.shiftUp();
                continue;
            }
            if (engine.isAutomaticVar.isActive || manualShiftCooldownTicks > 0) {
                continue;
            }

            if (manageManualShift(engine, gear)) {
                manualShiftCooldownTicks = MANUAL_SHIFT_COOLDOWN_TICKS;
            }
        }
    }

    /**
     * マニュアル変速の1エンジンぶんの変速判定。変速を実行したら true を返す。
     * 計算式は IV 本体の自動変速ロジックと揃えてある（詳細は {@link #manageGears()}）。
     */
    private static boolean manageManualShift(PartEngine engine, double gear) {
        double throttle = engine.vehicleOn.throttleVar.currentValue;
        int shiftTableIndex = (int) (gear + engine.reverseGears);

        if (gear < engine.forwardsGears) {
            double upBase = shiftTableValue(engine.definition.engine.upShiftRPM, shiftTableIndex, engine);
            double upThreshold = upBase * 0.5D * (1.0D + throttle);
            if (engine.rpm > upThreshold) {
                return engine.shiftUp();
            }
        }

        if (gear > 1.0D) {
            // IV (PartEngine.update bytecode): with a downShiftRPM table the
            // factor is 0.5; only the maxSafeRPM*0.9 fallback uses 0.25. Using
            // 0.25 for both kept manual cars in too high a gear after slowing.
            List<Integer> downTable = engine.definition.engine.downShiftRPM;
            boolean hasDownTable = downTable != null && !downTable.isEmpty();
            double downBase = shiftTableValue(downTable, shiftTableIndex, engine);
            double downThreshold = downBase * (hasDownTable ? 0.5D : 0.25D) * (1.0D + throttle);
            if (engine.rpm < downThreshold) {
                return engine.shiftDown();
            }
        }

        return false;
    }

    /** 車両定義の変速RPM表からこのギアの値を取り出す。無ければ maxSafeRPM ベースの既定値を返す。 */
    private static double shiftTableValue(List<Integer> shiftRpmTable, int index, PartEngine engine) {
        if (shiftRpmTable != null && !shiftRpmTable.isEmpty() && index >= 0) {
            return shiftRpmTable.get(Math.min(index, shiftRpmTable.size() - 1));
        }
        int maxSafeRPM = engine.definition.engine.maxSafeRPM;
        return (maxSafeRPM > 0 ? maxSafeRPM : 6000) * 0.9D;
    }

    /** {@link #computeObstacleAvoidance(double, boolean)} の戻り値。 */
    private static final class ObstacleAvoidance {
        final double targetThrottle;
        final boolean wantBrake;

        ObstacleAvoidance(double targetThrottle, boolean wantBrake) {
            this.targetThrottle = targetThrottle;
            this.wantBrake = wantBrake;
        }
    }

    /**
     * 進行方向前方をレイキャストし、村の建物の壁などの障害物が近ければ
     * スロットルを絞り、目前まで迫っていればブレーキで止める。
     *
     * <p>目的地/敵に向かって直線的に走らせている都合上、間に建物があっても
     * このAI自体は避けて通ることはしない（真の経路探索はしていない）が、
     * 少なくとも壁にフルスロットルで突っ込んで事故る、という最悪の挙動は防ぐ。
     * ブロックにぶつかりそうなほど接近して止まった場合は、
     * {@link #checkAndHandleStuck()} のスタック検出がバック脱出を試みるため、
     * 何度か切り返しながら障害物の脇へ抜けることも多い。
     *
     * @param desiredThrottle 通常のロジック（カーブ/目的地接近など）が求めているスロットル
     * @param desiredBrake    通常のロジックが求めているフットブレーキ要求
     */
    private ObstacleAvoidance computeObstacleAvoidance(double desiredThrottle, boolean desiredBrake) {
        double obstacleDist = findForwardObstacleDistance();
        if (obstacleDist <= OBSTACLE_STOP_DISTANCE) {
            return new ObstacleAvoidance(0.0D, true);
        }
        if (obstacleDist < OBSTACLE_LOOKAHEAD_DISTANCE) {
            double ratio = MathHelper.clamp(
                    (obstacleDist - OBSTACLE_STOP_DISTANCE) / (OBSTACLE_LOOKAHEAD_DISTANCE - OBSTACLE_STOP_DISTANCE),
                    0.0D, 1.0D);
            double cappedThrottle = Math.min(desiredThrottle, SLOW_THROTTLE * ratio);
            boolean brake = desiredBrake || (vehicle.velocity > SHARP_TURN_MAX_SPEED && ratio < 0.5D);
            return new ObstacleAvoidance(cappedThrottle, brake);
        }
        return new ObstacleAvoidance(desiredThrottle, desiredBrake);
    }

    /**
     * 車両の現在の進行方向（{@link #computeYawDeltaToTarget} と同じ vanilla yaw 換算）へ
     * {@link #OBSTACLE_LOOKAHEAD_DISTANCE} ブロック分レイキャストし、最初に当たった
     * ブロックまでの距離を返す。何も無ければ {@link Double#MAX_VALUE}。
     */
    private double findForwardObstacleDistance() {
        if (driver.world == null) {
            return Double.MAX_VALUE;
        }
        try {
            Point3D angles = vehicle.orientation.convertToAngles();
            double yawRad = Math.toRadians(-angles.y);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);

            double eyeY = vehicle.position.y + OBSTACLE_RAY_HEIGHT_OFFSET;
            Vec3d start = new Vec3d(vehicle.position.x, eyeY, vehicle.position.z);
            Vec3d end = new Vec3d(
                    vehicle.position.x + dirX * OBSTACLE_LOOKAHEAD_DISTANCE,
                    eyeY,
                    vehicle.position.z + dirZ * OBSTACLE_LOOKAHEAD_DISTANCE);

            RayTraceResult result = driver.world.rayTraceBlocks(start, end, false, true, false);
            if (result == null || result.typeOfHit != RayTraceResult.Type.BLOCK) {
                return Double.MAX_VALUE;
            }
            return start.distanceTo(result.hitVec);
        } catch (Throwable t) {
            // ワールド未ロード等の一時的な不整合。障害物なしとして扱う。
            return Double.MAX_VALUE;
        }
    }

    /**
     * バック & スタック脱出処理を開始する。現在のステア方向と逆へハンドルを切ってから
     * ギアをリバースに入れる。スタックしたと判定した場合 true を返す。
     */
    private boolean checkAndHandleStuck() {
        double speed = vehicle.velocity;
        if (stuckCooldownTicks > 0) {
            stuckCooldownTicks--;
        }
        if (speed < STUCK_SPEED_THRESHOLD && stuckCooldownTicks == 0) {
            lowSpeedTicks++;
            if (lowSpeedTicks > STUCK_DETECT_TICKS) {
                enterReverse();
                return true;
            }
        } else {
            lowSpeedTicks = 0;
        }
        return false;
    }

    private void enterReverse() {
        state = DriveState.REVERSING;
        stateTimer = 0;
        lowSpeedTicks = 0;
        double sign = currentSteering >= 0 ? 1.0D : -1.0D;
        currentSteering = -sign * MAX_STEERING_ANGLE * 0.6D;
    }

    private void tickReverse() {
        stateTimer++;

        // ハンドルは脱出方向へ保持する。
        setSteering(currentSteering);
        setParkingBrake(false);

        // 全エンジンのギアをリバースへ入れる。
        for (PartEngine engine : vehicle.engines) {
            if (engine.currentGearVar.currentValue >= 0) {
                engine.shiftDown();
            }
        }

        stepThrottleAndBrake(0.4D, false, THROTTLE_STEP_PER_TICK);

        if (stateTimer > REVERSE_ESCAPE_TICKS) {
            // 前進ギアへ戻し、一旦しっかり停止させてから走行を再開する。
            for (PartEngine engine : vehicle.engines) {
                // Loop: a car with several reverse gears may be at -2 or lower.
                for (int i = 0; i < 8 && engine.currentGearVar.currentValue < 0; i++) {
                    if (!engine.shiftUp()) {
                        break;
                    }
                }
            }
            stepThrottleAndBrake(0.0D, true, THROTTLE_STEP_PER_TICK);
            state = DriveState.ACTIVE;
            stateTimer = 0;
            stuckCooldownTicks = STUCK_COOLDOWN_TICKS;
        }
    }

    // ------------------------------------------------------------------
    // 3. 目的地到達判定 & 降車
    // ------------------------------------------------------------------

    private void tickArrive() {
        // Cut the throttle at once (it used to fall only 0.05/tick, so the car
        // coasted well past the target).
        vehicle.throttleVar.setTo(0.0D, true);
        stepThrottleAndBrake(0.0D, true, BRAKE_STEP_PER_TICK);
        setSteering(0.0D);

        // Overshot badly while stopping: drive back instead of parking here.
        if (destination != null) {
            double dist = Math.sqrt(horizontalDistanceSq(vehicle, destination.getX() + 0.5D, destination.getZ() + 0.5D));
            if (dist > arrivalRadius * 2.5D && vehicle.velocity < SHARP_TURN_MAX_SPEED) {
                state = DriveState.ACTIVE;
                return;
            }
        }

        if (vehicle.velocity < STOP_SPEED_THRESHOLD) {
            setParkingBrake(true);
            // [到着後に乗り降りを延々繰り返す不具合の修正] The destination used
            // to be kept after arriving: the mount AI re-boarded ~3 s later,
            // the car was "already inside the radius", so it parked and got
            // out again -- forever. Clear it and mark the ally as parked so it
            // stays out of the car until a new waypoint is given.
            destination = null;
            PARKED.put(driver, Boolean.TRUE);
            dismountFromVehicle();
            state = DriveState.STOPPED;
        }
    }

    /**
     * Allies that reached their waypoint and got out. The mount AI leaves them
     * alone (unless an enemy shows up) until {@link #setDestination} gives them
     * somewhere new to go.
     */
    private static final java.util.Map<EntityFriendlyCreature, Boolean> PARKED = new java.util.WeakHashMap<EntityFriendlyCreature, Boolean>();

    static boolean isParked(EntityFriendlyCreature mob) {
        return PARKED.containsKey(mob);
    }

    private void safeFullStop(EntityVehicleF_Physics veh) {
        if (veh == null) {
            return;
        }
        try {
            veh.throttleVar.setTo(0.0D, true);
            veh.brakeVar.setTo(1.0D, true);
            veh.rudderInputVar.setTo(0.0D, true);
        } catch (Throwable ignored) {
            // 車両が既に破棄されている場合など。無視して構わない。
        }
    }

    // ------------------------------------------------------------------
    // 低レベル制御ヘルパー（ComputedVariable への直接書き込み）
    // ------------------------------------------------------------------

    /** yawDelta（度、正=右へ切る必要がある）から目標ステア角を計算し、なめらかに追従させる。 */
    private void applySteering(double yawDeltaDegrees) {
        double targetSteering = MathHelper.clamp(yawDeltaDegrees * STEERING_KP, -MAX_STEERING_ANGLE, MAX_STEERING_ANGLE);
        double delta = MathHelper.clamp(targetSteering - currentSteering, -MAX_STEERING_RATE_PER_TICK, MAX_STEERING_RATE_PER_TICK);
        currentSteering = MathHelper.clamp(currentSteering + delta, -MAX_STEERING_ANGLE, MAX_STEERING_ANGLE);
        setSteering(currentSteering);
    }

    /** ステアリング角（度、IV の rudderInputVar と同じ ±45° レンジ）を直接設定する。 */
    private void setSteering(double angleDegrees) {
        vehicle.rudderInputVar.setTo(angleDegrees, true);
    }

    /** スロットル・ブレーキを目標値へ向けてなめらかに変化させる（急発進/急ブレーキを避ける）。 */
    private void stepThrottleAndBrake(double targetThrottle, boolean wantBrake, double throttleStep) {
        double curThrottle = vehicle.throttleVar.currentValue;
        double newThrottle;
        if (wantBrake) {
            // ブレーキ中はスロットルを速やかに0へ。
            newThrottle = Math.max(0.0D, curThrottle - throttleStep * 2.0D);
        } else {
            double throttleDelta = MathHelper.clamp(targetThrottle - curThrottle, -throttleStep, throttleStep);
            newThrottle = MathHelper.clamp(curThrottle + throttleDelta, 0.0D, 1.0D);
        }
        vehicle.throttleVar.setTo(newThrottle, true);

        double curBrake = vehicle.brakeVar.currentValue;
        double targetBrake = wantBrake ? 1.0D : 0.0D;
        double brakeDelta = MathHelper.clamp(targetBrake - curBrake, -BRAKE_STEP_PER_TICK, BRAKE_STEP_PER_TICK);
        double newBrake = MathHelper.clamp(curBrake + brakeDelta, 0.0D, 1.0D);
        vehicle.brakeVar.setTo(newBrake, true);
    }

    private void setParkingBrake(boolean on) {
        if (vehicle.parkingBrakeVar.isActive != on) {
            vehicle.parkingBrakeVar.setActive(on, true);
        }
    }

    // ------------------------------------------------------------------
    // 幾何計算ヘルパー
    // ------------------------------------------------------------------

    private static double horizontalDistanceSq(EntityVehicleF_Physics veh, double targetX, double targetZ) {
        double dx = targetX - veh.position.x;
        double dz = targetZ - veh.position.z;
        return dx * dx + dz * dz;
    }

    /**
     * 車両の現在の進行方向（yaw、Minecraft の座標系に準拠: 南(+Z)=0°、西(-X)=90°、
     * 北(-Z)=180°、東(+X)=-90°）と、目標座標へ向かうベクトルとの角度差を計算する。
     * 戻り値は -180〜180 度で、正の値は「右（時計回り）に切る必要がある」ことを表す。
     *
     * <p><b>重要:</b> {@code RotationMatrix#convertToAngles()} が返す {@code angles.y}
     * は IV 内部の yaw であり、これは vanilla の {@code Entity#rotationYaw} と正負が逆
     * （{@code mcinterface1122.WrapperEntity#getYaw()} が vanilla の yaw を
     * {@code -rotationYaw} として返していることからも確認できる）。
     * 一方 {@code desiredYaw} は vanilla のヨー角の定義（
     * {@code forward.x = -sin(yaw)}, {@code forward.z = cos(yaw)}）に基づいて
     * {@code atan2(-dx, dz)} で計算している。この2つを符号反転せずに直接引き算すると、
     * 実際には正しい向きを向いていても角度差が0付近にならず、常に大きな値
     * （最悪 ±180°付近）を返してしまう。これが「目的地の方向を向いているのに
     * ハンドルが左右に振れ続けて直進・加速できない」不具合の原因だった。
     * ここで {@code angles.y} を反転して vanilla 系の yaw に変換してから比較する。
     */
    private static double computeYawDeltaToTarget(EntityVehicleF_Physics veh, double targetX, double targetZ) {
        double dx = targetX - veh.position.x;
        double dz = targetZ - veh.position.z;

        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        Point3D angles = veh.orientation.convertToAngles();
        double currentYaw = -angles.y;

        double delta = desiredYaw - currentYaw;
        while (delta > 180.0D) {
            delta -= 360.0D;
        }
        while (delta < -180.0D) {
            delta += 360.0D;
        }
        return delta;
    }
}
