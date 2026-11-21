package com.yayuy.overgrownvegetation.data;

import java.util.List;

/**
 * 「美しいが怖い」の怖い現象 A・D・E の値(atmosphere.json の節 {@code scary}。2026-11-16)。{@link AtmosphereTable#scary()} から読む。
 * 決定の正本は Vault「美しいが怖い(音・現象・霧の恐怖要素)」、作りの記録は「怖い現象 A・D・E(実装の記録)」。<b>数はすべて仮決め</b>。
 *
 * <ul>
 *     <li>{@code shadow}(D 林冠の影。{@code client/atmosphere/PassingShadow}): {@code everyDays} = 平均何日に 1 回、{@code speed} = 影の速さ
 *     (ブロック/秒)、{@code span} = 翼の幅(ブロック)、{@code chord} = 付け根の前後の長さ(ブロック)、{@code strength} = 影の濃さ(0〜1)。</li>
 *     <li>{@code figures}(E 霧の中のシルエット。{@code client/atmosphere/FogFigures}): {@code everySeconds} = 平均何秒に 1 体、
 *     {@code nearFraction}・{@code farFraction} = 出す距離(濃霧の見通しに対する割合)、{@code vanishFraction} = この割合より近づくと消え始める、
 *     {@code vanishBand} = 消えきるまでの幅(見通しに対する割合)、{@code lifeSeconds} = 近づかなくても消えるまでの秒、
 *     {@code darkness} = 影の濃さ(0〜1)、{@code hazeEase} = 影が受ける霞の距離の倍率(1 で地形と同じ。小さいほど霧の中で濃く見える)、
 *     {@code minFog} = 濃霧の度合いがこれ以上で出す、{@code species} = 輪郭に使う G3 の種。</li>
 *     <li>{@code farSound}(A 遠くの音だけの存在。{@code client/atmosphere/sound/FarPresenceSound}): {@code enabled} は既定で false
 *     (見本の音をユーザーが聞いて選ぶまで鳴らさない)。{@code everySeconds}・{@code denseEverySeconds} = 警戒状態の間、平均何秒に 1 回
 *     (ふつう・汚染の濃い所)、{@code minDistance}・{@code maxDistance} = 音のする距離(ブロック)、{@code volume} = 大きさ、
 *     {@code darkLight} = 「暗い所」とみなす明るさ(0〜15。これ以下)。</li>
 * </ul>
 */
public final class AtmosphereScary {

    private AtmosphereScary() {
    }

    public static final class Json {
        public ShadowJson shadow;
        public FiguresJson figures;
        public FarSoundJson farSound;
    }

    public static final class ShadowJson {
        public Boolean enabled;
        /** dim = 影の真下にいる間、近くの景色を陰らせる割合(0〜1)。 */
        public Double everyDays, speed, span, chord, strength, dim;
    }

    public static final class FiguresJson {
        public Boolean enabled;
        public Double everySeconds, nearFraction, farFraction, vanishFraction, vanishBand, lifeSeconds, darkness, hazeEase, minFog;
        public List<String> species;
        /** 影絵の色 r,g,b(霧で薄まる前。本物は光る層で少し紫がかるので、それに寄せる)。 */
        public double[] color;
    }

    public static final class FarSoundJson {
        public Boolean enabled;
        public Double everySeconds, denseEverySeconds, minDistance, maxDistance, volume, darkLight;
    }

    public record Shadow(boolean enabled, float everyDays, float speed, float span, float chord, float strength, float dim) {
    }

    public record Figures(boolean enabled, float everySeconds, float nearFraction, float farFraction, float vanishFraction, float vanishBand,
                          float lifeSeconds, float darkness, float hazeEase, float minFog, List<String> species,
                          float red, float green, float blue) {
    }

    public record FarSound(boolean enabled, float everySeconds, float denseEverySeconds, float minDistance, float maxDistance, float volume, int darkLight) {
    }

    public record Params(Shadow shadow, Figures figures, FarSound farSound) {
        static Params of(Json j) {
            ShadowJson s = j != null && j.shadow != null ? j.shadow : new ShadowJson();
            FiguresJson f = j != null && j.figures != null ? j.figures : new FiguresJson();
            FarSoundJson a = j != null && j.farSound != null ? j.farSound : new FarSoundJson();
            double[] col = f.color != null && f.color.length >= 3 ? f.color : new double[]{0.15, 0.12, 0.17};
            List<String> species = f.species != null && !f.species.isEmpty() ? List.copyOf(f.species)
                    : List.of("clipeimnes", "clipeimnes", "falcimnes");
            return new Params(
                    new Shadow(s.enabled == null || s.enabled, c(s.everyDays, 3.0, 1.0, 1000.0), c(s.speed, 25.0, 2.0, 200.0),
                            c(s.span, 120.0, 16.0, 600.0), c(s.chord, 80.0, 8.0, 300.0), c(s.strength, 0.9, 0.0, 1.0),
                            c(s.dim, 0.45, 0.0, 1.0)),
                    new Figures(f.enabled == null || f.enabled, c(f.everySeconds, 90.0, 1.0, 36000.0), c(f.nearFraction, 0.72, 0.2, 2.0),
                            c(f.farFraction, 0.92, 0.2, 2.0), c(f.vanishFraction, 0.6, 0.05, 1.5), c(f.vanishBand, 0.15, 0.01, 1.0),
                            c(f.lifeSeconds, 60.0, 2.0, 3600.0), c(f.darkness, 0.95, 0.0, 1.0), c(f.hazeEase, 1.0, 0.1, 1.0),
                            c(f.minFog, 0.5, 0.0, 1.0), species, c(col[0], 0.15, 0.0, 1.0), c(col[1], 0.12, 0.0, 1.0), c(col[2], 0.17, 0.0, 1.0)),
                    new FarSound(a.enabled != null && a.enabled, c(a.everySeconds, 300.0, 5.0, 36000.0), c(a.denseEverySeconds, 150.0, 5.0, 36000.0),
                            c(a.minDistance, 20.0, 4.0, 128.0), c(a.maxDistance, 40.0, 4.0, 128.0), c(a.volume, 0.6, 0.0, 2.0),
                            Math.round(c(a.darkLight, 7.0, 0.0, 15.0))));
        }
    }

    private static float c(Double v, double def, double min, double max) {
        double x = v != null && Double.isFinite(v) ? v : def;
        return (float) Math.max(min, Math.min(max, x));
    }
}
