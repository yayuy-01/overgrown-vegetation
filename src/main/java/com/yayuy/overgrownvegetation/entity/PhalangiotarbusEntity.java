package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import com.yayuy.overgrownvegetation.entity.leap.LeapMotion;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlan;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlanner;
import com.yayuy.overgrownvegetation.entity.leap.LeapSpec;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.core.animation.RawAnimation;

import java.util.EnumSet;

/**
 * ファランギオタルブス {@code phalangiotarbus}(狩る側の環境生物・原生消費種。石炭紀の大きなクモ型。張り付いて飛びかかる狩り)。
 * 2026-11-16 環境生物の作り直しの段 1: 地上は「小さな地這いの虫」の型({@link CrawlerCritter})で歩く・急ぐ、跳びは「跳ぶ型」({@code entity/leap/})。
 * 行動(巣だけで暮らす・食べる・休む・巣の近くだけ襲う・幹に張り付く)は後の段。自然には湧かない。
 *
 * <ul>
 *   <li>形は 11-16 夜に直した正本(デスクトップ\blockbench\mobs\phalangiotarbus.bbmodel、背板の段 B)。描画倍率 1(geo に焼き込み)。全長 2.28・幅 1.55・高さ 0.80。</li>
 *   <li>walk・run は 11-16 に g2g3-bug-walk が足を地面に止めて焼いた(基準 walk 0.07・run 0.25 = 確定値。滑り walk 0.01 px・run 0.06 px 以下)。</li>
 *   <li><b>跳び(段 1 は移動の跳びだけ・攻撃しない)</b>: 這って行く先の経路が届かない・{@link #STUCK_TICKS} tick 進まないときは、その先へ跳ぶ。
 *       溜め {@code leap_windup} → 宙 {@code pounce} → 着地 {@code leap_land}({@link #specialAnimation})。確かめ用: 名前が {@code hop} の個体は {@link #HOP_EVERY} tick ごとに前寄りへ跳ぶ。</li>
 * </ul>
 */
public class PhalangiotarbusEntity extends CrawlerCritter {

    public static final String ID = "phalangiotarbus";

    /** walk(0.8 秒・脚 2 巡)・run(0.4 秒・脚 2 巡)を 1 倍で流したとき足が滑らない地面の速さ(11-16 g2g3-bug-walk の確定値)。 */
    public static final double WALK_REF = 0.07D, RUN_REF = 0.25D;
    /** 回る速さ(度/tick)・うろつきの速さ(移動の速さに掛ける)【仮】。 */
    static final float TURN_PER_TICK = 14.0F;
    static final double STROLL_SPEED = 0.55D;

    /**
     * 跳びの値【仮・Claude 11-16】: 水平 2〜6 ブロック・上へ 2・下へ 6。クモとして低く速い弧(頂点の上げ 0.8〜3)。
     * 体は芯の中心から鼻先まで 0.75・腹の先まで 1.55 ブロック(geo の前後の外接 −11.3〜24.7 px)、半幅 0.4。身を沈める 9 tick = 補間 3 + leap_windup 6・着地 6 tick = leap_land。
     */
    public static final LeapSpec LEAP = new LeapSpec(2.0D, 6.0D, 2.0D, 6.0D, 0.8D, 3.0D, 0.75D, 1.55D, 0.4D, 0.08D, 9, 6);
    /** 這って進まないとみなす間(tick)と距離(ブロック)・跳んだ後の待ち(tick)【仮】。 */
    static final int STUCK_TICKS = 40, LEAP_COOLDOWN = 40;
    static final double STUCK_MOVE = 0.3D;
    /** 確かめ用 hop の間隔(tick)と距離(ブロック)。 */
    public static final int HOP_EVERY = 200;
    static final double HOP_MIN = 3.0D, HOP_MAX = 5.5D;

    private static final EntityDataAccessor<Integer> DATA_LEAP = SynchedEntityData.defineId(PhalangiotarbusEntity.class, EntityDataSerializers.INT);
    private static final RawAnimation LEAP_WINDUP = RawAnimation.begin().thenPlayAndHold("leap_windup");
    private static final RawAnimation POUNCE = RawAnimation.begin().thenPlayAndHold("pounce");
    private static final RawAnimation LEAP_LAND = RawAnimation.begin().thenPlayAndHold("leap_land");

    private final LeapMotion leap = new LeapMotion(LEAP);

    public PhalangiotarbusEntity(EntityType<? extends PhalangiotarbusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 8);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    @Override
    protected double runRef() {
        return RUN_REF;
    }

    @Override
    protected double[] runRange() {
        return new double[]{0.6D, 1.3D, 0.17D, 0.15D};
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
        return 1.0D;
    }

    // ------------------------------------------------------------------ 跳び

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LEAP, 0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(2, new HopGoal());
        this.goalSelector.addGoal(4, new LeapOverGoal());
    }

    @Override
    public void aiStep() {
        boolean server = !this.level().isClientSide();
        if (server) {
            this.leap.preMove(this, this.getYRot());
            if (this.leap.phase() == LeapMotion.CROUCH) {
                float yaw = Mth.approachDegrees(this.getYRot(), this.leap.yaw(), TURN_PER_TICK);
                this.setYRot(yaw);
                this.yBodyRot = yaw;
                this.yHeadRot = yaw;
            }
            if (this.leap.active()) this.getNavigation().stop();
        }
        super.aiStep();
        if (server) {
            this.leap.postMove(this);
            this.entityData.set(DATA_LEAP, this.leap.encode());
        }
    }

    /** 跳んでいる間は歩く入力を足さない(弧の速度だけで動く)。 */
    @Override
    public void travel(Vec3 input) {
        super.travel(this.leap.active() ? Vec3.ZERO : input);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        if (this.leap.active()) return false;   // 自分の跳びでは落下ダメージを受けない
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    public LeapMotion leapMotion() {
        return this.leap;
    }

    /** {@code aim} の近くへ跳ぶ(跳べる弧が見つかれば true)。 */
    public boolean leapToward(Vec3 aim) {
        if (this.leap.active() || !this.onGround()) return false;
        LeapPlan p = LeapPlanner.plan(this, aim, LEAP);
        if (p == null) return false;
        this.leap.start(this, p);
        return true;
    }

    /**
     * 跳びのアニメ(11-16 g2g3-bug-walk が作った 3 本。クライアント): 身を沈める間 {@code leap_windup}(補間 3 + 6 tick、最後で止まる)→
     * 蹴った tick から {@code pounce}(補間 0。溜めの終わりの姿から始まり、宙の姿で止まる)→ 着いた tick から {@code leap_land}(補間 0・6 tick・待機の姿で終わる)。
     */
    @Nullable
    @Override
    protected RawAnimation specialAnimation() {
        return switch (LeapMotion.decodePhase(this.entityData.get(DATA_LEAP))) {
            case LeapMotion.CROUCH -> LEAP_WINDUP;
            case LeapMotion.AIR, LeapMotion.FALL -> POUNCE;
            case LeapMotion.LAND -> LEAP_LAND;
            default -> null;
        };
    }

    @Override
    protected int specialTransition() {
        int ph = LeapMotion.decodePhase(this.entityData.get(DATA_LEAP));
        return ph == LeapMotion.CROUCH ? 3 : 0;
    }

    private String devName() {
        return this.hasCustomName() ? this.getCustomName().getString() : "";
    }

    /** 確かめ用: 名前が {@code hop} の個体は {@link #HOP_EVERY} tick ごとに前寄り(左右 90 度まで)の 3〜5.5 ブロック先へ跳ぶ。 */
    final class HopGoal extends Goal {
        private int wait = 40;

        HopGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return devName().equals("hop");
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            PhalangiotarbusEntity m = PhalangiotarbusEntity.this;
            if (m.leap.active() || --this.wait > 0) return;
            this.wait = HOP_EVERY;
            for (int i = 0; i < 8; i++) {
                float r = (m.getYRot() + (m.getRandom().nextFloat() * 2.0F - 1.0F) * 90.0F) * Mth.DEG_TO_RAD;
                double d = HOP_MIN + m.getRandom().nextDouble() * (HOP_MAX - HOP_MIN);
                if (m.leapToward(m.position().add(-Mth.sin(r) * d, 0.0D, Mth.cos(r) * d))) return;
            }
            this.wait = 20;
        }
    }

    /**
     * 跳び越え(移動の跳び): 這って行く先へ経路が届かない、または {@link #STUCK_TICKS} tick 進まないときは、行き先の方へ跳ぶ
     * (遠ければ 5.5・4・2.5 ブロック先を順に試す)。フラグを持たない(うろつきの Goal と並んで動く)。
     */
    final class LeapOverGoal extends Goal {
        private int cooldown, check;
        private Vec3 last = Vec3.ZERO;

        @Override
        public boolean canUse() {
            return !PhalangiotarbusEntity.this.getNavigation().isDone() && !devName().startsWith("nostroll");
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            this.last = PhalangiotarbusEntity.this.position();
            this.check = 0;
        }

        @Override
        public void tick() {
            PhalangiotarbusEntity m = PhalangiotarbusEntity.this;
            if (this.cooldown > 0) this.cooldown--;
            if (m.leap.active() || this.cooldown > 0 || !m.onGround()) return;
            Path path = m.getNavigation().getPath();
            if (path == null) return;
            boolean blocked = !path.canReach();
            if (++this.check >= STUCK_TICKS) {
                this.check = 0;
                blocked |= m.position().distanceTo(this.last) < STUCK_MOVE;
                this.last = m.position();
            }
            if (!blocked) return;
            Vec3 goal = Vec3.atBottomCenterOf(path.getTarget());
            Vec3 dir = new Vec3(goal.x - m.getX(), 0.0D, goal.z - m.getZ());
            double dist = dir.length();
            if (dist < LEAP.minDist()) return;
            dir = dir.scale(1.0D / dist);
            this.cooldown = LEAP_COOLDOWN;
            for (double d : new double[]{Math.min(dist, LEAP.maxDist() - 0.5D), 4.0D, 2.5D}) {
                if (d < LEAP.minDist() || d > dist) continue;
                if (m.leapToward(new Vec3(m.getX() + dir.x * d, goal.y, m.getZ() + dir.z * d))) return;
            }
        }
    }
}
