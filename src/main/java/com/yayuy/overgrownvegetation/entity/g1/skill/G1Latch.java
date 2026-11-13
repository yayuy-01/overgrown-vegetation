package com.yayuy.overgrownvegetation.entity.g1.skill;

import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 取り付き(2026-11-12、能力の第 1 回。共通の部品 —— チビゾンビの (a) のほか、豚の食らいつき・後の回の種も使える)。
 * 噛みついたまま離れず、標的を遅くし、間をおいて少しずつ噛む。
 *
 * <ul>
 *   <li>位置: {@code follow} なら毎 tick 標的の脚の横(標的の体の向きの後ろ寄り)へ体を合わせる(乗り物にはしない —— プレイヤーに Mob を
 *   乗せるのはバニラが確かに扱わないので)。重力は切る。{@code follow} でなければ持ち手はその場で噛み続ける(豚の食らいつき用)。
 *   その場で噛む持ち手は、{@link #dragAlong} を付けると標的が歩いたぶんだけ引きずられる(付けないと、標的が
 *   {@value #BREAK_DISTANCE} ブロック歩けば外れる)。</li>
 *   <li>遅くする: 鈍足(強さ {@code slowAmplifier}、0 = I = −15%)をかけ直す。チビゾンビは −30%【仮】= II。</li>
 *   <li>噛む: {@code biteInterval} tick ごとに {@code biteDamage} の近接ダメージと暴露量 {@code biteDose}({@link G1SporeDose})。
 *   {@code biteDamage} が 0 以下なら傷は与えず、暴露量だけ足す(豚の食らいつき: 噛んでいる間は暴露量が積まれるだけ)。</li>
 *   <li>離れる: 長さ {@code maxTicks} が過ぎた・標的が死んだ / 消えた / 離れすぎた・標的に殴られた({@link #onHolderHurtBy})・
 *   プレイヤーがしゃがんだ(仕様「しゃがんで振り払う【案】」。チビゾンビだけ —— {@link #noCrouchRelease} で切る)。</li>
 * </ul>
 * 後から付ける口({@link #dragAlong}・{@link #noCrouchRelease})は作った直後に呼ぶ。付けなければチビゾンビの取り付きと同じ動き。
 */
public final class G1Latch {

    /** 離れすぎたら外れる(ブロック。テレポート・乗り物で引き離されたとき)。【仮】 */
    static final double BREAK_DISTANCE = 3.0D;
    /** 引きずられる速さの上限(ブロック / tick。走るプレイヤー 0.28 より少し上)。これより速く離れる相手(馬・エリトラ)には振り切られる。【仮】 */
    static final double DRAG_MAX_PER_TICK = 0.4D;

    private final Mob holder;
    private final LivingEntity target;
    private final int maxTicks;
    private final int biteInterval;
    private final float biteDamage;
    private final double biteDose;
    private final int slowAmplifier;
    private final boolean follow;
    private boolean crouchRelease = true;
    /** 引きずられ始める間(体の箱と箱のすきま、ブロック)。負 = 引きずられない。 */
    private double dragGap = -1.0D;
    private int ticks;
    private boolean released;
    private boolean knocked;

    public G1Latch(Mob holder, LivingEntity target, int maxTicks, int biteInterval, float biteDamage, double biteDose,
                   int slowAmplifier, boolean follow) {
        this.holder = holder;
        this.target = target;
        this.maxTicks = maxTicks;
        this.biteInterval = biteInterval;
        this.biteDamage = biteDamage;
        this.biteDose = biteDose;
        this.slowAmplifier = slowAmplifier;
        this.follow = follow;
        if (follow) {
            holder.setNoGravity(true);
        }
        holder.getNavigation().stop();
    }

    /**
     * その場で噛む持ち手({@code follow} でない)を、標的が歩いたぶんだけ引きずらせる(2026-11-13、豚の食らいつき「離さない」)。
     * 体の箱と箱のすきまが {@code gap} ブロックを超えたぶんだけ、持ち手を標的の方へ動かす。壁・柵には当たる
     * ({@code Entity#move}。越えられなければ置いていかれて {@value #BREAK_DISTANCE} ブロックで外れる)。
     */
    public G1Latch dragAlong(double gap) {
        this.dragGap = Math.max(0.0D, gap);
        return this;
    }

    /** プレイヤーがしゃがんでも外れないようにする(豚の食らいつきの対処は「攻撃 1 回」だけ)。 */
    public G1Latch noCrouchRelease() {
        this.crouchRelease = false;
        return this;
    }

    public LivingEntity target() {
        return target;
    }

    /** 取り付いてからの tick。 */
    public int ticks() {
        return ticks;
    }

    public boolean released() {
        return released;
    }

    /** 標的に殴られて振り落とされたか(時間切れ・標的が消えた、ではなく)。 */
    public boolean knocked() {
        return knocked;
    }

    /**
     * 1 tick 進める(サーバー)。
     *
     * @return まだ取り付いているか
     */
    public boolean tick() {
        if (released) return false;
        ticks++;
        if (ticks > maxTicks || !target.isAlive() || target.isRemoved() || holder.distanceToSqr(target) > BREAK_DISTANCE * BREAK_DISTANCE
                || target instanceof Player p && (crouchRelease && p.isShiftKeyDown() || p.isSpectator())) {
            release(false);
            return false;
        }
        if (follow) {
            // 標的の体の向きの後ろ寄り・右の脚の横(真後ろだと標的から見えないので斜め)
            float yaw = target.yBodyRot * Mth.DEG_TO_RAD;
            double side = target.getBbWidth() * 0.5D + holder.getBbWidth() * 0.35D;
            Vec3 at = target.position().add(-Mth.sin(yaw + 2.4F) * side, 0.0D, Mth.cos(yaw + 2.4F) * side);
            holder.setPos(at.x, at.y, at.z);
            holder.setDeltaMovement(Vec3.ZERO);
            holder.fallDistance = 0.0F;
        } else if (dragGap >= 0.0D) {
            // 標的が歩いたぶんだけ引きずられる(高さは重力に任せる)
            double dx = target.getX() - holder.getX();
            double dz = target.getZ() - holder.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            double over = d - (target.getBbWidth() * 0.5D + holder.getBbWidth() * 0.5D + dragGap);
            if (over > 0.0D && d > 1.0E-4D) {
                double step = Math.min(over, DRAG_MAX_PER_TICK);
                holder.move(MoverType.SELF, new Vec3(dx / d * step, 0.0D, dz / d * step));
            }
        }
        holder.getNavigation().stop();
        holder.getLookControl().setLookAt(target, 60.0F, 60.0F);
        holder.lookAt(target, 60.0F, 60.0F);
        holder.setYBodyRot(holder.getYRot());
        if (slowAmplifier >= 0) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 12, slowAmplifier, false, false), holder);
        }
        if (biteInterval > 0 && ticks % biteInterval == 0) {
            if (biteDamage <= 0.0F || target.hurt(holder.damageSources().mobAttack(holder), biteDamage)) {
                G1SporeDose.add(target, biteDose);
            }
        }
        return true;
    }

    /** 持ち手が殴られた: 標的(取り付かれている相手)からなら振り落とされる(仕様「殴る(1 撃で振り落とす)」)。 */
    public void onHolderHurtBy(LivingEntity attacker) {
        if (attacker == target) {
            release(true);
        }
    }

    /**
     * 離れる。
     *
     * @param knocked 振り落とされた(後ろへ跳ねる)
     */
    public void release(boolean knocked) {
        if (released) return;
        released = true;
        this.knocked = knocked;
        if (follow) {
            holder.setNoGravity(false);
        }
        if (knocked) {
            Vec3 away = holder.position().subtract(target.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() < 1.0E-4D) away = new Vec3(1.0D, 0.0D, 0.0D);
            away = away.normalize().scale(0.35D);
            holder.setDeltaMovement(away.x, 0.25D, away.z);
            holder.hurtMarked = true;
        }
    }
}
