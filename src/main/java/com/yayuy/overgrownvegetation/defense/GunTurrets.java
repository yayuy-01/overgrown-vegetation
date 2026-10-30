package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>動いている</b>機関砲台({@link GunTurretBlock})の台帳(2026-09-21)。忌避灯の
 * {@link RepellentLamps} とまったく同じ作りで、過繫茂に覆われていない砲台の座標だけを持つ。
 *
 * <p>引くのは {@code entity/ShielderTurretMortarGoal} —— 過繫茂したスカトゥリウムが
 * <b>迫撃砲で砲台を潰しに来る</b>(ユーザー要望 2026-09-21)。胞子爆弾が
 * {@code defense.json} の {@code gunTurret.corruptRadius} 以内で炸裂すると、砲台は胞子に覆われて
 * <b>止まる</b>({@link #corruptNear})。迫撃砲の弾は<b>1 発ごとに 15% の確率</b>(2026-09-24 に 35%、09-25 に 15%)、
 * それ以外の胞子爆弾は必ず止まる。右クリックで拭えば動き出す。
 *
 * <p>登録・解除は {@link com.yayuy.overgrownvegetation.block.GunTurretBlockEntity} の
 * ロード/アンロード/破壊と、{@link GunTurretBlock#setOvergrown} の状態変更から。
 * 通常の汚染({@code corruption/CorruptionRegistry})で覆われたときは
 * {@link GunTurretBlock#onPlace} が拾って外す。
 */
public final class GunTurrets {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> WORKING = new ConcurrentHashMap<>();

    /** その場所の状態を見て、動いている砲台なら登録・そうでなければ解除する。 */
    public static void sync(Level level, BlockPos pos, BlockState state) {
        if (GunTurretBlock.isWorking(state)) {
            WORKING.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
        } else {
            unregister(level, pos);
        }
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = WORKING.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    public static void clearAll() {
        WORKING.clear();
    }

    /** {@code from} から {@code range} 以内で最も近い、動いている砲台。無ければ null。 */
    @Nullable
    public static BlockPos nearestWorking(Level level, Vec3 from, double range) {
        Set<BlockPos> set = WORKING.get(level.dimension());
        if (set == null || set.isEmpty() || range <= 0.0D) {
            return null;
        }
        BlockPos best = null;
        double bestSq = range * range;
        for (BlockPos pos : set) {
            double sq = from.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (sq <= bestSq) {
                bestSq = sq;
                best = pos;
            }
        }
        return best;
    }

    /** そこにまだ動いている砲台があるか(台帳ではなく実際のブロックを見る)。 */
    public static boolean isWorking(Level level, BlockPos pos) {
        return GunTurretBlock.isWorking(level.getBlockState(pos));
    }

    /**
     * {@code center} から {@code radius} 以内の動いている砲台を、胞子で覆って止める。
     * 胞子爆弾が炸裂したときに呼ばれる({@code entity/VelophyllBombEntity})。覆った数を返す。
     *
     * <p><b>砲台ごとに {@code chance} の確率で</b>止まる(ユーザー指定: 2026-09-24「迫撃砲が当たって 35% で無効化」→ 09-25 に 15%)。
     * 外れた砲台は動き続け、台帳にも残るので、スカトゥリウムは次の弾・次の斉射でまた狙う。
     * 確率は弾の種類で決まる: 迫撃砲は {@code defense.json} の {@code gunTurret.mortarDisableChance}(既定 0.15)、
     * それ以外の胞子爆弾は {@code bombDisableChance}(既定 1.0 = 必ず)。
     */
    public static int corruptNear(ServerLevel level, Vec3 center, double radius, double chance, RandomSource random) {
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            return 0; // /ov corruption false(2026-10-10 ユーザー指定): 胞子爆弾でも砲台を覆わない
        }
        Set<BlockPos> set = WORKING.get(level.dimension());
        if (set == null || set.isEmpty() || radius <= 0.0D || chance <= 0.0D) {
            return 0;
        }
        double radiusSq = radius * radius;
        List<BlockPos> hit = new ArrayList<>();
        for (BlockPos pos : set) {
            if (center.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= radiusSq) {
                hit.add(pos);
            }
        }
        int covered = 0;
        for (BlockPos pos : hit) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof GunTurretBlock turret)) {
                unregister(level, pos); // 台帳がずれていた(ブロックが別物に変わっている)
                continue;
            }
            // 稼働中の胞子除去装置の圏内にある砲台は覆われない(ユーザー指定 2026-09-26)。
            // 通常の汚染は canCorrupt で止まるが、爆弾のこの経路はそこを通らないので個別に見る。
            if (com.yayuy.overgrownvegetation.spore.SporeDeviceData.protectsFromCorruption(level, pos)) {
                continue;
            }
            if (chance < 1.0D && random.nextDouble() >= chance) {
                continue; // 胞子を浴びたが持ちこたえた
            }
            turret.setOvergrown(level, pos, state, true);
            covered++;
        }
        return covered;
    }

    private GunTurrets() {
    }
}
