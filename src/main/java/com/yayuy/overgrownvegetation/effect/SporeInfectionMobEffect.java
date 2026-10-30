package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.EffectCure;

import java.util.Set;

/**
 * 「胞子感染」。付与と進行は {@link SporeInfectionEffects}、場所による判定は {@link SporeInfectionTracker}。
 *
 * <p><b>プレイヤー</b>(2026-10-14 ユーザー指定で作り直し)は 10 段階(amplifier 0〜9)。症状は {@code effects.json} の
 * {@code sporeInfection.player.stages}:
 * <ul>
 *     <li>空腹 1〜5(この効果の毎 tick の満腹度消耗。バニラの空腹 I = 0.005 の段数倍)</li>
 *     <li>過繁茂した Mob からの被検知距離 +5〜+20({@link SporeInfectionDetection})</li>
 *     <li>防御力 −10% / −20%(感染4〜)・攻撃力 −10% / −15%(感染8〜。{@link SporeInfectionCombatModifier})</li>
 *     <li>治るまで最大体力 −1 × (段階 − 1)</li>
 * </ul>
 * 防御力・移動速度・最大体力の修飾は、プレイヤーと Mob で値が違うので効果には持たせず {@link SporeInfectionEffects#syncModifiers} が張る。
 *
 * <p><b>Mob</b> は今までどおり 3 段階({@code level1〜3})で、効果が切れた瞬間に死ぬ({@link SporeInfectionExpiryHandler})。
 *
 * <p><b>牛乳では治らない</b>(2026-10-14 ユーザー指定)ので、治す手段({@link #fillEffectCures})を持たせない。
 */
public class SporeInfectionMobEffect extends MobEffect {

    public SporeInfectionMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        // 空腹(満腹度消耗)を毎 tick 与えるため常に true。
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player player && entity.level() instanceof ServerLevel && !MedicineEffects.symptomsSuppressed(player)) { // 気付け薬で抑える(2026-10-16)
            double exhaustion = DesignData.effects().playerInfection().stage(amplifier + 1).hungerExhaustionPerTick();
            if (exhaustion > 0.0D) {
                player.causeFoodExhaustion((float) exhaustion);
            }
        }
        return true;
    }

    /** 牛乳・トーテムなどで消えない。 */
    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
    }

    /** 今の段階の症状(プレイヤー用)。 */
    public static EffectTable.StageSpec playerStage(int amplifier) {
        return DesignData.effects().playerInfection().stage(amplifier + 1);
    }
}
