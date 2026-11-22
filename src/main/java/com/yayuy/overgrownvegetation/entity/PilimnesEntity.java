package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.lizard.LizardRunner;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ピリムネス {@code pilimnes}(G3・地上の遠距離。11-14 に本名。仮名ナゲトゲ、仮の id {@code g5_lancer}。pilum = 投槍)。
 * 人の弓に対抗して、背の葉を棘に変え、葉枕(水圧の関節)で弾き飛ばして離れた人の足を止め、近づいて T 字の口で仕留める(注文書 g5_lancer)。
 * 体の土台は背に棘の帆を持つ這う爬虫類(エダフォサウルス)。
 *
 * <h2>段 1(2026-11-15): 歩く・走るところまで</h2>
 * 移動は「トカゲ型」({@link LizardRunner}。この Mob のために作った)。低く這い、横へ張った 4 本の脚を足の置き場 + IK で置き、背骨を左右にくねらせ、
 * <b>3〜6 ブロック走っては 0.5〜1.5 秒止まって見回す</b>。止まる所は幹・岩の脇へ寄せる。2 ブロックの段は跳び上がる。地上だけ(幹へ張り付かない)。
 * 自然には湧かない(湧き方は全体の仕組みが決める)。
 *
 * <h2>能力の段(2026-11-16): 敵として襲う</h2>
 * 標的はほかの G3 と同じ(殴った相手・プレイヤー・植物側でない生き物。探すのは 20〜40 tick おき)。技は {@link PilimnesHuntGoal}:
 * 棘の弾き(足を鈍らせる)・扇の散らし撃ち・噛みつき・棘の雨(特殊)。どれも止まって出し、前触れは殴っても潰せない。棘は {@link PilimnesSpine}。
 * 光は棘の列(spine)と T 字の口(mouth)の 2 枚(脇腹 flank は使わない)。
 *
 * <h3>アニメ(段 2: 11-15 にアニメのセッションが walk を直し、run・look_around・look_around_r を足した。ユーザーが Blockbench で了承)</h3>
 * kamae・idle・walk・run・look_around・look_around_r。近く(足の置き場が効いている)では、歩いている間も idle を流し、脚・胴の高さ・
 * 背骨のくねりはコードが足す。遠くで進む間は、走り出すときに決めた walk か run を実際の速さに合わせて流す。止まっている間は look_around
 * (近くも遠くも。脚・胸にキーが無い)。見回しをアニメとコードのどちらでするかは {@link #LOOK_BY_ANIMATION}。
 */
public class PilimnesEntity extends LizardRunner implements GeoEntity, ParasitizedMob {

    public static final String ID = "pilimnes";

    /** 描画倍率(geo に焼き込まず描画で掛ける。テクスチャは 1 単位 2 テクセルなので、画素はブロックの 1 画素に揃う)。 */
    public static final float RENDER_SCALE = 2.0F;
    /** モデルの 1 単位(px)をブロックへ。 */
    static final double PX = RENDER_SCALE / 16.0D;

    // --- 速さ・旋回【仮・Claude 11-15】 ---
    /** 回る速さ(度/tick)・その場で回りだすずれ(度)。這う体はその場で素早く向きを変える。 */
    static final float TURN_PER_TICK = 14.0F, MOVE_OFF = 60.0F;
    /** うろつきの速さ(移動の速さ 0.30 に掛ける。地面の速さ ≈ 2.16 × (0.30 × 0.85)² ≈ 0.14 ブロック/tick)。 */
    static final double STROLL_SPEED = 0.85D;
    /** 経路の長い体の重み(ヴェナムネスと同じ)。 */
    public static final OvergrownNodeEvaluator.LongBody LONG_BODY = new OvergrownNodeEvaluator.LongBody(1.8F, 0.8F, 0.35F, 1.3F);
    /** 跳ぶ力(2 ブロックの段を跳び上がる。高さ ≈ 2.5)。 */
    public static final double JUMP_STRENGTH = 0.62D;

    // --- 体の作り(モデル prac2_g5_lancer_r7 の骨の位置 × 描画倍率) ---
    /**
     * 足の置き場(左前・右前・左後ろ・右後ろ): 前の爪は左右 ±12 px・前 12 px(休めの爪の位置)、後ろの足は左右 ±13 px・後ろ 8.9 px。
     */
    static final double[] LEG_HOME_LEFT = {12.0D * PX, -12.0D * PX, 13.0D * PX, -13.0D * PX};
    static final double[] LEG_HOME_FWD = {12.0D * PX, 12.0D * PX, -8.9D * PX, -8.9D * PX};
    /** 曲げの付け根: 首 neck0・neck1・neck2・頭 head(z −10・−17・−20・−23)、尾 tail0〜tail3(z 12・19・25.5・31.5)。 */
    static final double[] BEND_FRONT = {10.0D * PX, 17.0D * PX, 20.0D * PX, 23.0D * PX};
    static final double[] BEND_BACK = {12.0D * PX, 19.0D * PX, 25.5D * PX, 31.5D * PX};

    // --- 足の運び【仮・Claude 11-15】 ---
    /** 着いている足が流れる距離(ブロック)= 下限 + 速さ × 係数、上限まで。脚は横へ張っているので前後へ届くのは ±0.8 ほど。 */
    static final double STEP_MIN = 0.3D, STEP_PER = 3.0D, STEP_MAX = 0.75D;
    /** 踏み替えの長さ(tick)= 着いている時間 × 割合、下限〜上限。 */
    static final double SWING_SHARE = 0.6D;
    static final int SWING_MIN = 3, SWING_MAX = 7;
    /** 先回りの上限(tick)と、回っている間の上限。 */
    static final double LEAD_MAX = 6.0D, LEAD_TURNING = 2.0D;

    // --- アニメの速さ ---
    /** walk・run を速さ 1.0 で流したときの地上速度(ブロック/tick)。11-15 段 2 のアニメがこの値に合わせて作られた(walk 20 tick・run 12 tick で 1 巡)。 */
    public static final double WALK_REF = 0.10D, RUN_REF = 0.194D;
    /**
     * 止まっている間の見回しを look_around(アニメ)でするか、コードの見回し({@code SpineWave})でするか【仮・11-15 段 2】。
     * 【決定 11-15 ユーザー】アニメ版を残す(true で確定)。コードの見回しは使わない・切り替え用に残してある(二重にならないよう片方だけが効く)。
     */
    public static final boolean LOOK_BY_ANIMATION = true;
    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 3.0D;
    /** これ(ブロック/tick)以上前へ進んだ tick は歩き。 */
    static final double WALK_MIN_SPEED = 0.01D;
    static final float TURN_ANIM_MIN = 1.5F;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    /** 見回し(首を上げ、さっと左 → 右 → 正面。最後のコマで止まる)と、その左右逆。 */
    private static final RawAnimation LOOK = RawAnimation.begin().thenPlay("look_around");
    private static final RawAnimation LOOK_R = RawAnimation.begin().thenPlay("look_around_r");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // ------------------------------------------------------------------ 技(能力の段 11-16)

    /** 技の段の番号(前触れ → 実行 → 隙)。描画へ同期する。 */
    public static final int ACT_NONE = 0, ACT_FLICK_WINDUP = 1, ACT_FLICK = 2, ACT_FLICK_RECOVER = 3,
            ACT_FAN_WINDUP = 4, ACT_FAN = 5, ACT_FAN_RECOVER = 6, ACT_BITE_WINDUP = 7, ACT_BITE = 8, ACT_BITE_RECOVER = 9,
            ACT_RAIN_WINDUP = 10, ACT_RAIN = 11, ACT_RAIN_RECOVER = 12;
    private static final String[] ACT_ANIM = {null, "flick_windup", "flick", "flick_recover", "fan_windup", "fan", "fan_recover",
            "bite_windup", "bite", "bite_recover", "rain_windup", "rain", "rain_recover"};
    private static final RawAnimation[] ACT_RAW = new RawAnimation[ACT_ANIM.length];

    static {
        for (int i = 1; i < ACT_ANIM.length; i++) ACT_RAW[i] = RawAnimation.begin().thenPlayAndHold(ACT_ANIM[i]);
    }

    /** 技の番号(下 8 ビット)と、同じ技が続いたときに見分ける回数(上)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_ACT =
            net.minecraft.network.syncher.SynchedEntityData.defineId(PilimnesEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    @org.jetbrains.annotations.Nullable
    private PilimnesHuntGoal hunt;
    private int actSeq;

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACT, 0);
    }

    /**
     * 段 1 の Goal(浮く・うろつく・見回す)に、襲う Goal と標的を足す。標的はほかの G3(クリペイムネス・サルティムネス・ヴェナムネス)と同じ:
     * 殴った相手・プレイヤー(サバイバル・アドベンチャー)・植物側でない生き物(G1〜G8・環境生物・感染体・植物側へ変わった生き物・防具立ては狙わない)。
     * 探すのは {@link #SEEK_INTERVAL} tick に 1 回くらい(軽く)。
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.hunt = new PilimnesHuntGoal(this);
        this.goalSelector.addGoal(3, this.hunt);
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new SeekGoal<>(this, net.minecraft.world.entity.player.Player.class, e -> true));
        this.targetSelector.addGoal(3, new SeekGoal<>(this, net.minecraft.world.entity.LivingEntity.class,
                e -> !(e instanceof net.minecraft.world.entity.player.Player) && PlantFoes.isFoe(this, e)));   // 陣営と除外は共通(①13)
    }

    /** 標的を探す間隔の目安(tick。バニラの最寄りの Goal の randomInterval)【仮】。 */
    static final int SEEK_INTERVAL = 40;

    /** 自分で標的を探す Goal。名前が {@code nostroll} で始まる個体は自分では探さない(確かめ・GameTest 用。標的はテストが置く)。 */
    static final class SeekGoal<T extends net.minecraft.world.entity.LivingEntity> extends net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<T> {
        SeekGoal(PilimnesEntity mob, Class<T> type, java.util.function.Predicate<net.minecraft.world.entity.LivingEntity> filter) {
            super(mob, type, SEEK_INTERVAL, true, false, filter);
        }

        @Override
        public boolean canUse() {
            if (com.yayuy.overgrownvegetation.entity.DevNames.noStroll(this.mob)) return false;
            return super.canUse();
        }
    }

    /** 技の最中(全身の技。うろつかない・向きは技が決める)。クライアントでも同期した番号で答える。 */
    @Override
    public boolean isActing() {
        return act() != ACT_NONE;
    }

    /**
     * 技の間も脚は足の置き場 + IK のまま(アニメのセッションの頼み: 胴を下げる・前へ出すキーに IK が付いていく。地形の上で足が浮かない)。
     * 止めるのは死・乗り物・水の中だけ。
     */
    @Override
    protected boolean legsFree() {
        return !this.isDeadOrDying() && !this.isPassenger() && !this.isInWater();
    }

    /** 技を描画へ送る。 */
    void setAct(int act) {
        this.actSeq = (this.actSeq + 1) & 0xFFFF;
        this.entityData.set(DATA_ACT, act | (this.actSeq << 8));
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("acts")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "PILI_ACT t %d 技 %d 標的 %s",
                    this.tickCount, act, this.getTarget() == null ? "-" : this.getTarget().getType().toShortString()));
        }
    }

    /** 今の技の番号(サーバー・クライアントどちらでも)。 */
    public int act() {
        return this.entityData.get(DATA_ACT) & 0xFF;
    }

    /** 体から見た点(右・上・前、ブロック。体格を掛ける)のワールドの位置。 */
    Vec3 bodyPoint(double right, double up, double fwd) {
        float r = this.getYRot() * Mth.DEG_TO_RAD;
        double s = Math.max(this.getScale(), 0.05F);
        double fx = -Mth.sin(r), fz = Mth.cos(r), rx = -Mth.cos(r), rz = -Mth.sin(r);
        return new Vec3(this.getX() + (fx * fwd + rx * right) * s, this.getY() + up * s, this.getZ() + (fz * fwd + rz * right) * s);
    }

    /** 棘の弾き: いちばん前の棘(sp0 の先。アニメの値 右 −0.37・上 3.23・前 1.16)から相手の足元へ 1 本。 */
    void shootFlick(net.minecraft.world.entity.LivingEntity t, double speed, float damage, int slowTicks, int slowLevel) {
        Vec3 from = bodyPoint(-0.37D, 3.23D, 1.16D);
        Vec3 aim = new Vec3(t.getX(), t.getY() + 0.15D, t.getZ());
        Vec3 v = aim.subtract(from).normalize().scale(speed);
        this.level().addFreshEntity(new PilimnesSpine(this.level(), this, PilimnesSpine.FLICK, from, v, damage).slow(slowTicks, slowLevel));
        playShot(1.0F);
    }

    /** 扇の散らし撃ち: 前の 3 本(sp0・sp1・sp2 の先)から、相手の胴へ向けた向きを左・正面・右へ {@code deg} 度ずつ開いて 3 本。 */
    void shootFan(net.minecraft.world.entity.LivingEntity t, float deg, double speed, float damage) {
        Vec3[] from = {bodyPoint(-0.42D, 3.04D, 1.01D), bodyPoint(0.51D, 3.66D, 0.80D), bodyPoint(-0.52D, 4.18D, 0.40D)};
        float[] yaw = {deg, 0.0F, -deg};
        Vec3 aim = new Vec3(t.getX(), t.getY() + t.getBbHeight() * 0.45D, t.getZ());
        for (int i = 0; i < 3; i++) {
            Vec3 v = aim.subtract(from[1]).normalize().yRot(yaw[i] * Mth.DEG_TO_RAD).scale(speed);
            this.level().addFreshEntity(new PilimnesSpine(this.level(), this, PilimnesSpine.FAN, from[i], v, damage));
        }
        playShot(0.9F);
    }

    /**
     * 棘の雨の 1 本: 背の棘 {@code index}(前から 0〜7。アニメの値 上 1.8〜2.9・前 −0.2〜−2.8)から空へ。{@code at} のまわり半径 {@code spread} の
     * どこかへ {@code fallTicks} tick 後に落ちる弧。
     */
    void shootRain(int index, Vec3 at, double spread, int fallTicks, float damage) {
        Vec3 from = bodyPoint((index & 1) == 0 ? 0.25D : -0.25D, 1.8D + 0.15D * index, -0.2D - 0.37D * index);
        double a = this.random.nextDouble() * Math.PI * 2.0D, r = spread * Math.sqrt(this.random.nextDouble());
        Vec3 to = new Vec3(at.x + Math.cos(a) * r, at.y + 0.1D, at.z + Math.sin(a) * r);
        Vec3 v = PilimnesSpine.rainVelocity(from, to, fallTicks);
        this.level().addFreshEntity(new PilimnesSpine(this.level(), this, PilimnesSpine.RAIN, from, v, damage));
        if (index == 0) playShot(0.7F);
    }

    private void playShot(float pitch) {
        this.level().playSound(null, this.getX(), this.getY() + 2.0D, this.getZ(), net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(),
                net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, pitch);
    }

    /** 噛みつきの当たり(攻撃力の傷。殴った相手の感染は全体の仕組み)。 */
    void bite(net.minecraft.world.entity.LivingEntity t) {
        this.doHurtTarget(t);
        this.level().playSound(null, t.getX(), t.getY(), t.getZ(), net.minecraft.sounds.SoundEvents.EVOKER_FANGS_ATTACK,
                net.minecraft.sounds.SoundSource.HOSTILE, 0.8F, 1.3F);
    }

    // --- 光と技のアニメ(クライアント)

    private int clientAct = ACT_NONE, clientActKey = -1, clientActStart, clientSkillStart, animActKey = -1;

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) return;
        int v = this.entityData.get(DATA_ACT);
        if (v != this.clientActKey) {
            this.clientActKey = v;
            this.clientAct = v & 0xFF;
            this.clientActStart = this.tickCount;
            if (this.clientAct == ACT_FLICK_WINDUP || this.clientAct == ACT_FAN_WINDUP || this.clientAct == ACT_BITE_WINDUP
                    || this.clientAct == ACT_RAIN_WINDUP) {
                this.clientSkillStart = this.tickCount;
            }
        }
    }

    /** 技の頭(前触れのアニメの 0 tick)からの tick。補間 {@link PilimnesHuntGoal#LEAD} を引く。 */
    private float skillTicks(float pt) {
        return this.tickCount - this.clientSkillStart + pt - PilimnesHuntGoal.LEAD;
    }

    /** a で点き始め・b で点ききり・c で消え始め・d で消えきり(直線)。 */
    private static float ramp(float t, float a, float b, float c, float d) {
        if (t <= a || t >= d) return 0.0F;
        if (t < b) return (t - a) / (b - a);
        if (t <= c) return 1.0F;
        return (d - t) / (d - c);
    }

    /** 棘の列の光(0〜1)。時刻は技の頭から(アニメの一覧 Vault 8 節): 弾き 2→10・18→24、扇 1→8・14→20、雨 3→14・34→42。 */
    public float spineGlow(float pt) {
        if (this.clientAct == ACT_NONE) return 0.0F;
        float t = skillTicks(pt);
        return switch (this.clientAct) {
            case ACT_FLICK_WINDUP, ACT_FLICK, ACT_FLICK_RECOVER -> ramp(t, 2, 10, 18, 24);
            case ACT_FAN_WINDUP, ACT_FAN, ACT_FAN_RECOVER -> ramp(t, 1, 8, 14, 20);
            case ACT_RAIN_WINDUP, ACT_RAIN, ACT_RAIN_RECOVER -> ramp(t, 3, 14, 34, 42);
            default -> 0.0F;
        };
    }

    /** T 字の口の光(0〜1): 噛みつきの頭から 0→6 で点き、当たり 11→16 で消える。 */
    public float mouthGlow(float pt) {
        return this.clientAct == ACT_BITE_WINDUP || this.clientAct == ACT_BITE || this.clientAct == ACT_BITE_RECOVER ? ramp(skillTicks(pt), 0, 6, 11, 16) : 0.0F;
    }

    // --- テスト・確かめ用

    /** テスト用: 次の判断で出す技(ACT_FLICK_WINDUP・ACT_FAN_WINDUP・ACT_BITE_WINDUP・ACT_RAIN_WINDUP)。 */
    public void forceActForTests(int act) {
        if (this.hunt != null) this.hunt.forceAct = act;
    }

    /** テスト用: 出した技の数 {弾き, 扇, 噛み, 雨}。 */
    public int[] actCountsForTests() {
        return this.hunt == null ? new int[4] : new int[]{this.hunt.flicks, this.hunt.fans, this.hunt.bites, this.hunt.rains};
    }

    public PilimnesEntity(EntityType<? extends PilimnesEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 30);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID).add(Attributes.JUMP_STRENGTH, JUMP_STRENGTH);
    }

    // ------------------------------------------------------------------ 型の値

    @Override
    protected double[] legHomeLeft() {
        return LEG_HOME_LEFT;
    }

    @Override
    protected double[] legHomeFwd() {
        return LEG_HOME_FWD;
    }

    @Override
    protected double[] bendFront() {
        return BEND_FRONT;
    }

    @Override
    protected double[] bendBack() {
        return BEND_BACK;
    }

    @Override
    protected OvergrownNodeEvaluator.LongBody longBodyWeights() {
        return LONG_BODY;
    }

    @Override
    protected float maxTurnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected float maxMoveOff() {
        return MOVE_OFF;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double cullingRadius() {
        return 5.0D * this.getScale();
    }

    // ------------------------------------------------------------------ 脚

    @Override
    protected void configurePlanter(FootPlanter fp) {
        fp.lift = 0.3D;        // 宙の足を上げる高さ(ブロック)【仮】。這う脚は低く運ぶ
        fp.probeUp = 1.1D;
        fp.probeDown = 1.8D;   // 胴の高さ 1.4 + 少し
    }

    /** 速いほど大きく・速く踏み替える(対角の 2 本ずつ)。 */
    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo)) / Math.max(this.getScale(), 0.05F);
        boolean turning = Math.abs(getTurnRate()) > 3.0F;
        double step = Mth.clamp(STEP_MIN + v * STEP_PER, STEP_MIN, STEP_MAX);
        double stance = v > 0.01D ? step * 2.0D / v : 40.0D;   // 足が着いている tick(着いてから離れるまでに 2 × step 流れる)
        fp.stepAt = step;
        fp.swingTicks = Mth.clamp((int) Math.round(stance * SWING_SHARE), SWING_MIN, turning ? SWING_MIN : SWING_MAX);
        fp.lead = Mth.clamp(step / Math.max(v, 0.01D), 0.0D, turning ? LEAD_TURNING : LEAD_MAX);
    }

    // ------------------------------------------------------------------ アニメ

    /**
     * アニメは 1 つのコントローラ。
     * <ul>
     *   <li>止まって見回す間({@link #isPaused()}、近くも遠くも): {@link #lookByAnimation()} なら look_around か look_around_r を頭から 1 回
     *       (最後のコマで止まる。止まる長さ 10〜30 tick のどこで切ってもよい作り)。左右は個体の id と止まった回数で交互に。</li>
     *   <li>近く(足の置き場が効いている): idle(脚・胴の高さ・背骨のくねりはコードが足す)。</li>
     *   <li>遠くで進む間: 走り出すときに決めた 1 本({@link #isDashRun()} なら run、でなければ walk)を、実際の速さに合わせて流す
     *       (位相を積み上げるコントローラなので、速さを変えても巻き戻らない)。走り出し・止まり際も同じ 1 本のまま倍率が速さに付いていく。</li>
     * </ul>
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
    }

    private PlayState mainState(AnimationState<PilimnesEntity> state) {
        AnimationController<PilimnesEntity> c = state.getController();
        c.setAnimationSpeed(1.0D);
        if (!this.isDeadOrDying() && this.clientAct != ACT_NONE && this.clientAct < ACT_RAW.length) {
            // 技: 前触れは歩き・待機から補間 LEAD tick、続く段(実行・隙)は補間 0(前の終わり = 次の頭。アニメのセッションが 1 本の時間軸で焼いた)
            boolean windup = this.clientAct == ACT_FLICK_WINDUP || this.clientAct == ACT_FAN_WINDUP || this.clientAct == ACT_BITE_WINDUP
                    || this.clientAct == ACT_RAIN_WINDUP;
            c.transitionLength(windup ? PilimnesHuntGoal.LEAD : 0);
            if (this.clientActKey != this.animActKey) {
                this.animActKey = this.clientActKey;
                c.forceAnimationReset();   // 同じ技が続けて来ても頭から
            }
            return state.setAndContinue(ACT_RAW[this.clientAct]);
        }
        c.transitionLength(4);
        if (!this.isDeadOrDying() && isPaused() && lookByAnimation()) {
            return state.setAndContinue(((this.getId() + pauseCount()) & 1) == 0 ? LOOK : LOOK_R);
        }
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        if (!this.isDeadOrDying() && !proceduralLegs() && (ahead >= WALK_MIN_SPEED || turning)) {
            if (isDashRun() && ahead >= WALK_MIN_SPEED) {
                c.setAnimationSpeed(Mth.clamp(ahead / (RUN_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
                return state.setAndContinue(RUN);
            }
            double v = Math.max(ahead, turning ? WALK_REF * 0.5D : 0.0D);
            c.setAnimationSpeed(Mth.clamp(v / (WALK_REF * this.getScale()), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    /**
     * 見回しをアニメ(look_around)でするか。既定は {@link #LOOK_BY_ANIMATION}。確かめ用(テストクライアントだけ): 名前に {@code codelook} を含む個体は
     * コードの見回し、{@code animlook} を含む個体はアニメの見回し。
     */
    @Override
    public boolean lookByAnimation() {
        if (DEV_HOOKS && this.hasCustomName()) {
            String n = this.getCustomName().getString();
            if (n.contains("codelook")) return false;
            if (n.contains("animlook")) return true;
        }
        return LOOK_BY_ANIMATION;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
