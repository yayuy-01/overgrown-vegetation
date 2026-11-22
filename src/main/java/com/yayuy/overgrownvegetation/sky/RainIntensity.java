package com.yayuy.overgrownvegetation.sky;

import net.minecraft.world.level.Level;

/**
 * 雨の強さ(2026-11-12、ユーザーの提案「雲と連携しているのだから雨に種類を: 大雨・小雨・普通」。決め方はユーザー選択「雨ごと + 真上の雲」)。
 *
 * <p>ここは世界で 1 つの強さ(雨ごとの種類 × 降り始めと止み際の立ち上がり)。サーバーとクライアントで同じ答え(雨の予定 {@link CloudWeather} が
 * 種と時刻で決まるので)。真上の雲での強弱(積乱雲の下は強く・雲の薄い所は弱く)はクライアントの見た目と音だけで足す
 * ({@code client.atmosphere.RainFeel})。胞子の場(サーバー、100 tick ごと)はこの世界で 1 つの値を使う。
 *
 * <ul>
 *   <li>種類: 予定の雨ごとに、その雨の始まりの時刻から決める(小雨 {@link #SHARE_LIGHT}・大雨 {@link #SHARE_HEAVY}・残り普通)。</li>
 *   <li>立ち上がり: 降り始めの {@link #RAMP_TICKS} と止む前の {@link #RAMP_TICKS} は、その雨の強さの {@link #RAMP_FLOOR} 倍から(降り始めと止み際は小雨)。</li>
 *   <li>予定の無い雨(手で {@code /weather rain}・雨を雲に合わせない設定)は普通。雷雨の間は大雨以上(見た目と音。胞子では雷雨は別扱い)。</li>
 * </ul>
 * 普通 = {@link #NORMAL}(0.6)で、胞子の仕組みの数値が今までと同じになるように正規化してある。数値はすべて仮決め。
 */
public final class RainIntensity {

    public static final float LIGHT = 0.3F;
    public static final float NORMAL = 0.6F;
    public static final float HEAVY = 0.9F;
    static final float SHARE_LIGHT = 0.35F;
    static final float SHARE_HEAVY = 0.25F;
    static final float RAMP_TICKS = 2000.0F;
    static final float RAMP_FLOOR = 0.45F;

    /** 予定の雨 r の種類(小雨・普通・大雨の強さ)。 */
    public static float kind(CloudWeather.Rain r) {
        long h = r.from() * 0x9E3779B97F4A7C15L + 0x5A1EL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        float u = (h >>> 40) / (float) (1 << 24);
        return u < SHARE_LIGHT ? LIGHT : (u < 1.0F - SHARE_HEAVY ? NORMAL : HEAVY);
    }

    /** 予定の雨 r の時刻 t での立ち上がり(RAMP_FLOOR〜1)。 */
    public static float ramp(CloudWeather.Rain r, double t) {
        double k = Math.min((t - r.from()) / RAMP_TICKS, (r.to() - t) / RAMP_TICKS);
        float s = (float) Math.max(0.0, Math.min(1.0, k));
        s = s * s * (3.0F - 2.0F * s);
        return RAMP_FLOOR + (1.0F - RAMP_FLOOR) * s;
    }

    /**
     * 世界で 1 つの雨の強さ(0〜1。雨が降っていなければ 0)。raining・thundering はバニラの天気(呼び手が素の値を渡す。クライアントの描画の
     * スレッドでは {@link CloudSky#rawRain} から。ここで {@code getRainLevel} を呼ぶと、雨の強さを掛けるフックの中から呼ばれて巡る)。
     */
    public static float global(Level level, boolean raining, boolean thundering, float partialTick) {
        if (!raining) {
            return 0.0F;
        }
        float v = NORMAL;
        if (CloudWeather.scheduleActive(level)) {
            double t = level.getDayTime() + (double) partialTick;
            CloudWeather.Rain r = CloudWeather.rainAt((long) t);
            if (r != null) {
                v = kind(r) * ramp(r, t);
            }
        }
        return thundering ? Math.max(v, HEAVY) : v;
    }

    /** 雷の無い雨の強さを「普通 = 1」に直した倍率(胞子の仕組みの補間に使う。0〜1.5)。 */
    public static float normalized(float intensity) {
        return intensity / NORMAL;
    }

    private RainIntensity() {
    }
}
