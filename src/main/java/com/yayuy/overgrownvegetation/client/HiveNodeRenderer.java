package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ハイヴノード」の描画。
 *
 * <p>発光レイヤー({@code AutoGlowingGeoLayer} + {@code _glowmask})は 2026-09-18 に外した。GeckoLib のマスクは
 * 「色が 0 でない画素」をすべて発光側へ移すので、不透明な黒で塗ったマスクだと網({@code ami})の穴が真っ黒になる。
 * 光る点はテクスチャの移り変わり(hive_node.png の 8 コマ)で表現し、明るさは {@link HiveNodeEntity} が
 * 小部屋に置く光源ブロック(明るさ 15)に任せる。
 */
public class HiveNodeRenderer extends GeoEntityRenderer<HiveNodeEntity> {

    public HiveNodeRenderer(EntityRendererProvider.Context context) {
        super(context, new HiveNodeModel());
        this.shadowRadius = 0.0F; // 小部屋の中で浮いているので影は落とさない
    }
}
