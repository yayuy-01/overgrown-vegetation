package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
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
        if (infection == null || MedicineEffects.symptomsSuppressed(entity)) { // 気付け薬で抑える(2026-10-16)
            return 0.0D;
        }
        return entity instanceof net.minecraft.world.entity.player.Player
                ? SporeInfectionMobEffect.playerStage(infection.getAmplifier()).detectionRangeBonus()
                : DesignData.effects().level(infection.getAmplifier()).detectionRangeBonus();
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
        if (seekerGroup != null) {
            // シダの茂みを歩いて・走って抜けた(胞子が舞う音と光)。どのグループの Mob も気付く({@link FernRustle}、2026-10-16)。
            bonus += FernRustle.bonusFor(candidate);
        }
        return bonus;
    }

    /** 全段階中の最大ボーナス。索敵の AABB を広めに取るために使う。 */
    public static double maxBonus() {
        return DesignData.effects().maxDetectionRangeBonus()
                + DesignData.effects().clingingDetectionBonus()
                + DesignData.effects().glowFruitDetectionBonus()
                + FernRustle.SPRINT_BONUS;
    }

    /**
     * 被検知ボーナス({@code effects.json} の各段階・纏わりついた胞子・香ばしいにおい)が全部 0 以上か。
     * 負の値(や数でない値)が入っていると {@link #bonusFor(LivingEntity, LivingEntity)} の上限を見積もれないので、
     * 索敵箱を縮めたり算数だけで足切りしたりしてはいけない。
     *
     * <p>2026-09-24 軽量化(報告 120 行)で {@code OvergrownHunterTargetGoal} から移した
     * ({@code ParasitizedTargetGoal} と共通にするため)。
     */
    public static boolean bonusesNonNegative() {
        EffectTable effects = DesignData.effects();
        if (!(effects.clingingDetectionBonus() >= 0.0D && effects.glowFruitDetectionBonus() >= 0.0D)) {
            return false;
        }
        for (int i = 0; i <= EffectTable.MAX_LEVEL_INDEX; i++) {
            if (!(effects.level(i).detectionRangeBonus() >= 0.0D)) {
                return false;
            }
        }
        for (EffectTable.StageSpec stage : effects.playerInfection().stages()) {
            if (!(stage.detectionRangeBonus() >= 0.0D)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code seeker} が索敵箱に足す被検知ボーナス —— {@link #maxBonus()} のうち、
     * <b>探し手のグループで {@link #bonusFor(LivingEntity, LivingEntity)} が実際に足しうるぶん</b>だけ
     * (2026-09-24 軽量化、報告 120 行)。纏わりついた胞子はグループ4 のときだけ、香ばしいにおいは
     * どこかのグループに属するときだけ足す。付きえないぶんまで箱を広げても、その外側の相手は
     * 距離の判定で必ず落ちるので、絞っても選ばれる相手は変わらない。
     * <b>bonusFor の足す条件を変えたらここも合わせること。</b>
     *
     * <p>足す順は bonusFor・maxBonus と同じ(段階 → 纏わりついた胞子 → 香ばしいにおい)なので、
     * グループ4 なら maxBonus と同じ値(葉擦れ {@link FernRustle} は今だれかに付いている分だけなので、それ以下)になり、それ以外は maxBonus を超えない。
     * ボーナスに負(や数でない値)が混じっているときは上限が成り立たないので、従来どおり maxBonus を返す。
     */
    public static double searchBonus(LivingEntity seeker) {
        if (!bonusesNonNegative()) {
            return maxBonus();
        }
        EffectTable effects = DesignData.effects();
        MobGroup group = MobGroup.of(seeker.getType());
        double bonus = effects.maxDetectionRangeBonus();
        if (group == MobGroup.GROUP_4) {
            bonus += effects.clingingDetectionBonus();
        }
        if (group != null) {
            bonus += effects.glowFruitDetectionBonus();
            // 葉擦れは今だれかに付いている分だけ(いつも 16 ブロック広げると、全部の Mob の索敵箱が大きくなる)。
            bonus += FernRustle.activeMax(seeker.level().getGameTime());
        }
        return bonus;
    }

    /** {@code entity} が「茂みの中にいても見つかる」段階の胞子感染を持っているか。 */
    public static boolean isRevealedInFoliage(LivingEntity entity) {
        MobEffectInstance infection = entity.getEffect(ModMobEffects.SPORE_INFECTION);
        // プレイヤーの 10 段階には「茂みでも見つかる」は無い(2026-10-14 ユーザー指定の表)。
        return infection != null && !(entity instanceof net.minecraft.world.entity.player.Player)
                && DesignData.effects().level(infection.getAmplifier()).revealInFoliage();
    }

    private SporeInfectionDetection() {
    }
}
