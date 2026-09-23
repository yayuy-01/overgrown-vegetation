package com.yayuy.overgrownvegetation.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 「フィロセンスの誘導」— フィロセンス
 * ({@link com.yayuy.overgrownvegetation.entity.PhyllosensEntity})が味方(グループ1〜10)へ配る印
 * (ユーザー要望 2026-09-14)。
 *
 * <p>これが付いている Mob は、<b>距離も視界も壁も無視して</b>
 * {@link com.yayuy.overgrownvegetation.registry.ModMobEffects#PHYLLOSENS_WATCH 「フィロセンスの視線」}
 * を持つプレイヤーの居場所を知り、襲いに行く({@link com.yayuy.overgrownvegetation.entity.GuidedHuntGoal})。
 *
 * <p>能力値は一切いじらない。<b>これは「どこを襲えばいいか知っている」という状態そのもの</b>で、
 * 情報の共有をワールド側のメモ帳ではなく<b>効果として持たせている</b>のがこの仕組みの肝 —
 * 誰が情報を持っているかがゲーム内のアイコンで見えるし、効果が切れれば自然に忘れる。
 */
public class PhyllosensGuidanceEffect extends MobEffect {

    public PhyllosensGuidanceEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    /** 能力値をいじらないので、毎 tick 呼ばれる必要がない。 */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
