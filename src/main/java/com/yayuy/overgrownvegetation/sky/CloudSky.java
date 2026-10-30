package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 今の空の雲の量と積乱雲(2026-10-17)。クライアントの描画({@code client/VoxelClouds})と、積乱雲を探すコマンド {@link StormCommand} が
 * 同じ答えを出すよう、ここにまとめる(どちらも設定 COMMON の値・ワールドの時刻と天気・{@link CloudField} の式だけで決まる)。
 */
public final class CloudSky {
    /** 立つ積乱雲のうち巨大になる割合(設定が −1 のとき)。仮決め。 */
    public static final float GIANT_SHARE = 0.12F;
    /** 積乱雲が立ち始める雲の量と、立つ確率がいちばん高くなる量・その確率(設定が −1 のとき)。仮決め。 */
    public static final float STORM_FROM = 0.0F;
    public static final float STORM_FULL = 0.9F;
    public static final float STORM_MAX_CHANCE = 0.45F;
    /** 雲の少ない日でも立つ確率(10-18 ユーザー「積乱雲が天気で消えないように」で 0.2。同じ日の夜に「雲海 3 割・晴れ 7 割」で 0.03 へ(晴れの日は少なく)。仮決め。 */
    public static final float STORM_MIN_CHANCE = 0.03F;

    /**
     * 本当の天気を読んでいる間か(クライアントは雲の上では雨を弱めて見せる({@code mixin/client/LevelRainAboveCloudsMixin})が、
     * 雲の量・積乱雲・雷はワールドの本当の天気で決めるので、その間だけ弱めない)。
     */
    private static final ThreadLocal<int[]> RAW = ThreadLocal.withInitial(() -> new int[1]);

    public static boolean readingRawWeather() {
        return RAW.get()[0] > 0;
    }

    /** 本当の雨の強さ。 */
    public static float rawRain(Level level, float partialTick) {
        int[] d = RAW.get();
        d[0]++;
        try {
            return level.getRainLevel(partialTick);
        } finally {
            d[0]--;
        }
    }

    /** 本当の雷雨の強さ。 */
    public static float rawThunder(Level level, float partialTick) {
        int[] d = RAW.get();
        d[0]++;
        try {
            return level.getThunderLevel(partialTick);
        } finally {
            d[0]--;
        }
    }

    /** 今の雲の量(設定で固定されていればその値)。 */
    public static float coverage(Level level, float partialTick) {
        double override = OvergrownVegetationConfig.COMMON.cloudCoverage.get();
        return override >= 0.0 ? (float) override
                : CloudField.coverage(level.getDayTime(), rawRain(level, partialTick), rawThunder(level, partialTick));
    }

    /**
     * 積乱雲が 1 升に立つ確率: 設定で固定されていればその値、−1 なら日の雲の量で(量 0 の日 0.03 から量 0.9 で 0.45。10-18 夜から。0 にはしない)、雷雨の間は 0.9 まで。
     * 時刻では変えない(雲ごとの一生は {@link CloudField#storms})。
     */
    public static float stormChance(Level level, float partialTick, float coverage) {
        if (OvergrownVegetationConfig.COMMON.cumulonimbus.get() <= 0.0) {
            return 0.0F;
        }
        double override = OvergrownVegetationConfig.COMMON.cumulonimbusChance.get();
        float t = Mth.clamp((coverage - STORM_FROM) / (STORM_FULL - STORM_FROM), 0.0F, 1.0F);
        float chance = override >= 0.0 ? (float) override : Mth.lerp(t * t * (3.0F - 2.0F * t), STORM_MIN_CHANCE, STORM_MAX_CHANCE);
        return Math.max(chance, 0.9F * rawThunder(level, partialTick));
    }

    public static float giantChance() {
        double giant = OvergrownVegetationConfig.COMMON.giantStormChance.get();
        return giant >= 0.0 ? (float) giant : GIANT_SHARE;
    }

    public static int style() {
        return OvergrownVegetationConfig.COMMON.cumulonimbusStyle.get();
    }

    /** 場の座標の範囲にかかる積乱雲。 */
    public static List<CloudField.Storm> storms(Level level, float partialTick, float coverage, double minX, double minZ, double maxX, double maxZ) {
        // 10-18 夜(ユーザー「晴れの日は大きい雲ばかりで分厚い」): 巨大な積乱雲も晴れの日ほど少なく(量 0 で 3 割・量 0.7 以上でそのまま)。
        float t = Mth.clamp(coverage / 0.7F, 0.0F, 1.0F);
        float giant = giantChance() * Mth.lerp(t * t * (3.0F - 2.0F * t), 0.3F, 1.0F);
        return CloudField.storms(minX, minZ, maxX, maxZ, level.getDayTime(), stormChance(level, partialTick, coverage),
                rawThunder(level, partialTick), giant, style());
    }

    /**
     * 世界の点 (x, z) の雲の柱(2026-10-19。空の巨大生物が雲海に潜って隠れる・鯨のように飛び出すのに使う。空の巨大生物のセッションからの依頼)。
     * bottom = いちばん下の雲の底、top = いちばん上の雲の天辺(y)、thickness = 雲の中の厚さの合計(間のすき間は数えない)、
     * seaBottom・seaTop = そのうち下の層・雲の海の地形・巨大な積乱雲の雲海の部分の底と天辺(無ければどちらも −1。積乱雲・吐いた雲は入らない)。
     */
    public record CloudColumn(int bottom, int top, int thickness, int seaBottom, int seaTop) {
    }

    /** 点 (x, z) のまわりの積乱雲(場の座標。{@link #columnAt(Level, double, double, float, List)} に何度も渡すとき用。範囲は ±range ブロック)。 */
    public static List<CloudField.Storm> stormsNear(Level level, double x, double z, float partialTick, double range) {
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        return storms(level, partialTick, coverage(level, partialTick), fx - range, fz - range, fx + range, fz + range);
    }

    /** {@link #columnAt(Level, double, double, float, List)} を、その点のまわりの積乱雲を引き直して(1 回ごとに雲の升を数えるので、何度も呼ぶなら積乱雲を渡す方を)。 */
    public static CloudColumn columnAt(Level level, double x, double z, float partialTick) {
        return columnAt(level, x, z, partialTick, stormsNear(level, x, z, partialTick, 64.0));
    }

    /**
     * 世界の点 (x, z)・今の時刻(partialTick)の雲の柱。雲が無ければ null。サーバーでもクライアントでも同じ答え(どちらも設定・ワールドの時刻と天気・
     * {@link CloudField} の式だけで決まる。吐いた雲はサーバーはサーバーが出した雲、クライアントは受け取った雲で数える)。数えるもの:
     * 下の層・雲の海の地形(床・裾野・谷)・巨大な積乱雲の雲海({@link CloudField#column})、積乱雲・入道雲(塊を 8 ブロックおきに縦にたどる)、吐いた雲。
     * 砲弾・ビームの穴は数えない(クライアントだけが知る)。storms = {@link #stormsNear} で引いた積乱雲(場の座標)。
     */
    public static CloudColumn columnAt(Level level, double x, double z, float partialTick, List<CloudField.Storm> storms) {
        long gt = level.getGameTime();
        double t = gt + (double) partialTick;
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        float coverage = coverage(level, partialTick);
        int bottom = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        int thick = 0;
        CloudField.Column c = CloudField.column(Mth.floor(fx / CloudField.CELL_XZ), Mth.floor(fz / CloudField.CELL_XZ), CloudField.threshold(coverage),
                coverage, storms, style());
        if (c.present()) {
            bottom = c.bottom();
            top = c.top();
            thick = c.top() - c.bottom();
        }
        for (CloudField.Storm s : storms) {
            double r = s.reach();
            if (Math.abs(fx - s.x()) > r || Math.abs(fz - s.z()) > r) {
                continue;
            }
            List<CloudField.Puff> puffs = CloudField.puffs(s);
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int y = CloudField.LOW_BASE + 4; y <= s.topY() + 40; y += 8) {
                if (CloudField.inside(puffs, fx, y, fz)) {
                    lo = Math.min(lo, y - 4);
                    hi = Math.max(hi, y + 4);
                    // 下の層と重なる所は二重に数えない。
                    if (!(c.present() && y >= c.bottom() && y <= c.top())) {
                        thick += 8;
                    }
                }
            }
            if (lo <= hi) {
                bottom = Math.min(bottom, lo);
                top = Math.max(top, hi);
            }
        }
        int[] puff = CloudPuffs.span(level.isClientSide(), t, fx, fz);
        if (puff != null) {
            bottom = Math.min(bottom, puff[0]);
            top = Math.max(top, puff[1]);
            thick += puff[2];
        }
        return thick > 0 ? new CloudColumn(bottom, top, thick, c.present() ? c.bottom() : -1, c.present() ? c.top() : -1) : null;
    }

    private CloudSky() {
    }
}
