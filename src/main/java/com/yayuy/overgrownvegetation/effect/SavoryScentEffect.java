package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 「香ばしいにおい」— {@link ModItems#OVERGROWN_GLOWBERRY 熟した垂れ草の光る果実}を食べると付くデバフ。
 * {@link WellFedEffect 栄養満点！}と対になる代償。未発達な種子({@link ModItems#UNDEVELOPED_SEED})を食べても付く。付与はどちらも {@link FruitEffects}。
 *
 * <p>効果は 1 つ: <b>過繫茂 Mob({@link MobGroup} のどれかに属する Mob)からの被検知距離が
 * {@code glowFruit.detectionBonus}(既定 15 ブロック)伸びる</b>
 * ({@link SporeInfectionDetection#bonusFor(net.minecraft.world.entity.LivingEntity,
 * net.minecraft.world.entity.LivingEntity)})。バニラ Mob の索敵は変わらない —— 匂いを嗅ぎ付けるのは
 * 汚染側の生物だけ、という扱い。
 *
 * <p>能力値はいじらないので {@code addAttributeModifier} は無く、tick 処理も要らない
 * (索敵の側が付いているかどうかを見に来る)。
 */
public class SavoryScentEffect extends MobEffect {

    public SavoryScentEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
