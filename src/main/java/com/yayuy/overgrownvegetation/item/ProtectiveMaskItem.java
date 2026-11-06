package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModArmorMaterials;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

/**
 * 「防護マスク」— 頭装備(2026-11-04 に parked から戻して書き直した)。
 *
 * <h2>11-04 ユーザー決定</h2>
 * <ul>
 *     <li>耐久値を持ち、濃度が高いほど速く減る。<b>耐久値がある間は胞子感染にかからない</b>(暴露を積まない)。</li>
 * </ul>
 * <h2>仮決め</h2>
 * <ul>
 *     <li>耐久 = フィルターが吸い取れる量 {@code spore_field.json} の exposure.maskCapacity(18000 %秒)。毎秒 その場の濃度 % × 秒 減り、
 *     5% 未満では減らない({@code SporeExposureTracker#wearMask})。10% の森で 30 分・50% で 6 分・100% で 3 分。</li>
 *     <li>耐久が残っている間は暴露を積まず、感染中でも加速しない(10 分ごとの昇格はそのまま)。</li>
 *     <li>0 になっても外れず壊れず、効かなくなるだけ。吸い取った量はデータコンポーネント {@link ModDataComponents#MASK_FILTER_USED} に持ち、
 *     バニラの耐久(殴られて減る・壊れる)は使わない。耐久のバーは自前で出す。直す手段はまだ無い。</li>
 * </ul>
 * 描画は GeckoLib の立体モデル({@code geo/armor/protective_mask.geo.json}、アイコンも同じモデル)。
 */
public class ProtectiveMaskItem extends ArmorItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ProtectiveMaskItem(Properties properties) {
        super(ModArmorMaterials.PROTECTIVE_MASK, Type.HELMET, properties);
    }

    /** {@code entity} が防護マスクを被っているか(フィルターの残りを問わない)。 */
    public static boolean isWearing(LivingEntity entity) {
        return entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof ProtectiveMaskItem;
    }

    /** フィルターの残ったマスクを被っていて、胞子を防いでいるか。 */
    public static boolean isProtecting(LivingEntity entity) {
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        return head.getItem() instanceof ProtectiveMaskItem && remaining(head) > 0.0D;
    }

    /** 吸い取った量(%秒)。 */
    public static int used(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.MASK_FILTER_USED, 0);
    }

    /** 残りの吸い取れる量(%秒)。 */
    public static double remaining(ItemStack stack) {
        return Math.max(0.0D, DesignData.sporeField().maskCapacity() - used(stack));
    }

    /** フィルターに {@code amount} %秒 吸わせる(上限で止める)。 */
    public static void absorb(ItemStack stack, int amount) {
        if (amount <= 0) return;
        int cap = (int) Math.ceil(DesignData.sporeField().maskCapacity());
        stack.set(ModDataComponents.MASK_FILTER_USED, Math.min(cap, used(stack) + amount));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return used(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        double cap = DesignData.sporeField().maskCapacity();
        return cap <= 0.0D ? 0 : Math.round(13.0F * (float) (remaining(stack) / cap));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        double cap = DesignData.sporeField().maskCapacity();
        float f = cap <= 0.0D ? 0.0F : (float) (remaining(stack) / cap);
        return Mth.hsvToRgb(f / 3.0F, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        double cap = DesignData.sporeField().maskCapacity();
        double rest = remaining(stack);
        if (rest <= 0.0D) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.spent").withStyle(ChatFormatting.RED));
        } else {
            int pct = cap <= 0.0D ? 0 : (int) Math.ceil(rest / cap * 100.0D);
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.filter", pct)
                    .withStyle(ChatFormatting.GRAY));
        }
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
