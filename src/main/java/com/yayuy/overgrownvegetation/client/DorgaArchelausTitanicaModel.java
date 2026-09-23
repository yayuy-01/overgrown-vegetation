package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * ドルガ=アーケロウス・ティタニカの GeckoLib モデル定義。実エンティティとインポスタで共通。
 * <ul>
 *   <li>形状: {@code assets/overgrown_vegetation/geo/dorga_archelaus_titanica.geo.json}</li>
 *   <li>アニメーション: {@code assets/overgrown_vegetation/animations/dorga_archelaus_titanica.animation.json}
 *       (花・増幅結晶・頭だけ。{@link TitanicaAnimations} 参照)</li>
 *   <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/dorga_archelaus_titanica.png}
 *       (64×64 のアトラス。既存ブロックテクスチャ 16 種を 4×4 に並べたもの)+ {@code _glowmask}(結晶の発光)</li>
 * </ul>
 *
 * <h2>モデルの取り込みで直したこと(Blockbench から再書き出しすると消える)</h2>
 * <ul>
 *   <li>首の 6 節({@code bone6}〜{@code bone}: 元は全て pivot {@code [0,17,0]} = 胴の中心)を、
 *       各節の後端 z = -112, -128, …, -192 に置き直した。元のままだと首を曲げる回転が胴の中心を
 *       軸に掛かり、首全体が胴ごと振れてしまう。cube の回転・pivot は別物なので見た目は変わらない。</li>
 *   <li>テクスチャ 16×16 を前提に貼られていた per-face UV を、材質(胴=樹皮 / ヒレ=葉 / 触手=蔦 /
 *       花弁 / 結晶)ごとに 64×64 アトラスの該当タイルへずらした。UV の値はタイル内では元のまま。</li>
 * </ul>
 *
 * <h2>手続き的な動き</h2>
 * <p>ヒレ 20 枚・触手 18 本 × 7 節・尾 10 節・首 6 節はキーフレームで書くには多すぎるので、
 * ここで毎フレーム計算する。{@link OvergrownNexusModel} と同じく、回転は常に
 * {@link GeoBone#getInitialSnapshot()}(レストポーズ)を基準にした<b>絶対値</b>で書く —
 * {@code get→+差分→set} の相対加算は毎フレーム積み上がって際限なく歪むため(あのクラスの
 * javadoc を参照)。
 *
 * <h2>首の向き(標的へ頭を向ける)</h2>
 * <p>{@link ClientSiegeBoss#neckYaw}/{@link ClientSiegeBoss#neckPitch}(胴基準の差分・度)を
 * 首の 6 節に等分して与える。{@link TitanicaKinematics#flowerPosition} が同じ分配で花の位置を
 * 求めるので、ビームは描画上の花から出る。符号について: GeckoLib は骨の Y 回転を描画フレームの
 * +Y まわりの素の回転として掛けるので、骨に {@code +θ} を与えると頭の向き(-Z)は
 * Minecraft ヨーで {@code -θ} だけ回る。ピッチも同様に {@code +χ} で頭が上を向く
 * (Minecraft のピッチは正で下)。そのためどちらも差分の符号を反転して与える。
 */
public class DorgaArchelausTitanicaModel<T extends TitanicaAnimatable> extends GeoModel<T> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/dorga_archelaus_titanica.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/dorga_archelaus_titanica.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/dorga_archelaus_titanica.animation.json");

    /** 首。付け根側から先端へ。 */
    private static final String[] NECK = {"bone6", "bone5", "bone4", "bone3", "bone2", "bone"};
    /** 胴。前から後ろへ(親子の鎖)。 */
    private static final String[] SPINE = {"bone28", "bone8", "bone9", "bone10", "bone11", "bone12",
            "bone13", "bone14", "bone15", "bone16", "bone17"};
    /** 尾。付け根から先端へ(親子の鎖)。 */
    private static final String[] TAIL = {"bone18", "bone19", "bone20", "bone21", "bone22", "bone23",
            "bone24", "bone25", "bone26", "bone27"};
    /** ヒレの対の数。k 対目は +X 側が hida{k}(k=1 は "hida")、-X 側が hida{21-k}。 */
    private static final int FIN_PAIRS = 10;
    /** 触手の根元の骨番号。各鎖はこの番号から +6 まで。 */
    private static final int[] TENTACLE_BASES = {98, 106, 113, 120, 127, 134, 141, 148, 155, 162,
            169, 176, 183, 190, 197, 204, 211};
    /** 背中側へ伸びる 1 本だけ番号が飛んでいる。 */
    private static final String[] DORSAL_TENTACLE = {"bone104", "bone92", "bone93", "bone94", "bone95", "bone96", "bone97"};
    private static final int TENTACLE_LINKS = 7;

    /** ヒレの羽ばたきの振幅(rad)。 */
    private static final float FIN_AMPLITUDE = 0.22F;
    /** 胴のうねり(1 節あたり rad)。 */
    private static final float SPINE_AMPLITUDE = 0.018F;
    /** 尾の振り(1 節あたり rad)。 */
    private static final float TAIL_AMPLITUDE = 0.045F;
    /** 触手の揺れ(1 節あたり rad)。 */
    private static final float TENTACLE_AMPLITUDE = 0.045F;

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> state) {
        super.setCustomAnimations(animatable, instanceId, state);
        float partialTick = state.getPartialTick();
        float t = (float) animatable.titanicaAge(partialTick);

        applyNeck(partialTick);
        applyFins(t);
        applySpineAndTail(t);
        applyTentacles(t);
    }

    private void applyNeck(float partialTick) {
        float yawStep = (float) Math.toRadians(ClientSiegeBoss.neckYaw(partialTick) / TitanicaKinematics.NECK_LINKS);
        float pitchStep = (float) Math.toRadians(ClientSiegeBoss.neckPitch(partialTick) / TitanicaKinematics.NECK_LINKS);
        for (String name : NECK) {
            getBone(name).ifPresent(bone -> {
                BoneSnapshot rest = bone.getInitialSnapshot();
                bone.setRotY(rest.getRotY() - yawStep);
                bone.setRotX(rest.getRotX() - pitchStep);
            });
        }
    }

    /**
     * ヒレの羽ばたき。前から後ろへ位相をずらした正弦波(メタクロナル波)。
     * +X 側と -X 側で Z 回転の符号を逆にして、両側が同時に下がる(GeckoLib は焼き込み時に X を
     * 鏡映するため、+X へ伸びる骨は +Z 回転で下がり、-X へ伸びる骨は -Z 回転で下がる)。
     */
    private void applyFins(float t) {
        for (int k = 1; k <= FIN_PAIRS; k++) {
            float flap = (float) Math.sin(t * 0.085F - k * 0.55F) * FIN_AMPLITUDE;
            String right = k == 1 ? "hida" : "hida" + k;
            String left = "hida" + (21 - k);
            getBone(right).ifPresent(bone -> bone.setRotZ(bone.getInitialSnapshot().getRotZ() + flap));
            getBone(left).ifPresent(bone -> bone.setRotZ(bone.getInitialSnapshot().getRotZ() - flap));
        }
    }

    private void applySpineAndTail(float t) {
        for (int i = 0; i < SPINE.length; i++) {
            float yaw = (float) Math.sin(t * 0.030F - i * 0.35F) * SPINE_AMPLITUDE;
            getBone(SPINE[i]).ifPresent(bone -> bone.setRotY(bone.getInitialSnapshot().getRotY() + yaw));
        }
        for (int i = 0; i < TAIL.length; i++) {
            float yaw = (float) Math.sin(t * 0.045F - i * 0.45F) * TAIL_AMPLITUDE;
            float pitch = (float) Math.sin(t * 0.032F - i * 0.30F + 1.1F) * TAIL_AMPLITUDE * 0.35F;
            getBone(TAIL[i]).ifPresent(bone -> {
                BoneSnapshot rest = bone.getInitialSnapshot();
                bone.setRotY(rest.getRotY() + yaw);
                bone.setRotX(rest.getRotX() + pitch);
            });
        }
    }

    /** 触手 18 本。鎖ごとに位相をずらし、節ごとにさらにずらして、ゆらゆら垂れ下がって見せる。 */
    private void applyTentacles(float t) {
        for (int c = 0; c < TENTACLE_BASES.length; c++) {
            float chainPhase = c * 0.9F;
            for (int k = 0; k < TENTACLE_LINKS; k++) {
                swayLink("bone" + (TENTACLE_BASES[c] + k), t, chainPhase, k);
            }
        }
        for (int k = 0; k < DORSAL_TENTACLE.length; k++) {
            swayLink(DORSAL_TENTACLE[k], t, 17.0F * 0.9F, k);
        }
    }

    private void swayLink(String name, float t, float chainPhase, int k) {
        float x = (float) Math.sin(t * 0.07F + chainPhase + k * 0.6F) * TENTACLE_AMPLITUDE;
        float z = (float) Math.sin(t * 0.05F + chainPhase * 1.3F + k * 0.5F) * TENTACLE_AMPLITUDE * 0.7F;
        getBone(name).ifPresent(bone -> {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.setRotX(rest.getRotX() + x);
            bone.setRotZ(rest.getRotZ() + z);
        });
    }
}
