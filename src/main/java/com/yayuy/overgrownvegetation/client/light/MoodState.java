package com.yayuy.overgrownvegetation.client.light;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.CaveLight;
import com.yayuy.overgrownvegetation.client.HighSky;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 画面の色の調子(ライトマップ {@link MoodLightmap} + 画面全体の LUT {@link MoodPost})の入力をまとめる(2026-10-21 試作。
 * 相談は Vault の構想ノート「軽くて綺麗な風景(シェーダー無しの低スペック向け)」)。
 *
 * <p>カメラのいるバイオームの<b>汚染度</b>を 4 段で見る: 外 / 繁茂({@link ModBiomes#isOvergrowthStage}) /
 * 過繁茂({@link ModBiomes#isFullyOvergrown} で極相でない) / 極相({@link ModBiomes#isClimax})。
 * コードの名前と段の名前は逆向き(overgrowth = 繁茂、overgrown = 過繁茂)なので注意。
 * 霧の {@code CorruptionAtmosphere#forestFactor} は 3 段どれも 1 なので使わない。
 *
 * <p>境を越えたら {@link #FADE_SECONDS} 秒かけて寄せる(霧の「森の度合い」と同じ考え。毎 tick、実時間で)。
 * 夜の度合いは空の暗さ({@code getSkyDarken} の生の値)から、地下の度合いは {@link CaveLight#underground}。
 * 高空の晴天({@link HighSky#clarity})では汚染の調子を外す。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class MoodState {

    /** 境を越えてから寄せ切るまでの秒数(10-21 夜、ユーザー「繁茂は通常 → 過繁茂のつなぎ。境で色味がゆっくり変わるように」で 4 → 6)。 */
    private static final float FADE_SECONDS = 6.0F;

    /** 開発用: 切り替え(devbridge の {@code mood})。寄せる値はいつも進め、効かせるかだけをここで決める(撮り比べがすぐ切り替わる)。 */
    public static volatile boolean lightmapOn = true;
    public static volatile boolean lutOn = true;
    public static volatile boolean vignetteOn = true;
    /** 開発用: 汚染度を固定する(-1 = バイオームから。0 外・1 繁茂・2 過繁茂・3 極相)。 */
    public static volatile int forceStage = -1;

    /** 汚染度ごとの重み(合わせて 1)。 */
    private static float outside = 1.0F;
    private static float overgrowth;
    private static float overgrown;
    private static float climax;
    private static float night;
    private static float underground;
    private static long lastMillis;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            lastMillis = 0L;
            return;
        }
        int stage = forceStage;
        if (stage < 0) {
            var biome = level.getBiome(mc.gameRenderer.getMainCamera().getBlockPosition());
            if (ModBiomes.isClimax(biome)) {
                stage = 3;
            } else if (ModBiomes.isFullyOvergrown(biome)) {
                stage = 2;
            } else if (ModBiomes.isOvergrowthStage(biome)) {
                stage = 1;
            } else {
                stage = 0;
            }
        }
        float high = HighSky.clarity();
        float tOut = stage == 0 ? 1.0F : high;
        float tOvergrowth = stage == 1 ? 1.0F - high : 0.0F;
        float tOvergrown = stage == 2 ? 1.0F - high : 0.0F;
        float tClimax = stage == 3 ? 1.0F - high : 0.0F;

        // 夜: 空の暗さ 1(昼)→ 0.2(夜)を 0〜1 に。極相の暗さ(LightTextureClimaxMixin)は Level の値を変えないので、ここは本当の時刻。
        float darken = level.dimensionType().hasSkyLight() ? level.getSkyDarken(1.0F) : 1.0F;
        float tNight = 1.0F - Mth.clamp((darken - 0.2F) / 0.6F, 0.0F, 1.0F);

        long now = Util.getMillis();
        float step = lastMillis == 0L ? 1.0F : Math.min(1.0F, (now - lastMillis) / (FADE_SECONDS * 1000.0F));
        lastMillis = now;
        outside = approach(outside, tOut, step);
        overgrowth = approach(overgrowth, tOvergrowth, step);
        overgrown = approach(overgrown, tOvergrown, step);
        climax = approach(climax, tClimax, step);
        float sum = outside + overgrowth + overgrown + climax;
        if (sum > 1.0E-4F) {
            outside /= sum;
            overgrowth /= sum;
            overgrown /= sum;
            climax /= sum;
        }
        // 時刻は自分で寄せない(空の暗さそのものがなめらか)。
        night = tNight;
        // 地下: CaveLight は巨大樹の傘の下も「岩の天井の下」と見る(傘の中の木でないブロックに当たる)。色味の判定では、
        // 天井が地面の素材(石・土・砂など)のときだけ地下にし、それ以外(傘・建物)は森のまま(10-21 夜、ユーザー「巨大樹の傘の下は森として扱う」)。
        if (++ticks % 10 == 0) {
            roof = scanRoof(level, mc.gameRenderer.getMainCamera().getPosition());
        }
        underground = roof == null || !isGroundLike(roof) ? 0.0F : CaveLight.underground();
    }

    private static long ticks;
    /** 頭の上で最初に当たった、木の一部でない不透明なブロック(無ければ null)。 */
    private static net.minecraft.world.level.block.state.BlockState roof;

    private static net.minecraft.world.level.block.state.BlockState scanRoof(ClientLevel level, net.minecraft.world.phys.Vec3 eye) {
        if (!level.dimensionType().hasSkyLight()) {
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int x = Mth.floor(eye.x);
        int z = Mth.floor(eye.z);
        int top = level.getMaxBuildHeight() - 1;
        for (int y = Mth.floor(eye.y) + 1; y <= top; y++) {
            var state = level.getBlockState(pos.set(x, y, z));
            if (state.isAir() || !state.canOcclude() || com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(state)) {
                continue;
            }
            return state;
        }
        return null;
    }

    /** 洞窟の天井になる素材か(石・土・砂・砂利・鉱石・テラコッタ。汚染で覆われた版も名前で拾う)。 */
    private static boolean isGroundLike(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.BlockTags.DIRT)
                || state.is(net.minecraft.tags.BlockTags.SAND) || state.is(net.minecraft.tags.BlockTags.TERRACOTTA)
                || state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || state.is(net.neoforged.neoforge.common.Tags.Blocks.GRAVELS)
                || state.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER)) {
            return true;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("stone") || path.contains("deepslate") || path.contains("dirt") || path.contains("gravel")
                || path.contains("tuff") || path.contains("granite") || path.contains("diorite") || path.contains("andesite");
    }

    /** 色を画面の色の調子が持っているか(このとき地形のシェーダー側の青緑・暖色は明暗だけにする。10-21 夜、ユーザー指定)。 */
    public static boolean colorOwned() {
        return lightmapActive() || lutActive();
    }

    private static boolean configLoaded() {
        return OvergrownClientConfig.SPEC.isLoaded();
    }

    /** 設定の「色味」がオンか(読めないうちはオン)。 */
    private static boolean moodEnabled() {
        return !configLoaded() || OvergrownClientConfig.CLIENT.moodEnabled.get();
    }

    public static boolean lightmapActive() {
        return lightmapOn && moodEnabled();
    }

    public static boolean lutActive() {
        return lutOn && moodEnabled();
    }

    public static boolean vignetteActive() {
        return vignetteOn && (!configLoaded() || OvergrownClientConfig.CLIENT.moodVignette.get());
    }

    /** 色味の強さ(0〜1)。 */
    public static float strength() {
        return configLoaded() ? OvergrownClientConfig.CLIENT.moodStrength.get() / 100.0F : 1.0F;
    }

    private static float approach(float current, float target, float step) {
        return current < target ? Math.min(target, current + step) : Math.max(target, current - step);
    }

    public static float outside() {
        return outside;
    }

    /** 繁茂(1 段目)の重み。 */
    public static float overgrowth() {
        return overgrowth;
    }

    /** 過繁茂(2 段目)の重み。 */
    public static float overgrown() {
        return overgrown;
    }

    /** 極相の重み。 */
    public static float climax() {
        return climax;
    }

    /** 森の調子の強さ(繁茂は過繁茂の半分。仮決め)。 */
    public static float forest() {
        return overgrown + 0.5F * overgrowth;
    }

    public static float night() {
        return night;
    }

    public static float underground() {
        return underground;
    }

    /** 開発用の 1 行。 */
    public static String describe() {
        return String.format("外 %.2f・繁茂 %.2f・過繁茂 %.2f・極相 %.2f・夜 %.2f・地下 %.2f(CaveLight %.2f・天井 %s)(固定 %d)・ライトマップ %s・LUT %s・ビネット %s・強さ %.2f",
                outside, overgrowth, overgrown, climax, night, underground, CaveLight.underground(),
                roof == null ? "なし" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(roof.getBlock()),
                forceStage, lightmapActive(), lutActive(), vignetteActive(), strength());
    }

    /** 開発用の命令(devbridge の {@code mood})。 */
    public static String devCommand(String arg) {
        String[] a = arg.trim().split("\\s+");
        if (a.length >= 2) {
            boolean on = a[1].equalsIgnoreCase("on");
            switch (a[0]) {
                case "all" -> {
                    lightmapOn = on;
                    lutOn = on;
                    vignetteOn = on;
                }
                case "lightmap" -> lightmapOn = on;
                case "lut" -> lutOn = on;
                case "vignette" -> vignetteOn = on;
                case "stage" -> forceStage = Integer.parseInt(a[1]);
                case "time" -> MoodPost.timing = on;
                default -> {
                    return "MOOD 知らない項目 " + a[0];
                }
            }
        } else if (a[0].equals("stat")) {
            return "MOOD " + MoodPost.takeStat();
        }
        // ライトマップは次の tick に作り直される(LightTexture#tick が毎 tick 印を付ける)。
        return "MOOD " + describe();
    }

    private MoodState() {
    }
}
