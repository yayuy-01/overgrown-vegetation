package com.yayuy.overgrownvegetation.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 効果の設計値({@code effects.json})。2026-11-04 に<b>プレイヤーの胞子感染 10 段階だけ</b>の版として書き直した
 * (感染システムの第 2 段。10-30 に退避した前の版 {@code parked/.../data/EffectTable.java} は薬・装備の汚染・装置・水ぐされ・
 * Mob の 3 段階なども持っていた。それらを戻すときは、この版に足していく = 前の版で上書きしない)。
 *
 * <h2>sporeInfection.player(2026-10-14 ユーザー指定・11-04 に「かかり方」と「加速」を作り直し)</h2>
 * <ul>
 *     <li>かかるのは暴露量が {@code spore_field.json} の exposure.infectDose(1500 %秒)に届いたとき(11-04 ユーザー決定。
 *     前の「濃度 50% に 30 秒」は消した)。過繁茂の Mob の攻撃で {@code attackInfectChance}。</li>
 *     <li>{@code stageTicks}(10 分)ごとに 1 段。感染10 からさらに 10 分で死ぬ。上がるたび {@code damagePerStage}、最大体力 −{@code maxHealthPerStage} × (段階 − 1)。</li>
 *     <li>加速(11-04 ユーザー決定「濃度に比例してなめらかに」・式は仮決め): 進む速さ ×(1 + {@code concentrationSpeedBonusPer100} × 濃度/100)、
 *     濃度 {@code speedUpMinPercent} 未満は加速なし。過繁茂のバイオームで ×(1 + {@code overgrownBiomeSpeedBonus})(重ねて掛ける)。</li>
 *     <li>{@code xpDrainIntervalTicks} ごとに経験値 {@code xpDrainPoints}(量は 10-14 の仮決め)。</li>
 *     <li>{@code stages}: 感染1〜10 の症状(空腹・被検知距離・防御力 / 攻撃力の低下)。被検知距離は探す Mob が退避中なので今は使わない。</li>
 * </ul>
 */
public final class EffectTable {

    public static final EffectTable DEFAULT = new EffectTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public SporeInfection sporeInfection;
    }

    public static final class SporeInfection {
        public PlayerInfection player;
    }

    public static final class PlayerInfection {
        public Integer stageTicks;
        public Double attackInfectChance;
        public Double speedUpMinPercent;
        public Double concentrationSpeedBonusPer100;
        public Double overgrownBiomeSpeedBonus;
        public Double attackAdvanceChance;
        public Integer attackAdvanceTicks;
        public Integer xpDrainIntervalTicks;
        public Integer xpDrainPoints;
        public Double damagePerStage;
        public Double maxHealthPerStage;
        public List<Stage> stages;
    }

    public static final class Stage {
        public Integer hunger;
        public Double detectionRangeBonus;
        public Double armorReduction;
        public Double attackDamageReduction;
    }

    /** 1 段ぶんの症状。 */
    public record StageSpec(int hunger, double detectionRangeBonus, double armorReduction, double attackDamageReduction) {
        /** 毎 tick の満腹度消耗(バニラの空腹は 1 段 0.005)。 */
        public double hungerExhaustionPerTick() {
            return 0.005D * Math.max(0, hunger);
        }
    }

    /** プレイヤーの胞子感染の確定値。 */
    public record PlayerInfectionSpec(int stageTicks, double attackInfectChance, double speedUpMinPercent,
                                      double concentrationSpeedBonusPer100, double overgrownBiomeSpeedBonus,
                                      double attackAdvanceChance, int attackAdvanceTicks, int xpDrainIntervalTicks,
                                      int xpDrainPoints, double damagePerStage, double maxHealthPerStage,
                                      List<StageSpec> stages) {
        /** 段階の数(10)。 */
        public int maxStage() {
            return stages.size();
        }

        /** 段階 {@code stage}(1〜)の症状。範囲外は端に丸める。 */
        public StageSpec stage(int stage) {
            return stages.get(Math.max(1, Math.min(stages.size(), stage)) - 1);
        }

        /**
         * 場所による進みの倍率。濃度 {@code percent} が {@link #speedUpMinPercent} 以上なら ×(1 + 係数 × 濃度/100)、
         * 過繁茂のバイオームならさらに ×(1 + {@link #overgrownBiomeSpeedBonus})。
         */
        public double rate(double percent, boolean overgrownBiome) {
            double r = 1.0D;
            if (percent >= speedUpMinPercent) r *= 1.0D + concentrationSpeedBonusPer100 * percent / 100.0D;
            if (overgrownBiome) r *= 1.0D + overgrownBiomeSpeedBonus;
            return r;
        }
    }

    private final PlayerInfectionSpec playerInfection;

    private EffectTable(Json src) {
        PlayerInfection d = defaults();
        PlayerInfection v = src != null && src.sporeInfection != null && src.sporeInfection.player != null
                ? src.sporeInfection.player : new PlayerInfection();
        List<Stage> stagesSrc = v.stages != null && !v.stages.isEmpty() ? v.stages : d.stages;
        List<StageSpec> stages = new ArrayList<>(stagesSrc.size());
        for (int i = 0; i < stagesSrc.size(); i++) {
            Stage s = stagesSrc.get(i) != null ? stagesSrc.get(i) : new Stage();
            Stage def = d.stages.get(Math.min(i, d.stages.size() - 1));
            stages.add(new StageSpec(orElse(s.hunger, def.hunger), orElse(s.detectionRangeBonus, def.detectionRangeBonus),
                    orElse(s.armorReduction, def.armorReduction), orElse(s.attackDamageReduction, def.attackDamageReduction)));
        }
        this.playerInfection = new PlayerInfectionSpec(
                Math.max(20, orElse(v.stageTicks, d.stageTicks)),
                orElse(v.attackInfectChance, d.attackInfectChance),
                Math.max(0.0D, orElse(v.speedUpMinPercent, d.speedUpMinPercent)),
                Math.max(0.0D, orElse(v.concentrationSpeedBonusPer100, d.concentrationSpeedBonusPer100)),
                Math.max(0.0D, orElse(v.overgrownBiomeSpeedBonus, d.overgrownBiomeSpeedBonus)),
                orElse(v.attackAdvanceChance, d.attackAdvanceChance),
                Math.max(0, orElse(v.attackAdvanceTicks, d.attackAdvanceTicks)),
                Math.max(1, orElse(v.xpDrainIntervalTicks, d.xpDrainIntervalTicks)),
                Math.max(0, orElse(v.xpDrainPoints, d.xpDrainPoints)),
                Math.max(0.0D, orElse(v.damagePerStage, d.damagePerStage)),
                Math.max(0.0D, orElse(v.maxHealthPerStage, d.maxHealthPerStage)),
                List.copyOf(stages));
    }

    public static EffectTable fromJson(Json j) {
        return new EffectTable(j);
    }

    public PlayerInfectionSpec playerInfection() {
        return playerInfection;
    }

    private static PlayerInfection defaults() {
        PlayerInfection p = new PlayerInfection();
        p.stageTicks = 12000;                    // 10 分ごとに 1 段(10-14 ユーザー指定)
        p.attackInfectChance = 0.30D;            // 過繁茂の Mob の攻撃で 30%(10-14 ユーザー指定)
        p.speedUpMinPercent = 5.0D;              // 5% 未満は加速なし(11-04 仮決め、暴露の下限と同じ)
        p.concentrationSpeedBonusPer100 = 0.10D; // 濃度 100% で +10%・50% で +5%(11-04 仮決め)
        p.overgrownBiomeSpeedBonus = 0.10D;      // 過繁茂のバイオームで +10%(10-14 ユーザー指定)
        p.attackAdvanceChance = 0.05D;           // 敵の攻撃で 5% の確率で
        p.attackAdvanceTicks = 200;              // 10 秒進む(10-14 ユーザー指定)
        p.xpDrainIntervalTicks = 200;            // 10 秒に 1 回(10-14 ユーザー指定)
        p.xpDrainPoints = 1;                     // 経験値 1(10-14 仮決め)
        p.damagePerStage = 1.0D;
        p.maxHealthPerStage = 1.0D;
        p.stages = List.of(
                stage(1, 0, 0.0D, 0.0D),
                stage(1, 5, 0.0D, 0.0D),
                stage(2, 5, 0.0D, 0.0D),
                stage(2, 10, 0.10D, 0.0D),
                stage(3, 10, 0.10D, 0.0D),
                stage(3, 15, 0.20D, 0.0D),
                stage(4, 15, 0.20D, 0.0D),
                stage(4, 20, 0.20D, 0.10D),
                stage(5, 20, 0.20D, 0.10D),
                stage(5, 20, 0.20D, 0.15D));
        return p;
    }

    private static Stage stage(int hunger, double detection, double armor, double attack) {
        Stage s = new Stage();
        s.hunger = hunger;
        s.detectionRangeBonus = detection;
        s.armorReduction = armor;
        s.attackDamageReduction = attack;
        return s;
    }

    private static <T> T orElse(T v, T d) {
        return v != null ? v : d;
    }
}
