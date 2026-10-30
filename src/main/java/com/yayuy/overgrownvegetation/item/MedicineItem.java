package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 乳鉢の薬(丸薬・粉薬。2026-10-16、ユーザー指定「食べて使う・ポーションより効果が短い代わりに安い・薬でしか得られない効果」)。
 * 食べ物と同じく食べる(いつでも食べられる・速い・満腹度は戻らない)。効き目は食べ終わったときに {@code effects.json} の
 * {@code medicine} から付ける(食べ物の性質に書くと持続が固定されるため)。
 */
public class MedicineItem extends Item {

    public enum Kind { SPORE_WARD, ELIXIR, SMELLING_SALTS, STASIS_PILL, WARMBLOOM_PILL }

    private final Kind kind;

    public MedicineItem(Kind kind, Properties properties) {
        super(properties.stacksTo(16)
                .food(new FoodProperties.Builder().nutrition(0).saturationModifier(0.0F).alwaysEdible().fast().build()));
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide()) {
            apply(entity);
        }
        return super.finishUsingItem(stack, level, entity);
    }

    /** 効き目を付ける(テストからも呼ぶ)。 */
    public void apply(LivingEntity entity) {
        EffectTable fx = DesignData.effects();
        switch (kind) {
            case SPORE_WARD -> entity.addEffect(new MobEffectInstance(ModMobEffects.SPORE_WARD, fx.medicineSporeWardTicks()));
            case SMELLING_SALTS -> entity.addEffect(new MobEffectInstance(ModMobEffects.ALERTNESS, fx.medicineAlertnessTicks()));
            case STASIS_PILL -> entity.addEffect(new MobEffectInstance(ModMobEffects.STASIS, fx.medicineStasisTicks()));
            case WARMBLOOM_PILL -> entity.addEffect(new MobEffectInstance(ModMobEffects.STAGE_GUARD, fx.medicineStageGuardTicks()));
            case ELIXIR -> {
                if (entity instanceof Player player) {
                    int stage = SporeInfectionEffects.stageOf(player);
                    if (stage > 0) {
                        SporeInfectionEffects.setPlayerStage(player, stage - fx.medicineElixirStages());
                    }
                }
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.medicine." + kind.name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.GRAY));
    }
}
