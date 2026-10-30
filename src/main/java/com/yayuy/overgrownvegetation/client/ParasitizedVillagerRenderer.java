package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ParasitizedVillager;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「寄生された村人」のレンダラー(2026-10-09)。バニラの村人と同じ 0.9375 倍で描く(死体ブロック・崩れ落ちと大きさをそろえる。
 * 死体から起き上がるとき・崩れ落ちから死体へ変わるときに大きさが跳ねないように)。
 */
public class ParasitizedVillagerRenderer extends GeoEntityRenderer<ParasitizedVillager> {

    public ParasitizedVillagerRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedVillagerModel());
        this.shadowRadius = 0.5F;
        withScale(0.9375F);
    }
}
