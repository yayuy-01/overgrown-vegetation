package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 監視塔フィロセンスの描画(2026-11-14)。描画倍率は掛けない(画素の大きさを揃える決まり)。発光マスク {@code phyllosens_glowmask.png} は
 * 花の芯 core だけ。芯は閉じている間アニメの倍率 0 で隠れる(+ PhyllosensModel が隠す)ので、光るのは見ている間だけ(仕様 4 節)。
 */
public class PhyllosensRenderer extends GeoEntityRenderer<PhyllosensEntity> {

    public PhyllosensRenderer(EntityRendererProvider.Context context) {
        super(context, new PhyllosensModel());
        this.shadowRadius = 1.6F;
        addRenderLayer(new G1Glow<>(this, e -> false));
    }

    /** バニラの「倒れると横へ 90° 転がる」を切る(倒れる形はアニメ death の仕事)。 */
    @Override
    protected float getDeathMaxRotation(PhyllosensEntity animatable) {
        return 0.0F;
    }
}
