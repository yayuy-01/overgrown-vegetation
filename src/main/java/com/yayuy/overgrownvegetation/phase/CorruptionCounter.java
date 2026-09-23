package com.yayuy.overgrownvegetation.phase;

import net.minecraft.server.level.ServerLevel;

/**
 * この MOD が新規に汚染したブロック数を {@link PhaseData} の累計へ足し込む入口。
 *
 * <p>数える経路(いずれも「元は普通のブロックだったものを汚染ブロックに変えた」瞬間):
 * <ul>
 *     <li>汚染シダ({@code blight_fern})の侵食</li>
 *     <li>汚染死体ブロックの侵食</li>
 *     <li>過繁茂バイオームの縁のにじみ出し汚染({@code OvergrownBiomeCreep})</li>
 *     <li>過繁茂したクリーパーの爆発による球状汚染</li>
 * </ul>
 *
 * <p>装飾植物(シダ・苔・蔦・茂み)や巨大樹の原木など「汚染ブロックタグは持つが地形変換ではない」
 * ものは数えない。導入前から存在した汚染や、データパックが直接置いた汚染ブロックも数えない
 * (あくまで「導入後にこの MOD が能動的に広げた量」の目安)。
 */
public final class CorruptionCounter {

    /** {@code amount} 個ぶんの新規汚染を累計へ加える。フェーズ1中に閾値を超えれば強制的にフェーズ2へ。 */
    public static void record(ServerLevel level, long amount) {
        if (amount <= 0L) {
            return;
        }
        PhaseData.get(level).addCorruptedBlocks(level, amount);
    }

    private CorruptionCounter() {
    }
}
