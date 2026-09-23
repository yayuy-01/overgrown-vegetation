package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.entity.SporophyllEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「スポロフィル」の描画。
 *
 * <p>描画のついでに<b>傘({@code kasa})が実際にどこにあるか</b>を本体へ渡している
 * ({@link SporophyllEntity#setCapPosition})。胞子を降らせる位置はこれで決まる。
 *
 * <h2>なぜ位置を計算で出さないか</h2>
 * 傘までの骨の鎖({@code neck → bone3 → bone4 → bone5 → head → kasa})には
 * <b>モデル側の基準回転</b>({@code bone5} と {@code head} がそれぞれ 45 度)が入っているうえ、
 * アニメーションでも首と傘が動く。ピボット座標をそのまま使うと<b>前下がりにずれる</b>
 * (2026-09-14 のユーザー報告:「パーティクルの位置が前方やや下部にずれています」)。
 * GeckoLib が焼いた行列から読めば、基準回転もアニメーションも {@code scale} も込みで必ず合う。
 */
public class SporophyllRenderer extends GeoEntityRenderer<SporophyllEntity> {

    /** 位置を読む骨の名前(キノコの傘)。 */
    private static final String CAP_BONE = "kasa";

    public SporophyllRenderer(EntityRendererProvider.Context context) {
        super(context, new SporophyllModel());
        this.shadowRadius = 1.1F;
    }

    @Override
    public void render(SporophyllEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // 行列の記録は既定で切ってあるので、描画の前に頼んでおく(getModelSpaceMatrix が有効化する)。
        // 有効化した最初のフレームはまだ中身が無いので、読むのは描画の<b>後</b>。
        getGeoModel().getBone(CAP_BONE).ifPresent(GeoBone::getModelSpaceMatrix);

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);

        getGeoModel().getBone(CAP_BONE).ifPresent(bone -> {
            Vector3d world = bone.getWorldPosition();
            Vec3 pos = new Vec3(world.x, world.y, world.z);
            // 有効化前の 1 フレームは原点が返る。体から離れすぎた値は捨てる。
            if (pos.distanceToSqr(entity.position()) < 400.0D) {
                entity.setCapPosition(pos);
            }
        });
    }

    /**
     * 影だけは {@code SCALE} 属性が掛からない(バニラの {@code getShadowRadius} は
     * {@code shadowRadius} をそのまま返す)ので、ここで掛ける。
     */
    @Override
    protected float getShadowRadius(SporophyllEntity entity) {
        return this.shadowRadius * entity.getScale();
    }
}
