package com.yayuy.overgrownvegetation.entity.stilt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * 屋根の下の相手を覗く「開口」の探し(2026-11-14。竹馬型 段 2 のフィロセンスの行動の中から、監視塔と使い回すために切り出した。中身は同じ)。
 *
 * <p>相手のまわり(半径 R の立方体)で、頭を寄せられる開口を探す。1 tick に {@code perTick} マスずつ続きから(段 1 の経路と同じく予算を守る)。
 * 開口 = 視線を通すマス(空気・ガラス・柵・鉄格子)で、横のどれかの向きに外へ 2 マス空いていて(2 マス先は空が見える)、そこから相手の目が見える。
 * 屋根の下のマス(空が見えない = 入口・窓の中)を先に選ぶ。一番近いもの。{@code allow} で使ってよいマスを絞れる(監視塔: 首の届く範囲だけ)。
 */
public final class OpeningSearch {
    public record Result(@Nullable BlockPos cell, Direction dir) {
    }

    private final BlockPos center;
    private final int r;
    @Nullable
    private final Predicate<BlockPos> allow;
    private int index;
    @Nullable
    private BlockPos best;
    private Direction bestDir = Direction.NORTH;
    private double bestScore = Double.MAX_VALUE;
    /** 計測用。 */
    public static volatile long cells;

    public OpeningSearch(BlockPos center, int r) {
        this(center, r, null);
    }

    public OpeningSearch(BlockPos center, int r, @Nullable Predicate<BlockPos> allow) {
        this.center = center;
        this.r = r;
        this.allow = allow;
    }

    /**
     * 真上に屋根が無いか(葉を除いた高さ地図)。空の光({@code canSeeSky})は組んだばかりの屋根にまだ追いついていないことがあり、
     * 家の中を「空が見える」と見た(11-14 GameTest)。葉の下は屋根でない(森の覗きで扱う)。
     */
    public static boolean skyOpen(ServerLevel level, BlockPos p) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) <= p.getY();
    }

    /** 続きを探す。まだ終わっていなければ null。 */
    @Nullable
    public Result step(ServerLevel level, Vec3 eye, Vec3 from, int perTick) {
        int n = 2 * this.r + 1, total = n * n * n;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos(), q = new BlockPos.MutableBlockPos();
        int end = Math.min(total, this.index + perTick);
        for (; this.index < end; this.index++) {
            int i = this.index;
            int dx = i % n - this.r, dy = (i / n) % n - this.r, dz = i / (n * n) - this.r;
            c.set(this.center.getX() + dx, this.center.getY() + dy, this.center.getZ() + dz);
            cells++;
            if (this.allow != null && !this.allow.test(c)) continue;
            if (StiltSight.blocks(level, c)) continue;
            boolean covered = !skyOpen(level, c);
            if (!covered && !StiltSight.seeThrough(level.getBlockState(c))) continue;   // 外の空気のマスは開口でない
            for (Direction d : Direction.Plane.HORIZONTAL) {
                q.set(c.getX() + d.getStepX(), c.getY(), c.getZ() + d.getStepZ());
                if (StiltSight.blocks(level, q) || !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) continue;
                q.set(c.getX() + d.getStepX() * 2, c.getY(), c.getZ() + d.getStepZ() * 2);
                if (StiltSight.blocks(level, q) || !skyOpen(level, q)) continue;
                Vec3 cc = Vec3.atCenterOf(c);
                double score = cc.distanceTo(from) + Math.abs(dy) * 0.5D;
                if (score >= this.bestScore) continue;
                if (!StiltSight.canSee(level, cc.add(d.getStepX() * 0.45D, 0, d.getStepZ() * 0.45D), eye)) continue;
                this.bestScore = score;
                this.best = c.immutable();
                this.bestDir = d;
            }
        }
        if (this.index < total) return null;
        return new Result(this.best, this.bestDir);
    }
}
