package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「フィロセンスの視線 / Phyllosens' Gaze」(2026-11-14、竹馬型 段 2)。フィロセンスの見張り・覗きで見られた相手に付く印。
 * <b>能力値は何も変えない</b>(決定 11-10 ユーザー「印が付くだけ」)。「見られている」と分かるだけ。味方を呼ぶ誘導は能力の段で足す。
 *
 * <p>前の版の印(退避中の {@code parked/…/PhyllosensWatchEffect}・{@code PhyllosensInfluence})は戻していない: 長さを設計データの効果の表
 * ({@code DesignData.effects()})から読み、誘導の効果・退避中の寄生植物装備({@code PlantArmor})にも絡むため。長さは {@link #TICKS}【仮】。
 */
public class PhyllosensWatchEffect extends MobEffect {

    /** 印の長さ(tick)【仮 1200 = 1 分。仕様 3 節「1 分で切れる」】。見られている間は付け直して延びる。 */
    public static final int TICKS = 1200;

    public PhyllosensWatchEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return false;
    }

    /** 印を付ける / 付け直す(残りが 1 秒以上減ってから。通信を毎 tick 出さない)。 */
    public static void mark(LivingEntity target) {
        MobEffectInstance cur = target.getEffect(ModPhyllosens.PHYLLOSENS_WATCH.get());
        if (cur == null || cur.getDuration() < TICKS - 20) {
            target.addEffect(new MobEffectInstance(ModPhyllosens.PHYLLOSENS_WATCH.get(), TICKS, 0, true, false, true));
        }
    }
}
