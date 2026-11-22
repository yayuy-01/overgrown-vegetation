package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;

/**
 * 構造物にかかる所で汚染の苗が育つとき(2026-11-19 構造物と森の融合 段 2b。浮いた木の直し)。
 *
 * <p>段 1 は木が構造物の部品の箱の中のマスを空気でも全部飛ばしていたので、覆われた建材の上に植わった苗が育つと、箱の中の幹が欠けて
 * 上だけが浮いた(Cataclysm の空の神殿で見つかった)。ユーザー決定「構造物は普通に覆われて汚染される。そのうえで木が構造物に対応した生え方に」
 * により、苗は今どおり植わり、育つときにここで構造物に合わせた形({@link StructureTree#saplingTree})にする。幹が通らなければ育てない(苗のまま)。
 *
 * <p>{@code ForcedTreeFeature}・{@code SigillariaTreeFeature}・{@code TreeFernFeature} の {@code place} の始めに呼ぶ。
 */
public final class StructureSaplingTrees {
    private StructureSaplingTrees() {
    }

    /** 苗のまわり ±3・下 1〜木の高さ + 4 に人工のブロックがあるか(段 3 追記 11-20「木やその根が建物から飛び出したり建物を伝い」)。 */
    static boolean nearArtificial(ServerLevel level, BlockPos origin, FusionTable t) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = origin.getY() - 1; y <= origin.getY() + t.saplingHeightMax + 4; y++) {
            for (int x = origin.getX() - 3; x <= origin.getX() + 3; x++) {
                for (int z = origin.getZ() - 3; z <= origin.getZ() + 3; z++) {
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (Takeover.maybeArtificial(s) && Takeover.isArtificial(level, m, s)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 幹の通り道(苗のまわり ±2・高さ saplingHeightMax + 4)か苗の足元が構造物の守る範囲にかかるか、まわりに人工のブロックがあるなら(段 3)、建物に合わせた木にする。
     * 戻り値: null = 構造物は関係ない(ふつうの木へ)、true = 構造物に合わせた木を置いた、false = 育てない(苗のまま・まだ分からない所)。
     */
    @Nullable
    public static Boolean tryGrow(WorldGenLevel worldLevel, BlockPos origin, RandomSource random) {
        if (!(worldLevel instanceof ServerLevel level)) return null; // 世界生成の並列スレッドでは見ない
        FusionTable t = StructureFusion.table();
        if (!t.enabled || !t.treeEnabled) return null;
        BoundingBox box = new BoundingBox(origin.getX() - 2, origin.getY() - 1, origin.getZ() - 2,
                origin.getX() + 2, origin.getY() + t.saplingHeightMax + 4, origin.getZ() + 2);
        StructureFusion.Query q = StructureFusion.near(level, box);
        if (q.unknown()) return Boolean.FALSE;
        if (q.hits().isEmpty() && !nearArtificial(level, origin, t)) return null; // 段 3 追記 11-20: 記録の外でも、人工のブロックのそばや上の苗は同じ形
        // 根・入口・部屋を見るため、まわりの部品も入れて作る
        StructureFusion.Query wide = StructureFusion.near(level, box.inflatedBy(12));
        StructureTree.Plan p = StructureTree.saplingTree(level, origin, wide.hits(), random.nextLong(), t);
        if (p == null) return Boolean.FALSE;
        StructureTrees.placeAllNow(level, p);
        return Boolean.TRUE;
    }
}
