package com.yayuy.overgrownvegetation.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * G1 の感染体のレンダラー(2026-10-09 の作り直し)。大きさはバニラのレンダラーと同じ(村人 0.9375 など)にして、
 * 崩れ落ち → 死体 → 起き上がりで大きさが跳ねないようにする。
 */
public class G1GeoRenderer<T extends LivingEntity & GeoEntity> extends GeoEntityRenderer<T> {

    public G1GeoRenderer(EntityRendererProvider.Context context, String id, float scale, float shadow) {
        this(context, id, scale, shadow, false);
    }

    /** @param translucent 半透明で描く(胞子嚢のある寄生されたガスト・ブレイズ、2026-10-09) */
    public G1GeoRenderer(EntityRendererProvider.Context context, String id, float scale, float shadow, boolean translucent) {
        this(context, new G1GeoModel<>(id, translucent), scale, shadow);
    }

    /** 骨を手で動かす種のモデル(寄生されたブレイズの {@link ParasitizedBlazeModel}、10-09 夜)。 */
    public G1GeoRenderer(EntityRendererProvider.Context context, G1GeoModel<T> model, float scale, float shadow) {
        super(context, model);
        this.shadowRadius = shadow;
        if (scale != 1.0F) {
            withScale(scale);
        }
    }
}
