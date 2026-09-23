package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.FastColor;

/**
 * 一人称で見える自分の腕にも浸食度を出す(2026-09-22)。
 *
 * <h2>なぜ別に要るのか</h2>
 * <p>一人称の腕は {@code PlayerRenderer#renderHand} が<b>腕と袖のパーツだけを直接描く</b>経路で、
 * レンダーレイヤー({@link ErosionPlayerLayer})を一切通らない。三人称で苔が出ていても、
 * 自分の手には何も出ない。<b>一人称では自分の体が見えないので、腕は数少ない「自分の状態が見える場所」</b>
 * でもある。
 *
 * <h2>姿勢の合わせ方</h2>
 * <p>苔は膨らませた別モデルで描くので、バニラの腕と同じ姿勢に合わせる必要がある。
 * {@code setupAnim} をもう一度回すのではなく、<b>バニラの腕パーツから
 * {@link ModelPart#copyFrom} で位置・回転・拡縮をそのまま写す</b> ——
 * {@code renderHand} は {@code xRot} を 0 に潰すなどの細工をしており、
 * 同じ手順を再現しようとすると必ずずれる。
 */
public final class ErosionFirstPerson {

    /** {@link EntityModelSet} はリソース再読み込みで作り直されるので、その単位で焼き直す。 */
    private static EntityModelSet cachedModels;
    private static PlayerModel<AbstractClientPlayer> wideModel;
    private static PlayerModel<AbstractClientPlayer> slimModel;

    /**
     * 一人称の腕の上に、色かぶりと苔を重ねる。{@code PlayerRendererHandMixin} が
     * {@code renderHand} の最後から呼ぶ。
     *
     * @param arm      バニラが描いた腕のパーツ(姿勢の写し元)
     * @param sleeve   バニラが描いた袖のパーツ
     * @param rightArm 右腕なら {@code true}
     */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                              AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, boolean rightArm) {
        float percent = ClientErosion.percentOf(player);
        if (percent <= 0.0F || player.isInvisible()) {
            return;
        }
        ClimaxTable table = DesignData.climax();
        float scale = (float) table.scale(percent);
        if (scale <= 0.0F) {
            return;
        }

        // 色かぶり: バニラが描いたのと同じパーツを、着色してもう一度重ねる。
        int tint = ErosionPlayerLayer.tintColor(table, scale);
        if (FastColor.ARGB32.alpha(tint) > 2) {
            VertexConsumer buffer =
                    bufferSource.getBuffer(RenderType.entityTranslucent(player.getSkin().texture()));
            arm.render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, tint);
            sleeve.render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, tint);
        }

        // 苔: 膨らませたモデルの同じ側の腕を、バニラの腕の姿勢へ合わせて描く。
        boolean slim = player.getSkin().model() == PlayerSkin.Model.SLIM;
        PlayerModel<AbstractClientPlayer> model = model(slim);
        if (model == null) {
            return;
        }
        ModelPart mossArm = rightArm ? model.rightArm : model.leftArm;
        mossArm.copyFrom(arm);
        VertexConsumer moss = bufferSource.getBuffer(
                RenderType.entityCutoutNoCull(ErosionPlayerLayer.mossTexture(slim, scale)));
        mossArm.render(poseStack, moss, packedLight, OverlayTexture.NO_OVERLAY);
    }

    private static PlayerModel<AbstractClientPlayer> model(boolean slim) {
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        if (models != cachedModels) {
            cachedModels = models;
            wideModel = new PlayerModel<>(
                    models.bakeLayer(OvergrownVegetationModelLayers.EROSION_DEFAULT), false);
            slimModel = new PlayerModel<>(
                    models.bakeLayer(OvergrownVegetationModelLayers.EROSION_SLIM), true);
        }
        return slim ? slimModel : wideModel;
    }

    private ErosionFirstPerson() {
    }
}
