package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.ClientEarthquake;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地割れ(地震)演出中、カメラに小刻みな揺れを乗せる。
 *
 * <p>{@link Camera#setup} の最後(バニラが視点の位置・向きを確定させた直後)に割り込み、
 * {@link ClientEarthquake#shakeIntensity} が返す強度に応じて向き(pitch/yaw)を数度ぶらし、
 * 位置をわずかに揺らす。強度 0(演出外・遠すぎ)なら何もしない。
 *
 * <p>揺れの位相は実時間({@code System.nanoTime()})から作った複数の正弦波の和なので、
 * フレームレートに依存せず、ゲームのポーズ中も破綻しない(強度側が 0 になる)。
 */
@Mixin(Camera.class)
public abstract class CameraShakeMixin {

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    private Vec3 position;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void move(float distanceOffset, float verticalOffset, float horizontalOffset);

    @Inject(method = "setup", at = @At("TAIL"))
    private void overgrownvegetation$earthquakeShake(BlockGetter level, Entity entity, boolean detached,
                                                     boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        float amp = ClientEarthquake.shakeIntensity(this.position.x, this.position.z, partialTick);
        if (amp <= 0.001F) {
            return;
        }

        double t = (System.nanoTime() % 100_000_000_000L) / 1.0e9D;
        float dPitch = amp * ((float) Math.sin(t * 37.0D) * 0.6F + (float) Math.sin(t * 61.0D) * 0.4F) * 1.7F;
        float dYaw = amp * ((float) Math.sin(t * 29.0D) * 0.6F + (float) Math.sin(t * 53.0D) * 0.4F) * 1.3F;
        this.setRotation(this.yRot + dYaw, Mth.clamp(this.xRot + dPitch, -90.0F, 90.0F));

        float shHoriz = amp * (float) Math.sin(t * 44.0D) * 0.13F;
        float shVert = amp * (float) Math.sin(t * 51.0D) * 0.10F;
        this.move(0.0F, shVert, shHoriz);
    }
}
