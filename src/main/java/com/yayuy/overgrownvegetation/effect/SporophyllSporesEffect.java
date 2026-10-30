package com.yayuy.overgrownvegetation.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 「スポロフィルの胞子」— スポロフィル
 * ({@link com.yayuy.overgrownvegetation.entity.SporophyllEntity})の傘の下にいる<b>味方</b>に付くバフ。
 *
 * <p>能力値そのものはいじらない。効くのは攻撃したときで、
 * {@link SporophyllCombatHandler} が 2 つのことをする:
 * <ul>
 *     <li>相手の<b>防御力を {@code sporophyllArmorPiercing}(effects.json の既定 20%)無視する</b></li>
 *     <li>相手の<b>防具の耐久値を、与えたダメージ × {@code sporophyllArmorDurabilityFactor} だけ削る</b></li>
 * </ul>
 * 持続は {@code sporophyllBuffTicks}(既定 1 分)で、範囲内に居るあいだは配り直されて途切れない。
 */
public class SporophyllSporesEffect extends MobEffect {

    public SporophyllSporesEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    /** 能力値をいじらないので毎 tick 呼ばれる必要がない。 */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
