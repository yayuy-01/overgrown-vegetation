package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * ヴェロフィルが<b>地上へ降りて胞子をばらまく</b>(ユーザー指定: 3 分プレイヤーを見つけられなかったら)。
 *
 * <p>グループ4 共通の「汚染を自分の足で運ぶ」役割を、飛べる分だけ遠くまで運ぶ形にしたもの。
 * 進行は 3 段:
 * <ol>
 *     <li><b>降りる</b> — 真下の地表へ向かって降下し、接地したら地上モードへ切り替える
 *         ({@link VelophyllEntity#setGrounded})。</li>
 *     <li><b>這って撒く</b> — 翼の先の手で体を引きずって少し移動しては({@code crawl})
 *         その場で撒く({@code spore})を繰り返す。撒いている間だけ足元の周りが
 *         {@code velophyllCorruptionRadius} まで内側から順に汚染されていく
 *         ({@link InvaderSporeField} — アルボラのうずくまりと同じ仕掛け)。</li>
 *     <li><b>飛び立つ</b> — 撒き終わるか、標的が現れるか、{@code velophyllGroundMaxTicks} が
 *         過ぎたら {@code takeoff} で空へ戻る。</li>
 * </ol>
 *
 * <p>飛行 Mob が地面に居座り続けないよう<b>時間の上限がある</b>のが、アルボラの
 * {@link InvaderSporeCrouchGoal}(汚し尽くすまで無制限)との違い。
 *
 * <h2>見た目の方針(グループ4 共通)</h2>
 * {@code spore} は<b>力んだ姿ではなくくつろいだ姿</b>にしてある(周期 8 秒・振幅小・上下の脈動ではなく
 * 左右のゆったりした揺れ)。グループ4 の mob はすべてこの方向で作ること。
 */
public class VelophyllGroundSporeGoal extends Goal {

    /** 降下中、接地したとみなす地表からの高さ(ブロック)。 */
    private static final double LANDED_HEIGHT = 1.2D;
    /** 降下に費やしてよい上限(tick)。降りられなければ諦める。 */
    private static final int DESCEND_TIMEOUT = 400;
    /** 着陸進入で、進行方向のどれだけ先の地表を指すか(ブロック)。小さいほど降下角が急になる。 */
    private static final double APPROACH_LEAD = 18.0D;
    /** 地面と天蓋の差がこれ以下なら「開けた地面」= 降りてよい(ブロック)。 */
    private static final int OPEN_GROUND_TOLERANCE = 3;
    /** 木の上を通過するときに天蓋から空ける高さ(ブロック)。 */
    private static final int LEVEL_OFF_CLEARANCE = 6;
    /** 1 か所を撒き終えたあと、次の場所まで這う距離(ブロック)。 */
    private static final double CRAWL_STEP = 6.0D;
    /** 這いに費やしてよい上限(tick)。着けなければその場で撒き始める。 */
    private static final int CRAWL_TIMEOUT = 160;
    /** 粒子を撒く間隔(tick)。 */
    private static final int PARTICLE_INTERVAL = 4;
    /** 周りの Mob へ胞子感染をかける間隔(tick)。 */
    private static final int INFECT_INTERVAL = 20;

    private final VelophyllEntity mob;

    /** 地上に降りてからの経過 tick(上限判定用)。 */
    private int groundTicks;
    /** 降下を始めてからの経過 tick。 */
    private int descendTicks;
    /** いまの這いに費やした tick。 */
    private int crawlTicks;
    /** 汚染の広がり方そのもの(候補の数え上げと 1 マスずつの進行)。 */
    private InvaderSporeField field;
    /** 這っている最中か(false = その場で撒いている)。 */
    private boolean crawling;

    public VelophyllGroundSporeGoal(VelophyllEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.hasAttackTarget()) {
            return false;
        }
        if (this.mob.isInWater()) {
            return false;
        }
        int quiet = OvergrownVegetationConfig.COMMON.velophyllQuietTicks.get();
        return this.mob.getPlayerFreeTicks() >= quiet;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.hasAttackTarget()) {
            return false;
        }
        if (this.mob.isInWater()) {
            return false;
        }
        if (!this.mob.isGrounded()) {
            // まだ降りている途中。降りられないまま時間切れなら諦める。
            return this.descendTicks < DESCEND_TIMEOUT;
        }
        return this.groundTicks < OvergrownVegetationConfig.COMMON.velophyllGroundMaxTicks.get();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.groundTicks = 0;
        this.descendTicks = 0;
        this.crawlTicks = 0;
        this.field = null;
        this.crawling = false;
        this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        aimAtApproach();
    }

    @Override
    public void stop() {
        this.field = null;
        if (this.mob.isGrounded()) {
            this.mob.setGrounded(false);
            this.mob.beginTakeoff();
        } else if (this.mob.getActionState() != VelophyllEntity.STATE_TAKEOFF) {
            this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        }
        this.mob.getNavigation().stop();
        // 降りた分の「探していない時間」はここで清算する(降りるたびに即また降りないように)。
        this.mob.resetPlayerFreeTicks();
    }

    @Override
    public void tick() {
        if (!this.mob.isGrounded()) {
            tickDescend();
            return;
        }
        this.groundTicks++;
        if (this.crawling) {
            tickCrawl();
        } else {
            tickSpore();
        }
    }

    // --- 降りる ---

    private void tickDescend() {
        this.descendTicks++;
        if (this.descendTicks % 10 == 0) {
            aimAtApproach();
        }
        BlockPos ground = groundBelow();
        if (this.mob.getY() - ground.getY() <= LANDED_HEIGHT || this.mob.onGround()) {
            land();
        }
    }

    /**
     * <b>着陸は滑走路へ降りるように</b>行き先を置く。飛び方が飛行機型になったので
     * ({@link VelophyllFlightMoveControl})真下の点を指しても垂直には降りられず、
     * その場をぐるぐる回るだけになる。進行方向の {@value #APPROACH_LEAD} ブロック先・
     * その地表の高さを指し続けることで、緩い角度で降りてきて接地する。
     */
    private void aimAtApproach() {
        float heading = (this.mob.getYRot() + 90.0F) * Mth.DEG_TO_RAD;
        double x = this.mob.getX() + Mth.cos(heading) * APPROACH_LEAD;
        double z = this.mob.getZ() + Mth.sin(heading) * APPROACH_LEAD;
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int ground = this.mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        int canopy = this.mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        if (canopy - ground > OPEN_GROUND_TOLERANCE) {
            // 進入先が木の下。ここへ降りようとすると枝に突っ込むので、高度を保って先へ進み、
            // 次の測り直しで開けた場所を探す(2026-09-14「森だと木につっかえる」への対処)。
            this.mob.setMoveTargetPoint(new Vec3(x, canopy + LEVEL_OFF_CLEARANCE, z));
            return;
        }
        this.mob.setMoveTargetPoint(new Vec3(x, ground + 0.5D, z));
    }

    private BlockPos groundBelow() {
        return this.mob.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                this.mob.blockPosition());
    }

    private void land() {
        this.mob.setGrounded(true);
        this.mob.setActionState(VelophyllEntity.STATE_GROUND_IDLE);
        beginSpore();
    }

    // --- 這う / 撒く ---

    private void beginSpore() {
        this.crawling = false;
        this.field = new InvaderSporeField(this.mob.blockPosition(),
                OvergrownVegetationConfig.COMMON.velophyllCorruptionRadius.get(),
                InvaderSporeField.defaultInterval(), this.mob.getRandom());
        this.mob.getNavigation().stop();
        this.mob.setActionState(VelophyllEntity.STATE_SPORE);
        if (this.mob.level() instanceof ServerLevel level) {
            level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 0.8F, 1.1F);
        }
    }

    /** その場で撒く。撒き尽くしたら次の場所へ這い出す。 */
    private void tickSpore() {
        this.mob.getNavigation().stop();
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.groundTicks % PARTICLE_INTERVAL == 0) {
            scatterParticles(level);
        }
        if (this.groundTicks % INFECT_INTERVAL == 0) {
            infectNearby();
        }
        if (this.field != null) {
            this.field.spread(level, this.mob.blockPosition());
            if (this.field.isFinished()) {
                beginCrawl();
            }
        }
    }

    /** 撒き終わった場所から少し離れた地点へ這い出す。 */
    private void beginCrawl() {
        this.crawling = true;
        this.crawlTicks = 0;
        this.field = null;
        this.mob.setActionState(VelophyllEntity.STATE_CRAWL);
        double angle = this.mob.getRandom().nextDouble() * Math.PI * 2.0D;
        double x = this.mob.getX() + Math.cos(angle) * CRAWL_STEP;
        double z = this.mob.getZ() + Math.sin(angle) * CRAWL_STEP;
        BlockPos ground = this.mob.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(x, this.mob.getY(), z));
        this.mob.getNavigation().moveTo(ground.getX() + 0.5D, ground.getY(), ground.getZ() + 0.5D, 1.0D);
    }

    private void tickCrawl() {
        this.crawlTicks++;
        // 着いた・詰まった・時間切れ、どれでもその場で撒き始める(歩き回るのが目的ではない)。
        if (this.mob.getNavigation().isDone() || this.crawlTicks >= CRAWL_TIMEOUT) {
            beginSpore();
        }
    }

    private void scatterParticles(ServerLevel level) {
        double x = this.mob.getX();
        double y = this.mob.getY() + 0.3D;
        double z = this.mob.getZ();
        level.sendParticles(ModParticles.FINE_SPORE.get(), x, y, z, 30, 1.4D, 0.25D, 1.4D, 0.01D);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 5, 1.8D, 0.4D, 1.8D, 0.0D);
    }

    private void infectNearby() {
        double radius = OvergrownVegetationConfig.COMMON.invaderSporeInfectRadius.get();
        if (radius <= 0.0D) {
            return;
        }
        AABB box = this.mob.getBoundingBox().inflate(radius);
        for (LivingEntity entity : this.mob.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (AbstractOvergrownInvaderEntity.isSporeVictim(this.mob, entity)) {
                SporeInfectionEffects.infect(entity, 0);
            }
        }
    }
}
