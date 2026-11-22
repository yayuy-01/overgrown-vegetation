package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * <b>風に揺れる葉と草</b>(2026-10-30 夜 第 2 段「動き」。元は parked の {@code LeafSway}、2026-10-15 夜 案 B)。過繁茂の葉・湿地の葉・茂み・
 * シダ・蔦・垂れ苔のブロックそのものを、光・胞子・音と同じ風({@link Wind#current()} = {@link GustField})で揺らす。
 *
 * <h2>しくみ</h2>
 * <ul>
 *     <li>揺らすのは地形のシェーダー(バニラの {@code rendertype_cutout(_mipped).vsh} の上書きと、Sodium の {@code block_layer_opaque.vsh}。
 *     中身は {@code shaders/include/ov_sway.glsl})。頂点を動かすだけで、層も面も足さない(葉の層は頂点の数が重さの主因)。</li>
 *     <li>どの頂点をどれだけ動かすかは、模型の側で頂点の透明度に印として書く({@link SwayModels})。</li>
 *     <li>ここは AFTER_SKY で、凪の空気の動き(強さ・向き)と今吹いている突風の前線(最大 4 つ)をバニラの 2 つのシェーダーへ渡す。
 *     Sodium へは {@code SodiumTerrain} が {@code Ov} で始まる uniform を写す。シェーダーは頂点ごとにその場の風を {@link GustField#strength}
 *     と同じ式で求めるので、前線が林を渡ると揺れも遠くから順に渡ってくる。</li>
 *     <li>揺れ = 風下へ押される分({@code push} × 強さ)+ 枝の揺れ(毎秒 0.25・0.61 回)+ 横へのはためき(毎秒 3.1 回、突風のときだけ)。
 *     どれも位置のなめらかな関数なので、隣り合うブロックは離れない。</li>
 *     <li><b>揺れるのは風の音が鳴っている間だけ</b>({@link WindGate})。凪では 0 で全く動かない。</li>
 *     <li>位相(時刻・カメラの位置)は CPU で倍精度で計算して 2π で丸めて渡す(大きな座標・長い時間で float の桁が足りなくならないように)。</li>
 * </ul>
 * 太い蔓(リアナ)は縄を別の描画で描くので、同じ揺れを {@link #displacement} で CPU で解く。
 * 「軽量」グラフィックでは葉が不透明の層で描かれるので葉は揺れない。Iris のシェーダーパックでは揺れない。数値は設計値の {@code sway}(仮決め)。
 */
public final class LeafSway {

    /** 水草が流れへ傾く大きさ(ブロック。重み 1・流れが最も強いとき。11-13 仮決め)。 */
    static final float CURRENT_LEAN = 0.2F;

    private static final double TWO_PI = Math.PI * 2.0;
    private static final float[] GUSTS = new float[GustField.MAX_GUSTS * 8];
    private static final double[] PUSH = new double[2];

    /** 今の揺れの大きさ(設計値の倍率 × 風の音が鳴っている度合い)。0 なら揺らさない。 */
    public static float amount(float partialTick) {
        return DesignData.atmosphere().sway().amount() * WindGate.value(partialTick);
    }

    /**
     * 世界の位置 (x, y, z) の揺れ(重み 1 のときの変位、ブロック)を {@code out} = {x, y, z} へ。シェーダー {@code ovSway} と同じ式を CPU で解く。
     * {@code amount} は {@link #amount}。太い蔓のように、チャンクの模型でない物を揺らすのに使う。
     */
    public static void displacement(double x, double y, double z, double time, float amount, double[] out) {
        if (amount <= 0.0F) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return;
        }
        AtmosphereTable.SwayParams p = DesignData.atmosphere().sway();
        Wind wind = Wind.current();
        double w = wind.strength(x, z, time);
        wind.velocity(x, z, time, PUSH);
        double pl = Math.sqrt(PUSH[0] * PUSH[0] + PUSH[1] * PUSH[1]);
        double dx = pl > 1.0E-4 ? PUSH[0] / pl : wind.dirX();
        double dz = pl > 1.0E-4 ? PUSH[1] / pl : wind.dirZ();
        double seconds = time / 20.0;
        double phi = x * 0.21 + z * 0.17 + y * 0.05;
        double s1 = 0.7 * Math.sin(TWO_PI * 0.25 * seconds + phi) + 0.3 * Math.sin(TWO_PI * 0.61 * seconds + 1.7 * phi);
        double psi = x * 0.9 + z * 0.7 + y * 0.8;
        double f = Math.sin(TWO_PI * 3.1 * seconds + psi);
        double along = p.push() * w + (p.branchCalm() + p.branchGust() * w) * s1;
        double across = p.flutter() * w * f;
        double up = (0.004 + 0.02 * w) * 0.5 * s1 + 0.6 * across;
        out[0] = (dx * along - dz * across) * amount;
        out[1] = up * amount;
        out[2] = (dz * along + dx * across) * amount;
    }

    /** AFTER_SKY(地形を描く前)に、葉と草のシェーダーへ今の風を渡す。{@code on} が false なら止める。 */
    static void upload(RenderLevelStageEvent event, boolean on) {
        on = on && VisualQuality.leafSway();
        ShaderInstance mipped = GameRenderer.getRendertypeCutoutMippedShader();
        ShaderInstance cutout = GameRenderer.getRendertypeCutoutShader();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float amount = on ? amount(partialTick) : 0.0F;
        if (mipped != null) {
            update(event, mipped, amount, partialTick, on);
        }
        if (cutout != null) {
            update(event, cutout, amount, partialTick, on);
        }
    }

    private static void update(RenderLevelStageEvent event, ShaderInstance shader, float amount, float partialTick, boolean on) {
        AtmosphereTable.SwayParams p = DesignData.atmosphere().sway();
        Vec3 cam = event.getCamera().getPosition();
        double time = Atmosphere.time(partialTick);
        double seconds = time / 20.0;
        // 11-01 夜(見え方の直し 1): 封印木・木生シダの板の葉の曲がりと上下、胞子昆布の葉の水の揺れ(風の音によらない。位相は倍精度で)。
        shader.safeGetUniform("OvSwayF").set(p.fringeBend(), p.fringeLift(), p.kelpAmount(), on ? 1.0F : 0.0F);
        double wa = wrap(TWO_PI * p.kelpHz() * seconds + cam.x * 0.31 + cam.z * 0.23 + cam.y * 0.17);
        double wb = wrap(TWO_PI * p.kelpHz() * 0.73 * seconds + cam.x * 0.19 - cam.z * 0.29 + cam.y * 0.11);
        shader.safeGetUniform("OvSwayW").set((float) Math.sin(wa), (float) Math.cos(wa), (float) Math.sin(wb), (float) Math.cos(wb));
        // 水の中の共通の流れ(11-13 水まわり ⑥ の直し): 水草が流れの向きへ傾く。向き × 強さ(0〜1)と、満の重みでの傾き(ブロック)。
        double cLen = Math.sqrt(WaterCurrent.x() * WaterCurrent.x() + WaterCurrent.z() * WaterCurrent.z());
        float cK = on && cLen > 1.0E-6 ? WaterCurrent.strength01() : 0.0F;
        shader.safeGetUniform("OvCurrent").set(cK > 0.0F ? (float) (WaterCurrent.x() / cLen) * cK : 0.0F, cK > 0.0F ? (float) (WaterCurrent.z() / cLen) * cK : 0.0F, CURRENT_LEAN, 0.0F);
        if (amount <= 0.0F) {
            shader.safeGetUniform("OvBase").set(0.0F, 1.0F, 0.0F, 0.0F);
            return;
        }
        GustField field = GustField.INSTANCE;
        shader.safeGetUniform("OvBase").set((float) field.calmStrength(), (float) field.dirX(), (float) field.dirZ(), amount);
        shader.safeGetUniform("OvSwayK").set(p.push(), p.branchCalm(), p.branchGust(), p.flutter());
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
        field.fillGusts(time, cam.x, cam.z, GUSTS);
        for (int i = 0; i < GustField.MAX_GUSTS; i++) {
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
