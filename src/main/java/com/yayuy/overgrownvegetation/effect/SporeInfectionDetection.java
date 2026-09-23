package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「胞子感染」の段階に応じた<b>被検知距離の増加</b>({@code effects.json} の {@code levelN.detectionRangeBonus})。
 * 過繁茂した Mob({@link ParasitizedTargetGoal})とグループ3 の捕食者
 * ({@link com.yayuy.overgrownvegetation.entity.OvergrownHunterTargetGoal})が、感染したプレイヤーを
 * {@code FOLLOW_RANGE} よりこのぶん広い範囲から見つけられるようにする。
 */
public final class SporeInfectionDetection {

    /** {@code entity} の感染段階に応じた被検知距離のボーナス(ブロック)。未感染なら 0。 */
    public static double bonusFor(LivingEntity entity) {
        MobEffectInstance infection = entity.getEffect(ModMobEffects.SPORE_INFECTION);
        if (infection == null) {
            return 0.0D;
        }
        return DesignData.effects().level(infection.getAmplifier()).detectionRangeBonus();
    }

    /**
     * 「誰から見た」被検知距離のボーナスか。感染ぶん({@link #bonusFor(LivingEntity)})に加えて、
     * <b>探しているのがグループ4 の Mob なら</b>
     * {@link ModMobEffects#CLINGING_SPORES 「纏わりついた胞子」}のぶん
     * ({@code sporophyllDetectionBonus})も足す。
     *
     * <p>探し手で分けているのは、このデバフが<b>スポロフィルの胞子を浴びて汚染側に目を付けられた</b>
     * という意味だから —— グループ3 の捕食者など、汚染側でない相手の索敵までは良くならない
     * (ユーザー指定「グループ4 の mob からの検知範囲が上がり」)。
     *
     * <p>{@link ModMobEffects#SAVORY_SCENT 「香ばしいにおい」}のぶんも同じ場所で足す。こちらは
     * <b>過繫茂 Mob なら誰でも</b>(グループ1〜10 のどれかに属していれば)嗅ぎ付ける —— 匂いは
     * 目を付けられているかどうかとは関係なく漂うが、バニラ Mob には効かない
     * (ユーザー指定「過繫茂 mob からの検知範囲が 15 ブロック分延びる」)。
     */
    public static double bonusFor(LivingEntity seeker, LivingEntity candidate) {
        double bonus = bonusFor(candidate);
        MobGroup seekerGroup = MobGroup.of(seeker.getType());
        if (seekerGroup == MobGroup.GROUP_4 && candidate.hasEffect(ModMobEffects.CLINGING_SPORES)) {
            bonus += DesignData.effects().clingingDetectionBonus();
        }
        if (seekerGroup != null && candidate.hasEffect(ModMobEffects.SAVORY_SCENT)) {
            bonus += DesignData.effects().glowFruitDetectionBonus();
        }
        return bonus;
    }

    /** 全段階中の最大ボーナス。索敵の AABB を広めに取るために使う。 */
    public static double maxBonus() {
        return DesignData.effects().maxDetectionRangeBonus()
                + DesignData.effects().clingingDetectionBonus()
                + DesignData.effects().glowFruitDetectionBonus();
    }

    /** {@code entity} が「茂みの中にいても見つかる」段階の胞子感染を持っているか。 */
    public static boolean isRevealedInFoliage(LivingEntity entity) {
        MobEffectInstance infection = entity.getEffect(ModMobEffects.SPORE_INFECTION);
        return infection != null && DesignData.effects().level(infection.getAmplifier()).revealInFoliage();
    }

    private SporeInfectionDetection() {
    }
}
