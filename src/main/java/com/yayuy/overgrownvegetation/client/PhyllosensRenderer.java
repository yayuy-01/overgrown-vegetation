package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「フィロセンス」の描画。描画倍率は掛けない(画素の大きさを揃える決まり)。発光マスク {@code phyllosens_glowmask.png}(花の芯)は
 * 見張り・焼く光の間だけ光る決まり(仕様 4 節)なので、歩くだけの段 1 では重ねない。
 * 影は出さない: エンティティの位置は胴(地面から 2.5〜6.5 上)で、バニラの影は位置の真下の近くにしか落ちない。
 */
public class PhyllosensRenderer extends GeoEntityRenderer<PhyllosensEntity> {

    public PhyllosensRenderer(EntityRendererProvider.Context context) {
        super(context, new PhyllosensModel());
        this.shadowRadius = 0.0F;
        // 段 2(11-14): 花の芯は見張り・覗きで見ている間だけ光る(仕様 4 節)。マスクの光る画素は 384 で空ではない
        addRenderLayer(new G1Glow<>(this, e -> !e.gaze().glowing()));
    }

    /** バニラの「倒れると横へ 90° 転がる」を切る(13 ブロックの体が横倒しで地面に埋まる。倒れる形はアニメ death の仕事)。 */
    @Override
    protected float getDeathMaxRotation(PhyllosensEntity animatable) {
        return 0.0F;
    }
}
