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
 *   <li>TD の前触れの間(感染 Mob と G4 以降が一切湧かなくなる)は、偵察・送り込み・苗床のスポナーを止める。TD は退避中なので、
 *   前触れかどうかは外から足す口({@link #registerTdOmen})。何も登録されていなければ「前触れではない」。</li>
 *   <li>フェーズの小さな段(導入・定着・最盛)の倍率は {@link #stageMultiplier}(別のセッションが口を作っている最中。今は 1)。</li>
 * </ul>
 * フェーズの数は {@code director.json} の {@code gates}(ユーザー・全体管理が決めた値。【仮】ではない)。
 */
public final class DirectorGates {

    private DirectorGates() {
    }

    /** 開くもの。 */
    public enum Gate {
        NURSERY("苗床"), ROOTED("根付き"), EVOLVE("進化"), SCOUT("偵察"), RECOGNIZE("認識"), RAID("送り込み"),
        SPAWNER("苗床のスポナー"), WAVE_G4("送り込みの G4"), WAVE_G5("送り込みの G5〜"), WAVE_MIXED("G4〜G6 の混ざった群れ");

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

    // ---------------------------------------------------------------- TD の前触れ

    /** 「今は TD の前触れの最中か」を答える口(TD を戻したら、TD の側が登録する)。 */
    public interface TdOmen {
        boolean active(ServerLevel level);
    }

    private static final List<TdOmen> TD_OMENS = new ArrayList<>();

    public static void registerTdOmen(TdOmen omen) {
        TD_OMENS.add(omen);
    }

    /** TD の前触れの最中か(何も登録されていなければ false)。この間は偵察・送り込み・苗床のスポナーを止める。 */
    public static boolean tdOmen(ServerLevel level) {
        for (TdOmen o : TD_OMENS) {
            if (o.active(level)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 小さな段の倍率

    /**
     * フェーズの小さな段(導入・定着・最盛)の倍率(送り込みの間を割る = 大きいほど送り込みが頻繁)。別のセッションが口を作っている最中なので、
     * 今は 1。口が出来たら、ここだけを差し替える。
     */
    public static double stageMultiplier(ServerLevel level) {
        return 1.0D;
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
        return String.format(Locale.ROOT, "フェーズ %s / 開いている: %s / まだ: %s / TD の前触れ %s / 小さな段の倍率 %.2f",
                phase, open.isEmpty() ? "なし" : open, closed.isEmpty() ? "なし" : closed, tdOmen(level) ? "中(偵察・送り込みを止める)" : "なし",
                stageMultiplier(level));
    }
}
