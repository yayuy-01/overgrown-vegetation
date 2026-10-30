package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Slot} の位置({@code public final int x / y})を書き換える口。持ち物の画面の左手の枠を作業枠の下へ動かすのと、
 * クリエイティブの持ち物タブで追加防具の枠を置き直すのに使う(2026-10-15、追加防具の枠)。
 */
@Mixin(Slot.class)
public interface SlotPositionAccessor {

    @Mutable
    @Accessor("x")
    void overgrown_vegetation$setX(int x);

    @Mutable
    @Accessor("y")
    void overgrown_vegetation$setY(int y);
}
