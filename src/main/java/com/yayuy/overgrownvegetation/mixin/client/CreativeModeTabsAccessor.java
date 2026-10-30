package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

/**
 * クリエイティブタブの中身を次に開いたときに組み直させる口(2026-10-09、実験的要素の切り替え)。
 * バニラは {@code CACHED_PARAMETERS}(前に組んだときの機能フラグ・OP・レジストリ)と同じなら組み直さないので、null にして忘れさせる。
 * {@link com.yayuy.overgrownvegetation.client.difficulty.ClientExperimentalFeatures} だけが使う。
 */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {

    @Accessor("CACHED_PARAMETERS")
    static void overgrown_vegetation$setCachedParameters(@Nullable CreativeModeTab.ItemDisplayParameters parameters) {
        throw new AssertionError();
    }
}
