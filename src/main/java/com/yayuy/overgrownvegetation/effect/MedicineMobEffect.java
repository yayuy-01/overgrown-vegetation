package com.yayuy.overgrownvegetation.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 乳鉢の薬でしか得られない効果(2026-10-16、ユーザー指定の 4 つ)。効果そのものは何もせず、印として持つ。
 * 効き目は {@link MedicineEffects} を通して、胞子感染の処理の側が見る。
 */
public class MedicineMobEffect extends MobEffect {

    public MedicineMobEffect(int color) {
        super(MobEffectCategory.BENEFICIAL, color);
    }
}
