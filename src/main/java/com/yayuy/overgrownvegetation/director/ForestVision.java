package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.VisionTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * <b>森の視界(戦場の霧)</b>(2026-11-20 ユーザー決定。正本は Vault「森の知性(司令塔)」の 11-18 夕・11-20 の節)。
 * 森(司令塔)が知っているのは次の和だけ。ここ以外のプレイヤー・拠点・倒された Mob は知らない。
 * <ol>
 *   <li>森(過繁茂の台帳のチャンク)の縁から {@code forestEdgeBlocks}(64)</li>
 *   <li>偵察する個体の周り {@code scoutBlocks}(32): 拠点へ出した偵察(体のある G1)と、探りに出した偵察({@link ScoutSearch}。体が無い間は司令塔の側の位置)</li>
 *   <li>地脈の線の地下: 地表より {@code leyDepthBlocks} 下で、地脈の線から {@code leyLineBlocks} 以内、森の縁から {@code leyReachBlocks} 以内(地上の拠点は分からない)</li>
 *   <li>フィロセンス(監視塔。見張りの莢の役を兼ねる)の周り {@code watcherBlocks}(育ち切ったもの)</li>
 * </ol>
 * 重さ: 1 回の問いで、台帳の升の引き 81 回 + 偵察と監視塔の数だけの距離の比べ。読み込まれていないチャンクには触らない
 * (地表の高さは読み込まれているときだけ見る)。見張り(20 秒ごと)とプレイヤーの数だけ呼ぶ。
 */
public final class ForestVision {

    private ForestVision() {
    }

    /** 拠点へ出した偵察(体のある G1)。G1Scout#begin で足し、見るときに使いの終わったものを外す。 */
    private static final Set<AbstractParasitizedMonster> BASE_SCOUTS = Collections.newSetFromMap(new WeakHashMap<>());

    static void addBaseScout(AbstractParasitizedMonster m) {
        BASE_SCOUTS.add(m);
    }

    public static VisionTable table() {
        return DesignData.director().vision;
    }

    /** 見えている理由(表示・テスト用)。NONE = 見えない。 */
    public enum Why {
        NONE, OFF, FOREST, SCOUT, LEY, WATCHER
    }

    /** そこが森に見えているか。 */
    public static boolean sees(ServerLevel level, BlockPos pos) {
        return why(level, pos) != Why.NONE;
    }

    public static Why why(ServerLevel level, BlockPos pos) {
        VisionTable t = table();
        if (!t.enabled || !DesignData.director().enabled) return Why.OFF;
        if (level.dimension() != Level.OVERWORLD) return Why.NONE;
        // 11-21 前線と部隊: 森の縁の視界は巨大樹に管理された森だけ(序盤は最初の巨大樹が育ち切るまで視界なし)
        if (nearForest(level, pos.getX() + 0.5D, pos.getZ() + 0.5D, t.forestEdgeBlocks)
                && (!com.yayuy.overgrownvegetation.front.FrontTrees.visionLimited(level)
                || com.yayuy.overgrownvegetation.front.FrontTrees.managed(level, pos.getX() + 0.5D, pos.getZ() + 0.5D))) return Why.FOREST;
        if (nearScout(level, pos, t.scoutBlocks)) return Why.SCOUT;
        if (nearWatcher(level, pos, t.watcherBlocks)) return Why.WATCHER;
        if (underLeyLine(level, pos, t)) return Why.LEY;
        return Why.NONE;
    }

    /** 森(過繁茂の台帳のチャンク)の縁から {@code blocks} 以内か。 */
    public static boolean nearForest(ServerLevel level, double x, double z, double blocks) {
        return forestDistance(level, x, z, blocks) <= blocks;
    }

    /** 森の縁までの水平の距離({@code within} より遠ければ +∞。台帳の升の引きは (2r+1)² 回)。 */
    public static double forestDistance(ServerLevel level, double x, double z, double within) {
        PhaseData pd = PhaseData.get(level);
        int cx = net.minecraft.util.Mth.floor(x) >> 4, cz = net.minecraft.util.Mth.floor(z) >> 4;
        int r = (int) Math.ceil(within / 16.0D);
        double best = Double.POSITIVE_INFINITY;
        net.minecraft.world.phys.Vec3 p = new net.minecraft.world.phys.Vec3(x, 0.0D, z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!pd.isOvergrownKey(ChunkPos.asLong(cx + dx, cz + dz))) continue;
                best = Math.min(best, ForestEdge.toChunk(p, cx + dx, cz + dz));
                if (best <= 0.0D) return 0.0D;
            }
        }
        return best;
    }

    static boolean nearScout(ServerLevel level, BlockPos pos, double r) {
        double r2 = r * r;
        BASE_SCOUTS.removeIf(m -> !m.isAlive() || m.fate() == null || !m.fate().scout().active());
        for (AbstractParasitizedMonster m : BASE_SCOUTS) {
            if (m.level() != level) continue;
            double dx = m.getX() - (pos.getX() + 0.5D), dz = m.getZ() - (pos.getZ() + 0.5D);
            if (dx * dx + dz * dz <= r2) return true;
        }
        return ScoutSearch.get(level.getServer()).anyExplorerWithin(pos.getX() + 0.5D, pos.getZ() + 0.5D, r);
    }

    static boolean nearWatcher(ServerLevel level, BlockPos pos, double r) {
        return nearWatcher(BaseMemory.get(level.getServer()), pos, r);
    }

    /** フィロセンス(育ち切った監視塔)の周り r 以内か(拠点の記憶を渡す形。テストは自分の記憶で)。 */
    public static boolean nearWatcher(BaseMemory memory, BlockPos pos, double r) {
        double r2 = r * r;
        for (BaseMemory.Base b : memory.bases()) {
            for (WatcherSite w : b.watchers()) {
                if (!w.grown()) continue;
                double dx = w.pos().getX() - pos.getX(), dz = w.pos().getZ() - pos.getZ();
                if (dx * dx + dz * dz <= r2) return true;
            }
        }
        return false;
    }

    /** テスト用: 拠点に育ち切ったフィロセンスを 1 本足す。 */
    public static void addGrownWatcherForTests(BaseMemory.Base b, BlockPos pos) {
        WatcherSite w = new WatcherSite(pos, 0.0D);
        w.setGrown();
        b.watchers.add(w);
    }

    /** 地脈の線の地下か(地表は読み込まれているときだけ見る。読み込まれていなければ見えない扱い)。 */
    static boolean underLeyLine(ServerLevel level, BlockPos pos, VisionTable t) {
        if (t.leyLineBlocks <= 0.0D) return false;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return false;
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15);
        if (pos.getY() > surface - t.leyDepthBlocks) return false;
        if (com.yayuy.overgrownvegetation.corruption.LeyLines.distanceToLine(level.getSeed(), pos.getX(), pos.getZ()) > t.leyLineBlocks) return false;
        return nearForest(level, pos.getX() + 0.5D, pos.getZ() + 0.5D, t.leyReachBlocks);
    }
}
