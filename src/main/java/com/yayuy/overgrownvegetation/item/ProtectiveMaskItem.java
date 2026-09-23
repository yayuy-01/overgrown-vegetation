package com.yayuy.overgrownvegetation.item;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
 * 「防護マスク」— 頭装備。被っている間、プレイヤーは「胞子感染」の<b>胞子感染1 までしかかからない</b>
 * (胞子感染2/3 への進行は、経路を問わず {@link com.yayuy.overgrownvegetation.effect.SporeInfectionEffects#infect}
 * が胞子感染1 に丸める)。防具値などは {@code gear.json} の {@code armor.protective_mask}(無ければ汎用値)。
 *
 * <p>バニラの平らな防具レイヤー(textures/models/armor/&lt;set&gt;_layer_1.png)ではなく、
 * GeckoLib の立体モデルで描画する({@code armorHead} ボーンだけに中身が入っている)。
 * アイテムアイコンも同じモデルで描く
 * ({@link com.yayuy.overgrownvegetation.client.ProtectiveMaskItemRenderer})。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/armor/protective_mask.geo.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/armor/protective_mask.png}</li>
 * </ul>
 *
 * <p>アニメーションはしない静的モデル。{@link #createGeoRenderer} 内の匿名クラスはクライアントでのみ
 * 遅延生成される({@link ScaturiumExoskeletonArmorItem} と同じ作り)ので、専用サーバーで
 * クライアント専用クラスが読み込まれることはない。
 */
public class ProtectiveMaskItem extends ArmorItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ProtectiveMaskItem(Holder<ArmorMaterial> material, Properties properties) {
        super(material, Type.HELMET, properties);
    }

    /** {@code player} が防護マスクを被っているか。 */
    public static boolean isWearing(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ProtectiveMaskItem;
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
                    this.itemRenderer = new com.yayuy.overgrownvegetation.client.ProtectiveMaskItemRenderer();
                }
                return this.itemRenderer;
            }

            @Override
            public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(@Nullable T livingEntity, ItemStack itemStack,
                    @Nullable EquipmentSlot equipmentSlot, @Nullable HumanoidModel<T> original) {
                if (this.renderer == null) {
                    this.renderer = new com.yayuy.overgrownvegetation.client.ProtectiveMaskArmorRenderer();
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
