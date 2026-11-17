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
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
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
 *     バニラの耐久(殴られて減る・壊れる)は使わない。耐久のバーは自前で出す。</li>
 *     <li>2026-11-14 段 2c: 耐久 = 中のフィルターの残り。換気機と同じフィルター(決定 15。式は {@link AirFilters})。持ち物の画面でフィルターをマスクの上で右クリックすると入れ替わる【仮】。</li>
 * </ul>
 * 描画は GeckoLib の立体モデル({@code geo/armor/protective_mask.geo.json}、アイコンも同じモデル)。
 */
public class ProtectiveMaskItem extends ArmorItem implements GeoItem, com.yayuy.overgrownvegetation.gear.FilterMask {

    /** フィルターの減りは基準の 1 倍(段の頭は {@code gear_tiers.json} で遅くなる。2026-11-17)。 */
    @Override
    public double filterWear() {
        return 1.0D;
    }

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ProtectiveMaskItem(Properties properties) {
        super(ModArmorMaterials.PROTECTIVE_MASK, Type.HELMET, properties);
    }

    /** {@code entity} が防護マスク(か、フィルターを使う段の頭。2026-11-17)を被っているか(フィルターの残りを問わない)。 */
    public static boolean isWearing(LivingEntity entity) {
        return com.yayuy.overgrownvegetation.gear.FilterMask.isWearing(entity);
    }

    /** フィルターの残ったマスク(か段の頭)を被っていて、胞子を防いでいるか。 */
    public static boolean isProtecting(LivingEntity entity) {
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        return com.yayuy.overgrownvegetation.gear.FilterMask.isMask(head) && remaining(head) > 0.0D;
    }

    /** 吸い取った量(%秒)。式はフィルターと共通({@link AirFilters}、2026-11-14 段 2c)。 */
    public static int used(ItemStack stack) {
        return AirFilters.used(stack);
    }

    /** 残りの吸い取れる量(%秒)。 */
    public static double remaining(ItemStack stack) {
        return AirFilters.remaining(stack);
    }

    /** フィルターに {@code amount} %秒 吸わせる(上限で止める)。 */
    public static void absorb(ItemStack stack, int amount) {
        AirFilters.absorb(stack, amount);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return AirFilters.barVisible(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return AirFilters.barWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return AirFilters.barColor(stack);
    }

    /**
     * フィルターの入れ替え(2026-11-14 段 2c【仮】): 持ち物の画面でフィルターをつかみ、このマスクの上で右クリックすると入れ替わる。
     * 新しいフィルターがマスクに入り、入っていた古いフィルター(詰まり・洗った回数をそのまま)が手に残る(つかんでいたのが 2 枚以上なら持ち物へ)。
     * 詰まり切ったフィルターは入れない。
     */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack mask, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
        if (action != ClickAction.SECONDARY || !AirFilters.isFilter(other) || AirFilters.clogged(other) || !slot.allowModification(player)) {
            return false;
        }
        swapFilter(mask, other, player, access);
        player.playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 1.2F);
        return true;
    }

    /** マスクのフィルターを {@code carried} の 1 枚と入れ替える(GameTest からも)。古いフィルターは {@code access}(手)か持ち物へ。 */
    public static void swapFilter(ItemStack mask, ItemStack carried, Player player, SlotAccess access) {
        ItemStack old = AirFilters.asFilterItem(mask);
        AirFilters.copyState(carried, mask);
        if (carried.getCount() <= 1) {
            access.set(old);
        } else {
            carried.shrink(1);
            if (!player.getInventory().add(old)) {
                player.drop(old, false);
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (AirFilters.clogged(stack)) {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.spent").withStyle(ChatFormatting.RED));
        } else {
            tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.filter", AirFilters.percentLeft(stack))
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("item.overgrown_vegetation.protective_mask.swap_hint").withStyle(ChatFormatting.DARK_GRAY));
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
