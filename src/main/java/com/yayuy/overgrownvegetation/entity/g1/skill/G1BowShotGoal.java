package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * スケルトンの 1 段目の (b)「植物の弓で胞子の塊を射る」(2026-11-13、能力の第 3 回。仕様 Vault「G1 スケルトン(1 段目)」3 節【決定 11-09・数は仮】)。
 * 左手を胸の肋骨の間へ突っ込んで光る塊をちぎり取り、弦を引いて、山なりの遅い弾 {@link G1SporeBolt} を射る
 * (当たれば {@value #DAMAGE} ダメージ + 暴露量 +{@value #DOSE}、盾で受ければ暴露なし。外れたら着弾点を少し汚染)。
 *
 * <ul>
 *   <li>使う間合い: 標的が見えていて {@value #MIN_RANGE}〜{@value #RANGE} ブロック({@value #MIN_RANGE} 未満は (a) の薙ぎ)。
 *   (c) 根を張って据えている間は {@value #ANCHORED_MIN_RANGE}〜{@value #ANCHORED_RANGE} ブロック。</li>
 *   <li>段: shoot_windup 20({@value #AIM_UNTIL} tick まで向きを追う)→ shoot 2(1 tick 目に放つ)→ shoot_recover 16。</li>
 *   <li>射る向きは<b>止めた体の向き</b>(標的の今の位置へまっすぐではない)。弾が遅いので、横へ歩けばよけられる。</li>
 *   <li>据えている間は 弾速 {@value #SPEED} → {@value #ANCHORED_SPEED}・ばらつき {@value #SPREAD} → {@value #ANCHORED_SPREAD}・射程が伸びる
 *   【決定 11-09: 射程と精度と弾速が良くなる】。代わりに振り向きが遅い(1 tick {@value #ANCHORED_TURN}° = 1 秒 120°【仮】。走って回り込みで勝てるのは約 2.7 ブロックより内側だけ = 近接の距離)。間隔は変えない。</li>
 *   <li>間隔: 放ってから次に放つまで {@value #INTERVAL} + 0〜{@value #INTERVAL_JITTER} tick(仕様「3 秒 + 乱数」)。</li>
 * </ul>
 */
public class G1BowShotGoal extends G1TechniqueGoal {

    public static final double MIN_RANGE = 3.0D;
    public static final double RANGE = 16.0D;
    static final double ANCHORED_MIN_RANGE = 1.5D;
    public static final double ANCHORED_RANGE = 24.0D;
    public static final float SPEED = 0.9F;
    public static final float ANCHORED_SPEED = 1.4F;
    /** ばらつき(バニラの shoot の inaccuracy。スケルトンの矢は難易度で 2〜10)。【仮】 */
    static final float SPREAD = 4.0F;
    static final float ANCHORED_SPREAD = 2.0F;
    public static final float DAMAGE = 2.0F;
    public static final double DOSE = 500.0D;
    static final int INTERVAL = 60;
    static final int INTERVAL_JITTER = 20;
    static final int AIM_UNTIL = 18;
    public static final float ANCHORED_TURN = 6.0F;
    private static final int[] LENGTHS = {20, 2, 16};

    public G1BowShotGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.SHOOT_WINDUP, G1Move.SHOOT, G1Move.SHOOT_RECOVER}, LENGTHS);
    }

    /** 弓の間合いで見えているか(据えていない間。この間は近づかずに立って射る = {@link DevelopedParasitized#busy})。 */
    public static boolean inBowRange(DevelopedParasitized mob, LivingEntity target) {
        double d = mob.distanceTo(target);
        return d >= MIN_RANGE && d <= RANGE && mob.getSensing().hasLineOfSight(target);
    }

    @Override
    protected boolean canStartFrom(int action) {
        return action == AbstractParasitizedMonster.ACTION_NONE || (action == G1Move.ANCHORED_IDLE.id() && mob.anchored());
    }

    @Override
    protected boolean ready() {
        if (target == null || !target.isAlive() || !mob.canAttack(target) || !mob.getSensing().hasLineOfSight(target)) return false;
        double d = mob.distanceTo(target);
        return mob.anchored() ? d >= ANCHORED_MIN_RANGE && d <= ANCHORED_RANGE : d >= MIN_RANGE && d <= RANGE;
    }

    @Override
    protected int aimUntil() {
        return AIM_UNTIL;
    }

    @Override
    protected float turnRate() {
        return mob.anchored() ? ANCHORED_TURN : 30.0F;
    }

    @Override
    protected int cooldown() {
        // 技の長さ(20 + 2 + 16)を引いて、放ってから放つまでを INTERVAL + 乱数にそろえる
        return Math.max(0, INTERVAL + mob.getRandom().nextInt(INTERVAL_JITTER + 1) - (LENGTHS[0] + LENGTHS[1] + LENGTHS[2]));
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && tickInStage == 7) {
            // 胸の袋から塊をちぎり取る
            level.playSound(null, mob.getX(), mob.getY() + 1.2D, mob.getZ(), ModSounds.G1_SAC_SWELL.get(), SoundSource.HOSTILE, 0.8F, 0.6F);
        } else if (stageIndex == 0 && tickInStage == 13) {
            // 蔓の弦を引く
            level.playSound(null, mob.getX(), mob.getY() + 1.2D, mob.getZ(), SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), SoundSource.HOSTILE, 0.7F, 0.6F);
        } else if (stageIndex == 1 && tickInStage == 1) {
            shoot(level);
        }
    }

    private void shoot(ServerLevel level) {
        boolean anchored = mob.anchored();
        Vec3 face = Vec3.directionFromRotation(0.0F, lockedYaw);
        Vec3 from = mob.position().add(face.x * 0.5D, mob.getBbHeight() * 0.72D, face.z * 0.5D);
        // 向きは止めた体の向き。距離と高さは標的から(いなければ 8 ブロック先の同じ高さ)
        double dist = 8.0D, dy = 0.0D;
        if (target != null && target.isAlive()) {
            double tx = target.getX() - from.x, tz = target.getZ() - from.z;
            dist = Math.max(1.0D, Math.sqrt(tx * tx + tz * tz));
            dy = target.getY() + target.getBbHeight() * 0.5D - from.y;
        }
        float speed = anchored ? ANCHORED_SPEED : SPEED;
        Vec3 dir = G1SporeBolt.launch(from, from.add(face.x * dist, dy, face.z * dist), speed);
        G1SporeBolt bolt = new G1SporeBolt(level, mob).setLoad(DOSE, DAMAGE);
        bolt.setPos(from);
        bolt.shoot(dir.x, dir.y, dir.z, speed, anchored ? ANCHORED_SPREAD : SPREAD);
        level.addFreshEntity(bolt);
        mob.countBoltShot(bolt);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.SKELETON_SHOOT, SoundSource.HOSTILE, 1.0F, 0.6F);
        level.playSound(null, from.x, from.y, from.z, ModSounds.G1_SAC_SWELL.get(), SoundSource.HOSTILE, 0.6F, 0.9F);
    }
}
