package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * 今の空の雲の量(2026-10-17。2026-11-08 雲の段階 1 で parked から戻した)。クライアントの描画({@code client.sky.VoxelClouds})・雲の影・
 * 薄明光線が同じ答えを出すよう、ここにまとめる(設計値 {@code sky.json}・ワールドの時刻と天気・{@link CloudField} の式だけで決まる)。
 *
 * <p>段階 1 では積乱雲・雲の穴・吐いた雲を戻していないので、前の版の {@code stormChance}・{@code storms}・{@code stormsNear} と
 * {@code columnAt} の積乱雲の分は外した(元は parked の同じパス。段階 2 で戻す)。
 */
public final class CloudSky {

    /**
     * 本当の天気を読んでいる間か(クライアントは雲の上では雨を弱めて見せる({@code mixin/client/LevelRainAboveCloudsMixin})が、
     * 雲の量はワールドの本当の天気で決めるので、その間だけ弱めない)。
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

    /** 今の雲の量(設計値で固定されていればその値)。 */
    public static float coverage(Level level, float partialTick) {
        double override = CloudShape.current().coverage();
        return override >= 0.0 ? (float) override
                : CloudField.coverage(level.getDayTime(), rawRain(level, partialTick), rawThunder(level, partialTick));
    }

    /** 雲の並び方(1 = 群れ・2 = 谷のある雲の海・3 = 壁と橋)。 */
    public static int style() {
        return CloudShape.current().style();
    }

    /**
     * 世界の点 (x, z)・今の時刻の雲の柱(底と天辺の y)。雲が無ければ null。段階 1 は下の層(雲の海)だけ。
     * 雲の影・薄明光線・雲の中の判定の土台(形と量は {@link CloudField} だけが決める)。
     */
    public static int[] columnAt(Level level, double x, double z, float partialTick) {
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        float coverage = coverage(level, partialTick);
        CloudField.Column c = CloudField.column(Mth.floor(fx / CloudField.CELL_XZ), Mth.floor(fz / CloudField.CELL_XZ), CloudField.threshold(coverage),
                coverage, java.util.List.of(), style());
        return c.present() ? new int[]{c.bottom(), c.top()} : null;
    }

    /** 描いている雲の床の柱の大きさ(ブロック。谷のある雲の海。{@code client.sky.VoxelClouds} と同じ)。 */
    public static final int FLOOR_CELL = 24;

    /**
     * 描いている形と同じ柱(11-08 仕上げ 7。谷のある雲の海は {@link #FLOOR_CELL} の柱の真ん中、群れは 8 ブロックの柱)。場の座標で引く。
     * サーバーでもクライアントでも同じ答え(種・雲の流れ・雲の形の値が同じなら)。
     */
    public static CloudField.Column columnRendered(double fx, double fz, float threshold, float coverage, int style) {
        if (style >= CloudField.STYLE_VALLEY) {
            int per = FLOOR_CELL / CloudField.CELL_XZ;
            int gx = Mth.floor(fx / FLOOR_CELL);
            int gz = Mth.floor(fz / FLOOR_CELL);
            return CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, coverage, java.util.List.of(), style);
        }
        return CloudField.column(Mth.floor(fx / CloudField.CELL_XZ), Mth.floor(fz / CloudField.CELL_XZ), threshold, coverage, java.util.List.of(), style);
    }

    /** 世界の点 (x, z) の、描いている雲の床の柱 {底, 天辺}(y)。雲が無ければ null。サーバーからも引ける。 */
    public static int[] floorAt(Level level, double x, double z, float partialTick) {
        float coverage = coverage(level, partialTick);
        if (coverage <= 0.0F) {
            return null;
        }
        long gt = level.getGameTime();
        CloudField.Column c = columnRendered(CloudField.fieldX(x, gt, partialTick), CloudField.fieldZ(z, gt, partialTick), CloudField.threshold(coverage),
                coverage, style());
        return c.present() ? new int[]{c.bottom(), c.top()} : null;
    }

    /** 世界の点 (x, y, z) が雲の中か(描いている柱の底〜天辺)。 */
    public static boolean insideCloud(Level level, double x, double y, double z, float partialTick) {
        int[] c = floorAt(level, x, z, partialTick);
        return c != null && y >= c[0] && y <= c[1];
    }

    /** 世界の点 (x, z) の晴れの日の積雲の厚さ(0 = 無い)。 */
    public static float cumulusAt(Level level, double x, double z, float partialTick) {
        long gt = level.getGameTime();
        return CloudField.cumulusThickness(CloudField.fieldX(x, gt, partialTick), CloudField.fieldZ(z, gt, partialTick), coverage(level, partialTick));
    }

    /** 世界の点 (x, y, z) が晴れの日の積雲の下か(上昇気流の判定の土台。積雲があり、雲の底 y 300 より下)。 */
    public static boolean underCumulus(Level level, double x, double y, double z, float partialTick) {
        return y < CloudField.LOW_BASE && cumulusAt(level, x, z, partialTick) > 0.0F;
    }

    private CloudSky() {
    }
}
