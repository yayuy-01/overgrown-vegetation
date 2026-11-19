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
 *   <li>段: shoot_windup 20({@value #AIM_UNTIL} tick = 放つ tick まで向きを追う)→ shoot 2(1 tick 目に放つ)→ shoot_recover 16。</li>
 *   <li>据えていない間は、引く間も横へ歩く(2026-11-19 溜めを外す 2 便。バニラのスケルトンの横歩きと同じ形【仮】)。
 *   向きは 1 tick {@link G1SkillTiming#AIM_TURN_DEG}° まで(回り込めばよけられる)。</li>
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
    /** 弦を引く段の長さ。 */
    static final int DRAW = 20;
    /** 向きを追う最後の tick = 放つ tick(11-19 溜めを外す 2 便。元 18)。 */
    static final int AIM_UNTIL = DRAW + 1;
    /** 横歩きの向きを変えるか見る間隔(tick)と、変える割合(バニラの RangedBowAttackGoal と同じ)【仮】。 */
    static final int STRAFE_FLIP_EVERY = 20;
    static final float STRAFE_FLIP_CHANCE = 0.3F;
    /** 横歩きの速さ(MoveControl.strafe に渡す。バニラと同じ 0.5)【仮】。 */
    static final float STRAFE_SPEED = 0.5F;
    public static final float ANCHORED_TURN = 6.0F;
    private static final int[] LENGTHS = {DRAW, 2, 16};
    private boolean clockwise;

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
        return mob.anchored() ? ANCHORED_TURN : G1SkillTiming.AIM_TURN_DEG;
    }

    /** 据えていなければ足を止めない(引く間も横へ歩く)。 */
    @Override
    protected boolean holdStill() {
        return mob.anchored();
    }

    @Override
    public void start() {
        super.start();
        clockwise = mob.getRandom().nextBoolean();
    }

    /**
     * 横歩きの入力を消す。バニラの {@code MoveControl} は横歩きの後に横の入力({@code xxa})を戻さないので、そのままだと技の後も横へ滑り続ける
     * (据えた後に動いていた。11-19)。
     */
    @Override
    public void stop() {
        super.stop();
        mob.setXxa(0.0F);
        mob.setZza(0.0F);
    }

    @Override
    protected int cooldown() {
        // 技の長さ(DRAW + 2 + 16)を引いて、放ってから放つまでを INTERVAL + 乱数にそろえる
        return Math.max(0, INTERVAL + mob.getRandom().nextInt(INTERVAL_JITTER + 1) - (LENGTHS[0] + LENGTHS[1] + LENGTHS[2]));
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!mob.anchored()) strafe(stageIndex, tickInStage);
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

    /**
     * 引く・放つ・戻す間、標的のまわりを横へ歩く(バニラの {@code RangedBowAttackGoal} の横歩きと同じ形)。遠すぎれば前へ、近すぎれば後ろへ。
     * 経路の歩きは止める(経路の目標が毎 tick 横歩きを上書きするため)。
     */
    private void strafe(int stageIndex, int tickInStage) {
        mob.getNavigation().stop();
        if (tickInStage % STRAFE_FLIP_EVERY == 0 && mob.getRandom().nextFloat() < STRAFE_FLIP_CHANCE) clockwise = !clockwise;
        float forward = 0.0F;
        if (target != null && target.isAlive()) {
            double d = mob.distanceTo(target);
            if (d > RANGE * 0.75D) forward = STRAFE_SPEED;
            else if (d < RANGE * 0.25D) forward = -STRAFE_SPEED;
        }
        mob.getMoveControl().strafe(forward, clockwise ? STRAFE_SPEED : -STRAFE_SPEED);
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
