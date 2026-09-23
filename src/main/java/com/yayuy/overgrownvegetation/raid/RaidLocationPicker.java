package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.List;

/**
 * レイドの苗床を出現させる地点を抽選する。
 *
 * <p>「全プレイヤーからできる限り遠い地表」抽選を、
 * 「全プレイヤーから {@code minDistance} 以上・いずれかのプレイヤーから {@code maxDistance} 以内・
 * ロード済み・地表として妥当・<b>過繫茂バイオームではない</b>」な帯に絞り込んだもの。
 * レイドは「まだ汚染されていない土地へ新たに汚染を広げる」ためのイベントなので、既に過繫茂
 * バイオーム化した場所には出現させない。ロード済みチャンクに過繫茂バイオーム以外の妥当な地点が
 * 1つも無ければ {@code null} を返す — 呼び出し側({@link RaidData})は少し後に再挑戦するので、
 * プレイヤーが未汚染の土地を読み込んだ時点でそこに湧く。
 *
 * <p><b>必ず本物の地面に出現させる:</b> ハイトマップ({@code MOTION_BLOCKING_NO_LEAVES})は
 * 密林などでは木の幹の天辺を「地表」として返すため、そのまま使うと苗床が木の上に湧いてしまい、
 * 根も汚染も広げられなくなる。そこでハイトマップの高さから下へ潜り、原木・葉・草などの
 * 「木・植生」を全て貫通して、最初に現れる本物の地面ブロックの上を出現位置にする
 * ({@link #descendToGround})。地面が見つからない列は候補から外す。
 */
public final class RaidLocationPicker {

    /** サンプリングする候補点の数。 */
    private static final int SAMPLES = 240;

    /** ハイトマップの高さからこの数だけ下まで探して地面が見つからなければ、その候補は捨てる。 */
    private static final int MAX_DESCEND = 40;

    @Nullable
    public static BlockPos pick(ServerLevel level, int minDistance, int maxDistance) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return null;
        }
        int min = Math.max(1, Math.min(minDistance, maxDistance));
        int max = Math.max(min + 1, Math.max(minDistance, maxDistance));
        RandomSource random = level.random;

        BlockPos best = null;
        double bestScore = -1.0D;

        for (int i = 0; i < SAMPLES; i++) {
            ServerPlayer anchor = players.get(random.nextInt(players.size()));
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = min + random.nextInt(max - min + 1);
            int x = anchor.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = anchor.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

            if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) {
                continue;
            }

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int groundY = descendToGround(level, x, surfaceY, z);
            if (groundY == Integer.MIN_VALUE) {
                // 木・植生を貫通しても本物の地面が見つからなかった(空洞の上など)。
                continue;
            }
            BlockPos pos = new BlockPos(x, groundY, z);

            double nearestSq = minDistanceSqToPlayers(pos, players);
            if (nearestSq < (double) min * min) {
                // 別のプレイヤーが近すぎる。
                continue;
            }
            if (!isValidSurface(level, pos)) {
                continue;
            }
            if (ModBiomes.isOvergrownLike(level.getBiome(pos))) {
                // 既に繁茂系バイオーム化した場所には出現させない(汚染を新たに広げる意味が無い)。
                continue;
            }

            // スコア = 最寄りプレイヤーまでの距離。遠いほど良い(ただし帯の中で)。
            if (nearestSq > bestScore) {
                bestScore = nearestSq;
                best = pos;
            }
        }

        return best;
    }

    /**
     * {@code (x, startY, z)} 列の上端から下へ潜り、木・植生(原木・葉・草・シダ・雪など)を
     * 貫通して、最初に現れる「本物の地面」ブロックの<b>1つ上</b>(=足を置ける Y)を返す。
     * {@value #MAX_DESCEND} ブロック下まで探しても地面が無ければ {@link Integer#MIN_VALUE}。
     */
    private static int descendToGround(ServerLevel level, int x, int startY, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int lowest = Math.max(level.getMinBuildHeight() + 1, startY - MAX_DESCEND);
        for (int y = startY; y >= lowest; y--) {
            p.set(x, y - 1, z); // この y に足を置くなら、地面はその1つ下
            BlockState below = level.getBlockState(p);
            if (isGround(below) && level.getFluidState(p).isEmpty()) {
                // 本物の地面かつ足元が液体でない
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 「木・植生・置換可能なブロック」ではなく、苗床の足場にできる本物の地面か。 */
    private static boolean isGround(BlockState state) {
        if (!state.blocksMotion() || state.canBeReplaced()) {
            return false;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(ModBlocks.OVERGROWN_LOG.get()) || state.is(ModBlocks.GIANT_OVERGROWN_LOG.get())
                || state.is(ModBlocks.OVERGROWN_LEAVES.get())) {
            // 木の一部(バニラ・このMOD双方)は地面扱いしない。
            return false;
        }
        return true;
    }

    private static boolean isValidSurface(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (!isGround(below)) {
            return false;
        }
        // 苗床は高さ3ブロック。頭までのぶんの隙間と足元の水が無いことを確認する。
        for (int dy = 0; dy < 3; dy++) {
            BlockPos p = pos.above(dy);
            if (!level.getFluidState(p).isEmpty()) {
                return false;
            }
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static double minDistanceSqToPlayers(BlockPos pos, List<ServerPlayer> players) {
        double min = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double d = player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (d < min) {
                min = d;
            }
        }
        return min;
    }

    private RaidLocationPicker() {
    }
}
