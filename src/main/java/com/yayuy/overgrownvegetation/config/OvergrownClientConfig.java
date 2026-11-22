package com.yayuy.overgrownvegetation.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * config/overgrown_vegetation-client.toml に出力される、この PC だけの設定(ワールドに関係しない見た目の好み)。2026-10-22 新設。
 * 画面は {@code client/difficulty/VisualSettingsScreen}(2026-11-01 から、バニラの「設定…」画面のボタンと Mods 画面の「設定」から)。項目を足すときは
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

        // 演出 1 つずつのオンオフ(2026-11-01、ユーザー指定「環境音とシェーダー(光などの演出)をオンオフ」。分け方は仮決め)。
        // どれも atmosphereEnabled の子(親がオフなら全部オフ)。読み手は client/VisualQuality の同じ名前の口。
        /** 虫・爬虫類の声。 */
        public final ModConfigSpec.BooleanValue ambientInsects;
        /** 風の音。 */
        public final ModConfigSpec.BooleanValue ambientWind;
        /** 地下の音(滴・根の軋み・胞子の溜まりのうなり。2026-11-01)。 */
        public final ModConfigSpec.BooleanValue ambientCave;
        /** 水中の音(潜っている間のこもった低音と、過繁茂の水でバニラの水中の音を下げる。2026-11-01)。 */
        public final ModConfigSpec.BooleanValue ambientUnderwater;
        /** 光の筋と、その中を漂う粒。 */
        public final ModConfigSpec.BooleanValue lightShafts;
        /** 木漏れ日。 */
        public final ModConfigSpec.BooleanValue canopyDapple;
        /** 林床の持ち上げ・緑の照り返し・林冠の影と色。 */
        public final ModConfigSpec.BooleanValue canopyLight;
        /** 胞子の霧・朝霧(地形の霧・バニラの霧の上書き・Distant Horizons の霧の上書き・霧の向こうの巨大樹の影)。 */
        public final ModConfigSpec.BooleanValue fog;
        /** 霧の細部(レイマーチ。重さの段が高のときだけ描く)。 */
        public final ModConfigSpec.BooleanValue fogDetail;
        /** 落ち葉・光る胞子・夕暮れの粒・雨上がりの滴・湯気。 */
        public final ModConfigSpec.BooleanValue motes;
        /** 羽虫・木の洞の虫と目。 */
        public final ModConfigSpec.BooleanValue critters;
        /** 葉と草の揺れ。 */
        public final ModConfigSpec.BooleanValue leafSway;
        /** 水面の輪と泡・濡れた照り(幹を伝う水は 2026-11-01 に退避)。 */
        public final ModConfigSpec.BooleanValue waterLife;
        /** 水中の光の網・光の筋・夜の光る藻。 */
        public final ModConfigSpec.BooleanValue underwater;
        /** 地下モード(過繁茂の地下の黒い霧と植物の点光源・Mob の明るさ。11-01 試作)。 */
        public final ModConfigSpec.BooleanValue caveLight;
        public final ModConfigSpec.BooleanValue glowBloom;
        public final ModConfigSpec.BooleanValue surfaceBump;
        public final ModConfigSpec.BooleanValue simpleAo;
        /** 雲(ボクセルの雲・雲の影・薄明光線・高空の晴天。2026-11-08 雲の段階 1)。シェーダーパック使用中はパックの雲に任せて描かない。 */
        public final ModConfigSpec.BooleanValue clouds;
        /** 監視塔フィロセンスに見られているときの画面の縁(2026-11-14。client/PhyllosensGazeEdge)の強さ: 目の数の倍率(%)・視界の内側に開く目・鼓動の縁。 */
        public final ModConfigSpec.IntValue gazeEyeScale;
        public final ModConfigSpec.BooleanValue gazeInnerEyes;
        public final ModConfigSpec.BooleanValue gazePulse;
        /** 大群の描画(2026-11-18 大群の軽量化 C。client/crowd): GPU スキニング + まとめ描き・遠くの群れの見た目・粗いモデルを描く遠さ。 */
        public final ModConfigSpec.BooleanValue crowdGpuSkinning;
        public final ModConfigSpec.EnumValue<com.yayuy.overgrownvegetation.client.crowd.HordeLook> hordeLook;
        public final ModConfigSpec.IntValue hordeModelFarBlocks;

        Client(ModConfigSpec.Builder b) {
            b.push("visual");
            plantMipmaps = b.comment("Draws this mod's plants with mipmaps (less shimmer on distant grass, one terrain layer less). Changing it reloads resources.")
                    .define("plantMipmaps", true);
            undergrowthDistance = b.comment("Distance in blocks beyond which the undergrowth sinks into the ground and is not drawn (it starts sinking at half the distance). 0 draws it at any distance.")
                    .defineInRange("undergrowthDistance", 48, 0, 256);
            curledFernDetail = b.comment("Detail of the curled fern model: FULL (86 faces), REDUCED (42 faces, 6 flat fronds). SIMPLE was removed on 2026-11-03 (an old SIMPLE value is read as REDUCED).",
                            "Same setting as 'Show all fern fronds' in Video Settings (ON = FULL).",
                            "Moved here from overgrown_vegetation-common.toml on 2026-10-23.")
                    .defineEnum("curledFernDetail", CurledFernDetail.REDUCED);
            atmosphereEnabled = b.comment("Draws the overgrown jungle atmosphere: the lifted forest floor, blue-green canopy shade, light shafts with glowing spores and sunflecks. Only inside the overgrown forest.")
                    .define("atmosphereEnabled", true);
            atmosphereQuality = b.comment("How heavy the atmosphere is: HIGH (all shafts and spores), MEDIUM (60% shafts, 70% spores; GTX 1650 class), LOW (30% shafts, 50% spores).")
                    .defineEnum("atmosphereQuality", AtmosphereQuality.HIGH);
            effectsWithShaderPack = b.comment("Also draws this mod's own effects while an Iris shader pack is in use (off by default; the pack draws its own light).")
                    .define("effectsWithShaderPack", false);
            b.pop();
            b.comment("Each part of the atmosphere on its own (2026-11-01). All of them need atmosphereEnabled in [visual].").push("atmosphere");
            ambientInsects = b.comment("Insect and reptile calls in the forest.").define("ambientInsects", true);
            ambientWind = b.comment("Wind in the canopy.").define("ambientWind", true);
            ambientCave = b.comment("Sounds underground in the overgrown region: dripping water, creaking roots, the hum of spore pools.").define("ambientCave", true);
            ambientUnderwater = b.comment("Muffled low sound while diving in overgrown water (also turns the vanilla underwater ambience down there).").define("ambientUnderwater", true);
            lightShafts = b.comment("Light shafts through the canopy and the motes floating in them.").define("lightShafts", true);
            canopyDapple = b.comment("Sunflecks on the forest floor.").define("canopyDapple", true);
            canopyLight = b.comment("Lifted forest floor light, green bounce light, canopy shade and tint, light through leaves.").define("canopyLight", true);
            fog = b.comment("Spore fog and morning mist (terrain fog, vanilla fog override, Distant Horizons fog override, giant tree silhouettes).").define("fog", true);
            fogDetail = b.comment("Fine detail of the fog (ray marching; only at atmosphereQuality HIGH).").define("fogDetail", true);
            motes = b.comment("Falling leaves, glowing spores, dusk motes, after-rain drops and steam.").define("motes", true);
            critters = b.comment("Gnat swarms, the critters in tree hollows, and the gnats and lizards around giant trees.").define("critters", true);
            leafSway = b.comment("Leaves and grass swaying in the wind.").define("leafSway", true);
            waterLife = b.comment("Rings and bubbles on water, wet sheen.").define("waterLife", true);
            underwater = b.comment("Caustics and light shafts under water, glowing algae at night.").define("underwater", true);
            caveLight = b.comment("Cave mode under the overgrown forest: black fog and the glow of cave plants (shader point lights, mobs lit too).").define("caveLight", true);
            glowBloom = b.comment("Soft glow around lights in the dark (torches, lava, glowing plants and spores). A full-screen pass; turn off on a weak GPU.").define("glowBloom", true);
            surfaceBump = b.comment("Relief of bark and rock from the light and dark of their textures under a low sun. Off by default: it assumes 16x16 textures, so resource packs with larger textures show seams.").define("surfaceBump", false);
            simpleAo = b.comment("Soft shade in crevices, at the foot of trunks, in wall corners and where plants meet the ground (screen-space ambient occlusion). Off by default: two full-screen passes every frame.").define("simpleAo", false);
            clouds = b.comment("Voxel clouds above y 300, their drifting shadows, crepuscular rays and the clear sky above the clouds (not drawn while a shader pack is in use).").define("clouds", true);
            b.pop();
            b.comment("The screen effect while a Phyllosens watchtower is watching you (2026-11-14). For players who find it too strong.").push("watched");
            gazeEyeScale = b.comment("How many eyes open around the screen edge, in percent of the full number (0 = none).").defineInRange("gazeEyeScale", 100, 0, 100);
            gazeInnerEyes = b.comment("Now and then an eye opens for a moment inside the view, near the crosshair.").define("gazeInnerEyes", true);
            gazePulse = b.comment("The dark screen edge throbs like a heartbeat.").define("gazePulse", true);
            b.pop();
            b.comment("Drawing large hordes (2026-11-18).").push("horde");
            crowdGpuSkinning = b.comment("Draw crowds of the same plant-side mob together on the GPU (GPU skinning). Turn off only if mobs look wrong on your graphics card.")
                    .define("crowdGpuSkinning", true);
            hordeLook = b.comment("How far hordes (before they become real mobs) are drawn: COMBINED = rough models up to hordeModelFarBlocks, flat pictures beyond; SPRITE = flat pictures only (lightest); MODEL = rough models only. Provisional default COMBINED.")
                    .defineEnum("hordeLook", com.yayuy.overgrownvegetation.client.crowd.HordeLook.COMBINED);
            hordeModelFarBlocks = b.comment("With COMBINED, how far (blocks) far hordes are drawn as rough models before switching to flat pictures. Provisional 128.")
                    .defineInRange("hordeModelFarBlocks", 128, 64, 512);
            b.pop();
        }
    }

    private OvergrownClientConfig() {
    }
}
