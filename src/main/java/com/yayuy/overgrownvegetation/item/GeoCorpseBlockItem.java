package com.yayuy.overgrownvegetation.item;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * 種族専用の GeckoLib 立体モデルを持つ死体ブロック
 * ({@link com.yayuy.overgrownvegetation.block.GeoCorruptedCorpseBlock}: 羊・豚・クリーパー・クモ)の
 * 設置アイテム。手持ち/インベントリ/落下アイテムでもブロックと同じ立体モデルを描画する
 * (通常のブロックアイテムの平らなアイコンの代わり)。
 *
 * <p>描画は GeckoLib の {@code GeoItemRenderer}(= {@code BlockEntityWithoutLevelRenderer})。
 * どの死体かは {@link #getBlock()} で判別できるので、モデル/テクスチャは
 * {@code CorruptedCorpseItemModel} が {@link com.yayuy.overgrownvegetation.client.CorruptedCorpseModel}
 * の対応表から引く。アイテムモデル JSON は {@code builtin/entity}(= カスタムレンダラー扱い)にして
 * あり、GUI/手持ちでの位置・大きさの微調整はその {@code display} 部分で行う。
 *
 * <p>アニメーションはしない静的モデル。{@link #createGeoRenderer} 内の匿名クラスはクライアントでのみ
 * 遅延生成される(GeckoLib の {@code AnimatableInstanceCache} が {@code isPhysicalClient()} を確認して
 * から呼ぶ)ので、専用サーバーでクライアント専用クラスが読み込まれることはない。
 */
public class GeoCorpseBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public GeoCorpseBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new com.yayuy.overgrownvegetation.client.CorruptedCorpseItemRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 静的モデルなのでアニメーションコントローラーは無し。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
