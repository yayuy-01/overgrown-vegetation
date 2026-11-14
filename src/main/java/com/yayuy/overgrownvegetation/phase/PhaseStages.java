package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import net.minecraft.server.level.ServerLevel;

/**
 * 「今のフェーズと小さな段」「そのフェーズの物の強さの倍率」を読む 1 か所の口(サーバー側、2026-11-14)。
 * Mob の湧き・イベント・嵐・合図などはここから読む。クライアントは {@code client.phase.ClientPhaseState}。
 *
 * <h2>使い方</h2>
 * 物が解禁されるフェーズ(例: G4 なら 4)を渡して {@link #strength(ServerLevel, int)} を掛ける:
 * <pre>{@code double chance = baseChance * PhaseStages.strength(level, 4);}</pre>
 * <ul>
 *   <li>今のフェーズ &lt; 解禁フェーズ → 0(まだ出ない)</li>
 *   <li>今のフェーズ = 解禁フェーズ → 今の段の倍率(【仮】導入 0.3 / 定着 0.7 / 最盛 1.0)</li>
 *   <li>今のフェーズ &gt; 解禁フェーズ → 解禁フェーズの最盛の倍率(前のフェーズの物は出そろっている。F5 の導入で G4 が 0.3 に戻らない)</li>
 * </ul>
 * フェーズ 0 では何を渡しても 0。
 */
public final class PhaseStages {

    private PhaseStages() {
    }

    public static int phase(ServerLevel level) {
        return PhaseData.get(level).getPhase();
    }

    /** 今の段(フェーズ 0 では導入を返す。フェーズ 0 かは {@link #phase} で見る)。 */
    public static PhaseStage stage(ServerLevel level) {
        return PhaseData.get(level).getStage();
    }

    /** 今のフェーズの進み(0〜1)。次のフェーズの閾値が無い(F10 など)・フェーズ 0 は -1。 */
    public static double progress(ServerLevel level) {
        PhaseData data = PhaseData.get(level);
        return data.getPhase() < 1 ? -1.0D : DesignData.phases().stageProgress(data.getPhase(), data.getPoints());
    }

    /** {@code unlockPhase} で解禁される物の、今の強さの倍率(上の表)。 */
    public static double strength(ServerLevel level, int unlockPhase) {
        PhaseData data = PhaseData.get(level);
        return strength(DesignData.phases(), data.getPhase(), data.getStage(), unlockPhase);
    }

    /** 今のフェーズの物の、今の段の倍率。 */
    public static double currentStrength(ServerLevel level) {
        PhaseData data = PhaseData.get(level);
        return strength(DesignData.phases(), data.getPhase(), data.getStage(), data.getPhase());
    }

    /** 純粋な計算(テスト・クライアントへ送る値)。 */
    public static double strength(PhaseTable table, int phase, PhaseStage stage, int unlockPhase) {
        if (phase < 1 || phase < unlockPhase) {
            return 0.0D;
        }
        if (phase == unlockPhase) {
            return table.stageMultiplier(phase, stage);
        }
        return table.stageMultiplier(unlockPhase, PhaseStage.PEAK);
    }
}
