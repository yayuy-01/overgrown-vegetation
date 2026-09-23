package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * フィロセンスの描画。ビームそのものは本体の姿勢と無関係にワールド座標で引きたいので、
 * ここではなく {@link PhyllosensBeamRenderer}(レベルの描画段階)が描く。
 *
 * <p>ただし<b>ビームがどこから出るか</b>はここで決まる —— 描画のついでに
 * <b>頭({@code head})の先端が実際にどこにあるか</b>を焼かれた行列から読んで本体へ渡す
 * ({@link PhyllosensEntity#setHeadTipPosition})。スポロフィルの傘
 * ({@link SporophyllRenderer})と同じ手口。
 *
 * <h2>なぜ計算で出さないか</h2>
 * 頭までの骨の鎖({@code neck → bone3 → bone4 → bone5 → head})は長く、
 * <b>ビームの溜めで首が持ち上がる</b>({@code beam_charge} で合計 36 度)。ピボット座標から出した
 * 概算では首の付け根あたりから光が出てしまう(2026-09-14 のユーザー報告:
 * 「ビームが出る場所が首の少し後ろになっている」)。焼かれた行列なら、アニメーションの途中の
 * 姿勢でも {@code scale} 込みで必ず頭の先に付いてくる。
 */
public class PhyllosensRenderer extends GeoEntityRenderer<PhyllosensEntity> {

    /** 位置を読む骨。 */
    private static final String HEAD_BONE = "head";
    /**
     * {@link #HEAD_BONE} のピボットから<b>頭の先端</b>までの距離(モデルのピクセル、前向き)。
     * 頭の箱は {@code z = -47.7 〜 -63.9}、ピボットは {@code z = -48.6} なので、
     * 先端までは 15.3 ピクセル。モデルでは <b>-Z が前</b>。
     */
    private static final float HEAD_TIP_FORWARD = 15.3F;

    public PhyllosensRenderer(EntityRendererProvider.Context context) {
        super(context, new PhyllosensModel());
        this.shadowRadius = 1.2F;
    }

    @Override
    public void render(PhyllosensEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // 行列の記録は既定で切ってあるので、描画の前に頼んでおく(getWorldSpaceMatrix が有効化する)。
        // 有効化した最初のフレームはまだ中身が無いので、読むのは描画の<b>後</b>。
        getGeoModel().getBone(HEAD_BONE).ifPresent(GeoBone::getWorldSpaceMatrix);

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        getGeoModel().getBone(HEAD_BONE).ifPresent(bone -> {
            // 行列はピボットの位置・骨の向きで焼かれているので、前方向へずらした点を通せば先端が出る。
            Vector4f tip = bone.getWorldSpaceMatrix()
                    .transform(new Vector4f(0.0F, 0.0F, -HEAD_TIP_FORWARD / 16.0F, 1.0F));
            Vec3 pos = new Vec3(tip.x(), tip.y(), tip.z());
            // 有効化前の 1 フレームは原点が返る。体から離れすぎた値は捨てる
            // (この首は長いので、スポロフィルの 20 ブロックより広く見る)。
            if (pos.distanceToSqr(entity.position()) < 1024.0D) {
                entity.setHeadTipPosition(pos);
            }
        });
    }

    /**
     * 影だけは {@code SCALE} 属性が掛からない(バニラの {@code getShadowRadius} は
     * {@code shadowRadius} をそのまま返す)ので、ここで掛ける。
     * こうしておけば {@code mobs.json} の {@code scale} を変えても影が付いてくる。
     */
    @Override
    protected float getShadowRadius(PhyllosensEntity entity) {
        return this.shadowRadius * entity.getScale();
    }
}
