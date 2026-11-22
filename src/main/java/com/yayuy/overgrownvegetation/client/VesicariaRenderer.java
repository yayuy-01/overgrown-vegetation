package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.crawler.SmallCrawlerRenderer;
import com.yayuy.overgrownvegetation.entity.VesicariaEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * ヴェシカリアの描画(2026-11-15 段 2)。「小さな地這いの虫」の型の描画に、袋の脈の光({@link MaskGlowLayer})を重ねる。
 * 弾けて死んだ後(しぼんだ姿で {@link VesicariaEntity#REMOVE_TICKS} tick 残る)は、バニラの死の赤い色を掛けない(傷を受けた瞬間の赤はそのまま)。
 */
public class VesicariaRenderer extends SmallCrawlerRenderer<VesicariaEntity> {

    public VesicariaRenderer(EntityRendererProvider.Context context) {
        super(context, new VesicariaModel(), VesicariaEntity.RENDER_SCALE, 0.5F);
        this.addRenderLayer(new MaskGlowLayer<>(this, MaskGlowLayer.mask("vesicaria_glowmask"), com.yayuy.overgrownvegetation.entity.VesicariaEntity::sacGlow));
    }

    @Override
    public int getPackedOverlay(VesicariaEntity animatable, float u, float partialTick) {
        return OverlayTexture.pack(OverlayTexture.u(u), OverlayTexture.v(animatable.hurtTime > 0 && !animatable.isDeadOrDying()));
    }
}
