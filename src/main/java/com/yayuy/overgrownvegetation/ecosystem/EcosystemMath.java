package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.data.EcosystemTable;
import net.minecraft.world.level.ChunkPos;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * 生態系の台帳の<b>計算だけ</b>(2026-09-24)。世界にも台帳にも触らないので、GameTest から直接叩ける。
 *
 * <p>「多様性」(豊かさ)は<b>ワールド全体で 1 つ</b>の 0〜1 の値(2026-10-14 ユーザー指定。前は区域ごと)。
 * 区域({@link #regionKey})は、防衛の兵を出すときの「プレイヤーの近くに過繁茂があるか」の目安にだけ残っている。
 */
public final class EcosystemMath {

    /**
     * 区域のキー。区域の座標 (rx, rz) を {@link ChunkPos#asLong} と同じ形に詰める
     * ({@link ChunkPos#getX(long)} / {@link ChunkPos#getZ(long)} で戻せる)。
     * 負の座標も {@link Math#floorDiv} で切るので、-1 チャンクは区域 -1 に入る(0 ではない)。
     */
    public static long regionKey(int chunkX, int chunkZ, int regionChunks) {
        return ChunkPos.asLong(Math.floorDiv(chunkX, regionChunks), Math.floorDiv(chunkZ, regionChunks));
    }

    /** 豊かさ {@code health} から {@code elapsedTicks} 経ったときの値(直線で戻り、1 で止まる)。 */
    public static double recovered(double health, long elapsedTicks, double perTick) {
        if (elapsedTicks <= 0L) {
            return Math.max(0.0D, Math.min(1.0D, health));
        }
        return Math.max(0.0D, Math.min(1.0D, health + elapsedTicks * perTick));
    }

    /**
     * 防衛の段階(0 = 防衛なし、1〜 = 段階)。{@code thresholds} は大きい順(段階 1 のしきい値が先頭)。
     *
     * <ul>
     *     <li><b>上げる</b>のは、豊かさがしきい値を<b>下回った瞬間</b>。</li>
     *     <li><b>下げる</b>のは、しきい値 + {@code hysteresis} まで<b>戻ってから</b>。
     *     境目で段階が出たり引っ込んだりしないように(豊かさは狩られるたびに減り、時間で少しずつ戻るので、
     *     余裕が無いと 1 体狩るたびに防衛が始まって、数分後に止まる、を繰り返す)。</li>
     * </ul>
     */
    public static int tierFor(double health, int previousTier, double[] thresholds, double hysteresis) {
        int tier = Math.max(0, Math.min(previousTier, thresholds.length));
        while (tier > 0 && health >= thresholds[tier - 1] + hysteresis) {
            tier--;
        }
        while (tier < thresholds.length && health < thresholds[tier]) {
            tier++;
        }
        return tier;
    }

    /**
     * 生態系スポーンの目標個体数を豊かさで減らす。端数は {@code roll}(0〜1 の乱数)で切り上げるかを決めるので、
     * 目標 1 体の種も豊かさ 30% なら 3 回に 1 回ほどしか目標に入らない(期待値が目標 × 豊かさになる)。
     */
    public static int scaledTarget(int target, double health, double roll) {
        if (target <= 0) {
            return 0;
        }
        double scaled = target * Math.max(0.0D, Math.min(1.0D, health));
        int whole = (int) Math.floor(scaled);
        return whole + (roll < scaled - whole ? 1 : 0);
    }

    /**
     * 1 秒あたりのバイオマス(2026-10-14 ユーザー指定)。「毎秒 {@code perSecond}(既定 1)、過繁茂のチャンク
     * {@code chunksPerStep}(既定 100)ごとに {@code perStep}(既定 1)ずつ増える」に、ワールドの多様性を掛ける
     * (グループ2・3 を狩るほど植物の稼ぎが減る)。
     */
    public static double incomePerSecond(double perSecond, double perStep, int chunksPerStep, int overgrownChunks,
                                         double health) {
        int steps = chunksPerStep > 0 ? Math.max(0, overgrownChunks) / chunksPerStep : 0;
        return (perSecond + perStep * steps) * Math.max(0.0D, Math.min(1.0D, health));
    }

    /**
     * 次に湧かせる防衛の兵のグループ。無ければ 0。
     *
     * <p><b>強いグループから</b>見て、「段階が求める頭数に足りていない」「解禁されている」
     * 「バイオマスで払える」を全部満たした最初のグループを返す。払えないときは 1 つ下のグループへ回る
     * (グループ5 の 1500 が払えなくても、グループ4 の 100 なら出す)—— 蓄えが尽きかけた植物は弱い兵しか出せない。
     *
     * @param current  グループ番号 → プレイヤーの周りに今いる、そのグループの防衛の兵の数
     * @param unlocked グループ番号 → 今のフェーズで湧かせられる種がいるか
     */
    public static int chooseDefenderGroup(int tier, IntUnaryOperator current, double biomass,
                                          EcosystemTable table, IntPredicate unlocked) {
        if (tier <= 0) {
            return 0;
        }
        for (int group = EcosystemTable.LAST_DEFENDER_GROUP; group >= EcosystemTable.FIRST_DEFENDER_GROUP; group--) {
            int want = table.defenders(group, tier);
            if (want <= 0 || current.applyAsInt(group) >= want) {
                continue;
            }
            if (biomass < table.cost(group) || !unlocked.test(group)) {
                continue;
            }
            return group;
        }
        return 0;
    }

    private EcosystemMath() {
    }
}
