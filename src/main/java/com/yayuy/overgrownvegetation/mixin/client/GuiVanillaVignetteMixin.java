package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.light.MoodPost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 画面の色の調子のビネット({@link MoodPost})が掛かっている間は、バニラの GUI のビネット(画面の四隅を暗くする絵)を描かない(2026-10-21 夜、
 * ユーザー「置き換えて問題なければバニラのビネットを消して自前だけ残す」)。ワールドの境の赤い警告はバニラのビネットで出るので、
 * 境に近い間はバニラのまま描く(式は {@code Gui#renderVignette} と同じ)。
 */
@Mixin(Gui.class)
public class GuiVanillaVignetteMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$replaceVignette(GuiGraphics guiGraphics, @Nullable Entity entity, CallbackInfo ci) {
        if (!MoodPost.drawsVignette(this.minecraft)) {
            return;
        }
        if (entity != null && this.minecraft.level != null) {
            WorldBorder border = this.minecraft.level.getWorldBorder();
            double distance = border.getDistanceToBorder(entity);
            double lerp = Math.min(border.getLerpSpeed() * (double) border.getWarningTime() * 1000.0,
                    Math.abs(border.getLerpTarget() - border.getSize()));
            if (distance < Math.max((double) border.getWarningBlocks(), lerp)) {
                return;
            }
        }
        ci.cancel();
    }
}
