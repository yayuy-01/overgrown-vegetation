package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「コエルロサウラヴス」の描画。
 *
 * <h2>滑空と急降下で体を下に向ける</h2>
 * <p>滑空は<b>降りる角度がそのまま体の角度</b>になる —— 水平のまま降りていくと、
 * 飛んでいるのではなく<b>引きずられている</b>ように見える。
 * エンティティ側が速度から出した角度({@link CoelurosauravusEntity#getGlidePitch()})を
 * そのまま X 軸まわりに掛ける。滑空比 3:1 なら約 18 度、急降下なら約 51 度。
 *
 * <p>角度は<b>フレームごとに滑らかに近づける</b>。滑空から急降下へ切り替わる瞬間に
 * 18 度 → 51 度と跳ぶと、体がカクンと折れて見えるため。
 *
 * <p>地上・登攀中は 0 度に戻る(エンティティ側が {@code DATA_PITCH} を 0 にする)。
 * 壁を登る姿勢はアニメーション側({@code climb})が受け持つので、ここでは何もしない。
 */
public class CoelurosauravusRenderer extends GeoEntityRenderer<CoelurosauravusEntity> {

    /** 1 フレームで詰める割合。小さいほどゆっくり傾く。 */
    private static final float PITCH_EASE = 0.25F;
    /** バンクは傾きより<b>ゆっくり</b>詰める。旋回は始まるのも終わるのも緩やかなので。 */
    private static final float ROLL_EASE = 0.12F;

    private float shownPitch;
    private float shownRoll;

    public CoelurosauravusRenderer(EntityRendererProvider.Context context) {
        super(context, new CoelurosauravusModel());
        this.shadowRadius = 0.4F;
    }

    @Override
    protected void applyRotations(CoelurosauravusEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);

        boolean flying = animatable.isAirborne();
        this.shownPitch = Mth.lerp(PITCH_EASE, this.shownPitch, flying ? animatable.getGlidePitch() : 0.0F);
        this.shownRoll = Mth.lerp(ROLL_EASE, this.shownRoll, flying ? animatable.getGlideRoll() : 0.0F);
        if (Math.abs(this.shownPitch) < 0.05F && Math.abs(this.shownRoll) < 0.05F) {
            return;
        }
        // 体の真ん中を軸に倒す。足元を軸にすると頭が大きく沈んで、機首下げではなく
        // 「地面にめり込む」動きになる。
        float centre = animatable.getBbHeight() * 0.5F / nativeScale;
        poseStack.translate(0.0F, centre, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(this.shownPitch));
        // 旋回している向きへ体を倒す(バンク)。滑空機と同じで、<b>曲がる側の翼が下がる</b>。
        poseStack.mulPose(Axis.ZP.rotationDegrees(this.shownRoll));
        poseStack.translate(0.0F, -centre, 0.0F);
    }
}
