package com.yayuy.overgrownvegetation.ally;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

/**
 * HK416(2026-10-01、仮)。同盟軍の兵士が持つ小銃。いまは見た目だけ(撃てない)—— 兵士の戦い方と、プレイヤーが使う銃の仕組みは後で。
 * モデルは GeckoLib の geo({@code tools/hk416} が作る)。手に持ったときの大きさ・向きは {@code models/item/hk416.json} の display。
 *
 * <p>描画の匿名クラスはクライアントでだけ作られる(専用サーバーでクライアントのクラスを読まない)。
 */
public class Hk416Item extends Item implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public Hk416Item(Properties properties) {
        super(properties);
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new com.yayuy.overgrownvegetation.ally.client.Hk416Renderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // まだアニメは無い(撃つ・弾倉を替える動きは銃の仕組みと一緒に)
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.hk416.desc").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
