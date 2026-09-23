package com.yayuy.overgrownvegetation.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 「Philosense is watching you」— フィロセンス
 * ({@link com.yayuy.overgrownvegetation.entity.PhyllosensEntity})に見つかり、
 * <b>周りの汚染側へ居場所を共有されている</b>あいだ付くデバフ(ユーザー要望 2026-09-14)。
 *
 * <p>能力値は一切いじらない。<b>これは「いま狙われている」という通知そのもの</b>で、
 * これが付いている間、
 * {@link com.yayuy.overgrownvegetation.registry.ModMobEffects#PHYLLOSENS_GUIDANCE 誘導}を受けた
 * Mob が<b>距離も視界も問わず</b>襲いに来る
 * ({@link com.yayuy.overgrownvegetation.entity.GuidedHuntGoal})。
 * 持続は {@code phyllosensWatchTicks}(既定 1200 = 1 分)で、範囲内に居るあいだは延び続ける —
 * アイコンが消えた＝もう追われていない、と読める。
 *
 * <p>付与は {@link com.yayuy.overgrownvegetation.entity.PhyllosensInfluence#markPlayers} の一箇所だけ。
 * ふだんの 32 ブロックの輪も、索敵中の 512 ブロックも、そこを通る。
 */
public class PhyllosensWatchEffect extends MobEffect {

    public PhyllosensWatchEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    /** 能力値も満腹度もいじらないので、毎 tick 呼ばれる必要がない。 */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
