package com.yayuy.overgrownvegetation.infect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 汎用の感染体の印(1 体ぶん。{@code ModAttachments.GENERIC_INFECTED}、2026-11-15)。{@link GenericInfection} だけが書く。
 * 変わっていない個体は {@link #turned} が false で、保存しない。
 *
 * <p>暴露量({@code SporeExposure})とは別にした: あちらの {@code reset()}(暴露量のリセットの命令・観戦)で印ごと消えないように。
 */
public final class GenericInfected {

    public static final Codec<GenericInfected> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("turned", false).forGetter(e -> e.turned),
            Codec.LONG.optionalFieldOf("seed", 0L).forGetter(e -> e.seed),
            Codec.LONG.optionalFieldOf("start", 0L).forGetter(e -> e.startTick),
            Codec.INT.optionalFieldOf("transform", 0).forGetter(e -> e.transformTicks),
            Codec.BOOL.optionalFieldOf("boss", false).forGetter(e -> e.boss),
            Codec.FLOAT.optionalFieldOf("blocked", 0.0F).forGetter(e -> e.blockedDamage)
    ).apply(i, GenericInfected::of));

    /** 保存するか(変わった個体か、弾かれた胞子のダメージを数えている途中の個体)。 */
    public boolean shouldSave() {
        return turned || blockedDamage > 0.0F;
    }

    /**
     * 弾かれた胞子のダメージの合計(変わる前だけ。2026-11-15 ユーザー決定「耐性を無視して胞子で変える」)。胞子のダメージの種類に耐性を持つ Mob は、
     * 20 ダメージが入らないので、入ったことにしてここへ足し、体力に届いたら変わる。保存する。
     */
    public float blockedDamage;

    /** 変わったか(変わっている途中も含む)。 */
    public boolean turned;
    /** 個体ごとの乱数の種(見た目の部品の位置・絵の通り。変わったときに 1 回だけ引いて保存する)。 */
    public long seed;
    /** 変わり始めたゲーム時刻(level.getGameTime)。 */
    public long startTick;
    /** 変わる動きの長さ(tick)。0 = すぐ変わり終えた。 */
    public int transformTicks;
    /** ボスとして変わったか(変わったときに決めて保存する)。 */
    public boolean boss;
    /** 最後に体当たりを当てたゲーム時刻(保存しない)。 */
    public long lastRamTick = Long.MIN_VALUE / 2;
    /** 監視塔の呼び寄せ(保存しない): 向かう先と、このゲーム時刻まで向かう。 */
    public double callX, callY, callZ;
    public long callUntil = Long.MIN_VALUE / 2;
    /**
     * 自前の狙う相手(保存しない)。{@code getTarget()} を Brain の ATTACK_TARGET で上書きする種(ピグリン・ホグリン・ゾグリン・ウォーデン・
     * ブリーズ・カエル・ウーパールーパー)では setTarget が読み返せないので、体当たり・呼び寄せはこちらを見る(ATTACK_TARGET は書かない = ★ 7:2)。
     */
    public net.minecraft.world.entity.LivingEntity prey;
    /** 体当たりの予備の見張り(保存しない): 見ている相手・その相手を狙い始めた tickCount・予備に切り替わったか。 */
    public net.minecraft.world.entity.LivingEntity trackedTarget;
    public int targetSince;
    public boolean fallbackOn;
    /** 攻撃の手段を持たない種として動きを付けたか(保存しない。付けたとき 1 回だけ決める。null = まだ決めていない)。 */
    public Boolean attackless;
    /** 外した Goal(戻す用。保存しない。Mob と一緒に消えるようここに置く)。 */
    Object removedGoals;

    private static GenericInfected of(boolean turned, long seed, long start, int transform, boolean boss, float blocked) {
        GenericInfected g = new GenericInfected();
        g.turned = turned;
        g.seed = seed;
        g.startTick = start;
        g.transformTicks = transform;
        g.boss = boss;
        g.blockedDamage = blocked;
        return g;
    }

    /** 変わる動きの途中か。 */
    public boolean transforming(long gameTime) {
        return turned && transformTicks > 0 && gameTime - startTick < transformTicks;
    }

    void clear() {
        turned = false;
        seed = 0L;
        startTick = 0L;
        transformTicks = 0;
        boss = false;
        blockedDamage = 0.0F;
    }
}
