package com.yayuy.overgrownvegetation.client.vent;

import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 地脈の換気機の描画(2026-11-22 3 ブロックの機械)。本体(下のブロック)の中身が 3 ブロック分の模型を描く。向きはブロックの
 * {@code facing} を GeckoLib が読んで回す(北 = 模型のまま、正面 = Blockbench の -Z)。
 */
public class VentRenderer extends GeoBlockRenderer<VentBlockEntity> {

    public VentRenderer() {
        super(new VentModel<>());
    }

    /** 模型は高さ 3 ブロック(排気の口が少し上へ出る)。本体の 1 ブロックの箱のままだと、下が画面の外へ出たとき上まで消える。 */
    @Override
    public AABB getRenderBoundingBox(VentBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).expandTowards(0.0D, 2.2D, 0.0D);
    }
}
