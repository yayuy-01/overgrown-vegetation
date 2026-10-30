package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * メガラクネが陸に上がってしまったとき、周囲を広めに探して<b>いちばん近い水</b>へ向かうゴール。
 * バニラの {@link net.minecraft.world.entity.ai.goal.TryFindWaterGoal}(近距離・1マス水でも可)より
 * 探索半径が広く、追跡対象がいない限り水に浸かるまで諦めない。
 */
public class MegarachneReturnToWaterGoal extends Goal {

    private static final int SEARCH_RADIUS = 12;
    private static final int VERTICAL = 6;

    private final PathfinderMob mob;
    private final double speed;
    private BlockPos targetWater;
    /**
     * 行けない水へ、2 tick ごとの canUse → start で同じマス同士の経路探索をやり直さない(2026-09-24)。
     * ゴールは経路を歩き終えるたびに止まって始め直すので、控えは start / stop をまたいでこのゴールが持つ。
     */
    private final PathRetryMemo pathMemo = new PathRetryMemo();

    /** 水探しの答えを、同じマスに居るあいだ使い回す時間(tick)。仮決め 2026-09-24(軽量化、報告 321)。 */
    private static final int SCAN_REUSE_TICKS = 20;
    /** 最後の水探しの答え(null = 見つからなかった)と、そのときの足元({@link BlockPos#asLong})・ゲーム時刻。 */
    private BlockPos scanResult;
    private boolean scanHeld;
    private long scanFrom;
    private long scanAt;

    public MegarachneReturnToWaterGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isInWater() || this.mob.getTarget() != null) {
            return false;
        }
        // 同じマスからの水探しは SCAN_REUSE_TICKS のあいだ答えを使い回す(2026-09-24 軽量化、報告 321)。
        // 答えは足元のブロックと周りの地形だけで決まる(距離もブロック単位)。水が見つからないとき・行けない水の手前で
        // 立ち止まっているときに、2 tick ごとに水のあるセクションを読み直していた。違いが出るのは、その間に
        // 近くの水が増えた・減ったときだけで、それも SCAN_REUSE_TICKS 以内に探し直す。
        long now = this.mob.level().getGameTime();
        long here = this.mob.blockPosition().asLong();
        if (!this.scanHeld || here != this.scanFrom || now - this.scanAt >= SCAN_REUSE_TICKS) {
            this.scanResult = findNearestWater();
            this.scanHeld = true;
            this.scanFrom = here;
            this.scanAt = now;
        }
        this.targetWater = this.scanResult;
        return this.targetWater != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.mob.isInWater()
                && this.mob.getTarget() == null
                && this.targetWater != null
                && !this.mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        // createPath(targetWater, 0) → moveTo と同じ。前回届かなかったのと同じマス同士なら探索を省く
        // (歩き終えた経路のままなので、元と同じく canContinueToUse で止まる)。
        this.pathMemo.moveTo(this.mob, this.targetWater, 0, this.speed);
    }

    @Override
    public void stop() {
        this.targetWater = null;
    }

    private BlockPos findNearestWater() {
        BlockPos origin = this.mob.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        // 陸に上がっている間は毎 tick 25×25×13 ≒ 8000 マスを読むので、チャンクキャッシュ付きの読みにする
        // (結果は Level#getFluidState と同じ)。
        ChunkCachedReader reader = new ChunkCachedReader(this.mob.level());
        // 水が見つからないまま陸にいると毎回 8000 マスを全部読むことになるので、<b>水を持ちえないセクションは
        // 読まずに飛ばす</b>(2026-09-24)。流体はブロックの状態から決まる(水浸しのブロックも含む)ので、
        // セクションのパレットに水を持つ状態が 1 つも無ければ、そのセクションのどのマスも水ではない。
        // 読む順番・比べ方・チャンクの引き方(未ロードなら同期ロード)は元のままで、結果は変わらない。
        // 縦の範囲が高さ範囲からはみ出すとき(ワールドの上下の端)は、飛ばさず全部読む。
        boolean skipDry = !reader.isOutsideBuildHeight(origin.getY() - VERTICAL)
                && !reader.isOutsideBuildHeight(origin.getY() + VERTICAL);
        int minChunkX = SectionPos.blockToSectionCoord(origin.getX() - SEARCH_RADIUS);
        int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ() - SEARCH_RADIUS);
        int minSectionY = SectionPos.blockToSectionCoord(origin.getY() - VERTICAL);
        int chunksZ = SectionPos.blockToSectionCoord(origin.getZ() + SEARCH_RADIUS) - minChunkZ + 1;
        int sectionsY = SectionPos.blockToSectionCoord(origin.getY() + VERTICAL) - minSectionY + 1;
        int chunksX = SectionPos.blockToSectionCoord(origin.getX() + SEARCH_RADIUS) - minChunkX + 1;
        // 範囲のセクション(高々 3×3×2)ごとに「水がありうるか」。0 = 未確認、1 = ありうる、-1 = 無い。
        byte[] waterInSection = new byte[chunksX * chunksZ * sectionsY];
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                // 元の走査がこの列の最初のマスで引くのと同じ呼び出し。未ロードなら null で、そのときは
                // この列を元どおり 1 マスずつ読む(その読みが同期ロードする)。
                LevelChunk chunk = skipDry ? reader.chunkNow(x, z) : null;
                int columnBase = ((SectionPos.blockToSectionCoord(x) - minChunkX) * chunksZ
                        + (SectionPos.blockToSectionCoord(z) - minChunkZ)) * sectionsY - minSectionY;
                for (int dy = -VERTICAL; dy <= VERTICAL; dy++) {
                    int y = origin.getY() + dy;
                    if (chunk != null
                            && !mayHoldWater(chunk, y, waterInSection, columnBase + SectionPos.blockToSectionCoord(y))) {
                        continue;
                    }
                    if (!reader.getFluidState(x, y, z).is(FluidTags.WATER)) {
                        continue;
                    }
                    // 少しでも深い水を優先(上に水があるマス)。
                    double bias = reader.getFluidState(x, y + 1, z).is(FluidTags.WATER) ? 0.0D : 8.0D;
                    double distSq = origin.distToLowCornerSqr(x, y, z) + bias;
                    if (distSq < bestSq) {
                        bestSq = distSq;
                        best = new BlockPos(x, y, z);
                    }
                }
                if (skipDry && chunk == null) {
                    // この列でチャンクを同期ロードした。ロードの間に他のチャンクのブロックが変わることも
                    // ありうるので、確認済みのセクションは全部忘れて確認し直す。
                    Arrays.fill(waterInSection, (byte) 0);
                }
            }
        }
        return best;
    }

    /**
     * このセクションのマスを読んだとき、水が返りうるか({@code cache[index]} に覚える)。
     * {@code false} なら確実に水は無い。空気だけのセクションは {@link LevelChunk#getFluidState} が
     * 中身を見ずに EMPTY を返すので、それに合わせる。
     */
    private static boolean mayHoldWater(LevelChunk chunk, int y, byte[] cache, int index) {
        if (cache[index] == 0) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            boolean may = section.hasOnlyAir()
                    ? Fluids.EMPTY.defaultFluidState().is(FluidTags.WATER)
                    : section.maybeHas(state -> state.getFluidState().is(FluidTags.WATER));
            cache[index] = may ? (byte) 1 : (byte) -1;
        }
        return cache[index] > 0;
    }
}
