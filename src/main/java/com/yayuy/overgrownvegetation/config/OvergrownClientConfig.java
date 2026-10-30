package com.yayuy.overgrownvegetation.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * config/overgrown_vegetation-client.toml に出力される、この PC だけの設定(ワールドに関係しない見た目の好み)。2026-10-22 新設。
 * 画面は {@code client/difficulty/VisualSettingsScreen}(難易度の画面の一番下の幅広のボタンから)。項目を足すときは
 * ここに 1 つ足し、画面の {@code VisualSettingsScreen#settings()} に 1 行・言語ファイル 3 つに名前と説明を足す。
 *
 * <p>2026-10-30 夜: 画面の演出(色味・解像度・粒・光の筋・霧の板・雲の遠景・動く光・シェーダーパック中の演出・G1 の手続きの動き)の項目と
 * 品質の段階({@code QualityPreset})は演出と一緒に parked/ へ退避した(元の丸ごとは parked の同じパス)。
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
        /** この MOD の植物を cutout_mipped の層へ寄せる(遠くの草のちらつきが減り、区画の層が 1 つ減る)。2026-10-23。 */
        public final ModConfigSpec.BooleanValue plantMipmaps;
        /** 下草を地面へ縮めて描かなくなる距離(ブロック。半分の距離から縮み始める。0 = 縮めない)。2026-10-23。 */
        public final ModConfigSpec.IntValue undergrowthDistance;
        /** 巻きシダの立体モデルの精細さ(2026-10-23 に common から移した。ビデオ設定の「シダの葉を立体で描く」も同じ値)。 */
        public final ModConfigSpec.EnumValue<CurledFernDetail> curledFernDetail;
        /** 過繁茂の密林の雰囲気(林床の持ち上げ・林冠の影・光の筋・光る胞子・木漏れ日)を描くか。2026-10-30。 */
        public final ModConfigSpec.BooleanValue atmosphereEnabled;
        /** 雰囲気の重さの段。2026-10-30。 */
        public final ModConfigSpec.EnumValue<AtmosphereQuality> atmosphereQuality;
        /** Iris のシェーダーパック使用中もこの MOD の演出を描くか(既定オフ。オンなら自前のシェーダーの書き込みの止めを外す)。2026-10-30 に戻した。 */
        public final ModConfigSpec.BooleanValue effectsWithShaderPack;

        Client(ModConfigSpec.Builder b) {
            b.push("visual");
            plantMipmaps = b.comment("Draws this mod's plants with mipmaps (less shimmer on distant grass, one terrain layer less). Changing it reloads resources.")
                    .define("plantMipmaps", true);
            undergrowthDistance = b.comment("Distance in blocks beyond which the undergrowth sinks into the ground and is not drawn (it starts sinking at half the distance). 0 draws it at any distance.")
                    .defineInRange("undergrowthDistance", 48, 0, 256);
            curledFernDetail = b.comment("Detail of the curled fern model: FULL (86 faces), REDUCED (42 faces, 6 flat fronds), SIMPLE (crossed planes, 4 faces).",
                            "Same setting as 'Show all fern fronds' in Video Settings (ON = FULL). Changing to or from SIMPLE reloads resources.",
                            "Moved here from overgrown_vegetation-common.toml on 2026-10-23.")
                    .defineEnum("curledFernDetail", CurledFernDetail.REDUCED);
            atmosphereEnabled = b.comment("Draws the overgrown jungle atmosphere: the lifted forest floor, blue-green canopy shade, light shafts with glowing spores and sunflecks. Only inside the overgrown forest.")
                    .define("atmosphereEnabled", true);
            atmosphereQuality = b.comment("How heavy the atmosphere is: HIGH (all shafts and spores), MEDIUM (60% shafts, 70% spores; GTX 1650 class), LOW (30% shafts, 50% spores).")
                    .defineEnum("atmosphereQuality", AtmosphereQuality.HIGH);
            effectsWithShaderPack = b.comment("Also draws this mod's own effects while an Iris shader pack is in use (off by default; the pack draws its own light).")
                    .define("effectsWithShaderPack", false);
            b.pop();
        }
    }

    private OvergrownClientConfig() {
    }
}
