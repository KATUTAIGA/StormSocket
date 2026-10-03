package com.example.examplemod;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAINearestAttackableTarget;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Lets an Engender ally that is handed a pickaxe or an axe go gather
 * matching resources on its own (any modded ore for a pickaxe, any modded
 * log for an axe -- this mod's own included) and bring them back to the
 * player who equipped it.
 *
 * <p>Mirrors the pattern used by {@link EngenderTechgunsBridge} for combat
 * weapons: it watches held-item changes on {@link EntityFriendlyCreature}
 * and attaches/detaches a matching {@link EntityAIGatherResourceAlly} task.</p>
 */
public class EngenderGatheringBridge {

    /** Lower priority (higher number) than the combat AIs (priority 2), so combat always wins. */
    private static final int GATHER_AI_PRIORITY = 4;
    /** [整地・拠点建設] same priority band as gathering -- building never outranks combat either. */
    private static final int BUILD_AI_PRIORITY = 4;
    /**
     * Highest priority of all (lowest number): per vanilla EntityAITasks
     * rules, a task with a lower priority number always wins a mutex
     * conflict, so the "never lose" survival brain can interrupt combat
     * (priority 2) or gathering (priority 4) the instant it needs to.
     */
    private static final int SURVIVE_AI_PRIORITY = 1;
    /** How many ticks after a player right-clicks the ally we still credit them as the giver. */
    private static final int OWNER_MEMORY_TICKS = 40;
    /** How long a "just got hit" flag stays true after a sudden health drop. */
    private static final int RECENT_HIT_FLAG_TICKS = 20;
    /** How close a hostile has to be before a held shield goes up. */
    private static final double SHIELD_THREAT_SCAN_RADIUS = 4.0;
    /** [視線・索敵] priority for the ally's own hostile-target radar. */
    private static final int TARGETING_AI_PRIORITY = 1;

    /** [仕事] エンティティの永続データに保存する仕事（pickaxe / axe / shovel / 空=なし）。 */
    private static final String JOB_KEY = "EngenderJob";
    private static final String JOB_INIT_KEY = "EngenderJobInit";
    /** 敵がいなくなってから仕事の道具に持ち替えるまでのTick。 */
    private static final int COMBAT_COOLDOWN_TICKS = 40;
    private static final double COMBAT_RANGE_SQ = 48.0 * 48.0;

    private final Map<EntityFriendlyCreature, String> managedJob = new WeakHashMap<EntityFriendlyCreature, String>();
    private final Map<EntityFriendlyCreature, Long> lastFightTick = new WeakHashMap<EntityFriendlyCreature, Long>();
    private final Map<EntityFriendlyCreature, EntityAIBase> managedTask = new WeakHashMap<EntityFriendlyCreature, EntityAIBase>();
    private final Map<EntityFriendlyCreature, EntityAIBase> managedSurviveTask = new WeakHashMap<EntityFriendlyCreature, EntityAIBase>();
    private final Map<EntityFriendlyCreature, EntityAIBase> managedTargetTask = new WeakHashMap<EntityFriendlyCreature, EntityAIBase>();
    /** [チーム・避難] 全味方に常時つけるAI。 */
    private final Map<EntityFriendlyCreature, EntityAIBase> managedTeamTask = new WeakHashMap<EntityFriendlyCreature, EntityAIBase>();
    private final Map<EntityFriendlyCreature, EntityAIBase> managedShelterTask = new WeakHashMap<EntityFriendlyCreature, EntityAIBase>();
    /** 避難AIの優先度。戦闘AI（2）と同じで、戦闘中は起動しない。採取/クエストより上。 */
    private static final int SHELTER_AI_PRIORITY = 2;
    private static final int TEAM_AI_PRIORITY = 4;
    private final Map<EntityFriendlyCreature, EntityPlayer> ownerPlayer = new WeakHashMap<EntityFriendlyCreature, EntityPlayer>();
    private final Map<EntityFriendlyCreature, EntityPlayer> pendingInteractPlayer = new WeakHashMap<EntityFriendlyCreature, EntityPlayer>();
    private final Map<EntityFriendlyCreature, Integer> pendingInteractTick = new WeakHashMap<EntityFriendlyCreature, Integer>();
    private final Map<EntityFriendlyCreature, Float> lastHealth = new WeakHashMap<EntityFriendlyCreature, Float>();
    private final Map<EntityFriendlyCreature, Integer> recentHitTicks = new WeakHashMap<EntityFriendlyCreature, Integer>();

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityFriendlyCreature) {
            EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntity();
            this.removeManagedGatherAI(entity);
            this.managedJob.remove(entity);
            this.lastFightTick.remove(entity);
            this.ownerPlayer.remove(entity);
            this.lastHealth.remove(entity);
            this.recentHitTicks.remove(entity);
            this.managedTargetTask.remove(entity);
            this.managedTeamTask.remove(entity);
            this.managedShelterTask.remove(entity);
            TargetRegistry.releaseAll(entity);
            // Idle wander/look tasks from the base mob used to outrank (and
            // randomly interrupt) gathering, building and quest work.
            AllyAIUtil.demoteIdleTasks(entity);
            // The survival AI is unconditional -- every ally gets it the
            // moment it (re)joins the world, regardless of what it's holding.
            this.ensureSurviveAI(entity);
            // [視線・索敵] likewise unconditional: without this, an ally
            // never notices a hostile mob on its own at all (attackTarget
            // only ever got set reactively, after the player was already
            // hit) -- this is what made allies look like they never even
            // turned to face a threat.
            this.ensureHostileTargetingAI(entity);
        }
    }

    /**
     * Fired when a player right-clicks the ally. We don't yet know whether
     * this interaction is what hands over the tool (that's entirely the
     * ally's own equip logic), so we just remember the candidate giver and
     * let {@link #onLivingUpdate} confirm it once the held item actually
     * changes to a pickaxe/axe on one of the next few ticks.
     */
    // [アイテム受け渡し] receiveCanceled=true: EngenderItemHandoffBridge now
    // legitimately cancels this event (ammo/material hand-off, or a tool
    // equip it performs itself), and owner attribution must still register
    // in every one of those cases -- not just for interactions nothing else handled.
    @SubscribeEvent(receiveCanceled = true)
    public void onPlayerInteractWithAlly(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntityPlayer().getEntityWorld().isRemote) {
            return;
        }
        if (event.getTarget() instanceof EntityFriendlyCreature) {
            EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getTarget();
            this.pendingInteractPlayer.put(entity, event.getEntityPlayer());
            this.pendingInteractTick.put(entity, this.now(entity));
        }
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
        if (!(event.getEntityLiving() instanceof EntityFriendlyCreature)) {
            return;
        }
        EntityFriendlyCreature entity = (EntityFriendlyCreature) event.getEntityLiving();
        if (entity.getEntityWorld().isRemote) {
            return;
        }

        // Safety net in case an ally never fired EntityJoinWorldEvent through
        // us (e.g. it already existed when this AI was added to the mod).
        this.ensureSurviveAI(entity);
        this.ensureHostileTargetingAI(entity);
        this.ensureTeamAndShelterAI(entity);
        // [自己メンテナンス] 道具の手入れ・食事（1秒に1回）
        if (entity.ticksExisted % 20 == 0) {
            AllyMaintenance.tick(entity, this);
        }
        // [チーム編成] 2秒に1回、味方ごとにずらして見直す
        if ((entity.ticksExisted + entity.getEntityId()) % 40 == 0) {
            AllyTeamManager.update(entity, this);
        }
        // These two run every tick regardless of which AI currently owns the
        // move/look mutex, so a melee hit is noticed instantly and a held
        // shield is raised/lowered without needing to fight for control.
        this.trackHealthForHitDetection(entity);
        this.maybeBlockWithShield(entity);

        // [戦闘は銃・仕事は道具] 敵がいれば持ち物から銃（無ければ剣）に持ち替え、
        // いなくなったら仕事の道具に戻す。
        String job = this.getJob(entity);
        this.updateCombatLoadout(entity, job);

        ItemStack mainhand = entity.getHeldItemMainhand();
        Item current = mainhand.isEmpty() ? null : mainhand.getItem();
        // Keep the base mob's own melee AI out of the way while a ranged gun
        // is held -- otherwise it always wins the priority-2 tie and the gun
        // AI never runs (see AllyAIUtil.updateMeleeSuppression).
        AllyAIUtil.updateMeleeSuppression(entity, isRangedGun(current));

        // 以前の版で道具を持たせてあった味方は、初回だけその道具を仕事として引き継ぐ。
        // （以後は手に持った物では仕事は変わらない。クエストAIがツルハシに持ち替えても木こりのまま。）
        if (!entity.getEntityData().getBoolean(JOB_INIT_KEY)) {
            entity.getEntityData().setBoolean(JOB_INIT_KEY, true);
            String fromHand = jobForTool(mainhand);
            if (job.isEmpty() && fromHand != null) {
                this.setJob(entity, fromHand);
                job = fromHand;
            }
        }
        // 仕事は手に持っている物ではなく「仕事」で決まる。持ち替えても採取/建築は続く。
        String running = this.managedJob.get(entity);
        if (!job.equals(running)) {
            this.applyJob(entity, job);
        }
    }

    // ------------------------------------------------------------------
    // 仕事
    // ------------------------------------------------------------------

    /** 渡された道具から仕事を決める（ツルハシ=採掘 / 斧=木こり / シャベル=整地建築）。 */
    public static String jobForTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Set<String> classes = stack.getItem().getToolClasses(stack);
        if (classes.contains("pickaxe")) {
            return "pickaxe";
        }
        if (classes.contains("axe")) {
            return "axe";
        }
        if (classes.contains("shovel")) {
            return "shovel";
        }
        return null;
    }

    public static String jobLabel(String job) {
        if ("pickaxe".equals(job)) {
            return "採掘";
        }
        if ("axe".equals(job)) {
            return "木こり";
        }
        if ("shovel".equals(job)) {
            return "整地・建築";
        }
        return "なし（護衛）";
    }

    public String getJob(EntityFriendlyCreature entity) {
        return entity.getEntityData().getString(JOB_KEY);
    }

    public void setJob(EntityFriendlyCreature entity, String job) {
        this.adoptPendingOwner(entity);
        entity.getEntityData().setString(JOB_KEY, job == null ? "" : job);
        // 家が完成した後にもう一度シャベルを渡されたら、新しい場所で整地・建築をやり直す
        EntityAIBase task = this.managedTask.get(entity);
        if ("shovel".equals(job) && task instanceof EntityAIBuildBaseAlly && ((EntityAIBuildBaseAlly) task).isDone()) {
            ((EntityAIBuildBaseAlly) task).restart();
        }
    }

    private final Map<EntityFriendlyCreature, Boolean> armedCache = new WeakHashMap<EntityFriendlyCreature, Boolean>();

    /** 手か持ち物に銃・剣がある（1秒ごとに更新するキャッシュ）。 */
    public boolean isArmed(EntityFriendlyCreature entity) {
        Boolean b = this.armedCache.get(entity);
        return b != null && b;
    }

    /**
     * 本当に戦っている時だけ true（道具への持ち替え・避難・手入れを控える）。
     * 常時レーダーは見えた敵を全部狙うので、「狙っているだけ」では戦闘扱いにしない:
     * 武器を持っている／最近殴られた／敵が6ブロック以内、のどれか。
     */
    public boolean isFighting(EntityFriendlyCreature entity) {
        EntityLivingBase target = entity.getAttackTarget();
        if (target != null && target.isEntityAlive() && entity.getDistanceSq(target) < COMBAT_RANGE_SQ
                && (this.isArmed(entity) || this.hasRecentlyBeenHit(entity) || entity.getDistanceSq(target) < 6.0 * 6.0)) {
            return true;
        }
        Long last = this.lastFightTick.get(entity);
        return last != null && this.now(entity) - last < COMBAT_COOLDOWN_TICKS;
    }

    private void updateCombatLoadout(EntityFriendlyCreature entity, String job) {
        if (entity.ticksExisted % 5 != 0) {
            return;
        }
        if (entity.ticksExisted % 20 == 0) {
            this.armedCache.put(entity, AllyTeamManager.hasWeapon(entity));
        }
        EntityLivingBase target = entity.getAttackTarget();
        boolean fighting = target != null && target.isEntityAlive() && entity.getDistanceSq(target) < COMBAT_RANGE_SQ
                && (this.isArmed(entity) || this.hasRecentlyBeenHit(entity));
        if (fighting) {
            this.lastFightTick.put(entity, (long) this.now(entity));
            try {
                AllyToolManager.equipWeapon(entity);
            } catch (Throwable ignored) {
                // 武器が無ければ今の手持ちのまま
            }
            return;
        }
        Long last = this.lastFightTick.get(entity);
        if (last != null && this.now(entity) - last >= COMBAT_COOLDOWN_TICKS) {
            this.lastFightTick.remove(entity);
            if (!job.isEmpty() && AllyToolManager.isRangedGun(entity.getHeldItemMainhand())
                    || !job.isEmpty() && entity.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemSword) {
                try {
                    AllyToolManager.equipToolClass(entity, job, null);
                } catch (Throwable ignored) {
                    // 道具が無ければ採取AIが自分で作る
                }
            }
        }
    }

    private void applyJob(EntityFriendlyCreature entity, String job) {
        this.removeManagedGatherAI(entity);
        this.managedJob.put(entity, job);
        if ("shovel".equals(job)) {
            // [整地・拠点建設] a shovel means "go flatten and build".
            EntityAIBase buildTask = new EntityAIBuildBaseAlly(entity, this);
            entity.tasks.addTask(BUILD_AI_PRIORITY, buildTask);
            this.managedTask.put(entity, buildTask);
        } else if ("pickaxe".equals(job) || "axe".equals(job)) {
            EntityAIBase newTask = new EntityAIGatherResourceAlly(entity, this, job);
            entity.tasks.addTask(GATHER_AI_PRIORITY, newTask);
            this.managedTask.put(entity, newTask);
        }
    }

    private static boolean isRangedGun(Item item) {
        if (item == null) {
            return false;
        }
        if (item instanceof techguns.items.guns.GenericGun && !(item instanceof techguns.items.guns.IGenericGunMelee)) {
            return true;
        }
        return EngenderFlanBridge.isFlanGun(item) || EngenderHBMBridge.isHbmGun(item);
    }

    private void ensureTeamAndShelterAI(EntityFriendlyCreature entity) {
        if (!this.managedTeamTask.containsKey(entity)) {
            EntityAIBase team = new EntityAITeamRole(entity, this);
            entity.tasks.addTask(TEAM_AI_PRIORITY, team);
            this.managedTeamTask.put(entity, team);
        }
        if (!this.managedShelterTask.containsKey(entity)) {
            EntityAIBase shelter = new EntityAIShelterAlly(entity, this);
            entity.tasks.addTask(SHELTER_AI_PRIORITY, shelter);
            this.managedShelterTask.put(entity, shelter);
        }
    }

    /** [チーム] その味方の採取AI（斧/ツルハシの仕事中のみ）。 */
    public EntityAIGatherResourceAlly getGatherTask(EntityFriendlyCreature entity) {
        EntityAIBase t = this.managedTask.get(entity);
        return t instanceof EntityAIGatherResourceAlly ? (EntityAIGatherResourceAlly) t : null;
    }

    /** [熟練度・戦闘] 味方が敵を倒したら戦闘スキルの経験値。 */
    @SubscribeEvent
    public void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntityLiving().world.isRemote) {
            return;
        }
        if (event.getEntityLiving() instanceof EntityFriendlyCreature) {
            // 味方が倒れたら、持ち物（と採取中の荷物）をその場に落とす（消さない）
            EntityFriendlyCreature dead = (EntityFriendlyCreature) event.getEntityLiving();
            EntityAIGatherResourceAlly gather = this.getGatherTask(dead);
            if (gather != null) {
                gather.dropEverythingCarried();
            }
            EntityAIBase t = this.managedTask.get(dead);
            if (t instanceof EntityAIBuildBaseAlly) {
                ((EntityAIBuildBaseAlly) t).dropStoredMaterials();
            }
            AllyInventory.dropAll(dead);
            TargetRegistry.releaseAll(dead);
        }
        net.minecraft.entity.Entity src = event.getSource().getTrueSource();
        if (src instanceof EntityFriendlyCreature && event.getEntityLiving() instanceof net.minecraft.entity.monster.IMob) {
            AllySkills.addXp((EntityFriendlyCreature) src, AllySkills.Skill.COMBAT, 5);
        }
    }

    private void adoptPendingOwner(EntityFriendlyCreature entity) {
        Integer interactedAt = this.pendingInteractTick.get(entity);
        EntityPlayer candidate = this.pendingInteractPlayer.get(entity);
        if (candidate != null && interactedAt != null && this.now(entity) - interactedAt <= OWNER_MEMORY_TICKS) {
            this.ownerPlayer.put(entity, candidate);
        }
    }

    /**
     * A real per-world clock. The old {@code tickCounter++} ran once per ally
     * per tick, so with N allies loaded every "N ticks" window was N times too
     * short (the 40-tick owner memory expired almost instantly with many allies).
     */
    private int now(EntityFriendlyCreature entity) {
        return (int) entity.getEntityWorld().getTotalWorldTime();
    }

    private void removeManagedGatherAI(EntityFriendlyCreature entity) {
        EntityAIBase existing = this.managedTask.remove(entity);
        if (existing != null) {
            entity.tasks.removeTask(existing);
            // Swapping tools used to silently delete everything the old task
            // was carrying (up to 128 gathered items + scaffold/build stock).
            if (existing instanceof EntityAIGatherResourceAlly) {
                ((EntityAIGatherResourceAlly) existing).dropEverythingCarried();
            } else if (existing instanceof EntityAIBuildBaseAlly) {
                ((EntityAIBuildBaseAlly) existing).dropStoredMaterials();
            }
        }
    }

    /**
     * Used by every ally AI to know who "their" player is. Prefers Engender's
     * own {@code getOwner()} (the real tamed owner) and only falls back to the
     * player who last handed over a tool. Before, only the latter was used, so
     * an ally that had never been handed a tool counted as ownerless.
     */
    public EntityPlayer getOwnerPlayer(EntityFriendlyCreature entity) {
        return AllyAIUtil.resolveOwnerPlayer(entity, this);
    }

    /** Raw "last player who handed this ally a tool" record (no fallback). */
    public EntityPlayer getRecordedHandoffPlayer(EntityFriendlyCreature entity) {
        return this.ownerPlayer.get(entity);
    }

    /**
     * [整地・拠点建設] used by {@link EngenderItemHandoffBridge} to route a
     * block item the player hands a shovel-mode ally straight into its
     * build material stock, instead of it being equipped (there was no
     * material-delivery path at all before). Returns false (and leaves the
     * item alone) if the ally isn't currently running the build AI.
     */
    /** たいまつ/棒/石炭を採取中の味方に「たいまつ資材」として渡す。採取AIが無ければ false。 */
    public boolean giveTorchSupplies(EntityFriendlyCreature entity, ItemStack stack) {
        EntityAIBase task = this.managedTask.get(entity);
        if (!(task instanceof EntityAIGatherResourceAlly)) {
            return false;
        }
        ((EntityAIGatherResourceAlly) task).receiveTorchSupplies(stack);
        return true;
    }

    public boolean giveMaterialToBuilder(EntityFriendlyCreature entity, ItemStack material) {
        EntityAIBase task = this.managedTask.get(entity);
        // A finished build used to "accept" materials (with a thank-you chat
        // message) and then never use or return them.
        if (!(task instanceof EntityAIBuildBaseAlly) || !((EntityAIBuildBaseAlly) task).acceptsMaterials()) {
            return false;
        }
        ((EntityAIBuildBaseAlly) task).receiveMaterial(material);
        return true;
    }

    // ------------------------------------------------------------------
    // "Never lose" survival AI wiring
    // ------------------------------------------------------------------

    private void ensureSurviveAI(EntityFriendlyCreature entity) {
        if (this.managedSurviveTask.containsKey(entity)) {
            return;
        }
        EntityAIBase surviveTask = new EntityAISurviveAndCombatAlly(entity, this);
        entity.tasks.addTask(SURVIVE_AI_PRIORITY, surviveTask);
        this.managedSurviveTask.put(entity, surviveTask);
    }

    /**
     * [視線・索敵] gives every ally a basic "notice a nearby hostile on my
     * own" radar. Before this, {@code attackTarget} was only ever set
     * reactively (see {@link EngenderTechgunsBridge#onLivingHurt}) after the
     * player or the ally itself had already traded a hit with something --
     * an ally standing near an undetected zombie would never even turn to
     * look at it. This is added to {@code targetTasks} (target selection),
     * completely separate from the {@code tasks} (behavior) mutex the
     * gather/build/combat AIs share, so it can't conflict with any of them.
     */
    private void ensureHostileTargetingAI(EntityFriendlyCreature entity) {
        if (this.managedTargetTask.containsKey(entity)) {
            return;
        }
        EntityAIBase targetTask = new EntityAINearestAttackableTarget<EntityMob>(entity, EntityMob.class, true);
        entity.targetTasks.addTask(TARGETING_AI_PRIORITY, targetTask);
        this.managedTargetTask.put(entity, targetTask);
    }

    /** Used by {@link EntityAISurviveAndCombatAlly} to trigger an immediate "hit & away" backstep. */
    public boolean hasRecentlyBeenHit(EntityFriendlyCreature entity) {
        Integer ticksLeft = this.recentHitTicks.get(entity);
        return ticksLeft != null && ticksLeft > 0;
    }

    /** Notices a sudden health drop the instant it happens, independent of which AI currently has control. */
    private void trackHealthForHitDetection(EntityFriendlyCreature entity) {
        float current = entity.getHealth();
        Float previous = this.lastHealth.put(entity, current);
        if (previous != null && current < previous - 0.01f) {
            this.recentHitTicks.put(entity, RECENT_HIT_FLAG_TICKS);
            return;
        }
        Integer ticksLeft = this.recentHitTicks.get(entity);
        if (ticksLeft != null) {
            if (ticksLeft <= 1) {
                this.recentHitTicks.remove(entity);
            } else {
                this.recentHitTicks.put(entity, ticksLeft - 1);
            }
        }
    }

    /** Requirement 7: auto-raise a held shield whenever something hostile is close, lower it otherwise. */
    private void maybeBlockWithShield(EntityFriendlyCreature entity) {
        ItemStack offhand = entity.getHeldItemOffhand();
        if (offhand == null || offhand.isEmpty() || !(offhand.getItem() instanceof ItemShield)) {
            return;
        }
        World world = entity.getEntityWorld();
        boolean threatNear = this.hasNearbyHostile(entity, world);
        if (threatNear) {
            if (!entity.isHandActive()) {
                entity.setActiveHand(EnumHand.OFF_HAND);
            }
        } else if (entity.isHandActive()) {
            entity.resetActiveHand();
        }
    }

    private boolean hasNearbyHostile(EntityFriendlyCreature entity, World world) {
        AxisAlignedBB area = new AxisAlignedBB(new BlockPos(entity))
                .grow(SHIELD_THREAT_SCAN_RADIUS, SHIELD_THREAT_SCAN_RADIUS, SHIELD_THREAT_SCAN_RADIUS);
        List<EntityMob> mobs = world.getEntitiesWithinAABB(EntityMob.class, area);
        return mobs != null && !mobs.isEmpty();
    }
}
