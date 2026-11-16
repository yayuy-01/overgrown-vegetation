package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.GaitAnimationController;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * グレガリア {@code gregaria}(G4・大群の先触れ。11-14 に本名。仮名ムラガリ、仮の id {@code g4_swarm})。
 * 群れで地面を走り回り、通った所に胞子を降らせる「地面を覆う胞子の潮」(Vault {@code Mob の能力/グレガリア.md})。
 *
 * <h2>段 1(2026-11-15): 歩く・走るところまで</h2>
 * 移動は「小さな地這いの虫」の型({@link SmallCrawler})。能力(胞子を降らせる・足首を噛む)・構え kamae・発光・群れはまだ。
 * 形は練習モデル {@code prac2_g4_swarm_r8}(正本 Blockbench の {@code gregaria.bbmodel})。アニメはそのまま(idle・walk)。
 *
 * <h3>歩き・走りの速さ合わせ(11-15 段 2 のアニメに合わせて)</h3>
 * walk(10 tick で 1 巡)は 1 倍で地面 {@link #WALK_REF}、run(5 tick で 1 巡)は {@link #RUN_REF} ブロック/tick で足が滑らない
 * (モデル制作のセッションが IK で焼いた確定値)。再生は実際の速さ ÷ 基準の速さ、walk は {@link #WALK_SPEED_MIN}〜{@link #WALK_SPEED_MAX} 倍、
 * run は {@link #RUN_SPEED_MIN}〜{@link #RUN_SPEED_MAX} 倍【仮】。{@link #RUN_ON} 以上で run、{@link #RUN_OFF} 以下で walk【仮】。
 * walk と run は同じ脚の並び(0 で同じ三脚が着く)なので、切り替えは 1 巡の中の位置を保って補間なしで移る({@link GaitAnimationController})。
 * 急ぎは地面で約 0.24(11-15 ユーザー「歩きより少し早いくらい」)、うろつきは約 0.07。
 */
public class GregariaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "gregaria";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk・run を 1 倍で流したときの地上速度(ブロック/tick。アニメの確定値)。 */
    public static final double WALK_REF = 0.07D, RUN_REF = 0.24D;
    /** 再生速度の範囲(関節の動きが約 100 度/tick を超えない所)【仮】。 */
    static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.2D, RUN_SPEED_MIN = 0.6D, RUN_SPEED_MAX = 1.25D;
    /** これ以上で run、これ以下で walk(ブロック/tick。行き来でばたつかないよう差を付ける)【仮】。 */
    static final double RUN_ON = 0.153D, RUN_OFF = 0.145D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.008D;

    /** 回る速さ(度/tick)【仮】。 */
    static final float TURN_PER_TICK = 30.0F;
    /** うろつき(小走り)の速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.07 ブロック/tick(walk 1 倍)を的に。 */
    static final double STROLL_SPEED = 0.545D;   // 地面の速さ ≈ 2.16 × (0.33 × 0.545)² ≈ 0.07

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");

    /** クライアント: 走りにしているか(速さのヒステリシス)。 */

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public GregariaEntity(EntityType<? extends GregariaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double bodyHalfLength() {
        return 0.55D;   // 胸の前から腹の後ろまで約 1.1 ブロック
    }

    @Override
    public void tick() {
        super.tick();
        if (DEV_HOOKS && !this.level().isClientSide() && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    // ------------------------------------------------------------------ 能力(2026-11-16。Vault「G4〜G6 の能力の決定(11-14)」・「グレガリア」の噛みつきの決定)

    /** 群れ({@link com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack})で走り回る。 */
    @Override
    protected boolean usesPack() {
        return true;
    }

    // --- 足首への噛みつき【仮・ユーザー了承 11-15】 ---
    /** 噛み始める間合い(体の中心どうしの水平の距離、ブロック)・上下の差の上限。 */
    public static final double BITE_START = 1.5D, BITE_DY = 1.0D;
    /** 前触れ 6 + 実行 3 + 隙 8 tick。当たりは前触れの頭から {@link #BITE_HIT_T} tick 目。 */
    public static final int BITE_HIT_T = 8, BITE_TOTAL_T = 17;
    /** 同じ個体が次に噛めるまで(噛み始めから、tick)・1 人を同時に噛める数。 */
    public static final int BITE_COOLDOWN = 40, BITE_MAX_PER_TARGET = 4;
    /** 口の先(体の中心から前へ・高さ、ブロック。大きさ 1)・届く幅。 */
    public static final double MOUTH_FWD = 0.95D, MOUTH_UP = 0.04D, MOUTH_REACH = 0.3D;
    /** 傷・暴露量の上乗せ(%秒。傷の暴露とは別)。 */
    public static final float BITE_DAMAGE = 1.0F;
    public static final double BITE_DOSE = 150.0D;
    /** 前触れの間に相手がこれより離れたら噛むのをやめる(ブロック)。 */
    static final double BITE_GIVE_UP = 2.5D;
    /** 噛み始めてよい、体の向きと相手への向きの差(度)【仮・全体管理 11-16】。 */
    public static final float BITE_AIM_DEG = 30.0F;
    /** 噛み始める近さの下限(これより近いと口先 0.95 が相手を通り越す)・前触れの間に詰め直す距離(ブロック)【仮・Claude 11-16】。 */
    public static final double BITE_MIN = 0.45D, BITE_CHASE = 1.2D;
    /** 噛める間合いにいるが向きがずれているので、まず体を向けている(サーバー)。 */
    private boolean aimingBite;

    private static final RawAnimation BITE = RawAnimation.begin().thenPlay("bite_windup").thenPlay("bite").thenPlay("bite_recover");

    /** 噛んでいる間の tick(-1 = 噛んでいない)・相手・次に噛める時刻(サーバー)。 */
    private int biteTick = -1;
    @org.jetbrains.annotations.Nullable
    private net.minecraft.world.entity.LivingEntity biteTarget;
    private long biteReadyAt;

    /** 計測・テスト: 噛み始め・口が当たった・外れた・やめた の回数(サーバー全体)。 */
    public static volatile long biteStarts, biteHits, biteMisses, biteCancels;

    /** 1 人を同時に噛んでいる個体の印: 相手 → 噛み終わる時刻(枠は時刻が過ぎれば空く = 死・消えで印が残っても漏れない)。 */
    private static final java.util.Map<net.minecraft.world.entity.LivingEntity, long[]> BITE_SLOTS = new java.util.WeakHashMap<>();

    /** 今 {@code target} を噛んでいる数(テスト用)。 */
    public static int bitersOn(net.minecraft.world.entity.LivingEntity target, long now) {
        long[] s = BITE_SLOTS.get(target);
        if (s == null) return 0;
        int n = 0;
        for (long e : s) if (e > now) n++;
        return n;
    }

    private static boolean takeSlot(net.minecraft.world.entity.LivingEntity target, long now) {
        long[] s = BITE_SLOTS.computeIfAbsent(target, k -> new long[BITE_MAX_PER_TARGET]);
        for (int i = 0; i < s.length; i++) {
            if (s[i] <= now) {
                s[i] = now + BITE_TOTAL_T;
                return true;
            }
        }
        return false;
    }

    // --- 通った所に胞子を降り積もらせる【仮・Claude 11-16】 ---
    /** 走った距離 1 ブロックあたりの降り積もり(発芽のしきい値の %)。 */
    public static final double DEPOSIT_PER_BLOCK_PERCENT = 0.2D;
    /** まとめて書く間隔(tick)。チャンクが変わったときも書く。 */
    public static final int DEPOSIT_EVERY = 40;
    private double depositBlocks;
    private double lastDepX = Double.NaN, lastDepZ = Double.NaN;
    private long depositChunk = Long.MIN_VALUE;
    /** この個体が書いた量の合計(しきい値の %。テスト用)。 */
    private double depositedPercent;

    public double depositedPercent() {
        return this.depositedPercent;
    }
    /** 計測・テスト: 場へ書いた回数・量の合計(しきい値の %)。 */
    public static volatile long depositCalls;
    public static volatile double depositPercentTotal;

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        tickBite(sl);
        tickDeposit(sl);
    }


    /** 体を相手の方へ、1 tick に回れるだけ回す(噛む向き合わせ)。 */
    private void faceTowards(net.minecraft.world.entity.LivingEntity t) {
        float want = (float) (Mth.atan2(t.getZ() - this.getZ(), t.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        float off = Mth.wrapDegrees(want - this.getYRot());
        this.setYRot(this.getYRot() + Mth.clamp(off, -turnPerTick(), turnPerTick()));
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
    }
    private void tickBite(net.minecraft.server.level.ServerLevel sl) {
        long now = sl.getGameTime();
        if (this.biteTick < 0) {
            this.aimingBite = false;
            net.minecraft.world.entity.LivingEntity t = this.getTarget();
            if (t == null || now < this.biteReadyAt || !t.isAlive()) return;
            double dx = t.getX() - this.getX(), dz = t.getZ() - this.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > BITE_START * BITE_START || d2 < BITE_MIN * BITE_MIN || Math.abs(t.getY() - this.getY()) > BITE_DY) return;   // 近すぎると口先が相手を通り越す
            // 口先を相手へ向けてから噛む(11-16 全体管理: 止まっている相手に 56% しか当たらなかった。囲む位置で脇を向いたまま噛み始めていた)
            float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            float off = Mth.wrapDegrees(want - this.getYRot());
            this.aimingBite = Math.abs(off) > BITE_AIM_DEG;
            if (this.aimingBite) {
                faceTowards(t);   // まず体を向ける: その場で止まり(lungePoint)、1 tick に回れるだけ相手へ回す
                return;
            }
            if (!takeSlot(t, now)) return;
            this.biteTick = 0;
            this.biteTarget = t;
            this.biteReadyAt = now + BITE_COOLDOWN;
            biteStarts++;
            this.triggerAnim("bite", "bite");
            return;
        }
        this.biteTick++;
        net.minecraft.world.entity.LivingEntity t = this.biteTarget;
        if (t != null && this.biteTick < BITE_HIT_T) faceTowards(t);   // 前触れの間も口先を相手へ向け続ける
        if (this.biteTick < BITE_HIT_T && (t == null || !t.isAlive() || t.distanceToSqr(this) > BITE_GIVE_UP * BITE_GIVE_UP)) {
            this.stopTriggeredAnim("bite", "bite");   // 前触れの間に離れた: 隙を飛ばしてやめる
            this.biteTick = -1;
            this.biteTarget = null;
            biteCancels++;
            return;
        }
        if (this.biteTick == BITE_HIT_T && t != null) {
            if (mouthReaches(t)) {
                bite(sl, t);
                biteHits++;
            } else {
                biteMisses++;
                if (DEV_HOOKS) com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT, "GREG_MISS 距離 %.2f 向きの差 %.0f 上下 %.2f 相手の幅 %.2f", Math.sqrt(this.distanceToSqr(t.getX(), this.getY(), t.getZ())), Mth.wrapDegrees((float) (Mth.atan2(t.getZ() - this.getZ(), t.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F - this.getYRot()), t.getY() - this.getY(), t.getBbWidth()));
            }
        }
        if (this.biteTick >= BITE_TOTAL_T) {
            this.biteTick = -1;
            this.biteTarget = null;
        }
    }

    /**
     * 噛む間の行き先(11-16): 向きを合わせている間はその場で止まる(向きは {@link #tickBite} が回す)。前触れの間は、口先が相手の真ん中に乗る所
     * (相手から自分の側へ {@link #MOUTH_FWD})へ寄る。相手の真ん中へ走ると重なって口先が相手を通り越し、止まっている相手に当たらなかった。
     */
    @Override
    @org.jetbrains.annotations.Nullable
    protected net.minecraft.world.phys.Vec3 lungePoint(net.minecraft.world.entity.LivingEntity t) {
        if (this.aimingBite) return this.position();
        if (this.biteTick < 0 || this.biteTick > BITE_HIT_T) return null;
        // 前触れの間: 口先が届く間合い(体の中心どうし BITE_CHASE 以内)ならその場で止まる(向きは tickBite が相手へ回し続ける)。
        // それより離れた(相手が動いた)ら相手の真ん中へ詰める = 前へ走るので向きは相手のまま。下がりはしない(下がると背を向ける)
        double dx = t.getX() - this.getX(), dz = t.getZ() - this.getZ();
        if (dx * dx + dz * dz <= BITE_CHASE * BITE_CHASE) return this.position();
        return t.position();
    }

    /** 口の先(体の向きの前へ {@link #MOUTH_FWD}・高さ {@link #MOUTH_UP})が相手の箱を {@link #MOUTH_REACH} 広げた中にあるか。 */
    public boolean mouthReaches(net.minecraft.world.entity.LivingEntity t) {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double s = this.getScale();
        double mx = this.getX() - Mth.sin(yaw) * MOUTH_FWD * s, mz = this.getZ() + Mth.cos(yaw) * MOUTH_FWD * s;
        double my = this.getY() + MOUTH_UP * s;
        return t.getBoundingBox().inflate(MOUTH_REACH).contains(mx, my, mz);
    }

    /**
     * 噛む: 傷 {@link #BITE_DAMAGE}(傷の暴露は全体の仕組みが足す)+ 暴露量 {@link #BITE_DOSE}。盾で防がれたら 0。
     * 上乗せは口が当たった回数ごと(傷が入らなかった噛みでも)・マスクは通り抜ける(傷口に入れる)【決定 11-16 ユーザー】。
     */
    private void bite(net.minecraft.server.level.ServerLevel sl, net.minecraft.world.entity.LivingEntity t) {
        net.minecraft.world.damagesource.DamageSource src = this.damageSources().mobAttack(this);
        if (t.isDamageSourceBlocked(src)) {
            t.hurt(src, BITE_DAMAGE);   // 盾が受ける(盾のすり減り・音はバニラ)
            return;
        }
        t.hurt(src, BITE_DAMAGE);
        addBiteDose(t, BITE_DOSE);
    }

    /** 暴露量の上乗せ(傷の暴露と同じ扱い: マスクは見ない・感染中のプレイヤーは段の進みへ・創造/観戦・測らない Mob には足さない)。 */
    static void addBiteDose(net.minecraft.world.entity.LivingEntity victim, double amount) {
        com.yayuy.overgrownvegetation.spore.SporeExposureTracker.addExposure(victim, amount, false); // 11-16 コードの見直し ③6: 共通の口へ(傷と同じ扱い)
    }

    /** 走った距離を貯め、{@link #DEPOSIT_EVERY} tick おき(個体ごとにずらす)かチャンクが変わったときに、まとめて 1 回だけ場へ書く。 */
    private void tickDeposit(net.minecraft.server.level.ServerLevel sl) {
        // customServerAiStep はこの tick の移動(travel)より前なので、xo との差は 0。前の tick の位置を自分で持つ
        double dx = Double.isNaN(this.lastDepX) ? 0.0D : this.getX() - this.lastDepX, dz = Double.isNaN(this.lastDepZ) ? 0.0D : this.getZ() - this.lastDepZ;
        this.lastDepX = this.getX();
        this.lastDepZ = this.getZ();
        if (this.onGround() && dx * dx + dz * dz < 1.0D) this.depositBlocks += Math.sqrt(dx * dx + dz * dz);   // 1 tick に 1 ブロック超えは押し出し・テレポートとして数えない
        long chunk = this.chunkPosition().toLong();
        boolean flush = (this.tickCount + this.getId()) % DEPOSIT_EVERY == 0 || chunk != this.depositChunk;
        if (!flush) return;
        long was = this.depositChunk;
        this.depositChunk = chunk;
        if (this.depositBlocks < 0.5D || was == Long.MIN_VALUE) {
            if (was == Long.MIN_VALUE) this.depositBlocks = 0.0D;
            return;
        }
        double percent = this.depositBlocks * DEPOSIT_PER_BLOCK_PERCENT;
        this.depositBlocks = 0.0D;
        // 書くのは今いる所(チャンクが変わった tick なら、変わる前に走った分も入る。1 チャンク分のずれは許す)
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.depositPoint(sl, this.blockPosition(),
                com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold * percent / 100.0D);
        depositCalls++;
        depositPercentTotal += percent;
        this.depositedPercent += percent;
    }

    /** テストクライアント用: 名前 nostroll_run… は 10〜20 ブロック先へ急ぎで走り続け、…trace… は 10 tick ごとに速さを書く。 */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("bitelog") && this.tickCount % 100 == 0) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "GREG_BITE 始め %d 当たり %d 外れ %d やめた %d 降り積もり %d 回 %.1f%% 群れの相手探し %d 回 平均 %.1f us",
                    biteStarts, biteHits, biteMisses, biteCancels, depositCalls, depositPercentTotal,
                    com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.searchCalls,
                    com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.searchCalls == 0 ? 0.0D
                            : com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.searchNanos / 1e3 / com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack.searchCalls));
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "GREG_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 経路 %s", name, this.getX(), this.getY(), this.getZ(),
                    Math.sqrt(dx * dx + dz * dz), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new GaitAnimationController<>(this, "main", 3, this::mainState));
        // 噛みつき: 頭・顎・触角だけの層を歩き・走りの上に重ねる(後に登録 = 同じ骨は後が勝つ)。サーバーの triggerAnim で鳴らし、普段は止めておく
        controllers.add(new AnimationController<>(this, "bite", 1, s -> PlayState.STOP).triggerableAnim("bite", BITE));
    }

    private PlayState mainState(AnimationState<GregariaEntity> state) {
        GaitAnimationController<GregariaEntity> c = (GaitAnimationController<GregariaEntity>) state.getController();
        double v = clientMoveSpeed(state.getPartialTick());
        RawAnimation a = this.gait.pick(c, v, this.getScale(), !this.isDeadOrDying(), false);
        if (a != com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait.IDLE) devGait(c, v);
        return state.setAndContinue(a);
    }

    /** 歩き ⇄ 走りの選び方(共通の部品。数値はこの種のもの)。 */
    private final com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait gait = new com.yayuy.overgrownvegetation.entity.crawler.WalkRunGait(
            WALK_MIN_SPEED, RUN_ON, RUN_OFF, WALK_REF, RUN_REF, WALK_SPEED_MIN, WALK_SPEED_MAX, RUN_SPEED_MIN, RUN_SPEED_MAX);

    /** テストクライアント用: 名前に gait を含む個体の、フレームごとの歩き方と 1 巡の中の位置。 */
    private void devGait(GaitAnimationController<GregariaEntity> c, double v) {
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("gait")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "GREG_ANIM t %d %s 位置 %.3f 速さ %.3f", this.tickCount, c.currentName(), c.fraction(), v));
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
