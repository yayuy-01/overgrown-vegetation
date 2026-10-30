package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * 「アクムネス」の描画。目の光({@code acumnes_glowmask.png})を重ねる。
 *
 * <p>照準線は描かない(2026-09-28 ユーザー指定「照準線は消せますか？」。runClient で見て、概ね大丈夫の上で消した)。
 * 狙いの予兆は、針を回して前へ向け止まって震えるアニメ(aim / aim_v、2 秒)と狙い始めの音だけ。
 * 撃つまでの時間・射線を切られたら取りやめる仕組みはそのまま({@link AcumnesEntity})。
 */
public class AcumnesRenderer extends GeoEntityRenderer<AcumnesEntity> {

    public AcumnesRenderer(EntityRendererProvider.Context context) {
        super(context, new AcumnesModel());
        this.shadowRadius = 0.9F;
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    /** 倒れる動き(death の仰向け・death_aim の横倒し)はアニメが持っているので、バニラの横転を切る(ほかの G5 と同じ)。 */
    @Override
    protected float getDeathMaxRotation(AcumnesEntity animatable) {
        return 0.0F;
    }
}
