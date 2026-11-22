package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.MeltedIce;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 汚染された地面・覆ったマスの隣の水は凍らない(2026-10-16 ユーザー指定「汚染地域に氷が残ってしまう」)。
 *
 * <p>汚染が届いた氷は溶ける({@link MeltedIce})が、寒いバイオームのままの所ではバニラの天気の処理が岸の水からまた凍らせ、
 * 岸で「溶ける → 凍る」を繰り返してしまう。バニラが凍らせてよいと答えたときだけ隣を見る(ほとんどの呼び出しは暖かいバイオームで
 * 先に偽が返るので軽い)。世界生成({@code WorldGenRegion})は対象外。
 */
@Mixin(Biome.class)
public abstract class BiomeFreezeNearCorruptionMixin {

    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At("RETURN"), cancellable = true)
    private void overgrown_vegetation$noFreezeNearCorruption(LevelReader level, BlockPos water, boolean mustBeAtEdge,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && level instanceof ServerLevel server && MeltedIce.nextToCorruption(server, water)) {
            cir.setReturnValue(false);
        }
    }
}
