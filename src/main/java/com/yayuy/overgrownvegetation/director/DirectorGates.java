package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔の「フェーズで開く」口(2026-11-14、第 2 段の続き)。司令塔が道・偵察・認識・送り込み・苗床のスポナーを使ってよいかを、ここ 1 か所で答える。
 * 割り当ての正本は Vault「フェーズの仕組み(ポイント・日数)」の作り直し(11-14 ユーザー・全体管理)。
 *
 * <ul>
 *   <li>道: 苗床 = F1・根付き = F2・進化(1 段目)= F3。送り込みの枠を埋める進化も F3 から。</li>
 *   <li>偵察 = F4 から(前兆として)。拠点を覚えるのは F1 から裏で回す。認識と送り込み(襲撃)= F5 から。</li>
 *   <li>苗床のスポナー(G4 以降)= F4。送り込みの枠は 進化体 F3・G4 F4・G5〜 F5・G4〜G6 の混ざった群れ F6。</li>
 *   <li>根付きの向きはフェーズを見ない(F5 より前から、覚えた拠点の方を向く =「森は前から見ていた」の静かな前兆。ユーザー)。</li>
 *   <li>大きな襲撃(司令塔の襲撃の大・中・小の「大」)の前触れの間は、植物側の湧き(苗床・送り込み・危ない所へ送る・貯める)を止める。偵察は続ける(11-14 ユーザー T1 = 2)。
 *   それ以外の前触れの元は外から足す口({@link #registerLargeRaidOmen})。
 *   <li>フェーズの小さな段(導入・定着・最盛)の強さは {@link #strength}(PhaseStages の口を、司令塔が見るフェーズ・段で引く)。</li>
 * </ul>
 * フェーズの数は {@code director.json} の {@code gates}(ユーザー・全体管理が決めた値。【仮】ではない)。
 */
public final class DirectorGates {

    private DirectorGates() {
    }

    /** 開くもの。 */
    public enum Gate {
        NURSERY("苗床"), ROOTED("根付き"), EVOLVE("進化"), SCOUT("偵察"), RECOGNIZE("認識"), RAID("送り込み"),
        SPAWNER("苗床のスポナー"), WAVE_G4("送り込みの G4"), WAVE_G5("送り込みの G5〜"), WAVE_MIXED("G4〜G6 の混ざった群れ"),
        LEARN("学ぶ(危ない所へ進化体)"), LEARN_WAVE("学ぶ(送り込みの進化体を増やす)"), LARGE_RAID("大きな襲撃"), WATCHER("監視塔を植える");

        public final String label;

        Gate(String label) {
            this.label = label;
        }
    }

    /** そのゲートが開くフェーズ(純粋な計算)。 */
    public static int openAt(DirectorTable.Gates g, Gate gate) {
        return switch (gate) {
            case NURSERY -> g.nursery;
            case ROOTED -> g.rooted;
            case EVOLVE -> g.evolve;
            case SCOUT -> g.scout;
            case RECOGNIZE -> g.recognize;
            case RAID -> g.raid;
            case SPAWNER -> g.spawner;
            case WAVE_G4 -> g.waveG4;
            case WAVE_G5 -> g.waveG5;
            case WAVE_MIXED -> g.waveMixed;
            case LEARN -> g.learn;
            case LEARN_WAVE -> g.learnWave;
            case LARGE_RAID -> g.largeRaid;
            case WATCHER -> g.watcher;
        };
    }

    /** このフェーズでそのゲートは開いているか(純粋な計算)。 */
    public static boolean open(DirectorTable.Gates g, Gate gate, int phase) {
        return phase >= openAt(g, gate);
    }

    // ---------------------------------------------------------------- 今のフェーズ

    @Nullable
    private static Integer phaseOverride;

    /**
     * 今のフェーズ。GameTest のサーバーでは、差し替えが無ければ全部開いている(第 1 段・第 2 段のテストはフェーズを見ずに今の形のまま通す。
     * フェーズごとの解禁は {@link #setPhaseForTests} で差し替えて試す)。
     */
    public static int phase(ServerLevel level) {
        if (phaseOverride != null) return phaseOverride;
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return Integer.MAX_VALUE;
        return PhaseData.get(level).getPhase();
    }

    /** テスト・確かめ用: フェーズを差し替える(null で戻す)。 */
    public static void setPhaseForTests(@Nullable Integer phase) {
        phaseOverride = phase;
    }

    @Nullable
    public static Integer phaseOverride() {
        return phaseOverride;
    }

    public static boolean open(ServerLevel level, Gate gate) {
        return DesignData.director().enabled && open(DesignData.director().gates, gate, phase(level));
    }

    // ---------------------------------------------------------------- 大きな襲撃の前触れ

    /** 「今は大きな襲撃の前触れの最中か」を足す口(司令塔の気分のほかに、後で超大規模の襲撃などが登録する)。 */
    public interface LargeRaidOmen {
        boolean active(ServerLevel level);
    }

    private static final List<LargeRaidOmen> OMEN_SOURCES = new ArrayList<>();

    public static void registerLargeRaidOmen(LargeRaidOmen omen) {
        OMEN_SOURCES.add(omen);
    }

    /** 大きな襲撃の前触れの最中か(何も登録されていなければ false)。この間は偵察・送り込み・苗床のスポナーを止める。 */
    public static boolean largeRaidOmen(ServerLevel level) {
        if (ForestDirector.get(level).mood() == ForestDirector.Mood.LARGE_OMEN) return true;
        for (LargeRaidOmen o : OMEN_SOURCES) {
            if (o.active(level)) return true;
        }
        return false;
    }

    /**
     * 植物側の湧きを止めるか(大きな襲撃の前触れの間。11-14 ユーザー: 自然の湧き・苗床・送り込み・G4 以降が止まる。胞子の暴露から生まれる
     * 感染 Mob とバニラの Mob はふだんどおり)。湧かせる側(G1 の自然湧き・萌芽体の湧き・苗床・G4 以降を戻す側)が見る口。
     */
    public static boolean plantSpawnsSuppressed(ServerLevel level) {
        return largeRaidOmen(level);
    }

    // ---------------------------------------------------------------- 小さな段の倍率

    @Nullable
    private static com.yayuy.overgrownvegetation.phase.PhaseStage stageOverride;

    /** テスト・確かめ用: 小さな段を差し替える(null で戻す)。フェーズの差し替えと組で使う(PhaseStages はこちらの差し替えを見ない)。 */
    public static void setStageForTests(@Nullable com.yayuy.overgrownvegetation.phase.PhaseStage stage) {
        stageOverride = stage;
    }

    @Nullable
    public static com.yayuy.overgrownvegetation.phase.PhaseStage stageOverride() {
        return stageOverride;
    }

    /** 今の小さな段(差し替えがあればそれ)。 */
    public static com.yayuy.overgrownvegetation.phase.PhaseStage stage(ServerLevel level) {
        return stageOverride != null ? stageOverride : com.yayuy.overgrownvegetation.phase.PhaseStages.stage(level);
    }

    /**
     * その物の今の強さ(0〜1)。フェーズの小さな段の口 {@code PhaseStages.strength} を、司令塔が見るフェーズ・段(差し替えを含む)で引く:
     * 開く前は 0・開いたフェーズの中は今の段の倍率(導入 0.3 / 定着 0.7 / 最盛 1.0【仮】)・過ぎたフェーズは最盛の倍率。
     * 11-14 ユーザー「そのフェーズの新しい物は、導入でまれに、定着で普通に、最盛でいちばん多く強く」。
     */
    public static double strength(ServerLevel level, Gate gate) {
        if (!DesignData.director().enabled) return 0.0D;
        return com.yayuy.overgrownvegetation.phase.PhaseStages.strength(DesignData.phases(), phase(level), stage(level),
                openAt(DesignData.director().gates, gate));
    }

    /** 強さを確率として 1 回引く(道・スポナーを「まれに」する)。 */
    public static boolean roll(double strength, net.minecraft.util.RandomSource random) {
        return strength >= 1.0D || random.nextDouble() < strength;
    }

    /** 数に強さを掛け、端数は確率で切り上げる(送り込みの枠)。 */
    public static int scaleCount(int count, double strength, net.minecraft.util.RandomSource random) {
        double v = count * Math.max(0.0D, strength);
        int n = (int) Math.floor(v);
        return n + (random.nextDouble() < v - n ? 1 : 0);
    }

    // ---------------------------------------------------------------- 見る口

    /** 今のフェーズで開いているもの(確かめ用のコマンド)。 */
    public static String describe(ServerLevel level) {
        int p = phase(level);
        DirectorTable.Gates g = DesignData.director().gates;
        StringBuilder open = new StringBuilder(), closed = new StringBuilder();
        for (Gate gate : Gate.values()) {
            StringBuilder sb = open(g, gate, p) ? open : closed;
            if (!sb.isEmpty()) sb.append("・");
            sb.append(gate.label).append(" F").append(openAt(g, gate));
        }
        String phase = p == Integer.MAX_VALUE ? "全部開(テスト)" : "F" + p + (phaseOverride != null ? "(差し替え中)" : "");
        StringBuilder str = new StringBuilder();
        for (Gate gate : Gate.values()) {
            if (!str.isEmpty()) str.append("・");
            str.append(gate.label).append(String.format(Locale.ROOT, " %.2f", strength(level, gate)));
        }
        return String.format(Locale.ROOT, "フェーズ %s・段 %s%s / 開いている: %s / まだ: %s / 大きな襲撃の前触れ %s / 今の強さ(導入でまれ・定着で普通・最盛でいちばん多い。偵察・認識はユーザー指定の日数なので掛けていない): %s",
                phase, stage(level).name(), stageOverride != null ? "(差し替え中)" : "", open.isEmpty() ? "なし" : open, closed.isEmpty() ? "なし" : closed,
                largeRaidOmen(level) ? "中(偵察・送り込みを止める)" : "なし", str);
    }
}
