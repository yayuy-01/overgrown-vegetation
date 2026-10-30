package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.item.ExtraArmorContainer;
import com.yayuy.overgrownvegetation.mixin.SlotPositionAccessor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * クリエイティブの持ち物タブで、追加防具の 4 枠(2026-10-15)を置き直す。バニラの {@code selectTab} は枠の番号 46 以降を
 * 持ち物の升目の計算で並べるので、そのままだと持ち物の上に重なる。バニラの防具の 2 列(x 54・108)の右に、同じ形で並べる
 * (頭 132,6 / 体 132,33 / 腰 150,6 / 足 150,33。バニラの防具の列と 5 ドット空けて、境目が分かるように)。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryExtraArmorMixin {

    private static final int[][] POSITIONS = {{132, 6}, {132, 33}, {150, 6}, {150, 33}};

    @Inject(method = "selectTab", at = @At("TAIL"))
    private void overgrown_vegetation$placeExtraArmorSlots(CreativeModeTab tab, CallbackInfo ci) {
        int n = 0;
        for (Slot slot : ((AbstractContainerScreen<?>) (Object) this).getMenu().slots) {
            if (slot.container instanceof ExtraArmorContainer && n < POSITIONS.length) {
                ((SlotPositionAccessor) slot).overgrown_vegetation$setX(POSITIONS[n][0]);
                ((SlotPositionAccessor) slot).overgrown_vegetation$setY(POSITIONS[n][1]);
                n++;
            }
        }
    }
}
