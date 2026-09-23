package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

/**
 * 「寄生植物装備」({@code plant_armor})。胸の装備 1 つで体と両腕を覆う GeckoLib の立体モデル
 * (ユーザー製 {@code geo/armor/plant_armor.geo.json}、胸の胞子の塊と芽が脈打つ {@code idle} アニメ付き)。
 *
 * <p>数値(防御力・最大体力・耐久値)は<b>段階ごとに変わる</b>ので、防具素材ではなく
 * {@link #getDefaultAttributeModifiers(ItemStack)} / {@link #getMaxDamage(ItemStack)} で段階
 * ({@link PlantArmor#stageOf(ItemStack)})から出す。仕様と段階の表は {@link PlantArmor}。
 *
 * <ul>
 *   <li>壊れない: 耐久値は 1 を下回らない({@link #damageItem})。食料と引き換えに勝手に回復する
 *       ({@link PlantArmorEvents})。</li>
 *   <li>装備音は鳴らさない(素材の音を空にしてある): 耐久値が回復するたびに「装備が変わった」扱いになり
 *       着脱音が鳴ってしまうため。</li>
 *   <li>束縛の呪いで外せない({@link PlantArmor#create})。エンチャントの光沢は出さない({@link #isFoil})。</li>
 * </ul>
 * 2 段階目・3 段階目のモデルは {@code tools/plant-armor/run.ps1} が段階 1 から生成する({@code plant_armor_2.*} / {@code plant_armor_3.*})。
 * 段階の切り替えは {@code PlantArmorModel#setStage}(描画側が描く直前に呼ぶ)。
 */
public class PlantArmorItem extends ArmorItem implements GeoItem {

    /** 段階ごとの待機アニメ。名前を分けているのは、昇格後に GeckoLib が段階 1 の焼き上がりを使い続けないようにするため。 */
    private static final RawAnimation[] IDLE = {
            RawAnimation.begin().thenLoop("idle"),
            RawAnimation.begin().thenLoop("idle2"),
            RawAnimation.begin().thenLoop("idle3")
    };

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public PlantArmorItem(Holder<ArmorMaterial> material, Properties properties) {
        super(material, Type.CHESTPLATE, properties);
    }

    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        PlantArmor.Stage stage = PlantArmor.stageOf(stack);
        return ItemAttributeModifiers.builder()
                .add(Attributes.ARMOR, new AttributeModifier(OvergrownVegetation.id("plant_armor_defense"),
                        stage.defense, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST)
                .add(Attributes.MAX_HEALTH, new AttributeModifier(OvergrownVegetation.id("plant_armor_health"),
                        stage.maxHealth - 20.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST)
                .build();
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        return PlantArmor.stageOf(stack).durability;
    }

    /** 壊れない: 残り 1 までしか傷まない。 */
    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, @Nullable T entity, Consumer<Item> onBroken) {
        int room = getMaxDamage(stack) - 1 - stack.getDamageValue();
        return Math.max(0, Math.min(amount, room));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        PlantArmor.Stage stage = PlantArmor.stageOf(stack);
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.plant_armor.stage", stage.number)
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.plant_armor.bound").withStyle(ChatFormatting.DARK_GRAY));
        if (context.level() != null && context.level().isClientSide()) {
            // 自分の装着時間はクライアントのデータ。専用サーバーで Minecraft クラスを触らないよう別クラスに分けてある。
            com.yayuy.overgrownvegetation.client.PlantArmorClientTooltip.appendNextStage(stack, stage, tooltip);
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private HumanoidModel<?> renderer;
            private net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer itemRenderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.itemRenderer == null) {
                    this.itemRenderer = new com.yayuy.overgrownvegetation.client.PlantArmorItemRenderer();
                }
                return this.itemRenderer;
            }

            @Override
            public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(@Nullable T livingEntity, ItemStack itemStack,
                    @Nullable EquipmentSlot equipmentSlot, @Nullable HumanoidModel<T> original) {
                if (this.renderer == null) {
                    this.renderer = new com.yayuy.overgrownvegetation.client.PlantArmorRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 0, state -> {
            ItemStack stack = state.getData(software.bernie.geckolib.constant.DataTickets.ITEMSTACK);
            int stage = stack == null ? 1 : PlantArmor.stageOf(stack).number;
            return state.setAndContinue(IDLE[stage - 1]);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
