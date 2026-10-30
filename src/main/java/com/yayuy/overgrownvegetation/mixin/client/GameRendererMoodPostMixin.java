package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.light.MoodPost;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 画面の色の調子(LUT + ビネット、{@link MoodPost})を、世界と手を描き終えた後・バニラの後処理(スペクテイターの見え方など)と GUI の前に掛ける
 * (2026-10-21 試作)。{@code RenderLevelStageEvent.AFTER_LEVEL} は手より前、{@code RenderGuiEvent} は GUI を隠すと来ないので、ここに刺す。
 * Sodium・Iris は {@code GameRenderer#render} を置き換えない。
 */
@Mixin(GameRenderer.class)
public class GameRendererMoodPostMixin {

    @Shadow
    @Final
    Minecraft minecraft;

    @Inject(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V", shift = At.Shift.AFTER))
    private void overgrown_vegetation$moodGrade(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        MoodPost.render(this.minecraft);
    }
}
