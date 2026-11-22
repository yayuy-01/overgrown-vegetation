package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class BlightFernRenderer extends GeoEntityRenderer<BlightFernEntity> {

    // 10-23 萌芽体のモデル(体格 1、根の脚が半径約 0.9 ブロックに広がる)で 0.8 に(仮決め)
    /**
     * 影の半径(仮決め、旧 2.0)。バニラの影は毎フレーム、半径ぶんの正方形の列 × 高さ 2〜3 段のブロックを
     * 1 つずつ調べる(2.0 だと 5×5×3 = 75 ブロック、1.0 なら 3×3×2 = 18 ブロック)。汚染地帯ではカメラから
     * 16 ブロック以内にシダが何十本も立つので効く。葉先は幹から約 3.7 ブロックまで広がるが、6 枚の葉が
     * 重なって密なのは最初の 1 節(幹から約 1.3 ブロック)までなので、その内側の 1.0 にした。
     * GeoEntityRenderer は SCALE で影を広げないので、体格 2.5 倍を掛けた後の値をそのまま書く。
     */
    private static final float SHADOW_RADIUS = 0.8F;

    public BlightFernRenderer(EntityRendererProvider.Context context) {
        super(context, new BlightFernModel());
        this.shadowRadius = SHADOW_RADIUS;
    }
}
