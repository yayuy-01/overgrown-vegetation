package com.yayuy.overgrownvegetation.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * config/overgrown_vegetation-client.toml に出力される、この PC だけの設定(ワールドに関係しない見た目の好み)。2026-10-22 新設。
 * 画面は {@code client/difficulty/VisualSettingsScreen}(難易度の画面の一番下の幅広のボタンから)。項目を足すときは
 * ここに 1 つ足し、画面の {@code VisualSettingsScreen#settings()} に 1 行・言語ファイル 3 つに名前と説明を足す。
 */
public final class OvergrownClientConfig {

    public static final ModConfigSpec SPEC;
    public static final Client CLIENT;

    static {
        Pair<Client, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = pair.getLeft();
        SPEC = pair.getRight();
    }

    public static final class Client {
        /** 画面の色の調子(ライトマップの色味 + 画面全体の LUT)。 */
        public final ModConfigSpec.BooleanValue moodEnabled;
        /** 色味の強さ(0〜100%)。 */
        public final ModConfigSpec.IntValue moodStrength;
        /** 画面の四隅を暗くする(バニラの GUI のビネットの代わり)。 */
        public final ModConfigSpec.BooleanValue moodVignette;
        /** 世界を描く解像度(50〜100%)。手・GUI は等倍。2026-10-23。 */
        public final ModConfigSpec.IntValue renderScale;
        /** この MOD の植物を cutout_mipped の層へ寄せる(遠くの草のちらつきが減り、区画の層が 1 つ減る)。2026-10-23。 */
        public final ModConfigSpec.BooleanValue plantMipmaps;
        /** 下草を地面へ縮めて描かなくなる距離(ブロック。半分の距離から縮み始める。0 = 縮めない)。2026-10-23。 */
        public final ModConfigSpec.IntValue undergrowthDistance;
        /** 粒の量(10〜100%)。細かい胞子・落ち葉・雨上がりの滴・光の中の粒の出る確率と上限に掛ける。品質の段階で変わる。2026-10-23。 */
        public final ModConfigSpec.IntValue particleAmount;
        /** 光の筋(10〜100%)。林冠の光の筋・木漏れ日・薄明光線の本数の上限に掛ける。2026-10-23。 */
        public final ModConfigSpec.IntValue lightShafts;
        /** 朝霧・洞窟の霧の板の枚数。2026-10-23。 */
        public final ModConfigSpec.EnumValue<QualityPreset.MistSheets> mistSheets;
        /** 雲の上から見る遠くの雲の海の粗さ。2026-10-23。 */
        public final ModConfigSpec.EnumValue<QualityPreset.FarCloudDetail> farClouds;
        /** 動く光(光る追加防具を付けたプレイヤーのまわりを照らす)。2026-10-23。 */
        public final ModConfigSpec.BooleanValue dynamicLights;
        /** 巻きシダの立体モデルの精細さ(2026-10-23 に common から移した。ビデオ設定の「シダの葉を立体で描く」も同じ値)。 */
        public final ModConfigSpec.EnumValue<CurledFernDetail> curledFernDetail;
        /**
         * Iris のシェーダーパック使用中も、この MOD の自前のシェーダーの演出(空の巨大生物など)を描く。既定オフ(2026-10-23 ユーザー決定)。
         * 品質の段階には入れない。雲とその由来の演出はこの値に関係なくパック使用中は描かない({@code client/ShaderPacks})。
         */
        public final ModConfigSpec.BooleanValue effectsWithShaderPack;
        /** G1 の進化体の手続きの動き(胞子嚢の鼓動・触手の緩急のある揺れ)。重さはほぼゼロ。品質の段階には入れない。2026-10-24。 */
        public final ModConfigSpec.BooleanValue organicMotion;

        Client(ModConfigSpec.Builder b) {
            b.push("visual");
            moodEnabled = b.comment("Colour mood: tints the lightmap and grades the whole screen by corruption stage and time (also works with Sodium).")
                    .define("moodEnabled", true);
            moodStrength = b.comment("Strength of the colour mood in percent.")
                    .defineInRange("moodStrength", 100, 0, 100);
            moodVignette = b.comment("Darkens the screen corners (replaces the vanilla vignette while on).")
                    .define("moodVignette", true);
            renderScale = b.comment("Resolution the world is drawn at, in percent of the window (the hand and the GUI stay sharp). Lower is lighter on the GPU.")
                    .defineInRange("renderScale", 100, 50, 100);
            plantMipmaps = b.comment("Draws this mod's plants with mipmaps (less shimmer on distant grass, one terrain layer less). Changing it reloads resources.")
                    .define("plantMipmaps", true);
            undergrowthDistance = b.comment("Distance in blocks beyond which the undergrowth sinks into the ground and is not drawn (it starts sinking at half the distance). 0 draws it at any distance.")
                    .defineInRange("undergrowthDistance", 48, 0, 256);
            particleAmount = b.comment("Amount of this mod's ambient particles in percent (fine spores, falling leaves, after-rain drops, motes in light shafts).")
                    .defineInRange("particleAmount", 100, 10, 100);
            lightShafts = b.comment("Number of light shafts in percent (canopy light shafts, sun flecks, crepuscular rays).")
                    .defineInRange("lightShafts", 100, 10, 100);
            mistSheets = b.comment("Sheets in the morning mist and cave mist. FEW draws fewer, denser sheets (about the same overall thickness).")
                    .defineEnum("mistSheets", QualityPreset.MistSheets.MANY);
            farClouds = b.comment("Detail of the distant cloud sea seen from above the clouds. COARSE uses columns twice as large (about a quarter of the faces).")
                    .defineEnum("farClouds", QualityPreset.FarCloudDetail.FINE);
            dynamicLights = b.comment("Moving light around players wearing glowing gear (drawing only).")
                    .define("dynamicLights", true);
            curledFernDetail = b.comment("Detail of the curled fern model: FULL (86 faces), REDUCED (42 faces, 6 flat fronds), SIMPLE (crossed planes, 4 faces).",
                            "Same setting as 'Show all fern fronds' in Video Settings (ON = FULL). Changing to or from SIMPLE reloads resources.",
                            "Moved here from overgrown_vegetation-common.toml on 2026-10-23.")
                    .defineEnum("curledFernDetail", CurledFernDetail.REDUCED);
            effectsWithShaderPack = b.comment("Keeps drawing this mod's own effects (the sky leviathan, mist, sky) while an Iris shader pack is in use.",
                            "Some shader packs may show them broken or run slower. This mod's clouds are never drawn while a shader pack is in use.")
                    .define("effectsWithShaderPack", false);
            organicMotion = b.comment("Procedural organic motion on evolved parasitized mobs: spore sacs pulse, tentacles sway with bursts (drawing only).")
                    .define("organicMotion", true);
            b.pop();
        }
    }

    private OvergrownClientConfig() {
    }
}
