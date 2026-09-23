package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.HiveRitualClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 寄生植物装備の儀式の間、乗っている本人の視点を蔦モデルの {@code camera} 骨(head の子)の位置・向きに置き換える。
 * {@link Camera#setup} の最後(バニラが三人称の位置を決めた直後)で上書きする。
 * 値は {@link HiveRitualClient} が描画のたびに更新している(1 フレーム前の骨の位置)。
 */
@Mixin(Camera.class)
public abstract class HiveRitualCameraMixin {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void overgrownvegetation$hiveRitualCamera(BlockGetter level, Entity entity, boolean detached,
                                                      boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!HiveRitualClient.wantsCamera(entity)) {
            return;
        }
        this.setRotation(HiveRitualClient.cameraYaw(), HiveRitualClient.cameraPitch());
        this.setPosition(HiveRitualClient.cameraX(), HiveRitualClient.cameraY(), HiveRitualClient.cameraZ());
    }
}
