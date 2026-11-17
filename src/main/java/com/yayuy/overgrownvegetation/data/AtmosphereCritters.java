package com.yayuy.overgrownvegetation.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.yayuy.overgrownvegetation.data.AtmosphereLife.c;
import static com.yayuy.overgrownvegetation.data.AtmosphereLife.tiers;

/**
 * 見た目だけの小さな生き物(2026-11-17 ユーザー決定)の設計値。{@code atmosphere.json} の節 {@code smallCritters} の受け皿。
 * 読むのはクライアントの {@code client/atmosphere/SmallCritters} だけ。
 *
 * <p><b>ユーザーが決めたこと</b>: 種は 10・出るのは過繁茂だけ・数は升の G2・G3 の頭数を映す・近づいたときの反応は種ごと・
 * 起きるのは食べる/食べられるの演出だけ・プレイヤーは触れない。<b>それ以外(時刻・数・距離・速さ・食物網の組み合わせ)はすべて仮決め</b>。
 * 写しが古くてこの節が無くても同じに動くよう、Java の既定値を json と揃えてある。
 */
public final class AtmosphereCritters {

    /** 種の名前(json のキー)。並びは {@code SmallCritters} の番号と同じ。 */
    public static final List<String> NAMES = List.of("damselfly", "mayfly", "moth", "firefly", "spider", "beetle", "millipede", "lizard",
            "strider", "cave");

    public static final class Json {
        public Boolean enabled;
        /** 出す距離(ブロック)・時刻の端のなじませ(時)・逃げた後に戻るまで(秒)。 */
        public Double range, fadeHours, respawnSeconds;
        /** 明かりを持っているとき: 手元に寄る蛾の数・蛾が揃うまで(秒)・ホタルが寄る距離(ブロック)と寄る割合(0〜1)。 */
        public Integer handMoths;
        public Double handMothSeconds, lightPullDistance, lightPull;
        /** 食べられる演出: 口元からの届く距離(ブロック)・同じ Mob が次に食べるまで(秒)。 */
        public Double eatReach, eatCooldownSeconds;
        public Map<String, SpeciesJson> species;
        /** 食物網: 食べる側のエンティティ id(名前空間なし)→ 食べられる種の名前。 */
        public Map<String, String[]> prey;
    }

    /** 1 種: 出すか・同時に出す所の数(重さの段 高・中・低)・升(8 ブロック)に居る確率・出る時刻(時。from > to は夜をまたぐ)・反応する距離・入れ替わる間(秒)。 */
    public static final class SpeciesJson {
        public Boolean enabled;
        public int[] counts;
        public Double chance, fromHour, toHour, react, slotSeconds;
    }

    public record Species(boolean enabled, int[] counts, float chance, float fromHour, float toHour, float react, int slotTicks) {
        static Species of(SpeciesJson j, Species def) {
            if (j == null) {
                return def;
            }
            return new Species(j.enabled == null ? def.enabled : j.enabled, tiers(j.counts, def.counts, 64), c(j.chance, def.chance, 0.0, 1.0),
                    c(j.fromHour, def.fromHour, 0.0, 24.0), c(j.toHour, def.toHour, 0.0, 24.0), c(j.react, def.react, 0.0, 16.0),
                    Math.round(c(j.slotSeconds, def.slotTicks / 20.0, 20.0, 3600.0) * 20.0F));
        }

        /** 時刻 {@code hour}(0〜24)での出方(0〜1)。端は {@code fade} 時間でなじませる。 */
        public float weight(float hour, float fade) {
            if (toHour - fromHour >= 23.99F) {
                return 1.0F;
            }
            float in;
            float out;
            if (fromHour <= toHour) {
                in = (hour - fromHour) / fade;
                out = (toHour - hour) / fade;
                return Math.max(0.0F, Math.min(1.0F, Math.min(in, out)));
            }
            // 夜をまたぐ: from 以後か to 以前。
            in = (hour - fromHour) / fade;
            out = (toHour - hour) / fade;
            return Math.max(0.0F, Math.min(1.0F, Math.max(in, out)));
        }
    }

    public record Params(boolean enabled, float range, float fadeHours, float respawnSeconds, int handMoths, float handMothSeconds,
                         float lightPullDistance, float lightPull, float eatReach, float eatCooldownSeconds, Species[] species,
                         Map<String, String[]> prey) {

        static Params of(Json j) {
            Json v = j != null ? j : new Json();
            Species[] def = defaults();
            Species[] out = new Species[NAMES.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = Species.of(v.species != null ? v.species.get(NAMES.get(i)) : null, def[i]);
            }
            return new Params(v.enabled == null || v.enabled, c(v.range, 28.0, 8.0, 48.0), c(v.fadeHours, 0.5, 0.05, 3.0),
                    c(v.respawnSeconds, 20.0, 1.0, 600.0), v.handMoths != null ? Math.max(0, Math.min(8, v.handMoths)) : 3,
                    c(v.handMothSeconds, 8.0, 0.5, 120.0), c(v.lightPullDistance, 14.0, 2.0, 32.0), c(v.lightPull, 0.6, 0.0, 0.95),
                    c(v.eatReach, 1.0, 0.2, 6.0), c(v.eatCooldownSeconds, 3.0, 0.2, 120.0), out, v.prey != null ? v.prey : defaultPrey());
        }
    }

    /** 既定値(すべて仮決め。json の節 smallCritters と揃える)。 */
    private static Species[] defaults() {
        return new Species[]{
                new Species(true, new int[]{6, 4, 2}, 0.5F, 8.0F, 17.0F, 3.0F, 2400),     // イトトンボ: 昼・水辺
                new Species(true, new int[]{6, 4, 2}, 0.7F, 16.5F, 19.5F, 2.5F, 3000),    // カゲロウの群れ: 夕方・水面の上
                new Species(true, new int[]{6, 4, 2}, 1.0F, 19.0F, 5.0F, 0.0F, 3600),     // 蛾: 夜・明かりのまわり
                new Species(true, new int[]{14, 9, 4}, 0.75F, 19.5F, 4.0F, 0.0F, 3000),    // ホタル: 夜・林床の上
                new Species(true, new int[]{8, 5, 2}, 0.5F, 0.0F, 24.0F, 1.5F, 6000),     // クモ・ザトウムシ: 幹・壁
                new Species(true, new int[]{8, 5, 2}, 0.8F, 0.0F, 24.0F, 2.5F, 4800),     // 甲虫・シロアリ: 倒木
                new Species(true, new int[]{10, 6, 3}, 0.5F, 0.0F, 24.0F, 1.5F, 4800),    // ヤスデ(小): 林床
                new Species(true, new int[]{5, 3, 2}, 1.0F, 9.0F, 16.0F, 4.0F, 6000),     // 小さなトカゲ: 昼・日の当たる岩
                new Species(true, new int[]{6, 4, 2}, 0.7F, 6.0F, 19.5F, 2.5F, 4000),     // アメンボ: 水面
                new Species(true, new int[]{6, 4, 2}, 0.6F, 0.0F, 24.0F, 3.0F, 4800),     // ゲジ: 過繁茂の下の洞窟の壁(カマドウマは CaveCrickets)
        };
    }

    /** 食物網の既定(11-17。組み合わせは仮決め。ホタル・アメンボ・洞窟の虫は食べる相手なし)。 */
    private static Map<String, String[]> defaultPrey() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("meganeura", new String[]{"mayfly", "damselfly", "moth"});
        m.put("coelurosauravus", new String[]{"spider", "moth"});
        m.put("hylonomus", new String[]{"beetle"});
        m.put("petrolacosaurus", new String[]{"beetle"});
        m.put("pulmonoscorpius", new String[]{"millipede"});
        m.put("ianthasaurus", new String[]{"lizard"});
        m.put("phalangiotarbus", new String[]{"lizard"});
        m.put("arthropleura", new String[]{"beetle", "millipede"});
        m.put("giant_snail", new String[]{"beetle", "millipede"});
        return m;
    }

    private AtmosphereCritters() {
    }
}
