package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * クリーパーの導火の長さ({@code maxSwell}、バニラ 30 tick)を書く口。莢持ち(G1 の作り直し 第 1 段)の「導火が少し長い」に使う。
 * {@code maxSwell} は Creeper 自身のフィールドなので、宣言しているクラスへの Accessor で届く。
 */
@Mixin(Creeper.class)
public interface CreeperAccessor {

    @Accessor("maxSwell")
    void overgrown$setMaxSwell(int ticks);
}
