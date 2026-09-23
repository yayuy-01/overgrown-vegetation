package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;

/**
 * <b>節を潰されたあと、器官が枯れていく</b>(2026-09-23、要望)。
 *
 * <h2>その場では消さない</h2>
 * <p>節を壊した瞬間に塔が消えると、<b>手応えが無い</b>。
 * 数百マスが一瞬で消えるのは処理としても重い。
 * だから効果だけ先に切り、姿は {@code witherBlocksPerTick} ずつ<b>上から</b>崩していく ——
 * 潰したあとに塔が痩せ細っていくのを見届けられる。
 *
 * <h2>形は覚えていない</h2>
 * <p>どのマスが器官だったかは<b>保存していない</b>。節の位置と種類から
 * {@link OrganShape} が同じ形を計算し直す。だから
 * <ul>
 *     <li>セーブが太らない(塔 1 本で数百マス)</li>
 *     <li>途中でサーバーが落ちても、次に読み込まれたときに最初からやり直せる
 *     (消す作業は何度やっても同じ結果になる)</li>
 * </ul>
 *
 * <h2>読み込まれていないところは飛ばす</h2>
 * <p>チャンクが読み込まれていなければそのマスは飛ばし、<b>節を台帳に残したまま</b>にする。
 * 次にそのあたりが読み込まれたときに続きをやる。
 * 全部飛ばされた(＝誰も近くに居ない)ときは {@link #IDLE_TICKS} だけ休む ——
 * 遠くで誰も見ていない器官のために毎 tick 形を計算し直すのは無駄。
 */
public final class OrganWither {

    /** 何も消せなかったときに休む長さ(tick)。 */
    private static final long IDLE_TICKS = 100L;

    /** いま崩している節({@link BlockPos#asLong})。 */
    private static long currentNode = Long.MIN_VALUE;

    /** その器官のマス(上から順)。 */
    private static List<OrganCell> pending = List.of();

    private static int cursor;

    /** 何も消せなかったあと、次に動いてよい時刻。 */
    private static long wakeAt = Long.MIN_VALUE;

    private OrganWither() {
    }

    /** 1 tick ぶん崩す({@code ClimaxTicker} から)。 */
    public static void tick(ServerLevel level) {
        OrganData data = OrganData.get(level);
        Long2ObjectMap<OrganKind> dying = data.withering();
        if (dying.isEmpty()) {
            reset();
            return;
        }
        long now = level.getGameTime();
        if (now < wakeAt) {
            return;
        }
        if (currentNode == Long.MIN_VALUE || !dying.containsKey(currentNode)) {
            start(level, dying);
            if (currentNode == Long.MIN_VALUE) {
                return;
            }
        }

        BlockPos node = BlockPos.of(currentNode);
        int budget = OrganData.spec().witherBlocksPerTick();
        boolean skipped = false;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        while (budget > 0 && cursor < pending.size()) {
            OrganCell cell = pending.get(cursor++);
            at.set(node.getX() + cell.x(), node.getY() + cell.y(), node.getZ() + cell.z());
            if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) {
                skipped = true;
                continue; // まだ読み込まれていない。次に来たときにやる。
            }
            budget--;
            if (isOrganBlock(level.getBlockState(at))) {
                level.setBlock(at, level.getFluidState(at).createLegacyBlock(), Block.UPDATE_CLIENTS);
            }
        }

        if (cursor < pending.size()) {
            return; // 続きは次の tick。
        }
        if (skipped) {
            // 読み込まれていないぶんが残っている。台帳には残したまま、しばらく休んでやり直す。
            cursor = 0;
            wakeAt = now + IDLE_TICKS;
            return;
        }
        data.witherDone(currentNode);
        reset();
    }

    /** 次に崩す器官を選び、形を計算する。<b>上から</b>崩すので高い順に並べる。 */
    private static void start(ServerLevel level, Long2ObjectMap<OrganKind> dying) {
        reset();
        for (Long2ObjectMap.Entry<OrganKind> entry : dying.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            BlockPos node = BlockPos.of(packed);
            currentNode = packed;
            pending = OrganShape.build(entry.getValue(), level.getSeed(),
                            node.getX(), node.getY(), node.getZ())
                    .stream()
                    .sorted(Comparator.comparingInt(OrganCell::y).reversed())
                    .toList();
            cursor = 0;
            return;
        }
    }

    private static void reset() {
        currentNode = Long.MIN_VALUE;
        pending = List.of();
        cursor = 0;
    }

    /**
     * そのブロックが器官の体か。<b>器官が置いたものだけ</b>を消す ——
     * プレイヤーが跡地に建てたものまで巻き込まない。
     */
    private static boolean isOrganBlock(BlockState state) {
        return state.is(ModBlocks.ORGAN_VINE.get())
                || state.is(ModBlocks.SPORE_GLAND.get())
                || state.is(ModBlocks.OVERGROWN_BLOCK.get());
    }
}
