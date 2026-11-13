package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.survival.room.RoomCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ブロックが変わったら、部屋の判定の覚えを捨てる(2026-11-13 サバイバルの背骨 段 2a)。
 *
 * <p>{@code Level#setBlock} の通知({@code markAndNotifyBlock})の終わりに必ず呼ばれる {@code onBlockStateChange} に刺す。
 * プレイヤーが置いた・壊した(NeoForge のスナップショットの後の通知)・ドアの開け閉め・爆発・ピストン・コマンドもここを通る。
 * 中身は覚えが空なら即帰る・空気の通り方の印が同じなら帰るので軽い。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRoomInvalidateMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void overgrown_vegetation$forgetRooms(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        RoomCache.onBlockChanged((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
