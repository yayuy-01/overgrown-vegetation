package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

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
 * <p>空気・水・汚染済みのブロックしか入っていないセクション(パレットを見れば分かる)も、
 * 1 マスずつ読まずに飛ばす({@link #mayConvert})。ただしこちらは<b>1 マスにつき 1 ずつ予算を使う</b> ——
 * 1 マスずつ見ていたころと同じ tick で同じマスまで進むようにするため(進み方が変わると、どの tick で
 * どのマスが置き換わるかがずれ、{@code level.random} を引く順番まで変わる)。
 * 途中で読み込みが外れてやり直すチャンクが、読む手間だけ安くなる。
 *
 * <h2>やり終えたかどうかを覚える</h2>
 * <p>終わったチャンクは {@link PhaseData#markDeepConverted} に記録する。記録するのは
 * <b>最後まで終わったときだけ</b>なので、途中でサーバーを落としてもチャンクが読み込まれなく
 * なっても、次に読み込まれたときに最初からやり直す(置換は何度やっても同じ結果になる)。
 *
 * <p>作業中にチャンクが読み込まれなくなったら、その場で仕事を捨てる。
 * {@link ClimaxTicker} が次の読み込みでまた積む。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClimaxDeepConverter {

    /** 作業中・待機中のチャンク。同じチャンクを二重に積まないため順序付きの集合。 */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();

    /**
     * 露出面に植物を生やしたマスが使う予算(2026-09-28)。洞窟かどうかの判定(天井探し)と
     * 最大 6 面の抽選が入るので、<b>ただ置き換えるだけのマスとは桁が違う</b>。
     * 予算を多めに使わせて、1 tick の重さを {@code blocksPerTick} で今までどおり抑えられるようにする
     * (既定 4096 なら、1 tick に生やすのは多くて 256 マス)。
     *
     * <p>洞窟の多いチャンクはそのぶん置き換えに tick がかかる —— 裏で静かに進む処理なので、
     * 1 tick を重くするより時間をかけるほうを選ぶ。
     */
    private static final int PLANT_COST = 16;

    /** いま作業しているチャンク({@link ChunkPos#toLong})。無ければ {@link Long#MIN_VALUE}。 */
    private static long currentKey = Long.MIN_VALUE;

    /** いま作業しているチャンクの進み具合(0 〜 {@code 256 × 高さ})。 */
    private static int cursor;

    private ClimaxDeepConverter() {
    }

    /**
     * ワールドを閉じたら待ち行列と作業中のチャンクを捨てる(キーはチャンクの座標だけなので、残すと
     * 次に開いた別のワールドで、同じ座標のチャンクを岩盤まで置き換えてしまう)。
     * 起動し直したときと同じ状態に戻す。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
        currentKey = Long.MIN_VALUE;
        cursor = 0;
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
        if ((currentKey == Long.MIN_VALUE && QUEUE.isEmpty()) || !TickBudget.mayStart(PerfJob.CLIMAX_DEEP)) {
            return 0;
        }
        long start = TickBudget.begin();
        int placed = 0;
        try {
            placed = work(level, table);
        } finally {
            TickBudget.end(PerfJob.CLIMAX_DEEP, start, placed);
        }
        return placed;
    }

    /** {@link #tick} の中身。サーバーが重いと予算の番人({@link TickBudget})が途中で止める(続きは次の tick)。 */
    private static int work(ServerLevel level, ClimaxTable table) {
        int budget = table.deepConversionBlocksPerTick();
        int placed = 0;
        int deferred = 0;
        while (budget > 0 && (budget == table.deepConversionBlocksPerTick() || TickBudget.timeLeft())) {
            if (currentKey == Long.MIN_VALUE) {
                if (QUEUE.isEmpty()) {
                    break;
                }
                currentKey = QUEUE.removeFirstLong();
                cursor = 0;
            }
            ChunkPos pos = new ChunkPos(currentKey);
            if (!LoadedChunks.loaded(level, pos.x, pos.z)) {
                // 読み込まれなくなった(読み込みの途中も含む。hasChunk はチケットしか見ない)。印は付けない
                // ―― 次に読み込まれたら最初からやり直す。
                currentKey = Long.MIN_VALUE;
                continue;
            }
            if (!LoadedChunks.neighbourhoodLoaded(level, pos.x, pos.z)) {
                // 周りが読み込み範囲の外・読み込みの途中(2026-10-06)。縁のマスの露出判定と植物(横の苔など)は隣の
                // チャンクを読み書きするので、待たずに進めるとサーバースレッドが隣の読み込みを待って止まる。
                // 後ろへ回す(最初からやり直す)。全部が待ちになったらこの tick は終わる。
                QUEUE.add(currentKey);
                currentKey = Long.MIN_VALUE;
                if (++deferred > QUEUE.size()) {
                    break;
                }
                continue;
            }
            Progress progress = advance(level, pos, budget);
            placed += progress.placed();
            budget -= progress.spent();
            if (progress.done()) {
                finish(level, pos);
                currentKey = Long.MIN_VALUE;
            }
        }
        return placed;
    }

    /**
     * 1 チャンクを最後まで置き換え終えたときの後始末。常駐処理({@link #tick})とコマンド・テスト
     * ({@link #convertNow})の両方がここを通る(2026-10-03 にまとめた。足すものは必ずここへ)。
     * <b>1 チャンクにつき 1 回しか通らない</b>(終えたら台帳に記録し、二度と積まれない)。
     */
    private static void finish(ServerLevel level, ChunkPos pos) {
        // 全部を過繫茂ブロックに変えたあとで地脈を敷き直す。極相にした時点では
        // 地表がまだ汚染ブロックでないことがあり(コマンドで無理やり極相にしたときなど)、
        // LeyVeinPlacer が「汚染済みのブロックだけ置き換える」ので 1 つも置けずに終わる。
        LeyVeinPlacer.place(level, pos);
        // 極相の地下の鉱石(順 1、2026-10-03)。置換の後なので、鉱石が「覆われた」扱いにならない。
        ClimaxOrePlacer.place(level, pos);
        // 結晶地帯なら地脈に沿って結晶を突き出させる(地脈を敷いた後に。根元の地脈ブロックを結晶が埋める)。
        EnergyCrystalPlacer.place(level, pos);
        PhaseData.get(level).markDeepConverted(pos);
    }

    /**
     * {@link #advance} の戻り。{@code spent} は「使った予算」(飛ばした空セクションも 1 数える。
     * 変わりようのないマスだけのセクションは、飛ばしても 1 マス 1 ずつ数える)。
     */
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
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean plants = DesignData.climax().deepConversionPlants();
        // 前の tick の続きでセクションの途中から始まるときも、そのセクションを見直す
        // (tick の間に中身が変わっているかもしれないので、前の tick の答えは使わない)。
        boolean resumed = (cursor & 4095) != 0;
        // 2026-09-24 軽量化(報告 224 / 501 / 1472): 稼働中の胞子除去装置がこのチャンクに届かなければ、
        // 1 マスごとの canCorrupt で装置の判定を省く(届かないチャンクでは装置の判定は乱数を引かずに false を
        // 返すだけなので、置き換わるマスも乱数の引き方も同じ)。この tick の中で装置の一覧は変わらない
        // (装置の稼働が切り替わるのは装置自身の tick、装置のブロックは汚染で置き換わらない)ので、答えは使い回す。
        boolean devicesNear = SporeDeviceData.mayInhibitBlocksIn(level, pos, SporeDeviceData.Inhibit.CORRUPTION);
        int sinceTimeCheck = 0;

        while (cursor < volume && spent < budget) {
            // サーバーが重いときは予算の番人がこの tick を早めに切り上げる(2026-10-06。続きは次の tick、結果は同じ)。
            if (++sinceTimeCheck >= 256) {
                sinceTimeCheck = 0;
                if (!TickBudget.timeLeft()) {
                    break;
                }
            }
            // セクションの切れ目。中身が空なら 4096 マスまとめて飛ばす(地上より上の空はこれで終わる)。
            boolean sectionStart = (cursor & 4095) == 0;
            if (sectionStart || resumed) {
                resumed = false;
                int y = minY + (cursor >> 8);
                int index = chunk.getSectionIndex(y);
                if (index >= 0 && index < chunk.getSections().length) {
                    LevelChunkSection section = chunk.getSections()[index];
                    if (sectionStart && section.hasOnlyAir()) {
                        cursor += 4096;
                        spent++;
                        continue;
                    }
                    // 変わりようのないマスしか無い。1 マスずつ見たのと同じだけ予算を使って、
                    // セクションの終わりか、この tick の予算の終わりまで飛ばす。
                    if (!section.maybeHas(ClimaxDeepConverter::mayConvert)) {
                        int skip = Math.min(4096 - (cursor & 4095), budget - spent);
                        cursor += skip;
                        spent += skip;
                        continue;
                    }
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
            if (!CorruptionRegistry.canCorrupt(level, cursorPos, state, devicesNear)) {
                continue;
            }
            BlockState corrupted = CorruptionRegistry.corrupt(level, cursorPos, state, level.random);
            if (CorruptionRegistry.noChange(state, corrupted)) {
                // 対応表に無いブロックを「覆った」マス —— ブロックは置き換わらず記録だけが付く。
                // 置き換えが無くても、その上には普通の汚染ブロックと同じように植物を生やす
                // (2026-09-29 要望)。全層置換は 1 チャンクにつき 1 回しか走らないので、
                // ここで生やし直しが積み重なることはない。
                if (plants && OvergrownLayerData.isCovered(chunk, cursorPos, state)
                        && exposed(level, chunk, cursorPos, probe)) {
                    BlockPos coveredAt = cursorPos.immutable();
                    CorruptionSpreader.growSurfacePlants(level, coveredAt, state,
                            CorruptionSpreader.surfaceGrowthChance(level, coveredAt));
                    spent += PLANT_COST;
                }
                continue;
            }
            // 近隣更新は飛ばす(UPDATE_CLIENTS だけ)。形状更新の連鎖で地下の植物が崩れて
            // アイテムを撒き散らすのを避けるため。クライアントへの再送は ChunkHolder が
            // セクション単位でまとめてくれるので、パケットが溢れることはない。
            BlockPos placedAt = cursorPos.immutable();
            CorruptionRegistry.placeCorrupted(level, placedAt, state, corrupted, Block.UPDATE_CLIENTS);
            placed++;
            if (plants && exposed(level, chunk, cursorPos, probe)) {
                // 空気に接した面にだけ生やす。判定は通常の汚染とまったく同じ経路を通す ——
                // 地下だけ別の植物を生やすと、同じ極相の洞窟が汚染の経路によって違う姿になる。
                CorruptionSpreader.growSurfacePlants(level, placedAt, level.getBlockState(placedAt),
                        CorruptionSpreader.surfaceGrowthChance(level, placedAt));
                // 露出面 1 マスは 1 マスぶんよりずっと重い(洞窟の判定と最大 6 面の抽選)。
                // 予算を多めに使って、1 tick の重さを blocksPerTick で今までどおり抑えられるようにする。
                spent += PLANT_COST;
            }
        }
        return new Progress(placed, spent, cursor >= volume);
    }

    /**
     * そのマスが<b>空気に接している</b>か。岩の中は 6 面すべて塞がっているので、
     * 地下のほとんどのマスはここで落ちる(洞窟の壁と地表だけが通る)。
     *
     * <p>同じチャンクの中は {@link LevelChunk#getBlockState} で直に引く —— チャンクを引き直すより安い。
     */
    private static boolean exposed(ServerLevel level, LevelChunk chunk, BlockPos pos,
                                   BlockPos.MutableBlockPos probe) {
        for (Direction direction : Direction.values()) {
            probe.setWithOffset(pos, direction);
            if (probe.getY() < level.getMinBuildHeight() || probe.getY() >= level.getMaxBuildHeight()) {
                continue;
            }
            boolean sameChunk = (probe.getX() >> 4) == chunk.getPos().x && (probe.getZ() >> 4) == chunk.getPos().z;
            BlockState at = sameChunk ? chunk.getBlockState(probe) : level.getBlockState(probe);
            if (at.isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * このマスは置き換わる<b>かもしれない</b>か。{@code false} のものは、{@link #advance} が 1 マスずつ見ても
     * <b>必ず素通りする</b>もの —— 空気(見る前に飛ばす)と、{@link CorruptionRegistry#canCorrupt} が
     * 世界を一切読まずに {@code false} を返すものだけ:
     * <ul>
     *     <li>溶岩以外の液体(水など)。液体は溶岩の源泉しか通らない</li>
     *     <li>汚染済み({@link ModTags#CORRUPTED})で水などを含まないもの。水を含むものは
     *     canCorrupt が当たり判定の形を引きに行くことがあるので、ここでは飛ばさない</li>
     * </ul>
     * canCorrupt の判定の順番を変えたら、ここも合わせること。迷うものは {@code true}(今までどおり 1 マスずつ見る)に倒す。
     */
    private static boolean mayConvert(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return state.is(Blocks.LAVA);
        }
        return !state.is(ModTags.CORRUPTED) || !state.getFluidState().isEmpty();
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
        finish(level, pos);
        QUEUE.remove(pos.toLong());
        currentKey = savedKey;
        cursor = savedCursor;
        return placed;
    }
}
