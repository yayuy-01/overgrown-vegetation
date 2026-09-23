package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 巨大樹の根のアーチ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature} の
 * {@code inArch} 区間)と同じ「正弦カーブで持ち上げた橋」を、ワールドへ直接 1 本置く小さなヘルパー。
 *
 * <p>汚染の広がり({@link CorruptionSpreader})から3通りに使う:
 * <ul>
 *   <li>汚染の筋から低確率で生える<b>装飾アーチ</b>。</li>
 *   <li>そのうちさらに低確率で、渓谷などの溝をまたぐ<b>上下逆のアーチ</b>(持ち上げに負の値を渡すと
 *   中央が垂れ下がる)。</li>
 *   <li>触手が水でブロックされたときに架ける<b>対岸へ渡る橋</b>(川で汚染が止まらないように)。</li>
 * </ul>
 *
 * <p>両端は水平で軸並行(南北 or 東西)、高さは違ってもよい(基準の高さを線形補間し、その上に
 * 正弦カーブのリフトを足す)。{@link #build} は既存の汚染ブロック・破壊不能ブロックの上には
 * 置かない。
 */
public final class CorruptionArch {

    private CorruptionArch() {
    }

    /**
     * {@code from} から {@code to} へアーチ状の橋を架ける。両端は水平・軸並行であること
     * (南北 or 東西のどちらか一方向にだけ離れていること)。
     *
     * @param peakHeight アーチの中央でどれだけ持ち上げるか(ブロック)。<b>負の値</b>を渡すと上下逆のアーチ
     *                   (中央が垂れ下がる = 溝をまたぐ根)になる。
     * @param thickness  各ステップで置く塊の半径(0 で 1 マス、1 でおおよそ十字断面)
     * @param block      橋に使うブロック状態(通常 {@code overgrown_log})
     * @param crestOut   非 null なら、各ステップのアーチの背(中央列の一番上のブロック座標)を追加していく。
     *                   呼び出し元が背に草・苗木などを生やすのに使う。
     * @param shellOut   非 null なら、このアーチで実際に置き換えた<b>全ブロック</b>の座標を追加していく。
     *                   呼び出し元がアーチの側面・肩まで含めた表面全体に苔・下草・蔦を生やすのに使う
     *                   (背だけでなく、かまぼこ状の盛り上がりの斜面にも草が生えるように)。
     * @return 1 ブロックでも実際に置けたら true
     */
    public static boolean build(ServerLevel level, BlockPos from, BlockPos to, int peakHeight,
            int thickness, BlockState block, LongOpenHashSet touchedChunks,
            @Nullable List<BlockPos> crestOut, @Nullable List<BlockPos> shellOut) {
        int stepX = Integer.signum(to.getX() - from.getX());
        int stepZ = Integer.signum(to.getZ() - from.getZ());
        if (stepX != 0 && stepZ != 0) {
            return false;
        }
        int span = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        if (span < 2) {
            return false;
        }
        // 両端に「乗れる地面」が無いなら架けない(空中に浮いたアーチを防ぐ)。
        if (!hasGroundSupport(level, from) || !hasGroundSupport(level, to)) {
            return false;
        }

        int r = Math.max(0, thickness);
        int peak = peakHeight; // 負なら中央が垂れ下がる上下逆のアーチ。
        boolean placedAny = false;
        for (int i = 0; i <= span; i++) {
            double s = (double) i / span;
            int baseY = (int) Math.round(Mth.lerp(s, from.getY(), to.getY()));
            int lift = (int) Math.round(peak * Math.sin(Math.PI * s));
            int cx = from.getX() + stepX * i;
            int cz = from.getZ() + stepZ * i;
            int cy = baseY + lift;
            placedAny |= blob(level, cx, cy, cz, r, block, touchedChunks, shellOut);
            if (crestOut != null) {
                // 中央列(dx=dz=0)の一番上に置かれるのは cy + r(edgeSq が (r+0.35)² なので dy は r まで届く)。
                crestOut.add(new BlockPos(cx, cy + r, cz));
            }
        }
        return placedAny;
    }

    /**
     * {@code end}(アーチの端点)に「乗れる地面」があるか。端点そのもの、またはその 1〜2 マス下が
     * {@code blocksMotion()} な固体ブロックなら true。水没した固体(水中の地面)も可。
     * 水・空気・草・海草など上に乗れないブロックしか無ければ false。
     */
    private static boolean hasGroundSupport(ServerLevel level, BlockPos end) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy >= -2; dy--) {
            cursor.set(end.getX(), end.getY() + dy, end.getZ());
            if (!level.isLoaded(cursor)) {
                return false;
            }
            if (level.getBlockState(cursor).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    /** {@code (cx, cy, cz)} を中心に半径 {@code r}(+0.35)の角の落ちた塊を橋ブロックで埋める。 */
    private static boolean blob(ServerLevel level, int cx, int cy, int cz, int r, BlockState block,
            LongOpenHashSet touchedChunks, @Nullable List<BlockPos> shellOut) {
        boolean any = false;
        double edgeSq = (r + 0.35D) * (r + 0.35D);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((double) (dx * dx + dy * dy + dz * dz) > edgeSq) {
                        continue;
                    }
                    cursor.set(cx + dx, cy + dy, cz + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState current = level.getBlockState(cursor);
                    if (current.is(ModTags.CORRUPTED)) {
                        // 既にある汚染ブロック(自分の他の部分や別の筋)は上書きしない。
                        continue;
                    }
                    if (current.getBlock().defaultDestroyTime() < 0.0F) {
                        // 岩盤・バリアなど破壊不能ブロックは避ける。
                        continue;
                    }
                    BlockPos immutable = cursor.immutable();
                    level.setBlock(immutable, block, Block.UPDATE_ALL);
                    touchedChunks.add(new ChunkPos(immutable).toLong());
                    if (shellOut != null) {
                        shellOut.add(immutable);
                    }
                    any = true;
                }
            }
        }
        return any;
    }
}
