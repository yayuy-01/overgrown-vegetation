package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 攻城戦ボスの<b>近距離用</b>実エンティティの描画。遠距離のインポスタ描画
 * ({@link SiegeBossRenderer})とまったく同じ {@link DorgaArchelausTitanicaModel} を使うので、
 * 切り替わっても見た目は変わらない。
 *
 * <p>エンティティの位置は当たり判定の底面だが、ボスの「位置」は胴の中心線なので
 * ({@link DorgaArchelausTitanicaEntity#bodyCenter})、モデルの胴の中心線(y = 17 モデル単位)が
 * 当たり判定の中心の高さに来るよう {@link #preRender} で平行移動している。
 *
 * <p class="warn"><b>罠</b>: {@code preRender} は<b>描画レイヤーの再描画でもう一度呼ばれる</b>
 * ({@code GeoRenderer.reRender} が {@code isReRender = true} で呼ぶ)。そのときの PoseStack には
 * 1 回目の {@code preRender} の変換が<b>すでに載っている</b>ので、無条件に平行移動すると
 * レイヤーだけ二重にずれる。GeckoLib 自身の {@code scaleModelForRender} が {@code !isReRender} で
 * 守られているのと同じ理由で、ここも守る必要がある。
 * — 実際に、発光レイヤー({@code AutoGlowingGeoLayer})で描かれる増幅器の葯(このモデルで唯一
 * 発光するテクセル)だけが 0.5625 モデル単位 = <b>倍率 25 で 14 ブロック下</b>へずれ、
 * 「増幅器の先端が頭から外れて浮いている」ように見えていた。遠距離のインポスタ
 * ({@link TitanicaImpostorRenderer})は平行移動を持たないので正常で、近づいたときだけ壊れる。
 */
public class DorgaArchelausTitanicaRenderer extends GeoEntityRenderer<DorgaArchelausTitanicaEntity> {

    public DorgaArchelausTitanicaRenderer(EntityRendererProvider.Context context) {
        super(context, new DorgaArchelausTitanicaModel<>());
        this.shadowRadius = 0.0F;
        withScale(OvergrownVegetationConfig.COMMON.siegeScale.get().floatValue());
        // 全身を描き直す AutoGlowingGeoLayer ではなく、光る骨だけを重ねる版(TitanicaGlowLayer の javadoc)。
        addRenderLayer(new TitanicaGlowLayer<>(this));
    }

    /**
     * <b>裏面を描かない</b>(既定。{@code siegeCullBackFaces})。GeckoLib の既定は
     * {@code entityCutoutNoCull} = 両面描画だが、このモデルの箱 325 個はすべて厚みのある直方体なので
     * 内側が見えることは無く、裏面はそのぶん無駄に塗っているだけ。
     * 画面を覆うほど大きいボスでは、ここだけで塗りつぶしの負荷がおよそ半分になる。
     */
    @Override
    public RenderType getRenderType(DorgaArchelausTitanicaEntity animatable, ResourceLocation texture,
                                    @Nullable MultiBufferSource bufferSource, float partialTick) {
        return OvergrownVegetationConfig.COMMON.siegeCullBackFaces.get()
                ? RenderType.entityCutout(texture)
                : super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    /**
     * <b>本体の位置と見た目の位置がずれている</b>ので、最初に差分を戻す。
     *
     * <p>エンティティ本体は当たり判定パーツをクライアントへ届かせるために
     * {@link DorgaArchelausTitanicaEntity#trueBodyCenter 本当の胴の中心}ではなく
     * <b>体軸上のプレイヤーに近い点</b>へ寄せてある(理由は {@code anchorTo} の javadoc)。
     * モデルは軌道の式どおりの位置に描かなければならないので、その差だけ平行移動しておく。
     *
     * <p>ここ({@code render})でやるのは <b>{@code preRender} は描画レイヤーの再描画でもう一度
     * 呼ばれる</b>ため — あちらでやると発光レイヤーだけ二重にずれる(実際にやらかした)。
     */
    @Override
    public void render(DorgaArchelausTitanicaEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        // 描く場所が遠すぎるときは遠距離用のインポスタに任せる。エンティティとして描くと
        // バニラの霧に塗り潰されて<b>空と同じ色になって消える</b>(SiegeBossRenderer.impostorDrawn の javadoc)。
        Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (SiegeBossRenderer.impostorDrawn(entity.trueBodyCenter(partialTick), camera)) {
            return;
        }
        Vec3 offset = entity.renderOffset(partialTick);
        poseStack.pushPose();
        poseStack.translate(offset.x, offset.y, offset.z);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    @Override
    public void preRender(PoseStack poseStack, DorgaArchelausTitanicaEntity animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
        if (!isReRender) {
            // ここは既に siegeScale 倍された座標系。当たり判定の中心の高さへ胴の中心線を合わせる。
            float scale = Math.max(1.0E-3F, this.scaleWidth);
            poseStack.translate(0.0F,
                    animatable.getBbHeight() * 0.5F / scale - (float) (TitanicaKinematics.BODY_LINE_Y / 16.0D), 0.0F);
        }
    }

    @Override
    protected void applyRotations(DorgaArchelausTitanicaEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
        // GeckoLib のフレームは Y 上向きのままなので、Minecraft のピッチ(正で下)は符号を反転して掛ける。
        float pitch = Mth.lerp(partialTick, animatable.xRotO, animatable.getXRot());
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
    }
}
