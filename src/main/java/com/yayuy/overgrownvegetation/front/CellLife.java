package com.yayuy.overgrownvegetation.front;

import net.minecraft.server.level.ServerLevel;

/**
 * 地図の上の生態系(升の動物・村人の頭数と感染)への口(2026-11-21)。中身は並行の別セッションが {@code ecosystem/} で作り、
 * {@link #register} で差し込む。差し込まれるまでは {@link #NONE}(何も感染しない・どの升も生き物がいる扱い)。
 * 升は生態系の台帳と同じ 4×4 チャンク(cellX = chunkX >> 2)。
 */
public interface CellLife {

    /** 感染を進めた結果。gain は台帳の上で感染した分の稼ぎ(予算へは入れない。呼ぶ側 = 前線が入れる)。 */
    record Result(int infected, int villagers, double gain, boolean nowOvergrown) {
        public static final Result NOTHING = new Result(0, 0, 0.0D, false);
    }

    /** 升の頭数・感染した数・生き物の少ない升か(sparse = 胞子だけで広がる。広げる部隊を送らない)。 */
    record View(int heads, int infected, boolean sparse) {
        public static final View UNKNOWN = new View(0, 0, false);

        /** まだ感染させられる頭数。 */
        public int left() {
            return Math.max(0, heads - infected);
        }
    }

    /** 升 (cellX, cellZ) に attackers 体の広げる部隊が seconds 秒いた。 */
    Result infect(ServerLevel level, int cellX, int cellZ, int attackers, double seconds);

    /** 升の今(重い計算なしで)。 */
    View view(ServerLevel level, int cellX, int cellZ);

    CellLife NONE = new CellLife() {
        @Override
        public Result infect(ServerLevel level, int cellX, int cellZ, int attackers, double seconds) {
            return Result.NOTHING;
        }

        @Override
        public View view(ServerLevel level, int cellX, int cellZ) {
            return View.UNKNOWN;
        }
    };

    /** 差し込まれた中身(無ければ {@link #NONE})。 */
    static CellLife get() {
        return Holder.impl;
    }

    /** 生態系の側から中身を差し込む。 */
    static void register(CellLife impl) {
        Holder.impl = impl != null ? impl : NONE;
    }

    final class Holder {
        static volatile CellLife impl = NONE;

        private Holder() {
        }
    }
}
