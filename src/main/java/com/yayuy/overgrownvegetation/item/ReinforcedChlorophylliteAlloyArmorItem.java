package com.yayuy.overgrownvegetation.item;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
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
 * 「強化クロロフィライト合金装備」({@code reinforced_chlorophyllite_alloy_*})— 最上位の防具一式。
 *
 * <p>防御力・耐久はネザライトの 1.5 倍({@code gear.json} の
 * {@code armor.reinforced_chlorophyllite_alloy})。さらに<b>過繫茂 Mob(グループ1〜10)から受ける
 * ダメージを 1 部位につき 10%、4 部位そろえて 40% 減らす</b>
 * ({@link com.yayuy.overgrownvegetation.phase.OvergrowthWardDamage} /
 * タグ {@code #overgrown_vegetation:overgrowth_resistant})。
 *
 * <p>バニラの平らな防具レイヤーではなく GeckoLib の立体モデルで描画する。
 * アイテムアイコンも同じモデル
 * ({@link com.yayuy.overgrownvegetation.client.ReinforcedChlorophylliteAlloyArmorItemRenderer})。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/armor/reinforced_chlorophyllite_alloy.geo.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/armor/reinforced_chlorophyllite_alloy.png}</li>
 * </ul>
 *
 * <p>アニメーションはしない静的モデル。{@link #createGeoRenderer} 内の匿名クラスはクライアントでのみ
 * 遅延生成される({@link ScaturiumExoskeletonArmorItem} と同じ作り)ので、専用サーバーで
 * クライアント専用クラスが読み込まれることはない。
 */
public class ReinforcedChlorophylliteAlloyArmorItem extends ArmorItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ReinforcedChlorophylliteAlloyArmorItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private HumanoidModel<?> renderer;
            private BlockEntityWithoutLevelRenderer itemRenderer;

            /** インベントリ/手持ちのアイコンも、着用時と同じ立体モデルで描く。 */
            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.itemRenderer == null) {
                    this.itemRenderer =
                            new com.yayuy.overgrownvegetation.client.ReinforcedChlorophylliteAlloyArmorItemRenderer();
                }
                return this.itemRenderer;
            }

            @Override
            public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(@Nullable T livingEntity, ItemStack itemStack,
                    @Nullable EquipmentSlot equipmentSlot, @Nullable HumanoidModel<T> original) {
                if (this.renderer == null) {
                    this.renderer =
                            new com.yayuy.overgrownvegetation.client.ReinforcedChlorophylliteAlloyArmorRenderer();
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
