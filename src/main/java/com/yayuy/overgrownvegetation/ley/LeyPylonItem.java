package com.yayuy.overgrownvegetation.ley;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * <b>地脈の柱</b>のアイテム(2026-11-22)。置いた柱と同じ GeckoLib の模型を描く(電池の箱は隠す。描画は
 * {@code client/ley/LeyPylonItemRenderer}、大きさは {@code models/item/ley_pylon.json} の display)。置き方はふつうのブロックと同じ。
 */
public class LeyPylonItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public LeyPylonItem(Block block, Properties properties) {
        super(block, properties);
    }

    // 1.20.1: GeckoLib 4.8.4 は GeoRenderProvider を持たない。Forge の IClientItemExtensions で描き手を渡す
    @Override
    public void initializeClient(Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new com.yayuy.overgrownvegetation.client.ley.LeyPylonItemRenderer();
                }
                return renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
