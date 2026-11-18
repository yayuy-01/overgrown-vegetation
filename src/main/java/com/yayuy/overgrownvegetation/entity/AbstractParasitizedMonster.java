package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.corpse.RisingMob;
import com.yayuy.overgrownvegetation.corpse.RisingSupport;
import com.yayuy.overgrownvegetation.entity.g1.G1HurtByTargetGoal;
import com.yayuy.overgrownvegetation.entity.g1.G1NightWanderGoal;
import com.yayuy.overgrownvegetation.entity.g1.G1StrikeGoal;
import com.yayuy.overgrownvegetation.entity.g1.G1TargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * G1 段階 0 の感染体の共通部分(2026-11-08、作り直し 2 回目の組み込み。仕様の正本は Vault「Mob の能力/G1 段階 0 の共通」)。
 *
 * <p>11 種とも {@link Monster} から作る(バニラの牛・クモ・クリーパーなどを継がない)。段階 0 は「歩いて殴る・噛むだけ」で、
 * 元の Mob の能力(爆発・弓・壁登り・テレポート・卵・毛刈り…)は使わない(10-22 決定、11-05 ユーザー再確認)。バニラを継ぐと
 * その能力を種ごとに外す手間が増えるので、外す物の無い Monster から作る。Monster は {@code Enemy} なので、鉄のゴーレム・
 * スノーゴーレムは元の Mob が何であっても G1 を狙う(11-05「G1〜G10 はすべての Mob から平等に敵対される」の一部)。
 * 日の光で燃えない(Monster は燃えない)。
 *
 * <ul>
 *   <li>死体から起き上がる({@link RisingMob})。起き上がりの間は AI を止める(長さは種の表 {@link G1Species#riseTicks})。</li>
 *   <li>攻撃は G1 専用の 3 段の 1 撃 {@link G1StrikeGoal}(前触れ 12・実行 3・隙 14 tick。11-08 ユーザー決定)。段階は同期データ
 *   {@link #ACTION} でクライアントへ送り、アニメ {@code attack_windup}・{@code attack}・{@code attack_recover} を流す。</li>
 *   <li>どの生き物も襲う {@link G1TargetGoal}。夜は明かり・生き物の多い方へ歩く {@link G1NightWanderGoal}。</li>
 *   <li>歩きのアニメは実際の速さに合わせる({@link PhaseKeepingAnimationController} + {@link MobAnimation#walkSpeedFor}、11-05 ユーザー)。</li>
 *   <li>遠くへ離れても消えない(死体から起きた家畜の G1 が 128 ブロックで消えないように)。</li>
 * </ul>
 */
public abstract class AbstractParasitizedMonster extends Monster implements ParasitizedMob, GeoEntity, RisingMob {

    protected static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    protected static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    protected static final RawAnimation WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("attack_windup");
    protected static final RawAnimation STRIKE_ANIM = RawAnimation.begin().thenPlayAndHold("attack");
    protected static final RawAnimation RECOVER_ANIM = RawAnimation.begin().thenPlay("attack_recover");

    /** 行動の段階(同期)。 */
    public static final int ACTION_NONE = 0;
    public static final int ACTION_WINDUP = 1;
    public static final int ACTION_STRIKE = 2;
    public static final int ACTION_RECOVER = 3;

    private static final EntityDataAccessor<Integer> RISING =
            SynchedEntityData.defineId(AbstractParasitizedMonster.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACTION =
            SynchedEntityData.defineId(AbstractParasitizedMonster.class, EntityDataSerializers.INT);
    /** 司令塔が決めた変化の前兆(0 = 無し、1 = 進化の前の震え。2026-11-13)。 */
    private static final EntityDataAccessor<Integer> OMEN =
            SynchedEntityData.defineId(AbstractParasitizedMonster.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected AbstractParasitizedMonster(EntityType<? extends AbstractParasitizedMonster> type, Level level) {
        super(type, level);
    }

    /** この G1 の種(表の 1 行)。 */
    public abstract G1Species species();

    /** 3 段の 1 撃を持つか(既定は種の表。1 段目は種ごとの表)。 */
    public boolean attacks() {
        return species().attacks();
    }

    /** 歩きのアニメの足が滑らない速さ(ブロック/tick)。 */
    public double walkRef() {
        return species().walkRef();
    }

    /** 3 段の 1 撃の隙(tick、アニメ {@code attack_recover} の長さ)。段階 0 は全種 14。 */
    public int recoverTicks() {
        return G1StrikeGoal.RECOVER;
    }

    // ------------------------------------------------------------------ 1 段目の技の口(2026-11-12、能力の第 1 回)
    /** よろけ・転んでいる残り(サーバーだけ。保存しない)。 */
    private int stunTicks;

    /**
     * 3 段の 1 撃を振り出せない・追えない間か({@link G1StrikeGoal} が見る)。既定は、よろけている間と、ほかの Mob に乗っている間
     * (豚の背のチビゾンビが背から殴らないように)。1 段目は取り付き・種の技の間も足す。
     */
    public boolean busy() {
        return stunTicks > 0 || isPassenger();
    }

    /** よろける・転ぶ(その間は動かず殴らない)。豚から落ちたチビゾンビの 1 秒など。 */
    public void stun(int ticks) {
        stunTicks = Math.max(stunTicks, ticks);
        getNavigation().stop();
    }

    public boolean isStunned() {
        return stunTicks > 0;
    }

    /** 3 段の 1 撃が当たった(盾で防がれたら呼ばれない)。1 段目のチビゾンビはここで取り付く。 */
    public void onStrikeHit(net.minecraft.world.entity.LivingEntity target) {
    }

    /** 3 段の 1 撃が盾で防がれた(2026-11-13、1 段目の (a))。既定は何もしない。1 段目のゾンビ・村人は盾ごしに押す。 */
    public void onStrikeBlocked(net.minecraft.world.entity.LivingEntity target) {
    }

    /** 3 段の 1 撃が隙まで終わった(1 段目は次の技までの間を数え始める)。{@code gap} = 次の 1 撃までの間(tick)。 */
    public void onStrikeEnd(int gap) {
    }

    /**
     * 3 段の 1 撃を振り出してよい距離か(2026-11-13、1 段目の (a))。既定は間合いの中(バニラの {@code isWithinMeleeAttackRange})。
     * 1 段目の牛・羊は踏み込みのぶん遠くから振り出す。
     */
    public boolean strikeInRange(net.minecraft.world.entity.LivingEntity target) {
        return isWithinMeleeAttackRange(target);
    }

    /**
     * 3 段の 1 撃の実行の段の毎 tick(当たりの判定の前に呼ばれる。2026-11-13、1 段目の (a))。既定は何もしない。
     * 1 段目の牛・羊はここでまっすぐ前へ踏み込む。
     *
     * @param tick   実行の段の中の tick(1〜{@link G1StrikeGoal#STRIKE})
     * @param yaw    止めた向き(度)
     * @param target 標的(消えていれば null)
     */
    public void onStrikeTick(int tick, float yaw, @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity target) {
    }

    /**
     * 次の 1 撃までの間、届く所({@link #strikeInRange})では寄らずに待つか(2026-11-13、1 段目の (a))。既定は寄り続ける(段階 0)。
     * 1 段目の牛・羊(踏み込み)と鶏(伸びる首)は、寄ってしまうと踏み込み・長い間合いが見えなくなるので待つ。
     */
    public boolean strikeHoldsDistance() {
        return false;
    }

    /** 次の 1 撃を振り出してよいか(1 段目は種の技の直後に間を空ける)。 */
    public boolean strikeReady() {
        return true;
    }

    /** 行動の段に入るときの補間(tick)。段階 0 は前触れ 2・段の間 0・種の行動(クリーパーの puff)4。 */
    protected int actionTransition(int action) {
        return action == ACTION_WINDUP ? 2 : action > ACTION_RECOVER ? 4 : 0;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 司令塔が根付きに決めた個体は、森から離れて歩く(1 撃より先。決まっていない個体・1 段目では動かない。2026-11-13)
        this.goalSelector.addGoal(1, new com.yayuy.overgrownvegetation.director.G1WalkAwayGoal(this));
        // 司令塔の偵察に出された個体は、拠点を眺めに行って帰る(第 2 段、2026-11-14)
        this.goalSelector.addGoal(1, new com.yayuy.overgrownvegetation.director.G1ScoutGoal(this));
        // 森が貯めている間は、襲わず遠巻きにうろつく(第 3 段「貯めて一気に使う」。1 段目も持つ)
        this.goalSelector.addGoal(1, new com.yayuy.overgrownvegetation.director.G1HoldOffGoal(this));
        // 司令塔が認識した拠点へ送り込まれた個体は、拠点へ歩く(獲物がいればそちらが先。1 段目も持つ)
        this.goalSelector.addGoal(3, new com.yayuy.overgrownvegetation.director.G1MarchGoal(this));
        if (attacks()) {
            this.goalSelector.addGoal(2, new G1StrikeGoal(this));
        }
        this.goalSelector.addGoal(5, new G1NightWanderGoal(this));
        // うろつき: 4 つ目の引数 false = 創造・観戦のプレイヤーのそばでも止まらない(記憶 vanilla-stroll-stops-near-creative)。水は避ける。
        this.goalSelector.addGoal(7, new RandomStrollGoal(this, 0.8D, 120, false) {
            @Nullable
            @Override
            protected Vec3 getPosition() {
                return LandRandomPos.getPos(this.mob, 10, 7);
            }
        });
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        if (attacks()) {
            this.targetSelector.addGoal(1, new G1HurtByTargetGoal(this));
            this.targetSelector.addGoal(2, new G1TargetGoal(this));
        }
    }

    // ------------------------------------------------------------------ 同期データ
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(RISING, 0);
        builder.define(ACTION, ACTION_NONE);
        builder.define(OMEN, 0);
    }

    // ------------------------------------------------------------------ 司令塔(2026-11-13、第 1 段)
    /** 司令塔との約束(見に来る時刻・決まった道)。持たない個体(1 段目・ゴーレム・ウォーデン)は null。作るのは最初に要るとき。 */
    @Nullable
    private com.yayuy.overgrownvegetation.director.G1Fate fate;
    private boolean fateChecked;
    /** 前兆が始まってからの tick(クライアントだけ。震えと膨らみの強さ)。 */
    private int omenClientTicks;

    /** 司令塔が見に来る個体か。既定は「1 段目のある種の段階 0」。 */
    protected boolean directorVisits() {
        return com.yayuy.overgrownvegetation.director.G1Fate.developedOf(this) != null;
    }

    /**
     * 進化・根付きの入れ替えで消える前に、拾った持ち物(手・防具)を足元に落とす(11-18 見直し ①4。前は写されも落ちもせず消えた)。
     * バニラがプレイヤーに倒されずに死んだときと同じく、落ちるのは拾った物(落とす確率が 1 を超える物)だけ。湧いたときの持ち物は落とさない。
     */
    public void dropPickedUpForSwap() {
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            net.minecraft.world.item.ItemStack s = getItemBySlot(slot);
            if (s.isEmpty() || getEquipmentDropChance(slot) <= 1.0F) continue;
            if (net.minecraft.world.item.enchantment.EnchantmentHelper.has(s,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) continue;
            spawnAtLocation(s);
            setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    /**
     * 入れ替え先へ、減っていた体力の割合・効果・火を写す(11-18 見直し ①4。前は満タンで出て、効果と火が消えていた)。
     */
    public void copyConditionTo(net.minecraft.world.entity.LivingEntity next) {
        float ratio = getMaxHealth() > 0.0F ? getHealth() / getMaxHealth() : 1.0F;
        next.setHealth(Math.max(1.0F, next.getMaxHealth() * Math.min(1.0F, ratio)));
        for (net.minecraft.world.effect.MobEffectInstance e : getActiveEffects()) next.addEffect(new net.minecraft.world.effect.MobEffectInstance(e));
        next.setRemainingFireTicks(getRemainingFireTicks());
    }

    /** 司令塔との約束。持たない個体は null。 */
    @Nullable
    public com.yayuy.overgrownvegetation.director.G1Fate fate() {
        if (!fateChecked) {
            fateChecked = true;
            fate = directorVisits() ? new com.yayuy.overgrownvegetation.director.G1Fate(this) : null;
        }
        return fate;
    }

    /** 拠点へ攻めて行く使い(司令塔 第 2 段。段階 0 も 1 段目も持つ)。 */
    private final com.yayuy.overgrownvegetation.director.G1March march = new com.yayuy.overgrownvegetation.director.G1March();

    public com.yayuy.overgrownvegetation.director.G1March march() {
        return march;
    }

    /** 前兆の種類({@code G1Fate.OMEN_*}。0 = 無し)。前兆の間は立ち止まる。 */
    public int omen() {
        return this.entityData.get(OMEN);
    }

    public void setOmen(int omen) {
        this.entityData.set(OMEN, omen);
    }

    /** 前兆が始まってからの tick(クライアント。描画の間を埋める)。 */
    public float omenClientTicks(float partialTick) {
        return omenClientTicks + partialTick;
    }

    /** 間合いの箱(バニラの {@code getAttackBoundingBox}。エンダーマンは長い腕で広げる)。{@link G1StrikeGoal} の当たりの判定が使う。 */
    public net.minecraft.world.phys.AABB getAttackBoundingBoxForG1() {
        return getAttackBoundingBox();
    }

    public int action() {
        return this.entityData.get(ACTION);
    }

    public void setAction(int action) {
        this.entityData.set(ACTION, action);
    }

    // ------------------------------------------------------------------ 起き上がり
    /** 死体から起き上がるとき、死体の記録を引き継ぐ(羊の毛の色)。既定は何もしない。 */
    public void inheritCorpse(com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity corpse) {
    }

    @Override
    public void startRising(int ticks) {
        this.entityData.set(RISING, ticks);
    }

    @Override
    public boolean isRising() {
        return this.entityData.get(RISING) > 0;
    }

    /** 起き上がりの間は AI を止める(Mob#isEffectiveAi が見る)。 */
    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isRising() || omen() != 0; // 前兆の間も立ち止まる(司令塔、11-13)
    }

    @Override
    public void tick() {
        super.tick();
        RisingSupport.tick(this, RISING);
        if (level().isClientSide()) {
            omenClientTicks = omen() != 0 ? omenClientTicks + 1 : 0;
        } else if (fate() != null) {
            fate.tick(); // 司令塔が見に来る時刻・前兆・入れ替え
        }
        if (!level().isClientSide()) march.step(position()); // 拠点へ向かう使いの時間と着いたか(第 2 段)
        if (stunTicks > 0 && !level().isClientSide()) {
            stunTicks--;
            getNavigation().stop();
        }
    }

    // ------------------------------------------------------------------ 殴ってきた相手の記録(司令塔の「戦っている最中」「殴り返す」用。2026-11-13)
    /** 最後に殴ってきた相手と、その時刻(この Mob の tickCount)。バニラの lastHurtByMob は 100 tick で消えるので、10 秒を数えるために自前で持つ。保存しない。 */
    @Nullable
    private net.minecraft.world.entity.LivingEntity combatBy;
    private int combatByAt = -100000;

    @Nullable
    public net.minecraft.world.entity.LivingEntity combatBy() {
        return combatBy;
    }

    public int combatByAt() {
        return combatByAt;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide() && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity by && by != this) {
            combatBy = by;
            combatByAt = tickCount;
        }
        return hurt;
    }

    /** 死体から起きた家畜・村人の G1 も、遠くへ離れて消えないように(自然湧きが無いので、消えると戻らない)。 */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Rising", RisingSupport.remaining(this, RISING));
        if (fate() != null) fate.save(tag);
        march.save(tag);
        // バニラは isNoAi() が真なら NoAI を保存し、読み込みで本物の印にする。起き上がり・前兆・evolve_in の途中で保存された個体が
        // 読み込み後ずっと固まらないように、一時的に止まっているだけなら書かない(2026-11-13)
        if (!super.isNoAi()) tag.remove("NoAI");
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(RISING, tag.getInt("Rising"));
        if (fate() != null) fate.load(tag);
        march.load(tag);
    }

    // ------------------------------------------------------------------ アニメ
    /**
     * 1 本のコントローラで: 起き上がり(補間 0)> 攻撃の 3 段(段の間は補間 0。3 tick の実行が補間に食われないように)> 歩き / 待機。
     * 歩きの再生速度は実際に進んだ量 ÷ 基準の速さ(位相が巻き戻らないコントローラ)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "move", 5, state -> {
            AnimationController<AbstractParasitizedMonster> ctl = state.getController();
            ctl.setAnimationSpeed(1.0D);
            RawAnimation death = isDeadOrDying() ? deathAnimation() : null;
            if (death != null) {
                ctl.transitionLength(2);
                return state.setAndContinue(death); // 1 段目の死に際の破裂(段階 0 は null = バニラの倒れ方)
            }
            if (isRising()) {
                ctl.transitionLength(0);
                return state.setAndContinue(RisingSupport.RISE_ANIM);
            }
            RawAnimation act = actionAnimation(action());
            if (act != null) {
                ctl.transitionLength(actionTransition(action())); // 種の行動(クリーパーの puff)は 4、1 段目の技は段ごと(G1Move)
                return state.setAndContinue(act);
            }
            RawAnimation ride = getVehicle() instanceof AbstractParasitizedMonster ? ridingAnimation() : null;
            if (ride != null) {
                ctl.transitionLength(4);
                return state.setAndContinue(ride); // 豚の背に乗っている間(チビゾンビ。2026-11-13)
            }
            ctl.transitionLength(5);
            if (isWalkingNow()) {
                ctl.setAnimationSpeed(MobAnimation.walkSpeedFor(this, walkRef()));
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    /** G1 の背(1 段目の豚)に乗っている間のアニメ(繰り返し)。無い種は null = 歩き・待機のまま。チビゾンビの段階 0 と 1 段目だけが持つ。 */
    @Nullable
    protected RawAnimation ridingAnimation() {
        return null;
    }

    /** 死に際のアニメ(1 段目の death_burst)。段階 0 は null。 */
    @Nullable
    protected RawAnimation deathAnimation() {
        return null;
    }

    /** 行動の段階のアニメ(無ければ null = 歩き・待機)。種ごとに足す(クリーパーの puff)。 */
    @Nullable
    protected RawAnimation actionAnimation(int action) {
        return switch (action) {
            case ACTION_WINDUP -> WINDUP_ANIM;
            case ACTION_STRIKE -> STRIKE_ANIM;
            case ACTION_RECOVER -> RECOVER_ANIM;
            default -> null;
        };
    }

    /**
     * この tick に地面を進んだか(位置の差。{@code limbSwingAmount} は被弾で 1.5 に跳ねて止まっていても歩きが流れるので使わない。
     * 記憶 geckolib-animation-speed-rewinds-phase)。
     */
    public boolean isWalkingNow() {
        double dx = getX() - xo;
        double dz = getZ() - zo;
        return dx * dx + dz * dz > 0.015D * 0.015D;
    }

    /**
     * 鳴き声・被弾・死の声(バニラはどれも {@code makeSound} を通る)を、宿主のバニラの声のまま「声帯が植物につぶされた声」にして鳴らす
     * (2026-11-14 ユーザー決定: 版 2 つぶれ。{@link com.yayuy.overgrownvegetation.voice.G1Voice})。
     */
    @Override
    public void makeSound(@Nullable net.minecraft.sounds.SoundEvent sound) {
        if (sound != null) {
            com.yayuy.overgrownvegetation.voice.G1Voice.play(this, species(), sound, getSoundVolume(), getVoicePitch());
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
