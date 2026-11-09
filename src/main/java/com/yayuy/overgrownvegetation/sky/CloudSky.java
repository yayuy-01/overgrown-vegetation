package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 今の空の雲の量(2026-10-17。2026-11-08 雲の段階 1 で parked から戻した)。クライアントの描画({@code client.sky.VoxelClouds})・雲の影・
 * 薄明光線が同じ答えを出すよう、ここにまとめる(設計値 {@code sky.json}・ワールドの時刻と天気・{@link CloudField} の式だけで決まる)。
 *
 * <p>2026-11-09 雲の段階 2a で積乱雲を戻した({@link #stormChance}・{@link #storms}・{@link #stormsNear}、柱と雲の中の判定に積乱雲の裾野と塊、
 * {@link #insideStorm}・{@link #underStorm})。雲の穴・吐いた雲はまだ parked。雷雨の天気との組み(雷雨で積乱雲が育つ)は後で(今は天気によらない)。
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
        if (override >= 0.0) {
            return (float) override;
        }
        float c = CloudField.coverage(level.getDayTime(), rawRain(level, partialTick), rawThunder(level, partialTick));
        // 雨の前にだんだん曇る(11-09 段階 2a-5、{@link CloudWeather#preRain})。
        float pre = CloudWeather.preRain(level, partialTick);
        return pre > 0.0F ? Mth.lerp(pre, c, Math.max(c, 0.97F)) : c;
    }

    /** 雲の並び方(1 = 群れ・2 = 谷のある雲の海・3 = 壁と橋)。 */
    public static int style() {
        return CloudShape.current().style();
    }

    // ---------------------------------------------------------------- 積乱雲(11-09 段階 2a。parked の同じ名前の口を設計値で)

    /** 立つ積乱雲のうち巨大になる割合(設計値が −1 のとき)。仮決め。 */
    public static final float GIANT_SHARE = 0.12F;
    /** 積乱雲が立ち始める雲の量と、立つ確率がいちばん高くなる量・その確率(設計値が −1 のとき)。仮決め。 */
    public static final float STORM_FROM = 0.0F;
    public static final float STORM_FULL = 0.9F;
    /** 11-09 段階 2a-4: 0.45 → 0.35(ユーザー「雲の多い日でも、積乱雲の山並みが空をほぼ埋める所を少し減らす」。仮決め)。 */
    public static final float STORM_MAX_CHANCE = 0.35F;
    /** 雲の少ない日でも立つ確率(10-18 ユーザー「積乱雲が天気で消えないように」・同じ日の夜「雲海 3 割・晴れ 7 割」から 0.03)。仮決め。 */
    public static final float STORM_MIN_CHANCE = 0.03F;

    /**
     * 積乱雲が 1 升に立つ確率: 設計値で固定されていればその値、−1 なら日の雲の量で(量 0 の日 0.03 から量 0.9 で 0.45。0 にはしない)。
     * 時刻では変えない(雲ごとの一生 = 湧いては崩れるは {@link CloudField#storms})。雷雨で増やすのは後で(雷雨の天気との組みは段階 2a ではしない)。
     */
    public static float stormChance(float coverage) {
        CloudShape shape = CloudShape.current();
        if (shape.storms() <= 0.0F) {
            return 0.0F;
        }
        float t = Mth.clamp((coverage - STORM_FROM) / (STORM_FULL - STORM_FROM), 0.0F, 1.0F);
        return shape.stormChance() >= 0.0F ? shape.stormChance() : Mth.lerp(t * t * (3.0F - 2.0F * t), STORM_MIN_CHANCE, STORM_MAX_CHANCE);
    }

    /** 積乱雲を決めるときの雲の量(0.02 刻み)。 */
    public static float stormCoverage(float coverage) {
        return Math.round(coverage * 50.0F) / 50.0F;
    }

    public static float giantChance() {
        float g = CloudShape.current().giantChance();
        return g >= 0.0F ? g : GIANT_SHARE;
    }

    /** 場の座標の範囲にかかる積乱雲(サーバーでもクライアントでも同じ答え: 種・時刻・設計値だけで決まる)。 */
    public static List<CloudField.Storm> storms(Level level, float coverage, double minX, double minZ, double maxX, double maxZ) {
        // 雲の量は 0.02 刻みで見る(11-09 段階 2a-2: 雨の降り始め・止み際は量が毎 tick 少しずつ変わり、積乱雲の並びの覚えも描き直しも毎回やり直しになった)。
        coverage = stormCoverage(coverage);
        float chance = stormChance(coverage);
        if (chance <= 0.0F) {
            return List.of();
        }
        // 10-18 夜(ユーザー「晴れの日は大きい雲ばかりで分厚い」): 巨大な積乱雲も晴れの日ほど少なく(量 0 で 3 割・量 0.7 以上でそのまま)。
        float t = Mth.clamp(coverage / 0.7F, 0.0F, 1.0F);
        float giant = giantChance() * Mth.lerp(t * t * (3.0F - 2.0F * t), 0.3F, 1.0F);
        return CloudField.storms(minX, minZ, maxX, maxZ, level.getDayTime(), chance, 0.0F, giant, style(), coverage);
    }

    /**
     * 点 (x, z)(世界の座標)のまわり ±range ブロックにかかる積乱雲(場の座標)。何度も引かれるので、場の座標の 2048 ブロックの升ごと・
     * 時刻 100 tick ごとに覚える(積乱雲の育ちは 100 tick ではほとんど変わらない。升の縁は 1 升ぶん広げて探すので覆える)。
     */
    public static List<CloudField.Storm> stormsNear(Level level, double x, double z, float partialTick, double range) {
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        float coverage = coverage(level, partialTick);
        if (range > NEAR_CELL) {
            return storms(level, coverage, fx - range, fz - range, fx + range, fz + range);
        }
        int cx = Mth.floor(fx / NEAR_CELL);
        int cz = Mth.floor(fz / NEAR_CELL);
        NearKey key = new NearKey(level.dimension().location().toString(), cx, cz, level.getDayTime() / 100L, Math.round(stormCoverage(coverage) * 50.0F),
                CloudShape.current(), CloudField.seed(), level.isClientSide());
        List<CloudField.Storm> list = NEAR.get(key);
        if (list == null) {
            if (NEAR.size() > 64) {
                NEAR.clear();
            }
            double pad = NEAR_CELL;
            list = storms(level, coverage, cx * NEAR_CELL - pad, cz * NEAR_CELL - pad, (cx + 1) * NEAR_CELL + pad, (cz + 1) * NEAR_CELL + pad);
            NEAR.put(key, list);
        }
        return list;
    }

    private static final double NEAR_CELL = 2048.0;

    private record NearKey(String dim, int cx, int cz, long time, int coverage, CloudShape shape, long seed, boolean client) {
    }

    private static final java.util.Map<NearKey, List<CloudField.Storm>> NEAR = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 世界の点 (x, z)・今の時刻の雲の柱(底と天辺の y)。雲が無ければ null。下の層(雲の海と裾野)と積乱雲・入道雲(塊を 8 ブロックおきに縦にたどる)。
     * 雲の影・薄明光線・雲の中の判定の土台(形と量は {@link CloudField} だけが決める)。
     */
    public static int[] columnAt(Level level, double x, double z, float partialTick) {
        int[] floor = floorAt(level, x, z, partialTick);
        int[] storm = stormSpan(level, x, z, partialTick);
        if (floor == null) {
            return storm;
        }
        if (storm == null) {
            return floor;
        }
        return new int[]{Math.min(floor[0], storm[0]), Math.max(floor[1], storm[1])};
    }

    /** 描いている雲の床の柱の大きさ(ブロック。谷のある雲の海。{@code client.sky.VoxelClouds} と同じ)。 */
    public static final int FLOOR_CELL = 24;

    /**
     * 描いている形と同じ柱(11-08 仕上げ 7。谷のある雲の海は {@link #FLOOR_CELL} の柱の真ん中、群れは 8 ブロックの柱)。場の座標で引く。
     * サーバーでもクライアントでも同じ答え(種・雲の流れ・雲の形の値が同じなら)。積乱雲の裾野は入らない(入れるなら storms を渡す方)。
     */
    public static CloudField.Column columnRendered(double fx, double fz, float threshold, float coverage, int style) {
        return columnRendered(fx, fz, threshold, coverage, style, List.of());
    }

    /** storms = 近くの積乱雲(場の座標)。雲の海の地形(谷)では足元の裾野が床に入り、積乱雲の台の下(描くときに空ける所)は雲の床にしない。 */
    public static CloudField.Column columnRendered(double fx, double fz, float threshold, float coverage, int style, List<CloudField.Storm> storms) {
        if (style >= CloudField.STYLE_VALLEY) {
            int per = FLOOR_CELL / CloudField.CELL_XZ;
            int gx = Mth.floor(fx / FLOOR_CELL);
            int gz = Mth.floor(fz / FLOOR_CELL);
            if (!storms.isEmpty() && CloudField.baseCovers(storms, (gx + 0.5) * FLOOR_CELL, (gz + 0.5) * FLOOR_CELL)) {
                return new CloudField.Column(1, 0, 0.0F);
            }
            CloudField.Column c = CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, coverage, storms, style);
            // 積乱雲の足元の坂(上限より高い柱)は 8 ブロックの柱で(11-09 段階 2a-2: 24 ブロックの柱では山の裾が階段に見えた)。描くときも同じ。
            if (footColumn(c)) {
                return CloudField.column(Mth.floor(fx / CloudField.CELL_XZ), Mth.floor(fz / CloudField.CELL_XZ), threshold, coverage, storms, style);
            }
            return c;
        }
        int cx = Mth.floor(fx / CloudField.CELL_XZ);
        int cz = Mth.floor(fz / CloudField.CELL_XZ);
        if (!storms.isEmpty() && CloudField.baseCovers(storms, (cx + 0.5) * CloudField.CELL_XZ, (cz + 0.5) * CloudField.CELL_XZ)) {
            return new CloudField.Column(1, 0, 0.0F);
        }
        return CloudField.column(cx, cz, threshold, coverage, storms, style);
    }

    /** 雲の床の 24 ブロックの柱が積乱雲の足元の坂(厚さの上限より高い)か。そこは 8 ブロックの柱で描き・答える。 */
    public static boolean footColumn(CloudField.Column c) {
        return c.present() && c.top() > CloudField.LOW_BASE + CloudField.floorThickness();
    }

    /** 世界の点 (x, z) の、描いている雲の床の柱 {底, 天辺}(y。積乱雲の足元の裾野を含む)。雲が無ければ null。サーバーからも引ける。 */
    public static int[] floorAt(Level level, double x, double z, float partialTick) {
        float coverage = coverage(level, partialTick);
        if (coverage <= 0.0F) {
            return null;
        }
        long gt = level.getGameTime();
        CloudField.Column c = columnRendered(CloudField.fieldX(x, gt, partialTick), CloudField.fieldZ(z, gt, partialTick), CloudField.threshold(coverage),
                coverage, style(), stormsNear(level, x, z, partialTick, 64.0));
        return c.present() ? new int[]{c.bottom(), c.top()} : null;
    }

    /** 世界の点 (x, y, z) が雲の中か(雲の床の柱の底〜天辺、または積乱雲・入道雲の塊の中)。 */
    public static boolean insideCloud(Level level, double x, double y, double z, float partialTick) {
        int[] c = floorAt(level, x, z, partialTick);
        return c != null && y >= c[0] && y <= c[1] || insideStorm(level, x, y, z, partialTick);
    }

    /** 世界の点 (x, z) の晴れの日の積雲の厚さ(0 = 無い)。 */
    public static float cumulusAt(Level level, double x, double z, float partialTick) {
        long gt = level.getGameTime();
        return CloudField.cumulusThickness(CloudField.fieldX(x, gt, partialTick), CloudField.fieldZ(z, gt, partialTick), coverage(level, partialTick));
    }

    /** 世界の点 (x, y, z) が晴れの日の積雲の下か(上昇気流の判定の土台。積雲があり、雲の底 y 300 より下)。 */
    public static boolean underCumulus(Level level, double x, double y, double z, float partialTick) {
        if (y < CloudField.LOW_BASE && cumulusAt(level, x, z, partialTick) > 0.0F) {
            return true;
        }
        // 晴れの日の積雲(11-09 段階 2a-5、{@link CloudField#KIND_CUMULUS})の下も。
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        for (CloudField.Storm s : stormsNear(level, x, z, partialTick, 64.0)) {
            if (s.kind() != CloudField.KIND_CUMULUS) {
                continue;
            }
            int[] sp = stormSpan(s, fx, fz);
            if (sp != null && y < sp[0]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 世界の点 (x, z) にかかる積乱雲・入道雲 1 つ(場の座標の雲。いちばん背の高いもの)。無ければ null。
     * congestus = 入道雲(雄大積雲)も含めるか。
     */
    public static CloudField.Storm stormAt(Level level, double x, double z, float partialTick, boolean congestus) {
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        CloudField.Storm best = null;
        for (CloudField.Storm s : stormsNear(level, x, z, partialTick, 64.0)) {
            if (s.kind() != 0 && s.kind() != CloudField.KIND_TOWER || !congestus && s.congestus()) { // 積乱雲と入道雲だけ(尾根・浮いた雲・橋は除く。11-09)
                continue;
            }
            if (stormSpan(s, fx, fz) != null && (best == null || s.topY() > best.topY())) {
                best = s;
            }
        }
        return best;
    }

    /** 世界の点 (x, y, z) が積乱雲・入道雲(と雲の橋)の塊の中か(11-09 段階 2a。サーバーからも引ける)。 */
    public static boolean insideStorm(Level level, double x, double y, double z, float partialTick) {
        if (y < CloudField.LOW_BASE) {
            return false;
        }
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        for (CloudField.Storm s : stormsNear(level, x, z, partialTick, 64.0)) {
            double r = s.reach();
            if (Math.abs(fx - s.x()) > r || Math.abs(fz - s.z()) > r || y > s.topY() + 40.0) {
                continue;
            }
            if (CloudField.inside(CloudField.puffs(s), fx, y, fz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 世界の点 (x, y, z) が積乱雲・入道雲の下か(11-09 段階 2a。ユーザー決定「積乱雲の下の上昇気流は危険なほど強い」の判定の土台)。
     * 真上に積乱雲の塊があり、その雲の底より下(底 = {@link #stormSpan} の底。台の底 y 300、または張り出した金床の下の面)。
     */
    public static boolean underStorm(Level level, double x, double y, double z, float partialTick) {
        // 11-09 段階 2a-3 ユーザー決定: 積乱雲と入道雲の下だけ。浮いた雲の下は false。
        // 尾根(仮決め): 雲の床から立つ壁の尾根は山の一部なので true、アーチ(トンネル)は下を飛んで抜ける所なので false。
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        for (CloudField.Storm s : stormsNear(level, x, z, partialTick, 64.0)) {
            boolean rises = s.kind() == 0 || s.kind() == CloudField.KIND_TOWER || s.kind() == CloudField.KIND_RIDGE && !s.anvil();
            if (!rises) {
                continue;
            }
            int[] sp = stormSpan(s, fx, fz);
            if (sp != null && y < sp[0]) {
                return true;
            }
        }
        return false;
    }

    /** 世界の点 (x, z) の積乱雲・入道雲の塊の {底, 天辺}(y。8 ブロックおきに縦にたどる)。無ければ null。 */
    public static int[] stormSpan(Level level, double x, double z, float partialTick) {
        long gt = level.getGameTime();
        double fx = CloudField.fieldX(x, gt, partialTick);
        double fz = CloudField.fieldZ(z, gt, partialTick);
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (CloudField.Storm s : stormsNear(level, x, z, partialTick, 64.0)) {
            int[] sp = stormSpan(s, fx, fz);
            if (sp != null) {
                lo = Math.min(lo, sp[0]);
                hi = Math.max(hi, sp[1]);
            }
        }
        return lo <= hi ? new int[]{lo, hi} : null;
    }

    /** 積乱雲 1 つの、場の点 (fx, fz) の塊の {底, 天辺}(y)。無ければ null。 */
    public static int[] stormSpan(CloudField.Storm s, double fx, double fz) {
        double r = s.reach();
        if (Math.abs(fx - s.x()) > r || Math.abs(fz - s.z()) > r) {
            return null;
        }
        List<CloudField.Puff> puffs = CloudField.puffs(s);
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int y = CloudField.LOW_BASE + 4; y <= s.topY() + 40; y += 8) {
            if (CloudField.inside(puffs, fx, y, fz)) {
                lo = Math.min(lo, y - 4);
                hi = Math.max(hi, y + 4);
            }
        }
        return lo <= hi ? new int[]{lo, hi} : null;
    }

    private CloudSky() {
    }
}
