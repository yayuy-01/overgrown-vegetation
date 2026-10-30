package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 暇な群れが<b>過繁茂バイオームの外へ出る向き</b>を測る(群れ v2、2026-09-25)。
 * ユーザー要望(2026-09-15、v2 でも維持):「グループ4が群れを組んだ際、過繁茂したバイオームの外に出ようとする行動を追加してほしい」。
 *
 * <p>考え方は旧 {@code entity/InvaderPackMarch#exitTarget} と同じ(旧群れのクラスに依存しない写し。旧群れと一緒に消せるように)。
 * 8 方向へ 16 ブロック刻みでバイオームを見て、繁茂系が途切れる距離を方向ごとに測る。違いは 2 つ。
 * <ul>
 *     <li><b>近い順に全部</b>返す。区間が 3 回続けて引けない・進まない(川・崖)ときは、次に近い外へ向きを変える
 *     (レビュー指摘「川で行き止まりになった群れが岸で永久に止まる」)。</li>
 *     <li>チャンクは<b>読み込み済みのものだけ</b>読む({@code getChunkNow})。旧版の {@code hasChunkAt} は
 *     読み込みの途中でも true で、そのままバイオームを読むとメインスレッドが生成を待って止まる(2026-09-24 の重さの監査)。</li>
 * </ul>
 * 読めなかった先は「判らない」であって「外」ではない(旧版と同じ。未ロードを外とみなすと、描画距離の縁へ逃げる形になる)。
 * どの向きも判らなければ、群れの ID から決まる向きへ進む(全員が同じ答え・選び直しても同じ向き)。
 */
final class SwarmHeading {

    /** 外を探す方向の数(45 度刻み)。 */
    private static final int DIRECTIONS = 8;
    /** 1 方向あたりの走査の刻み幅(ブロック)。バイオームは 4×4 単位なのでこれで十分粗い。 */
    private static final double SAMPLE_STEP = 16.0D;
    /** 向きが測れなかったときに進む距離の下限(ブロック)。旧版と同じ。 */
    private static final double BLIND_MARCH = 96.0D;

    /**
     * {@code from} から見た「外」の候補(近い順)。どの向きも外が見つからなければ、ID で決まる向きの 1 点だけ。
     * Y は {@code from} の高さのまま(区間を引くときに地面へ合わせる)。
     */
    static List<Vec3> exits(ServerLevel level, Vec3 from, UUID swarmId, SwarmTable table) {
        double range = table.marchRange();
        double margin = table.marchMargin();
        double[] distance = new double[DIRECTIONS];
        List<Integer> found = new ArrayList<>(DIRECTIONS);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (range > 0.0D) {
            for (int i = 0; i < DIRECTIONS; i++) {
                double angle = i * (Math.PI * 2.0D / DIRECTIONS);
                double dx = Math.cos(angle);
                double dz = Math.sin(angle);
                distance[i] = -1.0D;
                for (double d = SAMPLE_STEP; d <= range; d += SAMPLE_STEP) {
                    cursor.set(Mth.floor(from.x + dx * d), Mth.floor(from.y), Mth.floor(from.z + dz * d));
                    Boolean overgrown = overgrownAt(level, cursor);
                    if (overgrown == null) {
                        // 判らない。ここから先は読まない。
                        break;
                    }
                    if (!overgrown) {
                        distance[i] = d;
                        found.add(i);
                        break;
                    }
                }
            }
        }
        found.sort((a, b) -> Double.compare(distance[a], distance[b]));
        List<Vec3> out = new ArrayList<>(found.size() + 1);
        for (int i : found) {
            double angle = i * (Math.PI * 2.0D / DIRECTIONS);
            double d = distance[i] + margin;
            out.add(new Vec3(from.x + Math.cos(angle) * d, from.y, from.z + Math.sin(angle) * d));
        }
        if (out.isEmpty()) {
            double angle = Math.floorMod(swarmId.hashCode(), 360) * Mth.DEG_TO_RAD;
            double d = Math.max(range, BLIND_MARCH) + margin;
            out.add(new Vec3(from.x + Math.cos(angle) * d, from.y, from.z + Math.sin(angle) * d));
        }
        return List.copyOf(out);
    }

    /**
     * その位置のバイオームが繁茂系か。チャンクが読み込まれていなければ null(判らない)。
     * バイオームはチャンクから直接読む({@code Level#getBiome} の混ぜ合わせは隣のチャンクを読みうる)。
     */
    @Nullable
    static Boolean overgrownAt(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(pos.getX()),
                QuartPos.fromBlock(Mth.clamp(pos.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1)),
                QuartPos.fromBlock(pos.getZ()));
        return ModBiomes.isOvergrownLike(biome);
    }

    private SwarmHeading() {
    }
}
