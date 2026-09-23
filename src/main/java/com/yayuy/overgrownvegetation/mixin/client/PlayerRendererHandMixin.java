package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.client.ErosionFirstPerson;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一人称の腕にも浸食度の見た目を出す(2026-09-22)。
 *
 * <p>{@code PlayerRenderer#renderHand} は腕と袖のパーツを<b>直接</b>描く経路で、
 * レンダーレイヤー({@code ErosionPlayerLayer})を通らない。ここへ差し込まないと
 * 三人称では苔むしているのに<b>自分の手だけきれいなまま</b>になる。
 *
 * <p>差し込む位置は {@code TAIL} —— バニラが腕と袖を描き終えたあと。
 * NeoForge の {@code RenderArmEvent} は腕を描く<b>前</b>に鳴るので、そこで重ねると
 * 同じ位置に後からバニラの腕が描かれて競合する。
 *
 * <p>左右の別は、渡されたパーツがレンダラーのモデルの右腕かどうかで見分ける
 * ({@code renderRightHand} / {@code renderLeftHand} がそれぞれ自分のパーツを渡してくる)。
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererHandMixin {

    @Inject(method = "renderHand", at = @At("TAIL"))
    private void overgrown_vegetation$erosionHand(PoseStack poseStack, MultiBufferSource buffer,
                                                         int combinedLight, AbstractClientPlayer player,
                                                         ModelPart rendererArm, ModelPart rendererArmwear,
                                                         CallbackInfo ci) {
        PlayerRenderer self = (PlayerRenderer) (Object) this;
        boolean rightArm = rendererArm == self.getModel().rightArm;
        ErosionFirstPerson.render(poseStack, buffer, combinedLight, player,
                rendererArm, rendererArmwear, rightArm);
    }
}
