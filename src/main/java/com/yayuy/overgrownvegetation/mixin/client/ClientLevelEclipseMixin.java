package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.yayuy.overgrownvegetation.client.worldevent.Eclipse;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 日食(ワールドイベント。2026-11-16、{@link Eclipse})の間、<b>クライアントの描画が読む</b>空の明るさ・色を夜へ寄せる(ユーザー決定: 夜と同じ真っ暗・星が出る)。
 * どれも {@code ClientLevel} だけにある描画用のメソッドで、サーバーの空の暗さ({@code Level.getSkyDarken()}。湧き・Mob の昼夜の判定)とは別
 * —— 敵対 Mob の動きは変わらない(ユーザー決定: 見た目だけ)。
 *
 * <p>{@code require = 0}: ほかの MOD が差し替えていて差せなくても起動は止めない(そのときは日食で暗くならないだけ)。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelEclipseMixin {

    /** ライトマップの空の明るさ: 夜は 0.2。 */
    @ModifyReturnValue(method = "getSkyDarken(F)F", at = @At("RETURN"), require = 0)
    private float overgrown_vegetation$eclipseSkyDarken(float value, float partialTick) {
        float d = Eclipse.dark(partialTick);
        return d > 0.0F && value > 0.2F ? value + (0.2F - value) * d : value;
    }

    /** 空の色: 夜は黒。 */
    @ModifyReturnValue(method = "getSkyColor", at = @At("RETURN"), require = 0)
    private Vec3 overgrown_vegetation$eclipseSkyColor(Vec3 value, Vec3 pos, float partialTick) {
        float d = Eclipse.dark(partialTick);
        return d > 0.0F ? value.scale(1.0F - d) : value;
    }

    /** 雲の色: 夜は 1 割ほどの明るさ。 */
    @ModifyReturnValue(method = "getCloudColor", at = @At("RETURN"), require = 0)
    private Vec3 overgrown_vegetation$eclipseCloudColor(Vec3 value, float partialTick) {
        float d = Eclipse.dark(partialTick);
        return d > 0.0F ? value.scale(1.0F - 0.9F * d) : value;
    }

    /** 星: 夜は 0.5。 */
    @ModifyReturnValue(method = "getStarBrightness", at = @At("RETURN"), require = 0)
    private float overgrown_vegetation$eclipseStars(float value, float partialTick) {
        float d = Eclipse.dark(partialTick);
        return d > 0.0F ? Math.max(value, 0.5F * d * d) : value;
    }
}
