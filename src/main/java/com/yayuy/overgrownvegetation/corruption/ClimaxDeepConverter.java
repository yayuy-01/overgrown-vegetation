package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.organ.OrganPlacer;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 極相になったチャンクを<b>岩盤まで丸ごと</b>過繫茂ブロックへ置き換える(2026-09-22、要望)。
 *
 * <p>「今まで表層だけが汚染されていたものが地下の見えないブロックの部分まで
 * 完全に過繫茂ブロックに置き換えられる」——  極相の二次生成パスの 1 工程目。
 *
 * <h2>何を置き換えるか</h2>
 * <p>判断は<b>通常の汚染とまったく同じ</b>({@link CorruptionRegistry#canCorrupt} →
 * {@link CorruptionRegistry#corrupt})。だから
 * <ul>
 *     <li>岩盤・バリアのような破壊不能ブロックは触らない</li>
 *     <li>チェスト・樽以外の BlockEntity は設定({@code corruptBlockEntities})に従う</li>
 *     <li>胞子除去装置の効果範囲は抜ける</li>
 *     <li><b>対応表に無いブロック</b>(コンクリート・鉄ブロックなど)はその場に残る ——
 *     見た目はそのままに「覆われて動かなくなったブロック」へ差し替わる</li>
 *     <li>水は素通り、溶岩の源泉だけ玄武岩に固まる(設定次第)</li>
 * </ul>
 * ここで独自の判定を足さないこと —— 「地下だけ特別扱い」を作ると、プレイヤーの地下基地が
 * 地上の建物と違う壊れ方をする。
 *
 * <h2>何 tick にも分ける</h2>
 * <p>1 チャンクは 16×16×384 ≒ <b>98000 マス</b>ある。まとめて回すと 1 tick が数百 ms 飛ぶので、
 * {@link ClimaxTable#deepConversionBlocksPerTick()}(既定 4096 = 1 セクションぶん)ずつ進める。
 * 空のセクション({@link LevelChunkSection#hasOnlyAir()})は 4096 マスまとめて飛ばすので、
 * 地上より上の空はほぼ無料で終わる。
 *
 * <h2>やり終えたかどうかを覚える</h2>
 * <p>終わったチャンクは {@link PhaseData#markDeepConverted} に記録する。記録するのは
 * <b>最後まで終わったときだけ</b>なので、途中でサーバーを落としてもチャンクが読み込まれなく
 * なっても、次に読み込まれたときに最初からやり直す(置換は何度やっても同じ結果になる)。
 *
 * <p>作業中にチャンクが読み込まれなくなったら、その場で仕事を捨てる。
 * {@link ClimaxTicker} が次の読み込みでまた積む。
 */
public final class ClimaxDeepConverter {

    /** 作業中・待機中のチャンク。同じチャンクを二重に積まないため順序付きの集合。 */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();

    /** いま作業しているチャンク({@link ChunkPos#toLong})。無ければ {@link Long#MIN_VALUE}。 */
    private static long currentKey = Long.MIN_VALUE;

    /** いま作業しているチャンクの進み具合(0 〜 {@code 256 × 高さ})。 */
    private static int cursor;

    private ClimaxDeepConverter() {
    }

    /**
     * そのチャンクの全層置換を予約する。既に済んでいる・既に積んである・設定で切ってあるときは何もしない。
     *
     * @return 新しく積んだら {@code true}
     */
    public static boolean enqueue(ServerLevel level, ChunkPos pos) {
        if (!DesignData.climax().deepConversion()) {
            return false;
        }
        if (PhaseData.get(level).isDeepConverted(pos)) {
            return false;
        }
        return QUEUE.add(pos.toLong());
    }

    /** いま待っているチャンク数(コマンドの表示用)。 */
    public static int pending() {
        return QUEUE.size() + (currentKey == Long.MIN_VALUE ? 0 : 1);
    }

    /**
     * 待っている仕事を 1 tick ぶん進める。{@link ClimaxTicker} からオーバーワールドの tick で呼ぶ。
     *
     * @return この tick で実際に置き換えたブロック数
     */
    public static int tick(ServerLevel level) {
        ClimaxTable table = DesignData.climax();
        if (!table.deepConversion()) {
            return 0;
        }
        int budget = table.deepConversionBlocksPerTick();
        int placed = 0;
        while (budget > 0) {
            if (currentKey == Long.MIN_VALUE) {
                if (QUEUE.isEmpty()) {
                    break;
                }
                currentKey = QUEUE.removeFirstLong();
                cursor = 0;
            }
            ChunkPos pos = new ChunkPos(currentKey);
            if (!level.hasChunk(pos.x, pos.z)) {
                // 読み込まれなくなった。印は付けない ―― 次に読み込まれたら最初からやり直す。
                currentKey = Long.MIN_VALUE;
                continue;
            }
            Progress progress = advance(level, pos, budget);
            placed += progress.placed();
            budget -= progress.spent();
            if (progress.done()) {
                // 全部を過繫茂ブロックに変えたあとで地脈を敷き直す。極相にした時点では
                // 地表がまだ汚染ブロックでないことがあり(コマンドで無理やり極相にしたときなど)、
                // LeyVeinPlacer が「汚染済みのブロックだけ置き換える」ので 1 つも置けずに終わる。
                LeyVeinPlacer.place(level, pos);
                PhaseData.get(level).markDeepConverted(pos);
                // 器官はこのチャンクが完全に飲み込まれてから育つ(1 チャンクにつきここを 1 回しか通らない)。
                OrganPlacer.maybeGrow(level, pos);
                currentKey = Long.MIN_VALUE;
            }
        }
        return placed;
    }

    /** {@link #advance} の戻り。{@code spent} は「使った予算」(飛ばした空セクションも 1 数える)。 */
    private record Progress(int placed, int spent, boolean done) {
    }

    /** いま作業しているチャンクを {@code budget} マスぶん進める。 */
    private static Progress advance(ServerLevel level, ChunkPos pos, int budget) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        int minY = level.getMinBuildHeight();
        int volume = level.getHeight() * 256;
        int spent = 0;
        int placed = 0;
        BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

        while (cursor < volume && spent < budget) {
            // セクションの切れ目。中身が空なら 4096 マスまとめて飛ばす(地上より上の空はこれで終わる)。
            if ((cursor & 4095) == 0) {
                int y = minY + (cursor >> 8);
                int index = chunk.getSectionIndex(y);
                if (index >= 0 && index < chunk.getSections().length
                        && chunk.getSections()[index].hasOnlyAir()) {
                    cursor += 4096;
                    spent++;
                    continue;
                }
            }
            int y = minY + (cursor >> 8);
            int local = cursor & 255;
            cursorPos.set(pos.getMinBlockX() + (local & 15), y, pos.getMinBlockZ() + (local >> 4));
            cursor++;
            spent++;

            BlockState state = chunk.getBlockState(cursorPos);
            if (state.isAir()) {
                continue;
            }
            if (!CorruptionRegistry.canCorrupt(level, cursorPos, state)) {
                continue;
            }
            BlockState corrupted = CorruptionRegistry.corrupt(level, cursorPos, state, level.random);
            if (CorruptionRegistry.noChange(state, corrupted)) {
                continue;
            }
            // 近隣更新は飛ばす(UPDATE_CLIENTS だけ)。形状更新の連鎖で地下の植物が崩れて
            // アイテムを撒き散らすのを避けるため。クライアントへの再送は ChunkHolder が
            // セクション単位でまとめてくれるので、パケットが溢れることはない。
            CorruptionRegistry.placeCorrupted(level, cursorPos.immutable(), state, corrupted,
                    Block.UPDATE_CLIENTS);
            placed++;
        }
        return new Progress(placed, spent, cursor >= volume);
    }

    /**
     * そのチャンクをその場で最後まで置き換える(コマンド・テスト用)。tick を分けないので、
     * <b>常駐処理からは呼ばないこと</b>。
     *
     * @return 置き換えたブロック数
     */
    public static int convertNow(ServerLevel level, ChunkPos pos) {
        if (!level.hasChunk(pos.x, pos.z)) {
            return 0;
        }
        long savedKey = currentKey;
        int savedCursor = cursor;
        currentKey = pos.toLong();
        cursor = 0;
        int placed = 0;
        Progress progress;
        do {
            progress = advance(level, pos, Integer.MAX_VALUE);
            placed += progress.placed();
        } while (!progress.done());
        LeyVeinPlacer.place(level, pos);
        PhaseData.get(level).markDeepConverted(pos);
        // 器官はこのチャンクが完全に飲み込まれてから育つ(1 チャンクにつきここを 1 回しか通らない)。
        OrganPlacer.maybeGrow(level, pos);
        QUEUE.remove(pos.toLong());
        currentKey = savedKey;
        cursor = savedCursor;
        return placed;
    }
}
