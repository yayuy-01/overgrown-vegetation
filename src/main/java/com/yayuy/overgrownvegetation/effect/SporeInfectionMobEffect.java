package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * 「胞子感染」(プレイヤーの 10 段階、amplifier 0〜9。2026-10-14 ユーザー指定、11-04 に parked から戻して書き直した)。
 * 付与と進行は {@link SporeInfectionEffects}、場所による判定は {@code spore/SporeExposureTracker}。
 *
 * <p>症状は {@code effects.json} の {@code sporeInfection.player.stages}: 空腹 1〜5(この効果の毎 tick の満腹度消耗)・
 * 防御力 −10% / −20%(感染4〜、{@link SporeInfectionEffects#syncModifiers})・攻撃力 −10% / −15%(感染8〜、
 * {@link SporeInfectionCombatModifier})・最大体力 −(段階 − 1)。被検知距離 +5〜20 は探す Mob が退避中なので今は効かない。
 *
 * <p><b>Mob にはかけない</b>(11-04: Mob は暴露量が満ちると 20 ダメージ。前の Mob の 3 段階は退避のまま)。
 * <b>牛乳では治らない</b>(10-14 ユーザー指定)ので {@link #getCurativeItems} は空。
 */
public class SporeInfectionMobEffect extends MobEffect {

    public SporeInfectionMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player player && entity.level() instanceof ServerLevel) {
            double exhaustion = playerStage(amplifier).hungerExhaustionPerTick();
            if (exhaustion > 0.0D) {
                player.causeFoodExhaustion((float) exhaustion);
            }
        }
    }

    /** 牛乳・トーテムなどで消えない(1.20.1: Forge の治す物の一覧を空に)。 */
    @Override
    public List<ItemStack> getCurativeItems() {
        return new ArrayList<>();
    }

    /** 今の段階の症状。 */
    public static EffectTable.StageSpec playerStage(int amplifier) {
        return DesignData.effects().playerInfection().stage(amplifier + 1);
    }
}
