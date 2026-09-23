package com.yayuy.overgrownvegetation.item;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * 「スカトゥリウムの外骨格」防具一式({@code exoskeleton_*})。
 *
 * <p>アイテムアイコンも平らなスプライトではなく同じ立体モデルで描く
 * ({@link com.yayuy.overgrownvegetation.client.ScaturiumExoskeletonArmorItemRenderer})。
 *
 * <p>バニラの平らな防具レイヤー(textures/models/armor/&lt;set&gt;_layer_{1,2}.png)ではなく、
 * GeckoLib の立体モデルで描画する。装備した部位に対応するボーンだけが表示される
 * ({@code armorHead} / {@code armorBody}+{@code armorRightArm}/{@code armorLeftArm} /
 * {@code armorLeftLeg}+{@code armorRightLeg})。
 *
 * <p><b>ブーツはアイテムごと存在しない</b>(2026-09-16 に削除)。モデルの {@code armorLeftBoot} /
 * {@code armorRightBoot} は子もキューブも無い空ボーンで、履いても見た目が一切付かなかったため。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/armor/scaturium_exoskeleton.geo.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/armor/scaturium_exoskeleton.png}</li>
 * </ul>
 *
 * <p>アニメーションはしない静的モデル。{@link #createGeoRenderer} 内の匿名クラスはクライアントでのみ
 * 遅延生成される({@link GeoCorpseBlockItem} と同じ作り)ので、専用サーバーでクライアント専用クラスが
 * 読み込まれることはない。防具値・耐久は他の防具と同じく {@code gear.json} 側。
 */
public class ScaturiumExoskeletonArmorItem extends ArmorItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ScaturiumExoskeletonArmorItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private HumanoidModel<?> renderer;
            private net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer itemRenderer;

            /** インベントリ/手持ちのアイコンも、着用時と同じ立体モデルで描く。 */
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.itemRenderer == null) {
                    this.itemRenderer = new com.yayuy.overgrownvegetation.client.ScaturiumExoskeletonArmorItemRenderer();
                }
                return this.itemRenderer;
            }

            @Override
            public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(@Nullable T livingEntity, ItemStack itemStack,
                    @Nullable EquipmentSlot equipmentSlot, @Nullable HumanoidModel<T> original) {
                if (this.renderer == null) {
                    this.renderer = new com.yayuy.overgrownvegetation.client.ScaturiumExoskeletonArmorRenderer();
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
