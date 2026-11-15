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
            Codec.BOOL.optionalFieldOf("boss", false).forGetter(e -> e.boss)
    ).apply(i, GenericInfected::of));

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

    private static GenericInfected of(boolean turned, long seed, long start, int transform, boolean boss) {
        GenericInfected g = new GenericInfected();
        g.turned = turned;
        g.seed = seed;
        g.startTick = start;
        g.transformTicks = transform;
        g.boss = boss;
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
    }
}
