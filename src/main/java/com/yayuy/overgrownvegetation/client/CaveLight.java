package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 地下の光(2026-10-16 ユーザー指定「地下の光: 弱い光を青緑にする明るさの調整・光る植物と結晶のにじみ・洞窟の口から差す光の筋」)。
 *
 * <ul>
 *     <li><b>弱い光を青緑に</b>: 汚染地域のバイオームは地下まで塗られているので、森の中の青緑({@link CanopySunMap#forestTint}。
 *     松明などの明かりで照らされた面は琥珀のまま)がそのまま地下にも効いている。</li>
 *     <li><b>真っ暗の手前を少しだけ明るく</b>(ここ): カメラが岩の天井の下(頭の上 24 ブロック以内に木でない不透明なブロック)にいて
 *     汚染地域の中なら、地形のシェーダー(ov_light.glsl の ovCaveFloor)が、ブロックの光も空の光も弱い面に、その面の色の
 *     {@link #FLOOR_R}・{@link #FLOOR_G}・{@link #FLOOR_B} 倍の青緑を足す。暗がりは残る(エレボムネス・フォディムネスを隠す)。</li>
     *     <li><b>深さで色を変える</b>(2026-10-16 ユーザー「4 の案(地下の霧と深さの色)のような、環境を美しく見せる感じが良い」): 底上げの色は
     *     カメラの高さで、浅い所({@link #SHALLOW_FROM} より上)は地上の緑が残る {@link #SHALLOW}、中ほどは青緑({@link #FLOOR_R} ほか)、
     *     深い所({@link #DEEP_TO} より下)は出自の「発光する岩」の淡い水色 {@link #DEEP}({@link #depthWeights})。地下の霧({@link CaveMist})も同じ帯で色を変える。</li>
 *     <li><b>光る植物と結晶のにじみ</b>: {@link LeyNightGlow} が、空の光の届かない所(地下)では昼でも描く。</li>
 *     <li><b>洞窟の口から差す光の筋</b>: {@link CanopyLight} が、カメラが岩の天井の下にいる間は洞窟の床から筋を探す。</li>
 * </ul>
 * 数値は全部 Claude の仮決め。設定 {@code caveLightFloor}。バニラの描画のときだけ(Sodium・Iris では底上げは出ない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CaveLight {

    /** 底上げの色(面の色に掛けて足す)。 */
    static final float FLOOR_R = 0.03F;
    static final float FLOOR_G = 0.065F;
    static final float FLOOR_B = 0.07F;
    /** 浅い所・深い所の底上げの色(明るさは中ほどとほぼ同じ。深い所だけ少し明るい)。 */
    static final float[] SHALLOW = {0.035F, 0.068F, 0.032F};
    static final float[] DEEP = {0.045F, 0.072F, 0.078F};
    /** 深さの帯の境目(y)。浅い = SHALLOW_FROM より上で全部・SHALLOW_TO より下で無し。深い = DEEP_FROM より下で始まり DEEP_TO より下で全部。 */
    static final double SHALLOW_FROM = 48.0;
    static final double SHALLOW_TO = 32.0;
    static final double DEEP_FROM = -8.0;
    static final double DEEP_TO = -24.0;
    /**
     * 天井を探す高さ(ブロック。世界の一番上で打ち切る)。前は 24(地下の霧は 64)までだったので、天井のとても高い洞窟では下に降りると
     * 「地下ではない」になり、地下の霧・暗がりの底上げ・暗い所の粒を消す処理が止まった(10-16 ユーザー指摘「霧より上にいる際は見えるが、下に行くと消える」)。
     */
    static final int ROOF_SCAN = 4096;

    /** カメラが岩の天井の下にいる度合い(0〜1、なめらかに寄せる)。 */
    private static float underground;
    private static boolean roofed;
    private static long ticks;
    private static boolean broken;

    /** カメラが岩の天井の下にいる度合い(0〜1)。 */
    public static float underground() {
        return underground;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            underground = 0.0F;
            return;
        }
        ticks++;
        if (ticks % 10 == 0) {
            roofed = hasRockRoof(level, mc.gameRenderer.getMainCamera().getPosition());
        }
        float target = roofed ? 1.0F : 0.0F;
        underground = target > underground ? Math.min(target, underground + 0.05F) : Math.max(target, underground - 0.05F);
    }

    /** 高さ {@code y} の深さの帯の重み(浅い・中・深い。足して 1)を {@code out} に入れる。 */
    static void depthWeights(double y, float[] out) {
        float shallow = CanopyLight.smooth((float) Mth.clamp((y - SHALLOW_TO) / (SHALLOW_FROM - SHALLOW_TO), 0.0, 1.0));
        float deep = CanopyLight.smooth((float) Mth.clamp((DEEP_FROM - y) / (DEEP_FROM - DEEP_TO), 0.0, 1.0));
        out[0] = shallow;
        out[2] = deep;
        out[1] = Math.max(0.0F, 1.0F - shallow - deep);
    }

    /** {@code eye} の上(世界の一番上まで)に、木の一部でない不透明なブロックがあるか(洞窟・建物の中)。 */
    static boolean hasRockRoof(ClientLevel level, Vec3 eye) {
        return hasRockRoof(level, eye, ROOF_SCAN);
    }

    /**
     * {@code eye} の上 {@code scan} ブロック以内に、木の一部でない不透明なブロックがあるか。木の一部(幹・根・葉)は飛ばしてその上を見る
     * (2026-10-16: 前は最初に当たったのが木の一部なら「天井なし」にしていたので、洞窟の中の根の下を通ると地下の霧・暗がりの底上げが消えた)。
     */
    static boolean hasRockRoof(ClientLevel level, Vec3 eye, int scan) {
        if (!level.dimensionType().hasSkyLight()) {
            return true;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = (int) Math.floor(eye.x);
        int z = (int) Math.floor(eye.z);
        int y0 = (int) Math.floor(eye.y) + 1;
        int top = Math.min(level.getMaxBuildHeight() - 1, y0 + scan);
        for (int y = y0; y <= top; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (state.isAir() || !state.canOcclude()) {
                continue;
            }
            if (TreeCanopyGroundFinder.isTreePart(state)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 空を描いた後・地形を描く前に、底上げの色と強さを地形のシェーダーへ渡す。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken || SodiumTerrain.unsupported()) {
            return;
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader()};
        try {
            float strength = underground * CorruptionAtmosphere.forestFactor()
                    * OvergrownVegetationConfig.COMMON.caveLightFloor.get().floatValue();
            float[] wt = new float[3];
            depthWeights(event.getCamera().getPosition().y, wt);
            float[] rgb = {
                    wt[0] * SHALLOW[0] + wt[1] * FLOOR_R + wt[2] * DEEP[0],
                    wt[0] * SHALLOW[1] + wt[1] * FLOOR_G + wt[2] * DEEP[1],
                    wt[0] * SHALLOW[2] + wt[1] * FLOOR_B + wt[2] * DEEP[2]};
            if (com.yayuy.overgrownvegetation.client.light.MoodState.colorOwned()) {
                // 画面の色の調子(2026-10-21)が色を持つ間は底上げの明るさだけ(同じ明るさの灰色)。
                float luma = 0.299F * rgb[0] + 0.587F * rgb[1] + 0.114F * rgb[2];
                rgb[0] = rgb[1] = rgb[2] = luma;
            }
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvCave").set(rgb[0], rgb[1], rgb[2], strength);
                }
            }
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("地下の明るさの底上げで例外が出たので、このセッションでは使わない", e);
        }
    }

    private CaveLight() {
    }
}
