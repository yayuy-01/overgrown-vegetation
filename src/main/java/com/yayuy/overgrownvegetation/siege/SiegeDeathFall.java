package com.yayuy.overgrownvegetation.siege;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 撃破された攻城戦ボスが<b>地面へ落ちていく</b>あいだの位置と姿勢。
 *
 * <h2>なぜ別クラスなのか</h2>
 * <p>このボスの位置は「軌道パラメータ + 経過 tick」からサーバーとクライアントが<b>それぞれ同じ式で</b>
 * 求める({@link SiegeBossMotion})。落下も同じ流儀でなければ、落ちていく絵だけ座標同期が要る
 * ことになってしまう。そこで落下も<b>式</b>にしてある。
 *
 * <p>ネットワークに追加で載せる情報は
 * <b>「いつ死んだか({@code deathAt} = 死んだ瞬間の経過 tick)」と「着地する Y」の 2 つだけ</b>。
 * 落ち始めの位置・向き・進行方向はすべて {@code motion.…At(deathAt)} から引けるので送らなくてよい。
 *
 * <h2>動き</h2>
 * <ul>
 *   <li>死んだ瞬間の位置から自由落下({@link #GRAVITY})。着地 Y で止まる。</li>
 *   <li>{@link #PITCH_TICKS} かけて頭から突っ込む角度({@link #NOSE_DOWN} 度)へ倒れ込む。</li>
 *   <li>落ちながら進行方向へわずかに滑る(周回の速さは 0.08 ブロック/tick と遅いので、ほぼ真下)。</li>
 *   <li>着地したら {@link #SETTLE_TICKS} かけて水平へ戻る(地面に横たわる)。</li>
 * </ul>
 */
public final class SiegeDeathFall {

    /** 落下の加速度(ブロック/tick²)。高度 210 → 地表 70 なら約 90 tick = 4.5 秒で着く。 */
    public static final double GRAVITY = 0.035D;
    /** 頭から突っ込む角度(度、正で下向き)。 */
    private static final float NOSE_DOWN = 62.0F;
    /** その角度へ倒れ込むまでの tick 数。 */
    private static final double PITCH_TICKS = 30.0D;
    /** 着地してから水平へ戻るまでの tick 数。 */
    private static final double SETTLE_TICKS = 30.0D;
    /** 着地してから攻城戦そのものを終える(ボスバーを消す)までの tick 数。 */
    public static final double LINGER_TICKS = 60.0D;

    /** 死んでから着地するまでの tick 数。 */
    public static double fallTicks(SiegeBossMotion motion, double deathAt, double groundY) {
        double height = Math.max(0.0D, motion.positionAt(deathAt).y - groundY);
        return Math.sqrt(2.0D * height / GRAVITY);
    }

    /** 死んでから {@code t - deathAt} tick 後の位置(胴の中心線上の点)。 */
    public static Vec3 positionAt(SiegeBossMotion motion, double deathAt, double groundY, double t) {
        double dt = Math.max(0.0D, t - deathAt);
        double capped = Math.min(dt, fallTicks(motion, deathAt, groundY));
        Vec3 start = motion.positionAt(deathAt);
        Vec3 heading = motion.headingAt(deathAt);
        // 失速しながら前へ滑る(周回が遅いので実際にはほんの数ブロック)。
        double drift = motion.speed() * capped * 0.6D;
        double y = Math.max(groundY, start.y - 0.5D * GRAVITY * capped * capped);
        return new Vec3(start.x + heading.x * drift, y, start.z + heading.z * drift);
    }

    /** 落下中の向き(ヨーは死んだ瞬間のまま)。 */
    public static float yawAt(SiegeBossMotion motion, double deathAt) {
        return motion.yawAt(deathAt);
    }

    /** 落下中のピッチ。頭から突っ込み、着地したら水平へ戻る。 */
    public static float pitchAt(SiegeBossMotion motion, double deathAt, double groundY, double t) {
        double dt = Math.max(0.0D, t - deathAt);
        double fall = fallTicks(motion, deathAt, groundY);
        float start = motion.pitchAt(deathAt);
        float diving = Mth.lerp((float) Math.min(1.0D, dt / PITCH_TICKS), start, NOSE_DOWN);
        if (dt <= fall) {
            return diving;
        }
        float atLanding = Mth.lerp((float) Math.min(1.0D, fall / PITCH_TICKS), start, NOSE_DOWN);
        return Mth.lerp((float) Math.min(1.0D, (dt - fall) / SETTLE_TICKS), atLanding, 0.0F);
    }

    /** もう地面に着いているか。 */
    public static boolean landed(SiegeBossMotion motion, double deathAt, double groundY, double t) {
        return t - deathAt >= fallTicks(motion, deathAt, groundY);
    }

    /** 落下の演出が終わり、攻城戦そのものを終えてよいか。 */
    public static boolean finished(SiegeBossMotion motion, double deathAt, double groundY, double t) {
        return t - deathAt >= fallTicks(motion, deathAt, groundY) + LINGER_TICKS;
    }

    private SiegeDeathFall() {
    }
}
