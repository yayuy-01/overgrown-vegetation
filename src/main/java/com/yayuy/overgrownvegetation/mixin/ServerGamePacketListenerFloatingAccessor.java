package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * プレイヤーが浮いている tick の数({@code aboveGroundTickCount}、private)を戻す口(2026-10-22、上昇気流機)。
 *
 * <p>専用サーバー(allow-flight = false)は、足場の無い所で 80 tick を超えて浮いているプレイヤーを「浮きすぎ」で切断する。
 * 上昇気流機の柱(高さ 32)を昇るとそれを超えるので、柱の中のプレイヤーは毎 tick 0 に戻す
 * ({@link com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity#serverTick})。
 * {@code aboveGroundTickCount} は {@code ServerGamePacketListenerImpl} 自身に宣言されているフィールド。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerFloatingAccessor {

    @Accessor("aboveGroundTickCount")
    void overgrown_vegetation$setAboveGroundTickCount(int ticks);
}
