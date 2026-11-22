package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * 特殊攻撃の出し方(グループ3 の共通部品、2026-09-23)。
 *
 * <p>ユーザー指定(2026-09-23、スクティムネスの依頼の中で): 「Falcimnes もそうなのですが、<b>最初から特殊攻撃を使わず、
 * 通常攻撃を使いながら、時々特殊攻撃を織り交ぜる</b>ようにしてほしい」。それまでのファルキムネスは交戦した直後から
 * 連撃・掴みを出していて、「通常攻撃で様子を見る → 大技」という流れが無かった。
 *
 * <p>数値はすべて<b>仮</b>(ユーザーが決めたのは「最初は出さない・通常攻撃の合間に時々」という方針だけ)。
 * {@link #standard()} の値:
 * <ul>
 *     <li><b>暖機 6 秒</b>(120 tick): 交戦を始めてからこれだけ経つまで出さない。通常攻撃 2 回ぶん(ファルキムネスの横薙ぎは
 *     振り 1.3 秒 + 間 0.5 秒)と、プレイヤーが相手の間合いと通常攻撃の前準備を覚える時間。</li>
 *     <li><b>最初の特殊攻撃の前に通常攻撃 2 回</b>: 時間だけだと、間合いの外でうろうろしているうちに 6 秒経ち、
 *     最初の一手が大技になる。「通常攻撃を使いながら」を回数でも縛る。</li>
 *     <li><b>特殊攻撃の後にも通常攻撃 2 回</b>: 大技が続けて出ないように。クールダウン(種ごと)とは別に数える ——
 *     クールダウンの違う大技を 2 つ持つ種(ファルキムネスの連撃と掴み)で、片方の直後にもう片方が出るのを防ぐ。</li>
 *     <li><b>35%</b>: 条件が揃った後、通常攻撃を出す場面ごとにこの確率で代わりに特殊攻撃。「時々」= 3 回に 1 回くらい。</li>
 * </ul>
 *
 * <h2>交戦のやり直し</h2>
 * <p>{@link #tick} に毎 tick(サーバー)いまの標的を渡す。暖機と回数を最初から数え直すのは、<b>生きた標的が 1 体も居ない状態が
 * 10 秒</b>({@link #LOST_RESET_TICKS})続いたときだけ。一瞬見失って戻ったときは続き(標的を選ぶ Goal は壁の向こうや茂みで
 * 標的を手放すことがある)。標的が居ない間も交戦中なら暖機は進む(見失っている間も戦いの最中)。
 *
 * <p><b>標的が替わってもやり直さない</b>(2026-09-24、レビューの指摘を受けて Claude がユーザーの代わりに決めた。仮)。
 * 初版は「別の個体を狙い始めたらやり直し」だったが、バニラの {@code HurtByTargetGoal}(この MOD の
 * {@code OvergrownHurtByTargetGoal} も)は<b>殴られるたびに標的を殴った相手へ替える</b>ので、2 人以上で交互に殴るだけで
 * 暖機が毎回やり直しになり、大技を一度も出さなくなっていた。「戦いが続いているか」だけを見る。
 * そのため、別の相手と戦っている途中(または倒してから 10 秒以内)に新しい相手が標的になると、その相手への最初の一手が
 * 大技になりうる(戦いは続いているので意図どおり)。
 *
 * <p><b>保存しない</b>。読み込み直したら交戦もやり直し(暖機 6 秒は短いので、やり直しで困ることは無い)。
 *
 * <h2>呼ぶ側の約束</h2>
 * <ul>
 *     <li>通常攻撃を<b>出した</b>ら {@link #onNormalAttack}、特殊攻撃を<b>出した</b>ら {@link #onSpecial}(当たったかどうかは問わない)。</li>
 *     <li>通常攻撃を出す場面で、特殊攻撃のクールダウンや間合いが揃っていれば {@link #roll} → true なら代わりに特殊攻撃。
 *     通常攻撃の無い場面(間合いの外など)で出す特殊攻撃は、{@link #ready} の間だけ種ごとの確率で。</li>
 *     <li>通常攻撃の間合いの外で相手を追い続けた時間は、種の判断で「通常攻撃を出そうとした 1 回」として {@link #onNormalAttack} で
 *     数えてよい(ファルキムネスは 5 秒ごと。間合いの外から撃ち続ける相手には通常攻撃が一度も出せず、大技も永遠に出せなくなるため)。</li>
 *     <li>演出(威嚇)や反射的な動き(回避)は数えない。</li>
 * </ul>
 * 使っている種: ファルキムネス(通常 = 横薙ぎ / 特殊 = 連撃・掴み)、スクティムネス(通常 = バッシュ・尾の薙ぎ払い / 特殊 = ガード → 叩きつけ)。
 */
public final class SpecialPacing {

    /** 標的が居ない・死んでいる状態がこれだけ続いたら交戦をやり直す(10 秒、仮)。 */
    public static final int LOST_RESET_TICKS = 200;
    /** 数える回数の上限(あふれ防止。条件に使う回数よりずっと大きければよい)。 */
    private static final int COUNT_CAP = 1000;

    private final int warmupTicks;
    private final int normalsBeforeFirst;
    private final int normalsBetween;
    private final float chance;

    /** 交戦中か(生きた標的を見てから、標的の居ない状態が {@link #LOST_RESET_TICKS} 続くまで)。どの標的かは覚えない。 */
    private boolean engaged;
    /** 交戦を始めてからの tick(暖機を満たしたらそれ以上は数えない)。 */
    private int engagedTicks;
    /** 標的が居ない・死んでいる状態が続いている tick。 */
    private int lostTicks;
    /** 交戦の開始、または最後の特殊攻撃から出した通常攻撃の回数。 */
    private int normals;
    /** この交戦で特殊攻撃を出したか(最初の 1 回とそれ以降で必要な通常攻撃の回数が違う)。 */
    private boolean usedSpecial;

    /**
     * @param warmupTicks        交戦を始めてから特殊攻撃を出さない tick
     * @param normalsBeforeFirst 交戦を始めてから最初の特殊攻撃までに要る通常攻撃の回数
     * @param normalsBetween     特殊攻撃の後、次の特殊攻撃までに要る通常攻撃の回数
     * @param chance             条件が揃った後、通常攻撃の場面で代わりに特殊攻撃を出す確率(0〜1)
     */
    public SpecialPacing(int warmupTicks, int normalsBeforeFirst, int normalsBetween, float chance) {
        this.warmupTicks = Math.max(0, warmupTicks);
        this.normalsBeforeFirst = Math.max(0, normalsBeforeFirst);
        this.normalsBetween = Math.max(0, normalsBetween);
        this.chance = Mth.clamp(chance, 0.0F, 1.0F);
    }

    /** 既定の出し方(仮): 暖機 6 秒・最初の前に通常攻撃 2 回・特殊攻撃の間に通常攻撃 2 回・35%。 */
    public static SpecialPacing standard() {
        return new SpecialPacing(120, 2, 2, 0.35F);
    }

    /**
     * 毎 tick(サーバー)、いまの標的を渡す。生きた標的が居れば交戦中(居なければ始める)。標的が替わってもそのまま続ける。
     * 標的が居ない・死んでいる状態が {@link #LOST_RESET_TICKS} 続いたら交戦をやり直す。
     */
    public void tick(@Nullable LivingEntity target) {
        if (target != null && target.isAlive()) {
            if (!this.engaged) {
                reset();
                this.engaged = true;
            }
            this.lostTicks = 0;
        } else if (this.engaged) {
            if (++this.lostTicks >= LOST_RESET_TICKS) {
                reset();
            }
        }
        if (this.engaged && this.engagedTicks < this.warmupTicks) {
            this.engagedTicks++;
        }
    }

    /** 暖機が明け、必要な回数の通常攻撃を出し終えているか。交戦していなければ false。 */
    public boolean ready() {
        if (!this.engaged || this.engagedTicks < this.warmupTicks) {
            return false;
        }
        return this.normals >= (this.usedSpecial ? this.normalsBetween : this.normalsBeforeFirst);
    }

    /**
     * 通常攻撃を出す場面で、代わりに特殊攻撃を出すか。{@link #ready} のときだけ乱数を引く
     * (揃っていない間は乱数を消費しない)。
     */
    public boolean roll(RandomSource random) {
        return ready() && random.nextFloat() < this.chance;
    }

    /** 通常攻撃を出した。 */
    public void onNormalAttack() {
        if (this.normals < COUNT_CAP) {
            this.normals++;
        }
    }

    /** 特殊攻撃を出した。次の特殊攻撃までに通常攻撃を数え直す。 */
    public void onSpecial() {
        this.usedSpecial = true;
        this.normals = 0;
    }

    /** 交戦をやり直す(暖機と回数を最初から。交戦していない状態へ)。 */
    public void reset() {
        this.engaged = false;
        this.engagedTicks = 0;
        this.lostTicks = 0;
        this.normals = 0;
        this.usedSpecial = false;
    }
}
