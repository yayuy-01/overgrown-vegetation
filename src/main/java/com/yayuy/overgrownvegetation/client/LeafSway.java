package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.sound.WindSoundModel;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * <b>風に揺れる葉と草</b>(案 B、2026-10-15 夜。ユーザー「葉が風に揺られないので木漏れ日や差し込む光がずっと同じ場所にい続けておかしく見える」→
 * 「A から順番に」の 2 つ目。続けて「茂み・シダ・蔦も同じ風で揺らして」)。過繁茂の葉・湿地の葉・茂み・シダ・蔦のブロックそのものを、
 * 光・胞子・音と同じ風({@link CanopyWind})で揺らす。
 *
 * <h2>しくみ</h2>
 * バニラのチャンクの {@code cutout}・{@code cutout_mipped} の層のシェーダーを、この MOD の資源で置き換えている
 * ({@code assets/minecraft/shaders/core/rendertype_cutout(_mipped).*}、中身は {@code shaders/include/ov_sway.glsl})。
 * <ul>
 *     <li>どの頂点をどれだけ動かすかは、模型の側で頂点の透明度に印として書く({@link SwayModels}。根元は動かさない・葉先ほど動く など)。</li>
 *     <li>ここは毎フレーム、地形を描く前に、凪の空気の動き(強さ・向き)と今吹いている突風(最大 4 つ: カメラから見た中心・向き・強さ・
 *     前後の長さ・横幅)を両方のシェーダーへ渡す。シェーダーは頂点ごとにその場の風を {@link CanopyWind#strength} と同じ式で求める
 *     (突風が林を渡ると、揺れも波のように渡っていく)。</li>
 *     <li>揺れ = 風下へ押される分(強さ × 0.05 ブロック)+ 枝の揺れ(毎秒 0.25 回・0.61 回。近くの物は揃って動く)+ 横へのはためき(毎秒 3.1 回、
 *     突風のときだけ)。重み 1 で最大 0.1 ブロックほど(大きいと葉と葉の間・幹との間に隙間が見える)。どれも位置のなめらかな関数なので、
 *     隣り合うブロックは離れない。</li>
 *     <li><b>揺れるのは風の音が鳴っている間だけ</b>(2026-10-16、ユーザー「大体 1〜2 分おきに風の音声が流れるようにし、その音声が流れている間だけ
 *     植物群が揺れるように」)。風の音と同じ見積もり({@link WindSoundModel}、聞く人のまわりと遠くの梢の音の大きさ)から「鳴っている度合い」
 *     {@link #gate} を作り、揺れの大きさに掛ける(0 なら全く動かない)。音の大きさ・消音の設定には左右されない(音を切っても植物は風で揺れる)。
 *     立ち上がりは約 0.6 秒・収まりは約 1.3 秒(音は 1.5 秒先を見て鳴らし始め、流す側の遅れが約 0.45 秒あるので、揺れは音より少し遅れて始まる)。</li>
 *     <li>時刻・カメラの位置の入る位相は CPU で倍精度で計算して渡す(大きな座標・長い時間で float の桁が足りなくならないように)。</li>
 * </ul>
 * 太い蔓(リアナ)は縄を別の描画で描くので、同じ揺れを {@link #displacement} で CPU で求めて {@link CorruptedLianaRenderer} が動かす。
 * 揺れるのはバニラの描画のときだけ。Sodium・Embeddium・Iris(シェーダーパック)は自分のシェーダーで描くので揺れない(壊れはしない)。
 * 「軽量」グラフィックでは葉が不透明の層で描かれるので葉は揺れない。同じシェーダーを上書きする資源パックが上にあると、そちらが効いて揺れない。
 * 設定 {@code canopyLeafSway}(0 で止める)。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class LeafSway {

    private static final double TWO_PI = Math.PI * 2.0;
    private static final float[] GUSTS = new float[32];
    /** 風の音の大きさがこれ以上なら、揺れは全開(それより小さい音では小さく揺れる)。仮決め。 */
    private static final double GATE_FULL_LEVEL = 0.15;
    private static final WindSoundModel MODEL = new WindSoundModel();
    private static boolean broken;
    /** 「風の音が鳴っている度合い」(0〜1)。直近の tick の値と、その前の tick の値(描画の途中の割合で補う)。 */
    private static float gate;
    private static float prevGate;

    /** 毎 tick、聞く人の所の風の音の大きさ(風の音と同じ見積もり)から {@link #gate} を寄せる。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return;
        }
        prevGate = gate;
        float target = 0.0F;
        if (mc.level != null && mc.player != null && mc.level.dimensionType().hasSkyLight()) {
            MODEL.compute(mc.player.getX(), mc.player.getZ(), CanopyWind.time(0.0F));
            double level = Math.max(MODEL.nearLevel, MODEL.farLevel);
            target = CanopyLight.smooth((float) Mth.clamp(level / GATE_FULL_LEVEL, 0.0, 1.0));
        }
        // 立ち上がりは速く(約 0.6 秒)、収まりはゆっくり(約 1.3 秒)。
        gate += (target - gate) * (target > gate ? 0.08F : 0.04F);
        if (gate < 0.002F && target == 0.0F) {
            gate = 0.0F;
        }
    }

    /** 今の揺れの大きさ(設定の倍率 × 風の音が鳴っている度合い)。0 なら揺らさない。リアナの描画も使う。 */
    static float swayAmount(float partialTick) {
        float amount = OvergrownVegetationConfig.COMMON.canopyLeafSway.get().floatValue();
        if (amount <= 0.0F) {
            return 0.0F;
        }
        return amount * Mth.lerp(partialTick, prevGate, gate);
    }

    /**
     * 世界の位置 (x, y, z) の揺れ(重み 1 のときの変位、ブロック)を {@code out} = {x, y, z} へ。シェーダー {@code ov_sway.glsl} の
     * {@code ovSway} と同じ式を CPU で解く(シェーダーはカメラからの位置と、カメラの位置を入れた位相で同じ値を出す)。
     * {@code amount} は {@link #swayAmount}。太い蔓(リアナ、{@link CorruptedLianaRenderer})のように、チャンクの模型でない物を揺らすのに使う。
     */
    static void displacement(double x, double y, double z, double time, float amount, double[] out) {
        double w = CanopyWind.strength(x, z, time);
        CanopyWind.velocity(x, z, time, PUSH);
        double pl = Math.sqrt(PUSH[0] * PUSH[0] + PUSH[1] * PUSH[1]);
        double dx = pl > 1.0E-4 ? PUSH[0] / pl : CanopyWind.dirX();
        double dz = pl > 1.0E-4 ? PUSH[1] / pl : CanopyWind.dirZ();
        double seconds = time / 20.0;
        double phi = x * 0.21 + z * 0.17 + y * 0.05;
        double s1 = 0.7 * Math.sin(TWO_PI * 0.25 * seconds + phi) + 0.3 * Math.sin(TWO_PI * 0.61 * seconds + 1.7 * phi);
        double psi = x * 0.9 + z * 0.7 + y * 0.8;
        double f = Math.sin(TWO_PI * 3.1 * seconds + psi);
        double along = 0.05 * w + (0.012 + 0.04 * w) * s1;
        double across = 0.015 * w * f;
        double up = (0.004 + 0.02 * w) * 0.5 * s1 + 0.6 * across;
        out[0] = (dx * along - dz * across) * amount;
        out[1] = up * amount;
        out[2] = (dz * along + dx * across) * amount;
    }

    private static final double[] PUSH = new double[2];

    /** 開発用のログ: 今の「風の音が鳴っている度合い」(0〜1)。 */
    public static float gate() {
        return gate;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 空を描いた後・地形を描く前に、葉と草のシェーダーへ今の風を渡す。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken) {
            return;
        }
        ShaderInstance mipped = GameRenderer.getRendertypeCutoutMippedShader();
        ShaderInstance cutout = GameRenderer.getRendertypeCutoutShader();
        try {
            if (mipped != null) {
                update(event, mipped);
            }
            if (cutout != null) {
                update(event, cutout);
            }
        } catch (RuntimeException e) {
            broken = true;
            if (mipped != null) {
                mipped.safeGetUniform("OvBase").set(0.0F, 1.0F, 0.0F, 0.0F);
            }
            if (cutout != null) {
                cutout.safeGetUniform("OvBase").set(0.0F, 1.0F, 0.0F, 0.0F);
            }
            OvergrownVegetation.LOGGER.error("葉と草の揺れで例外が出たので、このセッションでは揺らさない", e);
        }
    }

    private static void update(RenderLevelStageEvent event, ShaderInstance shader) {
        Minecraft mc = Minecraft.getInstance();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // 風の音が鳴っていない間(凪)は 0 —— シェーダーは何も動かさない。
        float amount = swayAmount(partialTick);
        if (mc.level == null || amount <= 0.0F || !mc.level.dimensionType().hasSkyLight()) {
            shader.safeGetUniform("OvBase").set(0.0F, 1.0F, 0.0F, 0.0F);
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        double time = CanopyWind.time(partialTick);
        double seconds = time / 20.0;

        shader.safeGetUniform("OvBase").set((float) CanopyWind.calmStrength(), (float) CanopyWind.dirX(), (float) CanopyWind.dirZ(), amount);
        // 位相にはカメラの位置と時刻が入るので、倍精度で計算して 2π で丸めてから渡す。
        shader.safeGetUniform("OvPhase").set(
                wrap(cam.x * 0.13 + time * 0.031),
                wrap(cam.z * 0.11 - time * 0.027),
                wrap(cam.x * 0.21 + cam.z * 0.17 + cam.y * 0.05),
                wrap(cam.x * 0.9 + cam.z * 0.7 + cam.y * 0.8));
        double a = wrap(TWO_PI * 0.25 * seconds);
        double b = wrap(TWO_PI * 0.61 * seconds);
        double c = wrap(TWO_PI * 3.1 * seconds);
        shader.safeGetUniform("OvOsc").set((float) Math.sin(a), (float) Math.cos(a), (float) Math.sin(b), (float) Math.cos(b));
        shader.safeGetUniform("OvOsc2").set((float) Math.sin(c), (float) Math.cos(c), 0.0F, 0.0F);
        CanopyWind.fillGusts(time, cam.x, cam.z, GUSTS);
        for (int i = 0; i < 4; i++) {
            int at = i * 8;
            shader.safeGetUniform("OvGustA" + i).set(GUSTS[at], GUSTS[at + 1], GUSTS[at + 2], GUSTS[at + 3]);
            shader.safeGetUniform("OvGustB" + i).set(GUSTS[at + 4], Math.max(0.5F, GUSTS[at + 5]), Math.max(0.5F, GUSTS[at + 6]),
                    Math.max(0.5F, GUSTS[at + 7]));
        }
    }

    private static float wrap(double radians) {
        return (float) (radians - Math.floor(radians / TWO_PI) * TWO_PI);
    }

    private LeafSway() {
    }
}
