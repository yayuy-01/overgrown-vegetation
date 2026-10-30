package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.ClientStorm;
import com.yayuy.overgrownvegetation.client.ClimaxJungle;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 極相の密林の「雨が林床に届かない」(2026-10-16 ユーザー指定、極相の 8 案の (8)): 林冠の下にいる間はバニラの雨の筋を描かない
 * ({@link ClimaxJungle#hideRain})。代わりに {@link ClimaxJungle} が光る滴を落とす。雨音・しぶき・湧きなどはバニラのまま(見た目だけ)。
 *
 * <p>胞子の嵐(2026-10-23 胞子の場 H): 雷雨の間は雨粒を胞子混じりの緑に染める(粒の数・絵はそのまま、色だけ。
 * 雨の筋の影の色 = シェーダーの色の掛け算)。描き終えたら元の色へ戻す(ここで止めたときは染めない)。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererClimaxRainMixin {

    @Unique
    private boolean overgrown_vegetation$tinted;

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$noRainUnderClimaxCanopy(CallbackInfo ci) {
        if (ClimaxJungle.hideRain()) {
            ci.cancel();
            return;
        }
        float storm = ClientStorm.sporeStorm();
        overgrown_vegetation$tinted = storm > 0.01F;
        if (overgrown_vegetation$tinted) {
            RenderSystem.setShaderColor(1.0F - 0.35F * storm, 1.0F, 1.0F - 0.5F * storm, 1.0F);
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("RETURN"))
    private void overgrown_vegetation$untintRain(CallbackInfo ci) {
        if (overgrown_vegetation$tinted) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            overgrown_vegetation$tinted = false;
        }
    }
}
