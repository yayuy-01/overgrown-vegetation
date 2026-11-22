package com.yayuy.overgrownvegetation.survival.vent;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * <b>地脈の換気機</b>のアイテム(2026-11-22 3 ブロックの機械)。
 * <ul>
 *     <li>見た目: 置いた機械と同じ GeckoLib の模型を縮めて描く(手に持つ・インベントリで形が分かるように。描画は
 *     {@code client/vent/VentItemRenderer}、大きさは {@code models/item/ley_ventilator.json} の display)。止まった姿のまま動かさない。</li>
 *     <li>上に 2 マスの空きがなくて置けないときは、訳を短く出す(アイテムは減らない)。</li>
 * </ul>
 */
public class VentBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VentBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        InteractionResult result = super.place(ctx);
        if (!result.consumesAction() && ctx.getPlayer() != null && !ctx.getLevel().isClientSide) {
            BlockPlaceContext c = updatePlacementContext(ctx);
            if (c != null && c.canPlace()) {
                BlockState lower = getBlock().defaultBlockState().setValue(VentBlock.FACING, c.getHorizontalDirection().getOpposite());
                if (!VentBlock.hasRoom(c.getLevel(), c.getClickedPos(), c, lower)) {
                    ctx.getPlayer().displayClientMessage(Component.translatable("message.overgrown_vegetation.ley_ventilator.no_room"), true);
                }
            }
        }
        return result;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (renderer == null) {
                    renderer = new com.yayuy.overgrownvegetation.client.vent.VentItemRenderer();
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
