package com.yayuy.overgrownvegetation.survival.decon;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * <b>地脈の洗い場</b>のアイテム(2026-11-22)。置いた洗い場と同じ GeckoLib の模型を止まった姿で描く(地形の模型の水の面が無いので、模型の水の箱を見せる。描画は
 * {@code client/decon/WasherItemRenderer}、大きさは {@code models/item/ley_washer.json} の display)。置き方はふつうのブロックと同じ。
 */
public class WasherBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public WasherBlockItem(Block block, Properties properties) {
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
                    renderer = new com.yayuy.overgrownvegetation.client.decon.WasherItemRenderer();
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
