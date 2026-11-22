package com.yayuy.overgrownvegetation.data;

/**
 * 地下と水中の環境音(2026-11-01。見本 C1・C2・C3・W1 をユーザー採用)の設計値。{@code atmosphere.json} の節 {@code sound} の中の {@code cave}・{@code water} の受け皿と値。
 * ユーザー指定: 滴の間隔「数秒〜数十秒」・軋みは 30〜90 秒に 1 回・うなりは 40〜80 Hz 中心で近づくほど大きい。ほかは仮決め(見本の報告の「鳴らし方の案」)。読むのはクライアントの音だけ。
 */
public final class AtmosphereSound {

    /**
     * 節 {@code sound.cave}: 鳴らし始める地下モードの係数(CaveLights.mix がこれを超えたら滴・軋み)・
     * 滴の間隔(秒)・雨の最中に間隔へ掛ける倍率(上から染みて多くなる)・滴の距離(ブロック)・大きさ・0 になる距離・
     * 軋みの間隔(秒)・距離(ブロック)・大きさ・0 になる距離・軋みの向きにする根を探す半径・
     * 胞子の溜まりのうなりが聞こえ始める半径(ブロック。近さ 0〜1 の 2 乗 × humVolume)・うなりの大きさ。
     */
    public static final class CaveSoundJson {
        public Double minMix, dripIntervalMin, dripIntervalMax, dripRainScale, dripDistanceMin, dripDistanceMax, dripVolume, dripRange;
        public Double creakIntervalMin, creakIntervalMax, creakDistanceMin, creakDistanceMax, creakVolume, creakRange, creakRootRadius;
        public Double humRadius, humVolume;
    }

    public record CaveSoundParams(float minMix, float dripIntervalMin, float dripIntervalMax, float dripRainScale, float dripDistanceMin,
                                  float dripDistanceMax, float dripVolume, int dripRange, float creakIntervalMin, float creakIntervalMax,
                                  float creakDistanceMin, float creakDistanceMax, float creakVolume, int creakRange, float creakRootRadius,
                                  float humRadius, float humVolume) {
        static CaveSoundParams of(CaveSoundJson c) {
            CaveSoundJson v = c != null ? c : new CaveSoundJson();
            float di = AtmosphereLife.c(v.dripIntervalMin, 5.0, 1.0, 600.0);
            float dd = AtmosphereLife.c(v.dripDistanceMin, 4.0, 1.0, 64.0);
            float ci = AtmosphereLife.c(v.creakIntervalMin, 30.0, 5.0, 1200.0);
            float cd = AtmosphereLife.c(v.creakDistanceMin, 20.0, 2.0, 96.0);
            return new CaveSoundParams(AtmosphereLife.c(v.minMix, 0.5, 0.0, 1.0),
                    di, Math.max(di, AtmosphereLife.c(v.dripIntervalMax, 40.0, 1.0, 600.0)), AtmosphereLife.c(v.dripRainScale, 0.6, 0.1, 2.0),
                    dd, Math.max(dd, AtmosphereLife.c(v.dripDistanceMax, 20.0, 1.0, 64.0)), AtmosphereLife.c(v.dripVolume, 0.4, 0.0, 1.0),
                    Math.round(AtmosphereLife.c(v.dripRange, 24.0, 4.0, 128.0)),
                    ci, Math.max(ci, AtmosphereLife.c(v.creakIntervalMax, 90.0, 5.0, 1200.0)),
                    cd, Math.max(cd, AtmosphereLife.c(v.creakDistanceMax, 40.0, 2.0, 96.0)), AtmosphereLife.c(v.creakVolume, 0.5, 0.0, 1.0),
                    Math.round(AtmosphereLife.c(v.creakRange, 64.0, 4.0, 128.0)), AtmosphereLife.c(v.creakRootRadius, 40.0, 0.0, 64.0),
                    AtmosphereLife.c(v.humRadius, 12.0, 2.0, 32.0), AtmosphereLife.c(v.humVolume, 0.6, 0.0, 1.0));
        }
    }

    /**
     * 節 {@code sound.water}: 潜っている間の低音の大きさ・入る秒(潜るとき)・消える秒(浮かぶとき)・この水深(ブロック)で depthVolume 倍(低域通過も最大 25% 下げる)・
     * 過繁茂の水のバイオームでバニラの水中の環境音(ambient.underwater.loop 系)に掛ける倍率。
     */
    public static final class WaterSoundJson {
        public Double volume, fadeIn, fadeOut, depthFull, depthVolume, vanillaScale;
    }

    public record WaterSoundParams(float volume, float fadeIn, float fadeOut, float depthFull, float depthVolume, float vanillaScale) {
        static WaterSoundParams of(WaterSoundJson w) {
            WaterSoundJson v = w != null ? w : new WaterSoundJson();
            return new WaterSoundParams(AtmosphereLife.c(v.volume, 0.5, 0.0, 1.0), AtmosphereLife.c(v.fadeIn, 0.6, 0.05, 10.0),
                    AtmosphereLife.c(v.fadeOut, 0.8, 0.05, 10.0), AtmosphereLife.c(v.depthFull, 24.0, 1.0, 128.0),
                    AtmosphereLife.c(v.depthVolume, 0.7, 0.0, 1.0), AtmosphereLife.c(v.vanillaScale, 0.3, 0.0, 1.0));
        }
    }

    private AtmosphereSound() {
    }
}
