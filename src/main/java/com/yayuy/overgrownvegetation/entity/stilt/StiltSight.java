package com.yayuy.overgrownvegetation.entity.stilt;

import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 竹馬型の「見える・見えない」(2026-11-14、段 2)。バニラの視線({@code Level#clip} の COLLIDER / VISUAL)はガラスで止まり、
 * 柵の隙間も止まることがあるので、決まりを自前で持つ。
 * <ul>
 *   <li><b>通す</b>: 空気・当たりの無いもの(草花・水など)・ガラス・板ガラス(色付きも)・柵・鉄格子(決定 11-10 ユーザー「ガラスや柵ごしでも見える」)。</li>
 *   <li><b>遮る</b>: 葉(森で隠れ直す手を残す。【仮・Claude】)・それ以外の当たりのあるブロック。</li>
 * </ul>
 * 光線はブロックを 1 マスずつたどる(DDA)。始めと終わりのマスは見ない(目と相手の体の中)。
 */
public final class StiltSight {

    private StiltSight() {
    }

    /** 計測用: たどったマスの数。 */
    public static volatile long steps;

    /** from から to が見えるか。 */
    public static boolean canSee(Level level, Vec3 from, Vec3 to) {
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int ex = Mth.floor(to.x), ey = Mth.floor(to.y), ez = Mth.floor(to.z);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dz);
        double tx = dx == 0 ? Double.MAX_VALUE : tdx * (sx > 0 ? (x + 1 - from.x) : (from.x - x));
        double ty = dy == 0 ? Double.MAX_VALUE : tdy * (sy > 0 ? (y + 1 - from.y) : (from.y - y));
        double tz = dz == 0 ? Double.MAX_VALUE : tdz * (sz > 0 ? (z + 1 - from.z) : (from.z - z));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int guard = 0; guard < 512; guard++) {
            if (tx < ty && tx < tz) {
                if (tx > 1.0D) break;
                x += sx;
                tx += tdx;
            } else if (ty < tz) {
                if (ty > 1.0D) break;
                y += sy;
                ty += tdy;
            } else {
                if (tz > 1.0D) break;
                z += sz;
                tz += tdz;
            }
            if (x == ex && y == ey && z == ez) return true;
            steps++;
            if (!level.isLoaded(p.set(x, y, z))) return false;
            if (blocks(level, p)) return false;
        }
        return true;
    }

    /** そのマスが視線を遮るか。 */
    public static boolean blocks(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return false;
        if (s.is(BlockTags.LEAVES) || s.getBlock() instanceof OvergrownLeavesBlock) return true;
        if (seeThrough(s)) return false;
        return !s.getCollisionShape(level, p).isEmpty() || s.canOcclude();
    }

    /** ガラス・板ガラス・柵・鉄格子(見通せる)。 */
    public static boolean seeThrough(BlockState s) {
        return s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS) || s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES)
                || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.getBlock() instanceof IronBarsBlock || s.is(Blocks.IRON_BARS);
    }
}
