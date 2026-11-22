package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.SpreadTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * GameTest 用の入口(2026-09-26)。台帳の広がりの内部(パッケージの中だけで使う処理)を、
 * {@code gametest} パッケージのテストから叩くためのもの。ゲームの中からは呼ばない。
 */
public final class LedgerSpreadTestHooks {

    /**
     * 睡眠で {@code ticks} 飛ばしたことにする。<b>フェーズのポイントは入れない</b> —— GameTest のワールドが
     * フェーズ1 以上だと、テストの睡眠でフェーズが上がってレイドや攻城戦が始まってしまうため。
     */
    public static long creditSleep(ServerLevel overworld, long ticks) {
        SpreadTable.Json json = new SpreadTable.Json();
        json.sleepGivesPhasePoints = false;
        return LedgerSpread.creditSleep(overworld, ticks, SpreadTable.fromJson(json));
    }

    /**
     * 読み込まれたチャンク(周りも)を書き換え待ちに載せ、ゲームと同じ道(バイオーム + 芽と萌芽体)でその場で書き換える。
     * 書き換えたら true(除去装置の範囲なら false)。
     */
    public static boolean convertNow(ServerLevel level, ChunkPos pos) {
        return LedgerSpreadCatchUp.convertNow(level, pos);
    }

    /** そのチャンクが書き換え待ちに残っているか。 */
    public static boolean isPending(ServerLevel level, ChunkPos pos) {
        return LedgerSpreadData.get(level.getServer()).isPending(pos.toLong());
    }

    /** 台帳の広がりがそのチャンクを「読み込まれていないので印を付けない」と判断するか(ワールドの設定を今の値で読む)。 */
    public static boolean unloadedBlocked(ServerLevel level, ChunkPos pos) {
        return LedgerSpread.unloadedBlocked(level,
                com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.unloadedSpread(level.getServer()), pos.x, pos.z);
    }

    private LedgerSpreadTestHooks() {
    }
}
