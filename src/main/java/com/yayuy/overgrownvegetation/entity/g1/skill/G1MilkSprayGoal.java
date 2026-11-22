package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 牛の 1 段目の (b)「胞子の乳」(2026-11-13、能力の第 2 回。仕様 Vault「G1 牛(1 段目)」3 節。効果は【決定 11-09 ユーザー】・数は【仮】)。
 * 元の牛の「乳」を植物が使う: 乳嚢が膨らみ、後ろ脚を開いて腹の下から後ろ・横へ胞子の乳を噴く。噴いた地面に乳だまり({@link G1MilkPuddle})が残る。
 *
 * <ul>
 *   <li>きっかけ: {@value #TRIGGER_RANGE} ブロック以内の獲物(植物側以外)が横か後ろ(体の向きから {@value #SIDE_FROM_DEG}° より外)にいる。
 *   追っている標的はたいてい正面にいるので、標的に限らず周りの獲物を見る(横へ回り込んだ相手・後ろから来た相手に噴く)。</li>
 *   <li>段: milk_windup 20(乳嚢が 3 回脈打って膨らみ、白っぽい汁が滴る。腹を下げて後ろ脚を開く)→ milk 10(1 tick 目に噴く)→ milk_recover 10。
 *   <b>向きは変えない</b>(噴くのは後ろと横なので、正面へ回れば浴びない = 仕様の対処)。</li>
 *   <li>噴く範囲: 後ろ半円(体の向きから {@value #SPRAY_FROM_DEG}° より外)の {@value #SPRAY_RADIUS} ブロック。浴びた相手は良い効果(バフ)だけ全部消え
 *   ({@link G1MilkPuddle#stripBuffs})、暴露量 +{@value #DOSE}。壁の向こうは浴びない。</li>
 *   <li>乳だまり: 後ろ半円の地面に {@value #PUDDLES} つ(半径 {@value #PUDDLE_RADIUS_MIN}〜{@value #PUDDLE_RADIUS_MAX})、{@value #PUDDLE_TICKS} tick 残る。</li>
 *   <li>冷却 {@value #COOLDOWN_MIN}〜{@value #COOLDOWN_MAX} tick(仕様「12〜16 秒に 1 回」)。</li>
 * </ul>
 */
public class G1MilkSprayGoal extends G1TechniqueGoal {

    static final double TRIGGER_RANGE = 6.0D;
    static final float SIDE_FROM_DEG = 60.0F;
    static final double SPRAY_RADIUS = 3.0D;
    static final float SPRAY_FROM_DEG = 80.0F;
    static final double DOSE = 200.0D;
    public static final int PUDDLES = 6;
    static final float PUDDLE_RADIUS_MIN = 0.8F;
    static final float PUDDLE_RADIUS_MAX = 1.2F;
    public static final int PUDDLE_TICKS = 120;
    static final int COOLDOWN_MIN = 240;
    static final int COOLDOWN_MAX = 320;

    public G1MilkSprayGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.MILK_WINDUP, G1Move.MILK, G1Move.MILK_RECOVER}, new int[]{20, 10, 10});
    }

    /** 体の向きから見た相手の方角(度、0 = 正面・180 = 真後ろ)。 */
    private static float bearing(float bodyYaw, Vec3 from, Vec3 to) {
        float yawTo = (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(yawTo - bodyYaw));
    }

    private static boolean isPrey(LivingEntity e) {
        if (!e.isAlive() || SporeExposureTracker.isPlantSide(e)) return false;
        return e instanceof Player p ? !p.isCreative() && !p.isSpectator() : SporeExposureTracker.isMeasuredMob(e);
    }

    @Override
    protected boolean ready() {
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(TRIGGER_RANGE))) {
            if (e == mob || !isPrey(e) || mob.distanceTo(e) > TRIGGER_RANGE) continue;
            if (bearing(mob.yBodyRot, mob.position(), e.position()) < SIDE_FROM_DEG) continue;
            if (mob.getSensing().hasLineOfSight(e)) return true;
        }
        return false;
    }

    /** 向きは追わない(噴くのは後ろと横)。 */
    @Override
    protected int aimUntil() {
        return 0;
    }

    @Override
    public void start() {
        super.start();
        lockedYaw = mob.yBodyRot; // 体の向きのまま止める
    }

    @Override
    protected int cooldown() {
        return COOLDOWN_MIN + mob.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && (tickInStage == 3 || tickInStage == 9 || tickInStage == 15)) {
            // 乳嚢の脈打ち(3 回)と、滴る白っぽい汁
            level.playSound(null, mob.getX(), mob.getY() + 0.4D, mob.getZ(), ModSounds.G1_SAC_SWELL.get(), SoundSource.HOSTILE, 0.7F, 0.55F);
            level.sendParticles(ParticleTypes.SPIT, mob.getX(), mob.getY() + 0.35D, mob.getZ(), 1, 0.2D, 0.05D, 0.2D, 0.0D);
        }
        if (stageIndex == 1 && tickInStage == 1) {
            spray(level);
        }
    }

    /** 噴く: 後ろ半円の相手へ効果と暴露量、地面に乳だまり。 */
    private void spray(ServerLevel level) {
        Vec3 belly = mob.position().add(0.0D, mob.getBbHeight() * 0.3D, 0.0D);
        for (LivingEntity e : sprayed(level, belly)) {
            G1MilkPuddle.stripBuffs(e);
            G1SporeDose.add(e, DOSE);
        }
        RandomSource random = mob.getRandom();
        // 乳だまり: 後ろ半円にばらして置く(真後ろ寄りに多く)
        for (int i = 0; i < PUDDLES; i++) {
            float a = lockedYaw + 180.0F + (random.nextFloat() * 2.0F - 1.0F) * (180.0F - SPRAY_FROM_DEG);
            double d = 0.6D + Math.sqrt(random.nextDouble()) * (SPRAY_RADIUS - 0.9D);
            Vec3 at = mob.position().add(Vec3.directionFromRotation(0.0F, a).scale(d));
            G1MilkPuddle.spawn(level, at, Mth.lerp(random.nextFloat(), PUDDLE_RADIUS_MIN, PUDDLE_RADIUS_MAX), PUDDLE_TICKS);
        }
        // 見た目: 腹の下から後ろ・横へ白っぽい汁(数 0 の sendParticles = 粒ごとの速度)と、混じった胞子の粒
        for (int i = 0; i < 36; i++) {
            float a = lockedYaw + 180.0F + (random.nextFloat() * 2.0F - 1.0F) * (180.0F - SPRAY_FROM_DEG);
            Vec3 v = Vec3.directionFromRotation(-10.0F - random.nextFloat() * 25.0F, a);
            double speed = Mth.lerp(random.nextDouble(), 0.25D, 0.55D);
            level.sendParticles(i % 3 == 0 ? ModParticles.SPORE_JET.get() : ParticleTypes.SPIT, belly.x, belly.y, belly.z, 0,
                    v.x * speed, v.y * speed, v.z * speed, 1.0D);
        }
        // 噴く音 + 小さな水しぶき(11-13 ユーザー: 試聴の B 案。バケツの COW_MILK は外した)
        level.playSound(null, belly.x, belly.y, belly.z, SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 0.9F, 0.7F);
        level.playSound(null, belly.x, belly.y, belly.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 0.4F, 1.2F);
    }

    /** 後ろ半円の {@value #SPRAY_RADIUS} ブロックにいて、間に壁の無い相手。 */
    private List<LivingEntity> sprayed(ServerLevel level, Vec3 belly) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(SPRAY_RADIUS + 1.0D))) {
            if (e == mob || !e.isAlive() || SporeExposureTracker.isPlantSide(e)) continue;
            double dx = e.getX() - mob.getX(), dz = e.getZ() - mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > SPRAY_RADIUS + e.getBbWidth() * 0.5D || Math.abs(e.getY() - mob.getY()) > 2.0D) continue;
            if (d > 0.5D && bearing(lockedYaw, mob.position(), e.position()) < SPRAY_FROM_DEG) continue;
            Vec3 to = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            if (level.clip(new ClipContext(belly, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob)).getType() == HitResult.Type.BLOCK) continue;
            out.add(e);
        }
        return out;
    }
}
