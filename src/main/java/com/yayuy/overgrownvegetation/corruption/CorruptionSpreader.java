package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import com.yayuy.overgrownvegetation.block.UndevelopedBushBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import com.yayuy.overgrownvegetation.util.ChunkCachedReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 起点から不規則に(木の根・血管のような分岐した筋で)汚染を広げる本体。
 *
 * <h2>アルゴリズム</h2>
 * <ol>
 *     <li>{@link #seedGround} で起点(通常はエンティティの座標)の真下のブロックを
 *         必ず汚染ブロックへ置き換えておく。起点自体(エンティティの足元)は空気なので
 *         汚染できず、これが無いと後続のフェーズの足がかりが無いまま汚染が一切始まらない。</li>
 *     <li>起点を中心に半径 {@code coreRadius} の「コア」を広げる({@link #stepCore})。
 *         木の根のように特定方向へ伸び続けるのではなく、汚染したばかりのマスに隣接する
 *         (半径内・露出面などの条件を満たす)候補をいったん洗い出し、その中からランダムに
 *         最大 {@value #CORE_BRANCHES_PER_CELL} マスだけを選んで次の展開先にする、を
 *         繰り返す。1マスから毎回全方向には広がらないので、円盤状に隙間なく埋まるのではなく
 *         苔や地衣類のようなまだらな広がり方になる({@code coreRadius} が 0 ならこのフェーズは
 *         実質1マスで即終わり、旧仕様と同じ)。</li>
 *     <li>コアが埋まったら、コアの縁(まだ外側に伸びる余地があるマス)から、ランダムな
 *         位置・向きを持つ複数本の「根」({@link Tendril}、{@code root=true})を伸ばし始める
 *         ({@code initialCount})。</li>
 *     <li>1 手ごとに、進める触手を 1 本選んで 1 ブロックだけ前進させる。根が 1 本でも
 *         生きていればその中からランダムに選び、根が全て枯れきる(あるいは半径に到達し
 *         尽きる)まで、根から分岐した「枝」(root=false)は一切前進しない({@link #pickTendrilIndex})。
 *         これにより「まず根が放射状に遠くまで伸びきり、それから根沿いに横向きの枝が湧いて
 *         残ったスペースを埋めていく」という順序で汚染が広がる。
 *         進む方向は「直前と同じ方向へまっすぐ」を優先しつつ({@code tendrilStraightChance})、
 *         塞がっていれば残りの横4方向をランダムな順に試す(来た方向へは戻らない)。
 *         進めた先が汚染できるブロックなら実際に置き換え、進めた先が既に汚染済み
 *         (ワールド再読み込み直後の復元時など)ならその場所を踏み台にするだけで置き換えは行わない。
 *         どの方向にも進めない場合、経路を(ブロックは置き換えずに)1マスだけ後戻りして
 *         別の方向を試す。これを {@code tendrilBacktrackBudget} が尽きるか経路が起点まで
 *         戻りきるまで繰り返し、それでも進めなければ本当にそこで枯れる(消滅する)。</li>
 *     <li>実際に置き換えが起きた場所では、確率で新しい触手(枝、root=false)がそこから分岐する
 *         ({@code tendrilBranchChance}、上限 {@code maxActive})。</li>
 *     <li>空気・免疫ブロックなど汚染できないブロックへは触手が進めないので、経路はそこで
 *         途切れる。露出していない(内部の)マスへ進む場合はさらに、直近の露出面のあるマスから
 *         何ブロック連続で内部を辿ったか(深さ)を触手ごとに追跡し、呼び出し元が指定する
 *         {@code maxInteriorDepth} を超える方向へは進めない(0 を指定すれば露出面のあるマスしか
 *         汚染しない)。露出面のあるマスへ進むと深さは 0 にリセットされる。</li>
 *     <li>全ての触手が枯れたら完了。</li>
 * </ol>
 *
 * <p>各触手が「まっすぐ伸びる/たまに曲がる/たまに枝分かれする/行き止まりで枯れる」を
 * 繰り返すことで、円盤や球を塗りつぶすのではなく、木の根や血管のように隙間の残る
 * 不規則な筋として汚染が広がっていく。起点のごく周辺(コア)も、密な塗りつぶしではなく
 * 隣接する露出面からランダムに数マスずつ選んで広がるまだらな見た目にしたうえで、
 * そこから外側へ触手が伸びるという、全体としてまだらな染みが外側へ根を伸ばしていく
 * ような見た目になる。半径 R による上限は変わらず維持する。
 *
 * <p>1 tick あたりの「置換数」と「走査数」の両方に上限を設けているので、
 * 空洞だらけの領域でもサーバーが固まらない。
 */
public class CorruptionSpreader {

    /** 現在アクティブな触手のリスト。要素数は {@code tendrilMaxActive} で頭打ちになる。 */
    private final List<Tendril> tendrils = new ArrayList<>();
    /**
     * どれかの触手・あるいはコアが一度でも踏んだ(踏み台にした、または新たに汚染した)
     * オフセットの集合({@link BlockPos#asLong(int, int, int)})。
     * 同じマスへ複数の触手/コアが重複して進まないようにする。
     */
    private final LongOpenHashSet claimed = new LongOpenHashSet();
    /**
     * 起点周辺を広げる「コア」フェーズ用の作業キュー。各マスから条件を満たす近傍を洗い出した
     * うえで、そのうちランダムに最大 {@value #CORE_BRANCHES_PER_CELL} マスだけを次の展開先として
     * 積むので、{@code coreRadius} の中も隙間なく埋まらずまだらに広がる({@link #stepCore} 参照)。
     * コアが完了したら空になり、以後は使わない。
     */
    private final LongArrayList coreQueue = new LongArrayList();
    /** コアの各オフセットの、直近の露出面のあるマスからの深さ(触手の {@code interiorDepth} と同じ考え方)。 */
    private final Long2IntOpenHashMap coreDepth = new Long2IntOpenHashMap();
    {
        coreDepth.defaultReturnValue(-1);
    }
    /**
     * コアが実際に踏破した(踏み台にした、または新たに汚染した)全オフセット。
     * コア完了時に「まだ外側に伸びる余地があるマス」を洗い出して触手の出発点にするために使う
     * ({@link #seedTendrilsFromCoreEdge}}。コア完了後は不要になるので手放す。
     */
    private final LongArrayList coreCells = new LongArrayList();
    /** コアフェーズが完了しているか(コア半径0の場合は最初の1マスの処理で即座に完了する)。 */
    private boolean coreDone = true;
    /**
     * {@link #tick} 専用の使い回しスクラッチ座標。呼ぶたびに new すると、
     * 感染を広げる Mob が同時に大量にいる場合に GC 負荷が積み重なるので、
     * インスタンスに 1 組だけ持たせて毎回上書きして使う。
     */
    private final BlockPos.MutableBlockPos corePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos exposureScratch = new BlockPos.MutableBlockPos();
    /** {@link #redirectSnowToGround} が積もった雪の真下の座標を書き込むための使い回しスクラッチ。 */
    private final BlockPos.MutableBlockPos groundPosScratch = new BlockPos.MutableBlockPos();
    /** {@link #stepCore} が1マス分の候補方向を貯めるための使い回しスクラッチ(最大6方向)。 */
    private final Direction[] coreCandidateDirs = new Direction[Direction.values().length];
    private final int[] coreCandidateDepths = new int[Direction.values().length];
    /**
     * {@link #stepTendril} が試す方向を優先順位順に並べるための使い回しスクラッチ
     * (最大 6 方向: 原木優先 1 + 通常 5)。{@link #orderedDirections} が埋めて個数を返す。
     * 以前は 1 歩ごとに配列を 2〜3 個 new していた(1 サイクルで最大 {@code checksPerCycle} 歩)。
     */
    private final Direction[] orderedDirScratch = new Direction[6];
    /** {@link #orderedDirections} が横 4 方向をシャッフルするための使い回しスクラッチ。 */
    private final Direction[] perpendicularScratch = new Direction[4];
    /**
     * 同じ tick 内のブロック読み用のチャンクキャッシュ。{@link #tick} と {@link #seedGround} の先頭で
     * {@link #readerFor} が張り直す(tick をまたいで古いチャンクを使わないため)。読みの結果は
     * {@code Level#getBlockState} / {@code Level#isLoaded} と同じ。
     */
    @Nullable
    private ChunkCachedReader reader;
    /**
     * {@link #isExposed} が 6 面を見る順。「1 面でも露出していれば true」なので順序で結果は変わらないが、
     * 地表のマス(圧倒的多数)は上面を最初に見れば 1 回の読みで済む。
     */
    private static final Direction[] EXPOSURE_ORDER = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    /** {@code facing} ごとの「facing とその反対以外の 4 方向」({@link Direction#values()} 順)。 */
    private static final Direction[][] PERPENDICULARS = buildPerpendiculars();
    /**
     * コアフェーズで、起点(足元)以外の1マスから次に展開する方向を何方向だけランダムに選ぶか。
     * 1にすることで、1マスからは常にランダムな1方向だけへ「1ブロックずつ」広がり、同じマスから
     * 複数方向へ同時に伸びて太い筋になることがなくなる。実際に複数方向へ広がる枝分かれは、
     * 起点だけが持つ {@link OvergrownVegetationConfig.Common#coreInitialBranches} 本の
     * 初期分岐でまかなう({@link #coreSeedExpanded} 参照)。
     */
    private static final int CORE_BRANCHES_PER_CELL = 1;
    /** 生えたばかりの「未発達の茂み」に最初から実が付いている確率(光るツタと同じ値)。 */
    private static final float UNDEVELOPED_BUSH_BERRY_CHANCE = 0.11F;
    /**
     * コアの起点(足元)セルを {@link #stepCore} で展開済みか。起点だけは
     * {@code coreInitialBranches} 本のランダムな方向へ最初に分岐させ(複数の独立した
     * 1本ずつの筋がそこから伸び始める)、それ以外の全てのマスは常に {@link #CORE_BRANCHES_PER_CELL}
     * (=1)方向だけへ進む。
     */
    private boolean coreSeedExpanded = false;
    /** 触手を起点だけの状態で初期化済みか(ワールド再読み込みのたびに立て直す)。 */
    private boolean seeded = false;
    /** {@link #seedGround} を(結果によらず)一度でも試したか。 */
    private boolean groundSeeded = false;

    private boolean finished = false;
    /** これまでに置き換えたブロック数(デバッグ・表示用)。 */
    private int corruptedCount = 0;
    /** この個体がこれまでに架けた「川の橋」の本数({@code corruptionBridgeMaxCount} で頭打ち)。 */
    private int bridgesBuilt = 0;
    /** 前回 tick 時点の半径。変化を検知して触手を立て直すために使う。 */
    private int activeRadius = -1;
    /** 前回 tick 時点の内部侵食の深さ上限。変化を検知して触手を立て直すために使う。 */
    private int activeInteriorDepth = -1;
    /** 前回 tick 時点のコア半径。変化を検知して触手を立て直すために使う。 */
    private int activeCoreRadius = -1;
    /**
     * 装飾アーチ・川の橋の生成確率にかける倍率(既定 1.0)。レイドの汚染起点
     * ({@link com.yayuy.overgrownvegetation.raid.RaidCorruptionNode})は起点を大量に撒くため、
     * ここを下げてアーチが並びすぎないようにしている。状態ではなく設定値なので NBT には保存しない
     * (読み込み側で毎回セットし直す)。
     */
    private double decorationChanceMultiplier = 1.0D;

    public boolean isFinished() {
        return finished;
    }

    /** 装飾アーチ・橋の生成確率にかける倍率を設定する({@code 0.0}〜)。 */
    public void setDecorationChanceMultiplier(double multiplier) {
        this.decorationChanceMultiplier = Math.max(0.0D, multiplier);
    }

    public int getCorruptedCount() {
        return corruptedCount;
    }

    /**
     * 今この瞬間の「1サイクルで置き換える最大ブロック数」を、これまでにこの個体が実際に
     * 汚染したブロック数({@link #corruptedCount})に応じて算出する。{@code blocksPerCycle}
     * (開始値)から始まり、{@code blocksPerCycleGrowthPer} ブロック汚染するごとに1ずつ増え、
     * {@code blocksPerCycleMax} で頭打ちになる。{@code blocksPerCycleGrowthPer} が0なら
     * 常に開始値のまま(旧仕様)。汚染範囲が広がるほど同時に置き換わる数も増えていく効果を狙ったもの。
     * 最後にワールドの難易度「汚染シダの汚染速度」(2026-09-25 からワールドごと。ワールドの値が無ければ config の
     * {@code corruptionSpeedMultiplier}、既定1.0。{@code /ov corruption speed}・{@code /ov difficulty}・葉っぱのボタンの画面で
     * 変えられる)を掛ける — blight_fern・汚染死体ブロックの
     * どちらにもこの倍率が効く。さらに、フェーズ1開始の地割れの胞子噴出エフェクトが出ている間は
     * {@code quakeEruptionCorruptionMultiplier}(既定5.0)も上乗せで掛かる。
     */
    private int effectiveBlocksPerCycle(ServerLevel level) {
        final int base = OvergrownVegetationConfig.COMMON.blocksPerCycle.get();
        final int growthPer = OvergrownVegetationConfig.COMMON.blocksPerCycleGrowthPer.get();
        long grown;
        if (growthPer <= 0) {
            grown = base;
        } else {
            final int max = OvergrownVegetationConfig.COMMON.blocksPerCycleMax.get();
            grown = Math.min(max, base + (long) corruptedCount / growthPer);
        }
        double multiplier = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.corruptionSpeed(level.getServer());
        if (com.yayuy.overgrownvegetation.quake.EarthquakeData.isEruptionActive()) {
            multiplier *= OvergrownVegetationConfig.COMMON.quakeEruptionCorruptionMultiplier.get();
        }
        long scaled = Math.round(grown * multiplier);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
    }

    /** 侵食を最初からやり直す。 */
    public void reset() {
        finished = false;
        seeded = false;
        groundSeeded = false;
        freeTendrilMemory();
    }

    /**
     * 触手・コア関連のコレクションを空にする。侵食が完了した Mob や、半径/深さ/コア半径の
     * 変更で立て直す際に呼ぶ。感染を広げる Mob が世界に大量に居続けても、使い終わった
     * (=もう二度と使わない)このコレクションだけでも積み重なるとメモリを圧迫するため、
     * 内部配列も縮小して手放す。
     */
    private void freeTendrilMemory() {
        tendrils.clear();
        claimed.clear();
        claimed.trim();
        coreQueue.clear();
        coreQueue.trim();
        coreDepth.clear();
        coreDepth.trim();
        coreCells.clear();
        coreCells.trim();
    }

    /**
     * スポーン直後に毎 tick 呼ぶこと。起点(通常はエンティティの座標)の真下のブロックを
     * 必ず汚染済みブロックへ置き換え、触手を伸ばし始める最初の足がかりを作る。
     *
     * <p>{@link #tick} の触手はこの起点の直下から伸び始める前提になっているため、
     * これを呼ばずに置き換えが起きないと(起点自体は空気で汚染できないので)
     * 隣接するブロックが存在せず、汚染が一切始まらない。
     *
     * <p>一度でも試したら(成功・失敗を問わず)以降は何もしない。既に汚染済みタグを
     * 持つブロックだった場合や、免疫・破壊不能・液体などで汚染できない場合は諦める。
     */
    public void seedGround(ServerLevel level, BlockPos origin) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 侵食しない(触手・終わったかどうかはそのまま残り、オンで続きから)
        }
        if (groundSeeded) {
            return;
        }

        BlockPos belowPos = origin.below();
        if (!level.isLoaded(belowPos)) {
            // チャンク未ロードなら次の tick で再試行する
            return;
        }
        groundSeeded = true;

        BlockPos targetPos = belowPos;
        BlockState state = level.getBlockState(belowPos);

        BlockState groundState = redirectSnowToGround(readerFor(level), belowPos, state);
        if (groundState != null) {
            targetPos = groundPosScratch.immutable();
            state = groundState;
        }

        if (state.is(ModTags.CORRUPTED)) {
            // 既に汚染済みなら、tick() 側の触手がそこから伸びてくれる
            return;
        }
        if (!CorruptionRegistry.canCorrupt(level, targetPos, state)) {
            return;
        }

        // 覆っただけのマスは #corrupted に入らないので、既に覆われていても毎回ここへ来る。
        // 植物を生やすのは「今 覆ったとき」だけにしないと、同じ壁に何度も抽選が走って生え続ける。
        boolean coveredBefore = OvergrownLayerData.isCovered(level, targetPos);
        BlockState corrupted = CorruptionRegistry.corrupt(level, targetPos, state, level.random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            if (OvergrownLayerData.isCovered(level, targetPos)) {
                // 対応表に無いブロックを覆った(置き換えは無い)。tick() 側はこれを足場として扱う。
                corruptedCount++;
                if (!coveredBefore) {
                    growPlantsOnCovered(level, targetPos.immutable(), state);
                }
            }
            return;
        }

        CorruptionRegistry.placeCorrupted(level, targetPos, state, corrupted, Block.UPDATE_ALL);
        corruptedCount++;
    }

    /**
     * そのマスは<b>苔に覆われている</b>({@link OvergrownLayerData})か。
     *
     * <p>対応表に無いブロック(コンクリート・ガラス・金床など)は汚染されても置き換わらず、
     * 覆った記録だけが付く。{@code #corrupted} タグでは見分けられないので、これを見ないと
     * 触手もコアも覆ったマスを「汚染できない壁」とみなして止まり、未対応ブロックの床に湧いた
     * 汚染シダが周りを覆っただけで完了扱いになって消えていた(2026-09-24)。
     */
    private boolean isCoveredCell(BlockPos pos, BlockState state) {
        LevelChunk chunk = reader.chunkNow(pos.getX(), pos.getZ());
        return chunk != null && OvergrownLayerData.isCovered(chunk, pos, state);
    }

    /**
     * 1 サイクル分の侵食を実行する。サーバー側からのみ呼ぶこと。blight_fern 用の設定
     * (config)一式 — {@code corruptionRadius}・{@code blightFernInteriorDepth}・
     * {@code tendrilCoreRadius}・{@code tendrilInitialCount}・{@code tendrilMaxActive} —
     * を使う。呼び出し元ごとに違う値を使いたい場合は
     * {@link #tick(ServerLevel, BlockPos, int, int, int, int, int)} を使うこと
     * (汚染死体ブロックは専用の {@code corpseTendril*} 設定を使う
     * {@link #tick(ServerLevel, BlockPos, int, int)} を使っている)。
     */
    public void tick(ServerLevel level, BlockPos origin) {
        tick(level, origin, OvergrownVegetationConfig.COMMON.corruptionRadius.get(),
                OvergrownVegetationConfig.COMMON.blightFernInteriorDepth.get(),
                OvergrownVegetationConfig.COMMON.tendrilCoreRadius.get(),
                OvergrownVegetationConfig.COMMON.tendrilInitialCount.get(),
                OvergrownVegetationConfig.COMMON.tendrilMaxActive.get());
    }

    /**
     * 1 サイクル分の侵食を実行する。サーバー側からのみ呼ぶこと。コア半径は 0(起点周辺の
     * 密な塗りつぶしを行わず、最初から触手だけで広がる旧仕様)。触手の本数・上限も
     * blight_fern とは別の専用設定({@code corpseTendrilInitialCount}/
     * {@code corpseTendrilMaxActive})を使う。汚染死体ブロックはこちらを使う。
     *
     * @param radius           侵食を広げる球の半径(ブロック)。呼び出しのたびに変わってもよい
     *                         (変わった tick で触手が起点から立て直される)。
     * @param maxInteriorDepth 露出面のあるマスから何ブロック奥(露出していない内部)まで
     *                         触手が進めるか。0 なら露出面のあるマスのみ(内部には一切広がらない)。
     */
    public void tick(ServerLevel level, BlockPos origin, int radius, int maxInteriorDepth) {
        tick(level, origin, radius, maxInteriorDepth, 0,
                OvergrownVegetationConfig.COMMON.corpseTendrilInitialCount.get(),
                OvergrownVegetationConfig.COMMON.corpseTendrilMaxActive.get());
    }

    /**
     * 1 サイクル分の侵食を実行する。サーバー側からのみ呼ぶこと。
     *
     * @param radius           侵食を広げる球の半径(ブロック)。呼び出しのたびに変わってもよい
     *                         (変わった tick で触手が起点から立て直される)。
     * @param maxInteriorDepth 露出面のあるマスから何ブロック奥(露出していない内部)まで
     *                         触手が進めるか。0 なら露出面のあるマスのみ(内部には一切広がらない)。
     * @param coreRadius       起点を中心に、触手ではなく隙間なく塗りつぶす「コア」の半径。
     *                         0 ならコアなしで最初から触手だけが起点から伸びる。
     * @param initialCount     コア完了後(コア無しなら起点)から放射状に伸ばす「根」の本数。
     * @param maxActive        同時に存在できる触手(根+枝)の最大本数。
     */
    public void tick(ServerLevel level, BlockPos origin, int radius, int maxInteriorDepth, int coreRadius,
                      int initialCount, int maxActive) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 侵食しない(触手・終わったかどうかはそのまま残り、オンで続きから)
        }
        // この 1 サイクルが引き起こした破壊(取り残された植物など)はアイテムを落とさない。
        // 変わるのはドロップだけで、置くブロック・乱数の順番はそのまま(CorruptionDropSuppressor)
        CorruptionDropSuppressor.enter();
        try {
            tickInScope(level, origin, radius, maxInteriorDepth, coreRadius, initialCount, maxActive);
        } finally {
            CorruptionDropSuppressor.exit();
        }
    }

    private void tickInScope(ServerLevel level, BlockPos origin, int radius, int maxInteriorDepth, int coreRadius,
                             int initialCount, int maxActive) {
        if (finished) {
            return;
        }

        if (radius != activeRadius || maxInteriorDepth != activeInteriorDepth || coreRadius != activeCoreRadius) {
            // 半径・内部侵食の深さ・コア半径のいずれかが変わったら起点から立て直す
            activeRadius = radius;
            activeInteriorDepth = maxInteriorDepth;
            activeCoreRadius = coreRadius;
            seeded = false;
        }
        final int radius2 = radius * radius;
        final int effectiveCoreRadius = Math.min(Math.max(coreRadius, 0), radius);
        final int coreRadius2 = effectiveCoreRadius * effectiveCoreRadius;

        RandomSource random = level.random;
        // この tick のブロック読み用チャンクキャッシュ(前の tick の分は捨てる)。
        readerFor(level);

        if (!seeded) {
            freeTendrilMemory();
            // 起点そのもの(エンティティの足元 = 空気)は汚染できないので、コア(あるいは
            // コア半径0の場合は実質1マスだけの前段)は起点の "直下" から広がり始める。
            // ここは seedGround が強制的に汚染済みにしている想定。
            long groundOffset = BlockPos.asLong(0, -1, 0);
            claimed.add(groundOffset);
            coreQueue.add(groundOffset);
            coreDepth.put(groundOffset, 0);
            coreCells.add(groundOffset);
            coreDone = false;
            coreSeedExpanded = false;
            seeded = true;
        }

        final int maxPlace = effectiveBlocksPerCycle(level);
        final int maxChecks = OvergrownVegetationConfig.COMMON.checksPerCycle.get();
        final double effectChance = OvergrownVegetationConfig.COMMON.effectChance.get();
        final double straightChance = OvergrownVegetationConfig.COMMON.tendrilStraightChance.get();
        final double branchChance = OvergrownVegetationConfig.COMMON.tendrilBranchChance.get();
        final int backtrackBudget = OvergrownVegetationConfig.COMMON.tendrilBacktrackBudget.get();
        final boolean preferTrees = OvergrownVegetationConfig.COMMON.preferExistingTrees.get();
        // 装飾アーチ(巨大樹の根の形の「根」)は、このワールドに巨大樹が 1 本でも生えている
        // (= 古代樹の核が存在する)ときだけ生やす。橋(bridgeChance)は汚染が川で止まらない
        // ようにするための機能なのでこのゲートの対象外。
        final boolean giantTreeGrown = PhaseData.get(level).hasGiantTreeGrown();
        final double archChance = giantTreeGrown
                ? OvergrownVegetationConfig.COMMON.corruptionArchChance.get() * decorationChanceMultiplier
                : 0.0D;
        final double bridgeChance = OvergrownVegetationConfig.COMMON.corruptionBridgeChance.get() * decorationChanceMultiplier;

        final int minY = level.getMinBuildHeight();
        final int maxY = level.getMaxBuildHeight();

        int placed = 0;
        int checks = 0;
        // このtickで実際にブロックを置き換えたチャンクの集合(重複排除用)。
        // ループを抜けたあとにまとめて OvergrownBiomeConverter へ渡す。
        LongOpenHashSet touchedChunks = new LongOpenHashSet();

        while (placed < maxPlace && checks < maxChecks) {
            if (!coreDone) {
                if (coreQueue.isEmpty()) {
                    // コアを塗りつぶし終えた。その縁(まだ外側に伸びる余地があるマス)から
                    // 触手を生やして、以後はいつも通り触手フェーズへ移る。
                    coreDone = true;
                    seedTendrilsFromCoreEdge(random, initialCount);
                    coreQueue.trim();
                    coreDepth.clear();
                    coreDepth.trim();
                    coreCells.clear();
                    coreCells.trim();
                    continue;
                }

                checks++;
                if (stepCore(level, origin, coreRadius2, maxInteriorDepth, random, effectChance, touchedChunks)) {
                    placed++;
                    corruptedCount++;
                }
                continue;
            }

            if (tendrils.isEmpty()) {
                finished = true;
                // もう二度と使わないので、抱えていた分のメモリを手放す
                freeTendrilMemory();
                notifyTouchedChunks(level, touchedChunks);
                return;
            }

            // 1本だけ選んで1歩だけ進める。「根」(root=true、コアの縁から放射状に
            // 伸ばした最初の数本)が1本でも生きている間はそちらだけを対象にランダムに選び、
            // 根が全て枯れる/半径に到達し終えて初めて、枝(分岐で生まれた触手)が
            // 前進権を得る。これにより「まず根が放射状に伸びきり、それから根沿いに
            // 横向きの枝が湧いて残りの空間を埋めていく」という順序になる。
            int index = pickTendrilIndex(random);
            Tendril tendril = tendrils.get(index);
            checks++;

            StepOutcome outcome = stepTendril(level, origin, tendril, radius2, maxInteriorDepth,
                    minY, maxY, random, straightChance, effectChance, preferTrees,
                    bridgeChance, backtrackBudget, maxActive, touchedChunks);

            if (outcome == StepOutcome.DEAD_END) {
                // どの方向にも進めなかった = この触手はここで枯れる。末尾と入れ替えて O(1) で除去。
                int last = tendrils.size() - 1;
                tendrils.set(index, tendrils.get(last));
                tendrils.remove(last);
                continue;
            }

            if (outcome == StepOutcome.MOVED_AND_PLACED) {
                placed++;
                corruptedCount++;

                TendrilNode placedHead = tendril.head();
                if (archChance > 0.0D && placedHead.interiorDepth == 0
                        && random.nextDouble() < archChance) {
                    // 汚染した筋から、巨大樹の根のアーチと同じ形の装飾アーチを1本生やす。
                    tryRandomArch(level, origin, placedHead, random, touchedChunks);
                }

                if (tendrils.size() < maxActive && random.nextDouble() < branchChance) {
                    // 今しがた汚染したばかりのこの場所から、横向きに新しい触手を1本分岐させる。
                    // 分岐した触手にも独立した後戻り予算をフルで持たせる(親の残量を分け合わない)。
                    TendrilNode newHead = tendril.head();
                    Direction branchFacing = randomPerpendicular(newHead.facing, random);
                    tendrils.add(new Tendril(newHead.x, newHead.y, newHead.z, branchFacing,
                            newHead.interiorDepth, backtrackBudget, false));
                }
            }
            // MOVED(既に汚染済みだったマスを踏み台にしただけ)の場合は置換数を増やさず、
            // 分岐もさせない。ワールド再読み込み後、既存の汚染済み領域を踏み台にして
            // 本当のフロンティア(未汚染との境界)まで無言で辿り着くための経路にすぎない。
        }

        notifyTouchedChunks(level, touchedChunks);
    }

    /**
     * 次に進める触手のインデックスを選ぶ。{@code root=true} の触手が1本でも残っていれば
     * その中からランダムに1本選び(reservoir sampling で追加の配列を作らず均等に選べる)、
     * 根が1本も残っていなければ全体からランダムに選ぶ。
     */
    private int pickTendrilIndex(RandomSource random) {
        int rootCount = 0;
        int rootIndex = -1;
        for (int i = 0; i < tendrils.size(); i++) {
            if (tendrils.get(i).root) {
                rootCount++;
                if (random.nextInt(rootCount) == 0) {
                    rootIndex = i;
                }
            }
        }
        return rootCount > 0 ? rootIndex : random.nextInt(tendrils.size());
    }

    /**
     * コアが完了した時点で、まだ外側に伸びる余地がある(=6近傍のどれかが {@link #claimed} に
     * 含まれていない)コアのマスをランダムに選び、そこから {@code initialCount} 本の
     * 触手(=「根」、{@link Tendril#root} が true)を生やす。コアが半径いっぱいまで
     * (あるいは世界の縦範囲の限界まで)完全に埋まっていて伸びる余地が無い場合は何もしない
     * (その場合 {@code tendrils} は空のままなので、次の {@link #tick} 呼び出しで
     * {@code isFinished()} になる)。
     */
    private void seedTendrilsFromCoreEdge(RandomSource random, int initialCount) {
        LongArrayList edgeCells = new LongArrayList();
        for (int i = 0; i < coreCells.size(); i++) {
            long offset = coreCells.getLong(i);
            if (hasUnclaimedNeighbour(offset)) {
                edgeCells.add(offset);
            }
        }
        if (edgeCells.isEmpty()) {
            return;
        }

        int initialBacktrackBudget = OvergrownVegetationConfig.COMMON.tendrilBacktrackBudget.get();
        // 6方向をシャッフルしてから順に割り当てることで、根の本数が6本以下なら
        // 全ての根が重複しない向きへ最初から散らばる(偶然同じ向きばかりに偏らない)。
        Direction[] directions = Direction.values();
        shuffle(directions, random);
        int firstRoot = tendrils.size();
        for (int i = 0; i < initialCount; i++) {
            long offset = edgeCells.getLong(random.nextInt(edgeCells.size()));
            int x = BlockPos.getX(offset);
            int y = BlockPos.getY(offset);
            int z = BlockPos.getZ(offset);
            int depth = coreDepth.get(offset);
            Direction facing = directions[i % directions.length];
            tendrils.add(new Tendril(x, y, z, facing, depth, initialBacktrackBudget, true));
        }
        rescueRootsAllOnOrigin(edgeCells, firstRoot, initialBacktrackBudget);
    }

    /**
     * 根が<b>全部</b>「起点そのもの」のマスから出ることになったときだけ、最後の 1 本を別の縁のマスから出し直す
     * (2026-09-24、ユーザー承認)。
     *
     * <p>コアは近傍を積むとき半径だけで絞るので、コア半径 0 でも距離 0 の起点(汚染死体のブロック・汚染シダの足元)が
     * 縁の候補に入る。平らな地面では起点の横も上も空気、下は踏み済みなので、そこから出た根は 1 歩も進めない。
     * 汚染死体(コア半径 0・根 4 本・縁は起点と真下の 2 マス)は (1/2)^4 ≒ 6% の確率で全部の根が起点を引き、
     * 真下の 1 マスだけ汚染して終わっていた。
     *
     * <p>救うのは<b>全滅するときだけ</b>。1 本でも起点以外から出ていれば何もしないので、それ以外の場合の
     * 根の出方と乱数の引き方は今までと同じ(ここでは乱数を引かない)。差し替え先は縁の候補を並びの順に見て
     * 起点でない最初のマス(コア半径 0 なら真下の地面)、向きはその根のまま。
     */
    private void rescueRootsAllOnOrigin(LongArrayList edgeCells, int firstRoot, int backtrackBudget) {
        int last = tendrils.size() - 1;
        if (last < firstRoot) {
            return;
        }
        for (int i = firstRoot; i <= last; i++) {
            TendrilNode start = tendrils.get(i).head();
            if (start.x != 0 || start.y != 0 || start.z != 0) {
                return;
            }
        }
        long originOffset = BlockPos.asLong(0, 0, 0);
        for (int i = 0; i < edgeCells.size(); i++) {
            long offset = edgeCells.getLong(i);
            if (offset != originOffset) {
                Direction facing = tendrils.get(last).head().facing;
                tendrils.set(last, new Tendril(BlockPos.getX(offset), BlockPos.getY(offset), BlockPos.getZ(offset),
                        facing, coreDepth.get(offset), backtrackBudget, true));
                return;
            }
        }
    }

    /** {@code offset} の6近傍のうち、まだどの触手・コアも踏んでいないマスが1つでもあるか。 */
    private boolean hasUnclaimedNeighbour(long offset) {
        int x = BlockPos.getX(offset);
        int y = BlockPos.getY(offset);
        int z = BlockPos.getZ(offset);
        for (Direction direction : Direction.values()) {
            long neighbourOffset = BlockPos.asLong(
                    x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
            if (!claimed.contains(neighbourOffset)) {
                return true;
            }
        }
        return false;
    }

    /**
     * コア(起点周辺の広がり)を1マス分処理する。条件を満たす近傍(未踏・半径内・露出面など)を
     * いったん全て洗い出したうえで、その中からランダムに選んだ方向だけをキューに積む
     * ({@link #coreCandidateDirs}/{@link #coreCandidateDepths} をスクラッチに使う、
     * 一部だけの Fisher-Yates シャッフル)。起点(足元)セルだけは特別に
     * {@code coreInitialBranches} 本の方向へ同時に分岐させ(複数の独立した筋がそこから
     * 伸び始める)、それ以外の全てのマスは常に {@value #CORE_BRANCHES_PER_CELL}(=1)方向だけへ
     * 進む — つまり起点から伸びた個々の筋は、同じマスから複数方向へ同時に太く伸びることはなく、
     * 1ブロックごとにランダムな1方向だけへ進む。空気・免疫ブロックなど汚染できないマスに
     * 当たった経路はそこで途切れ、近傍も探索しない(触手と同じ考え方)。
     *
     * @return 実際に新しく汚染できた場合 true(既に汚染済みだった場合や置換できなかった場合は false)
     */
    private boolean stepCore(ServerLevel level, BlockPos origin, int coreRadius2, int maxInteriorDepth,
                              RandomSource random, double effectChance, LongOpenHashSet touchedChunks) {
        long packed = coreQueue.removeLong(coreQueue.size() - 1);
        int x = BlockPos.getX(packed);
        int y = BlockPos.getY(packed);
        int z = BlockPos.getZ(packed);
        int depth = coreDepth.get(packed);

        corePos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
        BlockState state = reader.stateIfLoaded(corePos.getX(), corePos.getY(), corePos.getZ());
        if (state == null) {
            // 未ロード(Level#isLoaded が false になる条件と同じ)。
            return false;
        }
        boolean alreadyCorrupted = state.is(ModTags.CORRUPTED) || isCoveredCell(corePos, state);
        boolean placedNew = false;

        if (!alreadyCorrupted) {
            BlockPos targetPos = corePos;
            BlockState groundState = redirectSnowToGround(reader, corePos, state);
            if (groundState != null) {
                targetPos = groundPosScratch.immutable();
                state = groundState;
            }

            if (!CorruptionRegistry.canCorrupt(level, targetPos, state)) {
                // 空気・免疫ブロックなどはここで経路が途切れ、この先には広がらない
                return false;
            }

            BlockState corrupted = CorruptionRegistry.corrupt(level, targetPos, state, random);
            if (CorruptionRegistry.noChange(state, corrupted)) {
                if (!isCoveredCell(targetPos, state)) {
                    return false;
                }
                // 対応表に無いブロックを覆った。置き換えは無いが汚染の 1 歩として数え、
                // 下の近傍の洗い出しへ進む(触手の stepTendril と同じ扱い)。
                touchedChunks.add(new ChunkPos(targetPos).toLong());
                // 覆ったマスの上にも装飾植物を生やす(2026-09-29 要望)。ここへ来るのは
                // 上の alreadyCorrupted で弾かれなかったとき = 今 覆ったときだけ。
                growPlantsOnCovered(level, targetPos.immutable(), state);
                placedNew = true;
            } else {
                BlockPos target = targetPos.immutable();
                CorruptionRegistry.placeCorrupted(level, target, state, corrupted, Block.UPDATE_ALL);
                touchedChunks.add(new ChunkPos(target).toLong());

                if (effectChance > 0.0D && random.nextDouble() < effectChance) {
                    // 破壊パーティクル + 音
                    level.levelEvent(2001, target, Block.getId(state));
                }

                growPlants(level, target, corrupted);
                ensureGrassOnConvertedStone(level, target, state, corrupted, random);
                decayNaturalTreeLeaves(level, target, state);
                placedNew = true;
            }
        }

        // まず条件を満たす近傍(未踏・半径内・世界の縦範囲内・ロード済み・露出面や内部侵食の
        // 深さ制限を満たす)を洗い出すだけ洗い出す。ここではまだキューに積まない。
        int candidateCount = 0;
        for (Direction direction : Direction.values()) {
            int nx = x + direction.getStepX();
            int ny = y + direction.getStepY();
            int nz = z + direction.getStepZ();
            if (nx * nx + ny * ny + nz * nz > coreRadius2) {
                continue;
            }

            long neighbourOffset = BlockPos.asLong(nx, ny, nz);
            if (claimed.contains(neighbourOffset)) {
                continue;
            }

            int nwy = origin.getY() + ny;
            if (nwy < level.getMinBuildHeight() || nwy >= level.getMaxBuildHeight()) {
                continue;
            }
            int nwx = origin.getX() + nx;
            int nwz = origin.getZ() + nz;
            if (!reader.isLoaded(nwx, nwy, nwz)) {
                continue;
            }

            boolean exposed = isExposed(reader, nwx, nwy, nwz, exposureScratch);
            int newDepth = exposed ? 0 : depth + 1;
            if (!exposed && newDepth > maxInteriorDepth) {
                continue;
            }

            coreCandidateDirs[candidateCount] = direction;
            coreCandidateDepths[candidateCount] = newDepth;
            candidateCount++;
        }

        // 洗い出した候補のうち、ランダムに最大 branchesToPick 方向だけを実際にキューへ積む
        // (部分 Fisher-Yates シャッフル。候補がそれ未満ならあるだけ全部使う)。起点(足元)の
        // セルだけは coreInitialBranches 本へ同時分岐させ、以後の全マスは常に1方向だけに絞る
        // ことで、同じマスから複数方向へ太く伸びるのではなく、起点から伸びた個々の筋が
        // 1ブロックずつランダムな方向へ進む見た目にする。選ばれなかった候補は claimed に
        // 入らないので、後で別の隣接マスからの展開で拾われる可能性が残る。
        int branchesToPick = coreSeedExpanded
                ? CORE_BRANCHES_PER_CELL
                : OvergrownVegetationConfig.COMMON.coreInitialBranches.get();
        coreSeedExpanded = true;
        int pickCount = Math.min(branchesToPick, candidateCount);
        for (int i = 0; i < pickCount; i++) {
            int j = i + random.nextInt(candidateCount - i);
            Direction pickedDir = coreCandidateDirs[j];
            int pickedDepth = coreCandidateDepths[j];
            coreCandidateDirs[j] = coreCandidateDirs[i];
            coreCandidateDepths[j] = coreCandidateDepths[i];
            coreCandidateDirs[i] = pickedDir;
            coreCandidateDepths[i] = pickedDepth;

            long neighbourOffset = BlockPos.asLong(
                    x + pickedDir.getStepX(), y + pickedDir.getStepY(), z + pickedDir.getStepZ());
            claimed.add(neighbourOffset);
            coreDepth.put(neighbourOffset, pickedDepth);
            coreCells.add(neighbourOffset);
            coreQueue.add(neighbourOffset);
        }

        return placedNew;
    }

    /** {@link #stepTendril} の結果。 */
    private enum StepOutcome {
        /** どの方向にも進めなかった(その触手は消滅する)。 */
        DEAD_END,
        /** 既に汚染済みのマスへ進んだだけで、新たな置き換えは起きなかった。 */
        MOVED,
        /** 新しいマスを実際に汚染して、そこへ進んだ。 */
        MOVED_AND_PLACED
    }

    /**
     * 触手を1歩だけ進めようとする。{@link #candidateDirections} で優先順位付けした方向を
     * 順番に試し、最初に成功した方向へ新しい {@link TendrilNode} を積んで進む。
     * 汚染できた場合はその場でパーティクル/装飾植物などの副作用もここで済ませる。
     *
     * <p>先端(経路の最後尾)からどの方向にも進めなかった場合、経路をブロックを置き換えずに
     * 1マスだけ後戻りしてから改めて周囲を試す(まだ試していない方向が残っていることが多い)。
     * これを {@code tendril.backtrackBudget} が尽きるか、経路そのものが起点まで戻りきるまで
     * 繰り返す。どちらかに達したら本当の行き止まりとして {@link StepOutcome#DEAD_END} を返す。
     */
    private StepOutcome stepTendril(ServerLevel level, BlockPos origin, Tendril tendril, int radius2,
                                     int maxInteriorDepth, int minY, int maxY, RandomSource random,
                                     double straightChance, double effectChance, boolean preferTrees,
                                     double bridgeChance, int backtrackBudget, int maxActive,
                                     LongOpenHashSet touchedChunks) {
        while (true) {
            TendrilNode head = tendril.head();

            int directionCount = orderedDirections(origin, head, random, straightChance, preferTrees);
            for (int di = 0; di < directionCount; di++) {
                Direction direction = orderedDirScratch[di];
                int nx = head.x + direction.getStepX();
                int ny = head.y + direction.getStepY();
                int nz = head.z + direction.getStepZ();

                if (nx * nx + ny * ny + nz * nz > radius2) {
                    continue;
                }

                int wy = origin.getY() + ny;
                if (wy < minY || wy >= maxY) {
                    continue;
                }

                int wx = origin.getX() + nx;
                int wz = origin.getZ() + nz;
                neighbourPos.set(wx, wy, wz);
                if (!reader.isLoaded(wx, wy, wz)) {
                    continue;
                }

                long offset = BlockPos.asLong(nx, ny, nz);
                if (claimed.contains(offset)) {
                    // 既にこの触手網のどこかが踏んだマス。同じ場所を再訪しても意味が無いので次の方向へ
                    continue;
                }

                boolean exposed = isExposed(reader, wx, wy, wz, exposureScratch);
                int newDepth = exposed ? 0 : head.interiorDepth + 1;
                if (!exposed && newDepth > maxInteriorDepth) {
                    // 内部侵食の深さ上限を超える方向へは進めない。他の方向を試す
                    continue;
                }

                BlockState state = reader.getBlockState(wx, wy, wz);
                boolean alreadyCorrupted = state.is(ModTags.CORRUPTED) || isCoveredCell(neighbourPos, state);

                if (alreadyCorrupted) {
                    // ワールド再読み込み直後などで、既に汚染済みの領域を踏み台にして先へ進む。
                    // 新規の置き換え・パーティクル・装飾植物は発生させない。
                    claimed.add(offset);
                    tendril.path.add(new TendrilNode(nx, ny, nz, direction, newDepth));
                    return StepOutcome.MOVED;
                }

                BlockPos targetPos = neighbourPos;
                BlockState groundState = redirectSnowToGround(reader, neighbourPos, state);
                if (groundState != null) {
                    targetPos = groundPosScratch;
                    state = groundState;
                }

                if (!CorruptionRegistry.canCorrupt(level, targetPos, state)) {
                    // 水でブロックされたなら、確率で対岸まで根のアーチと同じ形の橋を架けて渡る
                    // (川で汚染が止まらないように)。橋を架けられたら、この触手は役目を終えて枯れ、
                    // 対岸に仕込んだ新しい触手が汚染を続ける。
                    if (bridgeChance > 0.0D && !alreadyCorrupted && direction.getAxis().isHorizontal()
                            && state.getFluidState().is(FluidTags.WATER)
                            && tendrils.size() < maxActive
                            && random.nextDouble() < bridgeChance
                            && tryBridgeAcross(level, origin, head, direction,
                                    backtrackBudget, random, touchedChunks)) {
                        return StepOutcome.DEAD_END;
                    }
                    // 空気・免疫ブロックなどはここへ進めない。他の方向を試す
                    continue;
                }

                BlockState corrupted = CorruptionRegistry.corrupt(level, targetPos, state, random);
                if (CorruptionRegistry.noChange(state, corrupted)) {
                    if (!isCoveredCell(targetPos, state)) {
                        // 置き換えも覆いもできなかった。他の方向を試す
                        continue;
                    }
                    // 対応表に無いブロックを覆った(置き換えずに記録だけ付いた)。これも汚染の 1 歩として
                    // 数え、そこを足場に先へ進む。元のブロックは残るが、その上にも装飾植物を生やす
                    // (2026-09-29 要望。ここへ来るのは上の alreadyCorrupted で弾かれなかったとき
                    // = 今 覆ったときだけ)。
                    touchedChunks.add(new ChunkPos(targetPos).toLong());
                    growPlantsOnCovered(level, targetPos.immutable(), state);
                    claimed.add(offset);
                    tendril.path.add(new TendrilNode(nx, ny, nz, direction, newDepth));
                    return StepOutcome.MOVED_AND_PLACED;
                }

                BlockPos target = targetPos.immutable();
                CorruptionRegistry.placeCorrupted(level, target, state, corrupted, Block.UPDATE_ALL);
                touchedChunks.add(new ChunkPos(target).toLong());

                if (effectChance > 0.0D && random.nextDouble() < effectChance) {
                    // 破壊パーティクル + 音
                    level.levelEvent(2001, target, Block.getId(state));
                }

                growPlants(level, target, corrupted);
                ensureGrassOnConvertedStone(level, target, state, corrupted, random);
                decayNaturalTreeLeaves(level, target, state);

                claimed.add(offset);
                tendril.path.add(new TendrilNode(nx, ny, nz, direction, newDepth));
                return StepOutcome.MOVED_AND_PLACED;
            }

            // 先端からはどの方向にも進めなかった。後戻りの予算と経路の余裕が両方あれば
            // 1マスだけ後戻りして(このマス自体は既に汚染済みのまま)別の方向を試す。
            // まだ試していない方向が残っていることが多く、これだけでかなり枯れにくくなる。
            if (tendril.path.size() <= 1 || tendril.backtrackBudget <= 0) {
                return StepOutcome.DEAD_END;
            }
            tendril.path.remove(tendril.path.size() - 1);
            tendril.backtrackBudget--;
        }
    }

    /**
     * 触手が次に試す方向を優先順位付きで {@link #orderedDirScratch} に並べ、その個数を返す
     * (5 方向、来た方向の反対側は最初から除外。{@code preferTrees} が有効で隣に既存の木の原木が
     * あればその方向を先頭に足して 6 方向)。
     *
     * <p>{@code straightChance} の確率で現在の向きを最優先にし、外れた場合は横4方向を先に
     * ランダムな順で試す(その4方向が全て塞がっていれば最後に現在の向きも試す)。
     * 原木優先({@link OvergrownVegetationConfig.Common#preferExistingTrees})により触手は周囲の
     * 地形よりも先に既存の木へ食い込んでいく。
     *
     * <p>乱数の消費順(横 4 方向のシャッフル → 直進判定)は配列を new していた旧実装と同じなので、
     * 同じシードなら同じ経路になる。
     */
    private int orderedDirections(BlockPos origin, TendrilNode head,
                                  RandomSource random, double straightChance, boolean preferTrees) {
        Direction facing = head.facing;
        System.arraycopy(PERPENDICULARS[facing.ordinal()], 0, perpendicularScratch, 0, 4);
        shuffle(perpendicularScratch, random);
        boolean straightFirst = random.nextDouble() < straightChance;

        int count = 0;
        if (preferTrees) {
            Direction logDir = preferredLogDirection(origin, head);
            if (logDir != null) {
                orderedDirScratch[count++] = logDir;
            }
        }
        if (straightFirst) {
            orderedDirScratch[count++] = facing;
            System.arraycopy(perpendicularScratch, 0, orderedDirScratch, count, 4);
            count += 4;
        } else {
            System.arraycopy(perpendicularScratch, 0, orderedDirScratch, count, 4);
            count += 4;
            orderedDirScratch[count++] = facing;
        }
        return count;
    }

    /**
     * 触手の先端の周囲(来た方向の反対以外の 5 方向)を見て、まだ踏んでいない・ロード済みの隣に
     * {@code #minecraft:logs}(既存の木の原木)があれば、その方向を返す。無ければ {@code null}。
     * 実際にそこへ進めるか(半径・Y範囲・{@link CorruptionRegistry#canCorrupt} など)の最終判定は
     * 呼び出し元の {@link #stepTendril} が通常どおり行うので、ここではタグの一致だけを軽く見る。
     */
    @Nullable
    private Direction preferredLogDirection(BlockPos origin, TendrilNode head) {
        Direction backward = head.facing.getOpposite();
        for (Direction direction : Direction.values()) {
            if (direction == backward) {
                continue;
            }
            int nx = head.x + direction.getStepX();
            int ny = head.y + direction.getStepY();
            int nz = head.z + direction.getStepZ();
            if (claimed.contains(BlockPos.asLong(nx, ny, nz))) {
                continue;
            }
            BlockState neighbour = reader.stateIfLoaded(origin.getX() + nx, origin.getY() + ny, origin.getZ() + nz);
            if (neighbour == null) {
                continue;
            }
            if (neighbour.is(BlockTags.LOGS) && !neighbour.is(ModTags.CORRUPTED)) {
                return direction;
            }
        }
        return null;
    }

    /** 分岐する新しい触手の向き。親の進行方向と反対以外からランダムに1つ選ぶ(横4方向のどれか)。 */
    private static Direction randomPerpendicular(Direction facing, RandomSource random) {
        Direction[] perpendiculars = PERPENDICULARS[facing.ordinal()];
        return perpendiculars[random.nextInt(perpendiculars.length)];
    }

    /** {@code facing} とその反対方向を除いた、残り4方向(横方向)を返す。 */
    private static Direction[] perpendicularsOf(Direction facing) {
        Direction opposite = facing.getOpposite();
        Direction[] result = new Direction[4];
        int index = 0;
        for (Direction direction : Direction.values()) {
            if (direction != facing && direction != opposite) {
                result[index++] = direction;
            }
        }
        return result;
    }

    /** {@link #PERPENDICULARS} を組み立てる(全 6 方向ぶん、起動時に 1 回)。 */
    private static Direction[][] buildPerpendiculars() {
        Direction[] all = Direction.values();
        Direction[][] table = new Direction[all.length][];
        for (Direction facing : all) {
            table[facing.ordinal()] = perpendicularsOf(facing);
        }
        return table;
    }

    /**
     * {@code level} 用のチャンクキャッシュ付き読み({@link #reader})を用意して返す。呼ぶたびに
     * 覚えているチャンクを捨てるので、tick(=同じ tick 内の一連の読み)の先頭で 1 回だけ呼ぶこと。
     */
    private ChunkCachedReader readerFor(ServerLevel level) {
        ChunkCachedReader current = this.reader;
        if (current == null || current.level() != level) {
            current = new ChunkCachedReader(level);
            this.reader = current;
        } else {
            current.reset();
        }
        return current;
    }

    private static void shuffle(Direction[] array, RandomSource random) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Direction tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    /**
     * このtickでブロックを置き換えたチャンクそれぞれについて、地表の汚染率が閾値を超えていないか
     * ({@link OvergrownBiomeConverter}) 確認する。呼び出し側でチャンクの重複を除いてから渡すこと。
     */
    private static void notifyTouchedChunks(ServerLevel level, LongOpenHashSet touchedChunks) {
        if (touchedChunks.isEmpty()) {
            return;
        }
        for (long packedChunkPos : touchedChunks) {
            OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(packedChunkPos));
        }
    }

    /**
     * 6 面のうち 1 面でも空気や非不透過ブロックに接していれば「露出している」とみなす。
     * 未ロードの面は飛ばす({@code Level#isLoaded} と同じ条件)。面を見る順は {@link #EXPOSURE_ORDER}。
     */
    private static boolean isExposed(ChunkCachedReader reader, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        for (Direction direction : EXPOSURE_ORDER) {
            int nx = x + direction.getStepX();
            int ny = y + direction.getStepY();
            int nz = z + direction.getStepZ();
            BlockState neighbour = reader.stateIfLoaded(nx, ny, nz);
            if (neighbour == null) {
                continue;
            }
            if (neighbour.isAir()) {
                return true;
            }
            scratch.set(nx, ny, nz);
            if (!neighbour.isSolidRender(reader.level(), scratch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code pos}/{@code state} が積もった雪(層状の薄い雪、{@link SnowLayerBlock}。
     * {@code Blocks.SNOW} がこれ)だった場合、雪ブロックそのものを汚染するのではなく、
     * まず雪を取り除いた ({@link ServerLevel#removeBlock}) うえで、真下のブロック
     * (本来の地面 = 草・土など)を実際の汚染対象として差し替える
     * (要望: 「雪原を汚染する際、雪のみを置き換えてしまうため、まず雪を削除してから
     * 下のブロックを置き換える」)。差し替え先の座標は {@link #groundPosScratch} に書き込む。
     *
     * <p>雪でない場合、真下が未ロード・既に汚染済み・汚染不可(免疫ブロック等)の場合は
     * 何もせず {@code null} を返す。この場合、呼び出し元は雪自体をいつも通り
     * {@link CorruptionRegistry#corrupt} に渡す。積もった雪は
     * {@link CorruptionRegistry#corruptsToAir} 扱いなので、そこでも下草にはならず
     * 単に {@code Blocks.AIR}(= 除去)が返る。
     *
     * <p>触手・コアの経路上の座標({@code claimed}/{@code TendrilNode} 等)はあえて元の
     * 雪の座標のまま扱う。雪を取り除いた跡は空気になるだけなので、そこから触手が
     * さらに横方向へ(=隣の雪マスへ)伸びていく通常の探索を妨げない。
     */
    @Nullable
    private BlockState redirectSnowToGround(ChunkCachedReader reader, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof SnowLayerBlock)) {
            return null;
        }

        groundPosScratch.set(pos.getX(), pos.getY() - 1, pos.getZ());
        BlockState groundState = reader.stateIfLoaded(pos.getX(), pos.getY() - 1, pos.getZ());
        if (groundState == null) {
            // 未ロード(Level#isLoaded が false になる条件と同じ)。
            return null;
        }

        ServerLevel level = (ServerLevel) reader.level();
        if (groundState.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, groundPosScratch, groundState)) {
            return null;
        }

        level.removeBlock(pos, false);
        return groundState;
    }

    /**
     * <b>1マスだけ</b>をその場で汚染する(触手もコアも使わない単発の置き換え)。置き換えに成功したら
     * 通常の汚染とまったく同じ後処理 —— 装飾植物({@link #growPlants})・石を土に変えたときの
     * 上面の下草({@link #ensureGrassOnConvertedStone})・自然木の葉の枯死
     * ({@link #decayNaturalTreeLeaves}) —— まで行う。
     *
     * <p>レイドの「面汚染」({@link com.yayuy.overgrownvegetation.raid.RaidAreaCorruption})専用。
     * あちらはビーコンから近い順に座標を1つずつ渡してくるので、触手のように「隣へ伸びる」必要が無い。
     *
     * <h2>まわりを完全に埋められたマスは軽く扱う(負荷対策)</h2>
     * <p>面汚染は地下をまるごと塗りつぶすので、処理するマスの大半は<b>6面とも塞がった地中</b>になる。
     * そういうマスでは:
     * <ul>
     *     <li>装飾植物を<b>一切呼ばない</b> —— {@link #growPlants} の中身(茂みの塊・下草・水中植物・
     *     洞窟構造物)はどれも「隣接する空きマスへ置く」処理なので、空きが無ければ結果は必ず何も無し。
     *     ただし {@link #growSurfacePlants} は面を見る<b>前に</b> {@link CaveGrowth#modeAt} で
     *     天井走査をしてしまうため、呼ぶだけで高い。</li>
     *     <li>設置フラグを巨大樹の根と同じ軽いもの({@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE})に落とす
     *     —— 見えないマスの近隣更新・光更新を毎秒何百回も走らせないため。</li>
     * </ul>
     * 露出しているマス(＝実際に見える面)はこれまでどおり {@code UPDATE_ALL} + 装飾植物のフルコース。
     *
     * @return 実際にブロックを置き換えたら true(空気・免疫・既に汚染済み・未ロードなら false)
     */
    public static boolean corruptSingle(ServerLevel level, BlockPos pos) {
        if (CorruptionSwitch.isOff()) {
            return false; // /ov corruption false(2026-10-10 ユーザー指定): 侵食しない(触手・終わったかどうかはそのまま残り、オンで続きから)
        }
        // 取り残された植物などの破壊でアイテムを落とさない(CorruptionDropSuppressor)
        CorruptionDropSuppressor.enter();
        try {
            return corruptSingleInScope(level, pos);
        } finally {
            CorruptionDropSuppressor.exit();
        }
    }

    private static boolean corruptSingleInScope(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(ModTags.CORRUPTED)) {
            return false;
        }
        if (!CorruptionRegistry.canCorrupt(level, pos, state)) {
            return false;
        }
        RandomSource random = level.random;
        // 覆っただけのマスは #corrupted に入らないので、既に覆われていても毎回ここへ来る。
        // 「今 覆ったのか」を見分けるために、置き換えの前の状態を控えておく。
        boolean coveredBefore = OvergrownLayerData.isCovered(level, pos);
        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            if (!coveredBefore && OvergrownLayerData.isCovered(level, pos)) {
                // 今 覆った。ブロックは元のままだが、その上にも装飾植物を生やす(2026-09-29 要望)。
                growPlantsOnCovered(level, pos.immutable(), state);
            }
            return false;
        }
        BlockPos target = pos.immutable();
        boolean exposed = hasFreeNeighbour(level, target);
        int flags = exposed ? Block.UPDATE_ALL : (Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        CorruptionRegistry.placeCorrupted(level, target, state, corrupted, flags);
        if (exposed) {
            growPlants(level, target, corrupted);
            ensureGrassOnConvertedStone(level, target, state, corrupted, random);
        }
        decayNaturalTreeLeaves(level, target, state);
        return true;
    }

    /**
     * 6面のどれか1つでも「何かを生やせる余地のあるマス」(空気・置き換え可能なマス・液体)に
     * 面しているか。{@link #corruptSingle} が装飾植物を呼ぶ価値があるかを安く判定するためのもの。
     *
     * <p>{@code canBeReplaced()} まで通すのは、苔の薄敷きが<b>既に苔が付いているマス</b>にも
     * 面を足せるため({@link #growSurfacePlants} 参照)。判定は広めに取って取りこぼさない側へ倒す。
     */
    private static boolean hasFreeNeighbour(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : EXPOSURE_ORDER) {
            cursor.setWithOffset(pos, direction);
            if (!level.isLoaded(cursor)) {
                continue;
            }
            BlockState neighbour = level.getBlockState(cursor);
            if (neighbour.isAir() || neighbour.canBeReplaced() || !neighbour.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 汚染し終えたばかりのブロックの周囲を見て、露出している面(空気に接した面)に
     * 確率でシダ・苔・蔦を生やす。上面は下草(まれに高さ2ブロックの植物)、
     * 下面は垂れ下がる植物、側面は壁のサンゴ状の蔦になる。
     *
     * <p>ただし {@code hostState} が {@link ModTags#GROWTH_HANGING_ONLY}
     * (葉ブロックなど)の場合は、上に乗ったり横から生えたりするのが不自然なので
     * 下面の垂れ下がる植物しか生やさない。
     */
    /**
     * <b>覆っただけのマスの上にも装飾植物を生やす</b>(2026-09-29、要望)。
     *
     * <p>対応表に無いブロック(コンクリート・鉄ブロック・階段など)は汚染されても<b>置き換わらず</b>、
     * 覆った記録が付くだけ({@link OvergrownLayerData})。その経路は
     * {@link CorruptionRegistry#noChange} で早々に折り返していたので、
     * <b>普通の汚染ブロックなら生えるはずの下草・蔦・苔が 1 本も生えていなかった</b> ——
     * 覆われた壁だけが苔のテクスチャ 1 枚のまま、という食い違いになっていた。
     *
     * <p>ホストは<b>元のブロックそのもの</b>を渡す。下草・蔦・苔はどれも「足場の面が固いか」しか
     * 見ていないので({@code isFaceSturdy})、コンクリートでも鉄ブロックでもそのまま生える。
     * <b>ガラスには付かない</b>(面が固くない)—— 窓が塞がらないので都合がよい。
     * 苗木だけは汚染された土の上にしか植わらないので、覆ったコンクリートから木は生えない。
     *
     * <p><b>覆った瞬間だけ呼ぶこと。</b> 覆ったマスは {@code #corrupted} に入らないので、
     * 汚染は同じマスを何度でも訪ね直す。毎回呼ぶと同じ壁で抽選が走り続けて、
     * 普通の汚染ブロックよりずっと濃く生い茂る(下草は描画がいちばん重い)。
     */
    private static void growPlantsOnCovered(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (!hasFreeNeighbour(level, pos)) {
            return;
        }
        growPlants(level, pos, hostState);
    }

    /**
     * ほかの汚染の経路(過繁茂の前進・台帳の広がり・建物の侵食・噴出・爆発)用: 置き換えの無かった({@link CorruptionRegistry#noChange})
     * マスが<b>今</b>覆われたのなら、上に植物を生やす(2026-10-10、ユーザー指定「対応していないブロックを汚染した際にもその上に植物や苗木が生えるように」。
     * それまでこの経路では覆っただけのマスで折り返し、植物が 1 本も生えなかった)。{@code corrupt} の直後に呼ぶこと。
     */
    public static void growPlantsIfNewlyCovered(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (OvergrownLayerData.takeNewlyCovered(pos)) {
            growPlantsOnCovered(level, pos.immutable(), hostState);
            // 新しく覆ったマスも段階の数に入れる(2026-10-30 夜。建築タブのブロックを覆うようになり、前は covered_ への置き換えとして
            // 数えていた分が抜けていた)。ここを呼ぶのは触手以外の経路だけ(触手は自分の corruptedCount で数える)なので二重にならない。
            com.yayuy.overgrownvegetation.phase.CorruptionCounter.record(level, 1);
        }
    }

    private static void growPlants(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (hostState.isAir() || hostState.is(Blocks.WATER)) { // 溶けた氷の跡の水(2026-10-16)にも生やさない
            // 雪のように「汚染しても消えるだけ」のブロックは、その跡地(空気)に何も生やさない。
            return;
        }
        // 茂み(overgrown_bush)の塊は装飾植物とは独立に生成する
        // (spawnGrowthPlants / growthChance の設定に左右されない)。
        LeafClumpGrowth.tryGrow(level, pos, level.random);
        growSurfacePlants(level, pos, hostState, surfaceGrowthChance(level, pos));
        growUnderwaterPlants(level, pos, hostState);
    }

    /**
     * その場所での装飾植物の生成確率。湿地系の環境(地域マップ。バイオームが書き換わる前でも)では
     * {@code growthChance} に {@code swampGrowthChanceMultiplier} を掛けて草を少なくする。
     */
    /**
     * その場所で苗木を植える確率。菌湿地帯では {@code fungalSwampTreeChanceMultiplier} を掛ける(菌の柱はもともと疎らなので間隔は見ない)。
     * それ以外は {@code treeSpacing} が 0 でなければ {@link TreeSpacing} の格子で決める: 木を立てる列なら必ず、ほかの列には植えない
     * (2026-10-15 ユーザー指定「木の密度を下げる」)。
     */
    private static double treeChanceAt(ServerLevel level, BlockPos pos) {
        double chance = OvergrownVegetationConfig.COMMON.treeChance.get();
        if (OvergrowthRegionMap.blendedVariantAt(level, pos.getX(), pos.getZ(), 4) == OvergrowthVariant.FUNGAL_SWAMP) {
            return chance * OvergrownVegetationConfig.COMMON.fungalSwampTreeChanceMultiplier.get();
        }
        // 林冠の穴(木を植えない輪)の中には植えない(2026-10-16。格子の有る無しにかかわらず)。
        if (TreeSpacing.inCanopyGap(level.getSeed(), pos.getX(), pos.getZ(), OvergrownVegetationConfig.COMMON.treeCanopyGapChance.get())) {
            return 0.0;
        }
        int spacing = OvergrownVegetationConfig.COMMON.treeSpacing.get();
        if (spacing > 1) {
            return TreeSpacing.allowsTree(level.getSeed(), pos.getX(), pos.getZ(), spacing,
                    OvergrownVegetationConfig.COMMON.treeClearingChance.get()) ? 1.0 : 0.0;
        }
        return chance;
    }

    /**
     * {@link TreeSpacing} で木を立てる列なら、下草・苔の抽選より先に苗木を植える(植えたら true)。
     * 抽選の後ろにあると、下草の確率(既定 0.85)と苔に先を越されて木の立つ列の約半分が外れ、格子を詰めても森が閉じなかった
     * (2026-10-15 の撮影: 間隔 6 で 113 マス中 50 本)。菌湿地帯・{@code treeSpacing} 0 は今までどおり抽選の中で。
     */
    private static boolean tryAnchorTree(ServerLevel level, BlockPos hostPos, BlockState hostState) {
        int spacing = OvergrownVegetationConfig.COMMON.treeSpacing.get();
        if (spacing <= 1 || !OvergrownVegetationConfig.COMMON.spawnTrees.get()) {
            return false;
        }
        BlockPos at = hostPos.above();
        if (!TreeSpacing.allowsTree(level.getSeed(), at.getX(), at.getZ(), spacing,
                OvergrownVegetationConfig.COMMON.treeClearingChance.get())
                || TreeSpacing.inCanopyGap(level.getSeed(), at.getX(), at.getZ(), OvergrownVegetationConfig.COMMON.treeCanopyGapChance.get())) {
            return false;
        }
        if (!(isTreeSoil(hostState) || CorruptedSaplingBlock.isCoveredGround(level, hostPos, hostState))
                || !level.isLoaded(at)) {
            return false;
        }
        if (!clearForAnchorTree(level, at, level.getBlockState(at))) {
            return false;
        }
        if (OvergrowthRegionMap.blendedVariantAt(level, at.getX(), at.getZ(), 4) == OvergrowthVariant.FUNGAL_SWAMP) {
            return false;
        }
        return growSapling(level, at);
    }

    /**
     * 木を立てる列の地面の上に苗木を植えてよいか(そこにある物は苗木で置き換える)。空気・薄い苔・茂みの塊・置き換えられる草花
     * ({@code canBeReplaced} / {@code #minecraft:replaceable_by_trees})・この MOD の当たり判定の無い下草(蔦・地衣類・シダ)はよい。
     * 水・ブロックエンティティ・人の置いた物(松明・レールなど、この MOD 以外の当たり判定の無いブロック)は置き換えない。
     *
     * <p>最初は空気と苔だけにしていたので、地割れ・前進の経路で地面を汚染した瞬間に上へバニラの草や先に生えた下草が載っていた列が
     * 外れ、自然の地形では木を立てる列の 4 割ほどしか木にならなかった(2026-10-15 のテストクライアント: 地割れの周り 81×81 の
     * 160 列のうち木 66・上が空気なのに木が無い 17・下草や茂みが載っていた約 30)。
     */
    private static boolean clearForAnchorTree(ServerLevel level, BlockPos at, BlockState above) {
        if (above.isAir() || above.is(ModBlocks.OVERGROWN_MOSS_LAYER.get()) || above.is(ModBlocks.OVERGROWN_BUSH.get())) {
            return true;
        }
        if (!above.getFluidState().isEmpty() || above.hasBlockEntity() || above.is(ModBlocks.OVERGROWN_SAPLING.get())) {
            return false;
        }
        if (above.canBeReplaced() || above.is(BlockTags.REPLACEABLE_BY_TREES)) {
            return true;
        }
        return above.getCollisionShape(level, at).isEmpty()
                && OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(above.getBlock()).getNamespace());
    }

    public static double surfaceGrowthChance(ServerLevel level, BlockPos pos) {
        double chance = OvergrownVegetationConfig.COMMON.growthChance.get();
        if (OvergrowthRegionMap.isSwampAtBlended(level, pos.getX(), pos.getZ(), 3)) {
            chance *= OvergrownVegetationConfig.COMMON.swampGrowthChanceMultiplier.get();
        }
        return chance;
    }

    /**
     * 汚染し終えたばかりのブロックの周囲を見て、<b>水に接した</b>露出面へ確率で水中植物を生やす。
     * 上面(水側)は海草(まれに背高海草・昆布状の藻柱)と、その真上の水面へまれにスイレン、
     * 下面(水側)は水中の垂れ蔓になる。側面と上面には加えて、薄く覆う藻
     * ({@code waterMossLayerChance}、既定 0.9)が海草類とは独立して生える(陸の薄い苔と同じ)。
     *
     * <p>{@link #growSurfacePlants} が「空気に接した面」だけを見るのに対し、こちらは
     * 「水に接した面」だけを見るので、地上の植物と水中の植物が同じマスへ二重に生えることはない。
     * {@code hostState} が {@link ModTags#GROWTH_HANGING_ONLY}(葉など)の場合は下面のみ。
     */
    private static void growUnderwaterPlants(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (hostState.isAir() || hostState.is(Blocks.WATER)) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.spawnUnderwaterPlants.get()) {
            return;
        }
        final double chance = OvergrownVegetationConfig.COMMON.underwaterGrowthChance.get();
        final double ceilingChance = OvergrownVegetationConfig.COMMON.waterCeilingGrowthChance.get();
        if (chance <= 0.0D && ceilingChance <= 0.0D) {
            return;
        }

        RandomSource random = level.random;
        final boolean hangingOnly = hostState.is(ModTags.GROWTH_HANGING_ONLY);
        BlockPos.MutableBlockPos growthPos = new BlockPos.MutableBlockPos();

        final boolean spawnWaterMoss = OvergrownVegetationConfig.COMMON.spawnWaterMossLayer.get();
        final double waterMossChance = OvergrownVegetationConfig.COMMON.waterMossLayerChance.get();

        for (Direction direction : Direction.values()) {
            if (hangingOnly && direction != Direction.DOWN) {
                continue;
            }
            final boolean side = direction.getAxis().isHorizontal();

            // 藻(多面)は側面と上面に、通常の水中植物(上面の海草類・下面の垂れ蔓)とは
            // 独立した確率で生える。陸の薄い苔と同じ考え方。
            boolean mossFace = side || direction == Direction.UP;
            boolean tryMoss = mossFace && spawnWaterMoss && waterMossChance > 0.0D
                    && random.nextDouble() < waterMossChance;
            // 上面(水草・藻柱)と下面(垂れ蔓)は別の確率で抽選する。下面のほうを低くして、
            // 岩の裏・オーバーハングの下が垂れ蔓で埋まらないようにしている。
            final double plantChance = direction == Direction.DOWN ? ceilingChance : chance;
            boolean tryPlant = !side && plantChance > 0.0D && random.nextDouble() < plantChance;
            if (!tryMoss && !tryPlant) {
                continue;
            }

            growthPos.setWithOffset(pos, direction);
            if (!level.isLoaded(growthPos)) {
                continue;
            }
            BlockState atGrowth = level.getBlockState(growthPos);
            boolean mossHere = atGrowth.is(ModBlocks.OVERGROWN_WATER_MOSS_LAYER.get());
            // 汚染植物の足場になるのは「水源で満たされた」マスか、既に藻が付いているマス
            // (藻は多面なので別方向の藻があるマスにも面を足せる)。海草・水没ブロックなどは対象外。
            boolean waterCell = atGrowth.is(Blocks.WATER) && atGrowth.getFluidState().isSource();
            if (!waterCell && !mossHere) {
                continue;
            }

            BlockPos immutable = growthPos.immutable();
            if (tryMoss) {
                tryGrowWaterMossFace(level, immutable, direction.getOpposite());
                mossHere = level.getBlockState(immutable).is(ModBlocks.OVERGROWN_WATER_MOSS_LAYER.get());
            }

            // マスが藻で埋まっているなら、同じマスに通常の水中植物は置けない。
            if (!tryPlant || mossHere || !waterCell) {
                continue;
            }
            switch (direction) {
                case UP -> growWaterUp(level, immutable, random);
                case DOWN -> growWaterDown(level, immutable);
                default -> { /* 側面は藻のみ */ }
            }
        }
    }

    /**
     * {@code pos}(host の水中露出面に隣接する水マス、または既に藻が付いているマス)へ、
     * host のある方向 {@code attachDir} ぶんの藻({@link ModBlocks#OVERGROWN_WATER_MOSS_LAYER})の
     * 面を 1 つ足す。{@link #tryGrowMossFace}(陸の薄い苔)の水中版。
     */
    private static void tryGrowWaterMossFace(ServerLevel level, BlockPos pos, Direction attachDir) {
        Block moss = ModBlocks.OVERGROWN_WATER_MOSS_LAYER.get();
        BlockState existing = level.getBlockState(pos);
        BooleanProperty faceProp = MultifaceBlock.getFaceProperty(attachDir);

        BlockState next;
        if (existing.is(moss)) {
            if (existing.getValue(faceProp)) {
                return;
            }
            next = existing.setValue(faceProp, true);
        } else if (existing.is(Blocks.WATER) && existing.getFluidState().isSource()) {
            next = moss.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED, true)
                    .setValue(faceProp, true);
        } else {
            return;
        }

        BlockPos supportPos = pos.relative(attachDir);
        if (!MultifaceBlock.canAttachTo(level, attachDir, supportPos, level.getBlockState(supportPos))) {
            return;
        }
        level.setBlock(pos, next, Block.UPDATE_ALL);
    }

    /**
     * 上面(host の真上)の水マスへ水中植物を生やす。抽選順は
     * スイレン(水面) → 藻柱(縦伸び) → 背高海草(高さ2) → 海草(高さ1)。
     * 手前の抽選に外れた/生やせなかった場合は次の候補へ進む。
     */
    private static void growWaterUp(ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextDouble() < OvergrownVegetationConfig.COMMON.lilyChance.get()
                && tryGrowLilyAtSurface(level, pos)) {
            return;
        }

        if (random.nextDouble() < OvergrownVegetationConfig.COMMON.waterColumnChance.get()
                && growWaterColumn(level, pos, random)) {
            return;
        }

        if (random.nextDouble() < OvergrownVegetationConfig.COMMON.underwaterTallChance.get()
                && level.getBlockState(pos.above()).is(Blocks.WATER)) {
            BlockState tall = ModBlocks.OVERGROWN_TALL_WATER_GRASS.get().defaultBlockState();
            if (tall.canSurvive(level, pos)) {
                DoublePlantBlock.placeAt(level, tall, pos, Block.UPDATE_ALL);
                return;
            }
        }

        BlockState grass = ModBlocks.OVERGROWN_WATER_GRASS.get().defaultBlockState();
        if (grass.canSurvive(level, pos)) {
            level.setBlock(pos, grass, Block.UPDATE_ALL);
        }
    }

    /**
     * 藻柱を水面の手前まで縦に積む。1マスでも置けたら true。
     *
     * <p>1 段目を積んだあとは、1 段積むごとに {@code waterColumnFlowerChance} を引く。当たった段は
     * 柱ではなく<b>藻柱の花</b>({@link ModBlocks#OVERGROWN_WATER_COLUMN_FLOWER})になり、そこで成長を
     * 止める(花の上には積まない)。花も高さに数えるので、抽選した高さより高くはならない。外れ続けた
     * 藻柱はこれまでどおり抽選した高さか水面まで伸びる。
     */
    private static boolean growWaterColumn(ServerLevel level, BlockPos base, RandomSource random) {
        int min = OvergrownVegetationConfig.COMMON.waterColumnMinHeight.get();
        int max = Math.max(min, OvergrownVegetationConfig.COMMON.waterColumnMaxHeight.get());
        int height = min + random.nextInt(max - min + 1);
        double flowerChance = OvergrownVegetationConfig.COMMON.waterColumnFlowerChance.get();

        BlockState column = ModBlocks.OVERGROWN_WATER_COLUMN.get().defaultBlockState();
        BlockState flower = ModBlocks.OVERGROWN_WATER_COLUMN_FLOWER.get().defaultBlockState();
        BlockPos.MutableBlockPos cursor = base.mutable();
        int placed = 0;
        for (int i = 0; i < height; i++) {
            if (!level.isLoaded(cursor)) {
                break;
            }
            if (!level.getBlockState(cursor).is(Blocks.WATER)) {
                break;
            }
            // 2 段目以降は、その段が花になって打ち止めになることがある。
            if (placed >= 1 && flowerChance > 0.0D && random.nextDouble() < flowerChance) {
                level.setBlock(cursor, flower, Block.UPDATE_ALL);
                return true;
            }
            level.setBlock(cursor, column, Block.UPDATE_ALL);
            placed++;
            if (!level.getBlockState(cursor.above()).is(Blocks.WATER)) {
                // 次のマスが水でない = 水面に達した。水面から突き出さないようここで止める。
                break;
            }
            cursor.move(Direction.UP);
        }
        return placed > 0;
    }

    /** host の真上の水柱をたどって水面を探し、見つかればそこへスイレンを浮かべる。 */
    private static boolean tryGrowLilyAtSurface(ServerLevel level, BlockPos startWaterPos) {
        int maxDepth = OvergrownVegetationConfig.COMMON.lilyMaxDepth.get();
        BlockPos.MutableBlockPos cursor = startWaterPos.mutable();
        for (int depth = 0; depth <= maxDepth; depth++) {
            if (!level.isLoaded(cursor)) {
                return false;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.is(Blocks.WATER)) {
                cursor.move(Direction.UP);
                continue;
            }
            // 最初に見つかった非水マス。ここが空気で、真下が水なら水面。
            if (!state.isAir()) {
                return false;
            }
            if (!level.getBlockState(cursor.below()).is(Blocks.WATER)) {
                return false;
            }
            BlockState lily = ModBlocks.OVERGROWN_LILY.get().defaultBlockState();
            BlockPos surface = cursor.immutable();
            if (!lily.canSurvive(level, surface)) {
                return false;
            }
            level.setBlock(surface, lily, Block.UPDATE_ALL);
            return true;
        }
        return false;
    }

    /** 下面(host の真下)の水マスへ、水中の垂れ蔓を生やす。 */
    private static void growWaterDown(ServerLevel level, BlockPos pos) {
        BlockState growth = ModBlocks.OVERGROWN_WATER_CEILING_GROWTH.get().defaultBlockState();
        if (growth.canSurvive(level, pos)) {
            level.setBlock(pos, growth, Block.UPDATE_ALL);
        }
    }

    /**
     * 石系ブロックが {@link OvergrownVegetationConfig.Common#stoneToGrassChance} の抽選で
     * {@code overgrown_grass_block}(過繁茂した草ブロック)へ化けた直後に呼ぶ。変換後が草ブロックで、
     * かつ元が石系({@link CorruptionRegistry#isStoneLike})で、真上がロード済みの空気なら、
     * {@link #growSurfacePlants} の露出面抽選(確率 {@code growthChance})に関係なく
     * <b>必ず</b>上面へ下草(まれに背高・苗木)を1つ生やす(要望: 石→草へ化けたときはちゃんと上に草が生える)。
     *
     * <p>{@link #growPlants} が既に上面へ何か生やしていれば真上は空気ではなくなっているので、
     * このメソッドは二重には置かない。
     */
    private static void ensureGrassOnConvertedStone(ServerLevel level, BlockPos pos, BlockState originalState,
                                                    BlockState corruptedState, RandomSource random) {
        if (!OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return;
        }
        if (!corruptedState.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get())) {
            return;
        }
        if (!CorruptionRegistry.isStoneLike(originalState)) {
            return;
        }
        BlockPos above = pos.above();
        if (!level.isLoaded(above) || !level.getBlockState(above).isAir()) {
            return;
        }
        growUp(level, above, random, corruptedState);
    }

    /** {@link #decayNaturalTreeLeaves} が汚染した原木から葉をたどる最大歩数(バニラの葉の最大 distance と同じ)。 */
    private static final int LEAF_DECAY_RANGE = 7;

    /**
     * {@link #decayNaturalTreeLeaves} 1 回で出す葉の破壊演出(levelEvent 2001 = 破壊パーティクル 64 粒 + 破壊音)の上限。
     * 消す葉がこれより多ければ、消した順に等間隔で間引いて出す(消える葉そのものは変わらない)。
     * 仮決め(2026-09-24): 巨大な天蓋 1 つで数百回の破壊演出が一度に届き、重かったため。
     */
    private static final int LEAF_DECAY_EFFECT_MAX = 20;

    /**
     * 既存の木の原木を汚染した直後に呼ぶ。汚染した原木の位置から、まだ汚染されていない
     * 自然生成の葉(プレイヤーが手で設置していない = {@code PERSISTENT=false} の葉)だけを
     * 6 近傍で幅優先にたどり、{@value #LEAF_DECAY_RANGE} 歩ぶんまでを空気へ置き換えて消す。
     * 葉づたいにしか伝播しないので、その原木につながっていた 1 本の木の葉群だけが対象になる。
     *
     * <p>これで既存の木を汚染すると葉が宙に浮いたまま残らず、木を丸ごと枯らして新しい木の
     * 土壌にする、という見た目になる({@link OvergrownVegetationConfig.Common#decayCorruptedTreeLeaves})。
     * ドロップは一切出さない(枯れるので木材・苗木を落とさせない)。
     *
     * <p>破壊の演出は、たどり終えてから最大 {@value #LEAF_DECAY_EFFECT_MAX} 枚ぶんだけ、消した順に等間隔で出す。
     * 間引きに乱数は使わない(汚染の乱数の並びを変えないため)。
     */
    private static void decayNaturalTreeLeaves(ServerLevel level, BlockPos logPos, BlockState logState) {
        if (!OvergrownVegetationConfig.COMMON.decayCorruptedTreeLeaves.get()) {
            return;
        }
        if (!logState.is(BlockTags.LOGS) || logState.is(ModTags.CORRUPTED)) {
            return;
        }

        LongOpenHashSet visited = new LongOpenHashSet();
        List<BlockPos> frontier = new ArrayList<>();
        frontier.add(logPos.immutable());
        visited.add(logPos.asLong());
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        // 消した葉の位置と状態 id(破壊の演出を後でまとめて間引いて出すため)。
        LongArrayList removedPos = new LongArrayList();
        IntArrayList removedStateId = new IntArrayList();

        for (int dist = 0; dist < LEAF_DECAY_RANGE && !frontier.isEmpty(); dist++) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos current : frontier) {
                for (Direction direction : Direction.values()) {
                    scratch.setWithOffset(current, direction);
                    if (!visited.add(scratch.asLong()) || !level.isLoaded(scratch)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(scratch);
                    if (!isNaturalLeaves(state)) {
                        continue;
                    }
                    BlockPos leafPos = scratch.immutable();
                    // ドロップ無しで消す。破壊パーティクル + 音は下でまとめて(間引いて)出す。
                    removedPos.add(leafPos.asLong());
                    removedStateId.add(Block.getId(state));
                    level.removeBlock(leafPos, false);
                    next.add(leafPos);
                }
            }
            frontier = next;
        }

        // 消した順に等間隔で最大 LEAF_DECAY_EFFECT_MAX 枚(それ以下なら全部)。
        int removed = removedPos.size();
        int effects = Math.min(removed, LEAF_DECAY_EFFECT_MAX);
        for (int k = 0; k < effects; k++) {
            int index = (int) ((long) k * removed / effects);
            level.levelEvent(2001, BlockPos.of(removedPos.getLong(index)), removedStateId.getInt(index));
        }
    }

    /** ワールド生成の木が置いた「自然な葉」(手で設置していない、まだ汚染されていない葉)か。 */
    private static boolean isNaturalLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES)
                && state.hasProperty(BlockStateProperties.PERSISTENT)
                && !state.getValue(BlockStateProperties.PERSISTENT)
                && !state.is(ModTags.CORRUPTED);
    }

    // --- アーチ / 川の橋 -------------------------------------------------------

    /**
     * 汚染した筋の先端 {@code head} から、水平方向へ巨大樹の根のアーチと同じ形の装飾アーチを
     * 1 本置く({@link CorruptionArch})。長さは設定範囲内でランダム、<b>太さと持ち上げは長さ(=アーチの
     * 大きさ)から決まる</b> — 大きいアーチほど太く、かつ高くなりやすい。
     *
     * <p><b>両端に地面がある場合のみ生成する</b>(終端の地面が見つからなければ何もしない —
     * マングローブ林などで空中にアーチができるのを防ぐ)。水中(両端が水没した地面)・水面をまたぐ形
     * でも、陸のアーチと同じ確率({@code corruptionArchChance})で生成する。生成後、アーチの表面全体
     * (背・側面・肩)に草・下草・苗木・蔦、水中の面には藻・水草を生やす({@link #decorateArchShell})。
     * 汚染をそこから続けたりはしない(あくまで装飾)。
     */
    private void tryRandomArch(ServerLevel level, BlockPos origin, TendrilNode head, RandomSource random,
                               LongOpenHashSet touchedChunks) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;

        // アーチの代わりに地面から突き出す根の先端(2026-10-09、ユーザー指定「アーチ:根の先端 = 7:3、合計は今のアーチと同じ」)。
        if (random.nextDouble() < CorruptionRootTip.SHARE) {
            tryGroundRootTip(level, origin, head, random, touchedChunks);
            return;
        }

        // ごく低確率で、まず「溝(渓谷)をまたぐ上下逆のアーチ」を試す。溝が無ければ通常のアーチに回る
        // ので、溝の無い場所でのアーチの本数は変わらない。
        double ravineShare = cfg.corruptionRavineArchShare.get();
        if (ravineShare > 0.0D && random.nextDouble() < ravineShare
                && tryRavineArch(level, origin, head, random, touchedChunks)) {
            return;
        }

        // 向きはどの水平の角度でも(2026-10-09、ユーザー指定。それまでは触手の向きか東西南北のどれか)。
        double angle = random.nextDouble() * Math.PI * 2.0D;

        int minLen = cfg.corruptionArchMinLength.get();
        int maxLen = Math.max(minLen, cfg.corruptionArchMaxLength.get());
        int span = minLen + (maxLen > minLen ? random.nextInt(maxLen - minLen + 1) : 0);

        // アーチの「大きさ」= 設定範囲内での相対的な長さ(0.0〜1.0)。太さ・高さはこれで決まる。
        double sizeFrac = maxLen > minLen ? (double) (span - minLen) / (maxLen - minLen) : 1.0D;

        // 太さ: ランダムではなく大きさに比例(min〜max を線形補間)。
        int minT = cfg.corruptionArchMinThickness.get();
        int maxT = Math.max(minT, cfg.corruptionArchMaxThickness.get());
        int thickness = minT + (int) Math.round(sizeFrac * (maxT - minT));

        // 持ち上げ: 上限は長さに比例。さらに大きいアーチほど「その上限に近い高さ」が出やすいよう、
        // 一様乱数を pow で歪ませる(sizeFrac=0 で一様、sizeFrac=1 で高い側へ強く偏る)。
        int peakCeil = Math.max(2, Math.round(span * 0.5F));
        double biased = Math.pow(random.nextDouble(), 1.0D - 0.7D * sizeFrac);
        int peak = 2 + (int) Math.round(biased * (peakCeil - 2));

        int hx = origin.getX() + head.x;
        int hy = origin.getY() + head.y;
        int hz = origin.getZ() + head.z;
        int tx = hx + (int) Math.round(Math.cos(angle) * span);
        int tz = hz + (int) Math.round(Math.sin(angle) * span);

        // 起点側・終端側の地面の高さ。どちらか片方でも「乗れる地面」が無ければアーチは架けない。
        int fromY = findArchAnchorY(level, hx, hz, hy);
        int toY = findArchAnchorY(level, tx, tz, hy);
        if (fromY == Integer.MIN_VALUE || toY == Integer.MIN_VALUE) {
            return;
        }

        // 水中に架かるアーチ・水面をまたぐアーチは、陸のアーチの確率(corruptionArchChance)にさらに
        // waterArchChanceMultiplier を掛けて間引く(2026-09-10、水中の根のアーチが多すぎるとの要望)。
        int mx = (hx + tx) / 2;
        int mz = (hz + tz) / 2;
        boolean watery = isWaterColumn(level, hx, fromY, hz) || isWaterColumn(level, tx, toY, tz)
                || isWaterColumn(level, mx, Math.min(fromY, toY), mz);
        if (watery && random.nextDouble() >= OvergrownVegetationConfig.COMMON.waterArchChanceMultiplier.get()) {
            return;
        }

        List<BlockPos> shell = new ArrayList<>();
        if (CorruptionArch.build(level, new BlockPos(hx, fromY, hz), new BlockPos(tx, toY, tz), peak,
                thickness, ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState(), touchedChunks, null, shell)) {
            decorateArchShell(level, shell, random, touchedChunks);
        }
    }

    /**
     * 地面から斜めに倒れて突き出す根の先端を 1 本({@link CorruptionRootTip}、2026-10-09 ユーザー指定)。触手の先の列の地面に根元を埋め、
     * どの水平の向きへでも、斜めに立ち上がって先が少し反る。経路が水・家・木で塞がっていれば建てない。表面はアーチと同じく草・苔で飾る。
     */
    private void tryGroundRootTip(ServerLevel level, BlockPos origin, TendrilNode head, RandomSource random,
                                  LongOpenHashSet touchedChunks) {
        int hx = origin.getX() + head.x;
        int hy = origin.getY() + head.y;
        int hz = origin.getZ() + head.z;
        int groundY = findArchAnchorY(level, hx, hz, hy);
        if (groundY == Integer.MIN_VALUE) {
            return;
        }
        CorruptionRootTip.Shape shape = CorruptionRootTip.rollGround(random);
        // 根元の中心は地表のブロックの 1 つ下 —— 埋まった区間を進むうちに地面から出る。
        List<CorruptionRootTip.Cell> cells = CorruptionRootTip.plan(hx + 0.5D, groundY - 0.5D, hz + 0.5D, shape);
        if (!CorruptionRootTip.pathIsOpen(cells, pos -> {
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState s = level.getBlockState(pos);
            return s.getFluidState().isEmpty() && (s.isAir() || s.canBeReplaced());
        })) {
            return;
        }
        List<BlockPos> shell = new ArrayList<>();
        if (CorruptionRootTip.place(level, cells, ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState(), touchedChunks, shell)) {
            decorateArchShell(level, shell, random, touchedChunks);
        }
    }

    /** 溝と認める最小の連続幅(列)。これ未満の裂け目にはアーチを架けない。 */
    private static final int RAVINE_ARCH_MIN_GAP = 2;
    /** 向こう岸の縁として認める、手前の縁との高さの差(ブロック)。 */
    private static final int RAVINE_ARCH_RIM_TOLERANCE = 3;
    /** 溝の底を探して下へ潜る最大の深さ(ブロック)。 */
    private static final int RAVINE_ARCH_FLOOR_PROBE = 48;
    /** 垂れ下がる深さの上限(ブロック)。渓谷の底まで落ちる長大な根になるのを防ぐ。 */
    private static final int RAVINE_ARCH_MAX_DIP = 10;

    /**
     * 渓谷など<b>溝</b>をまたぐ「上下逆のアーチ」を 1 本架ける。通常のアーチが地面から持ち上がる
     * かまぼこ形なのに対し、こちらは両岸の縁を結んで中央が谷底へ向かって<b>垂れ下がる</b>
     * ({@link CorruptionArch#build} に負の持ち上げを渡す)。裂け目に渡された巨大樹の根、という見た目。
     *
     * <p>手前の縁({@code head})から 4 方向を順に見て、最初に「溝 → 向こう岸の縁」が揃った方向へ架ける。
     * 溝は「その列の縁の高さから下へ {@code corruptionRavineArchMinDepth} マス分、乗れる地面も液体も
     * 無いこと」で判定する(水没した裂け目は対象外 — 水のアーチは通常のアーチ側で扱う)。縁の階段状の
     * 段はまたいでよく、溝の連続幅が {@value #RAVINE_ARCH_MIN_GAP} 列以上で、向こう岸の地面が手前の縁と
     * {@value #RAVINE_ARCH_RIM_TOLERANCE} ブロック以内の高さにあることを求める。
     *
     * <p>垂れ下がる深さは長さに比例させつつ、溝の底より手前で止める(底に着く根は作らない)。
     * 生成後は通常のアーチと同じく表面全体に草・下草・蔦を生やす({@link #decorateArchShell})。
     *
     * @return 架けられたら true。溝が見つからなければ false で、呼び出し元は通常のアーチに回る。
     */
    private boolean tryRavineArch(ServerLevel level, BlockPos origin, TendrilNode head, RandomSource random,
                                  LongOpenHashSet touchedChunks) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int hx = origin.getX() + head.x;
        int hy = origin.getY() + head.y;
        int hz = origin.getZ() + head.z;

        int fromY = findArchAnchorY(level, hx, hz, hy);
        if (fromY == Integer.MIN_VALUE) {
            return false;
        }
        int minDepth = Math.max(2, cfg.corruptionRavineArchMinDepth.get());
        int minLen = cfg.corruptionArchMinLength.get();
        int maxSpan = Math.max(minLen, cfg.corruptionArchMaxLength.get());
        int minT = cfg.corruptionArchMinThickness.get();
        int maxT = Math.max(minT, cfg.corruptionArchMaxThickness.get());

        // 向きはどの水平の角度でも(2026-10-09、ユーザー指定)。最初の角度から 90 度ずつ回して 4 方向を試す。
        double first = random.nextDouble() * Math.PI * 2.0D;
        for (int turn = 0; turn < 4; turn++) {
            double angle = first + turn * Math.PI / 2.0D;
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            int span = findRavineSpan(level, hx, fromY, hz, ux, uz, maxSpan, minDepth);
            if (span <= 0) {
                continue;
            }
            int tx = hx + (int) Math.round(ux * span);
            int tz = hz + (int) Math.round(uz * span);
            int toY = findArchAnchorY(level, tx, tz, fromY);
            if (toY == Integer.MIN_VALUE) {
                continue;
            }

            // 太さは通常のアーチと同じく長さから決める(長い根ほど太い)。
            double sizeFrac = maxSpan > minLen
                    ? Mth.clamp((span - minLen) / (double) (maxSpan - minLen), 0.0D, 1.0D)
                    : 1.0D;
            int thickness = minT + (int) Math.round(sizeFrac * (maxT - minT));

            // 垂れ下がる深さ: 長さに比例。溝の底が近ければ底に着かない深さまで詰める。
            int dip = Math.min(RAVINE_ARCH_MAX_DIP, Math.max(1, Math.round(span * 0.45F)));
            int mx = hx + (int) Math.round(ux * (span / 2));
            int mz = hz + (int) Math.round(uz * (span / 2));
            int floorDepth = gapFloorDepth(level, mx, mz, Math.min(fromY, toY));
            if (floorDepth > 0) {
                dip = Math.min(dip, Math.max(1, floorDepth - 2 - thickness));
            }

            List<BlockPos> shell = new ArrayList<>();
            if (!CorruptionArch.build(level, new BlockPos(hx, fromY, hz), new BlockPos(tx, toY, tz), -dip,
                    thickness, ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState(), touchedChunks,
                    null, shell)) {
                continue;
            }
            decorateArchShell(level, shell, random, touchedChunks);
            return true;
        }
        return false;
    }

    /**
     * {@code (x0, z0)}(手前の縁。地面の高さ {@code rimY})から水平の向き {@code (ux, uz)}(長さ 1)へ、溝をまたいで
     * 向こう岸の縁に届くまでの距離を返す。溝が無ければ 0。
     */
    private static int findRavineSpan(ServerLevel level, int x0, int rimY, int z0, double ux, double uz,
                                      int maxSpan, int minDepth) {
        int gapRun = 0;
        for (int k = 1; k <= maxSpan; k++) {
            int x = x0 + (int) Math.round(ux * k);
            int z = z0 + (int) Math.round(uz * k);
            if (isGapColumn(level, x, z, rimY, minDepth)) {
                gapRun++;
                continue;
            }
            int groundY = findArchAnchorY(level, x, z, rimY);
            if (groundY == Integer.MIN_VALUE) {
                return 0; // 未ロード —— この方向は諦める。
            }
            if (gapRun >= RAVINE_ARCH_MIN_GAP && Math.abs(groundY - rimY) <= RAVINE_ARCH_RIM_TOLERANCE) {
                return k; // 向こう岸の縁。
            }
            if (groundY > rimY + RAVINE_ARCH_RIM_TOLERANCE) {
                return 0; // 壁が立ち上がっている —— 溝ではない。
            }
            gapRun = 0; // 縁の階段状の段。溝はこの先。
        }
        return 0;
    }

    /**
     * その列が「溝」か。縁の高さ {@code rimY} から下へ {@code depth} マス分、乗れる地面も液体も
     * 無ければ true。未ロードの列は false(溝とみなさない)。
     */
    private static boolean isGapColumn(ServerLevel level, int x, int z, int rimY, int depth) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int d = 0; d < depth; d++) {
            cursor.set(x, rimY - d, z);
            if (!level.isLoaded(cursor)) {
                return false;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code rimY} から下へ {@value #RAVINE_ARCH_FLOOR_PROBE} マスまで潜って、溝の底(最初の
     * 乗れる地面か液面)までの深さを返す。見つからなければ 0(底なし扱い)。
     */
    private static int gapFloorDepth(ServerLevel level, int x, int z, int rimY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= RAVINE_ARCH_FLOOR_PROBE; d++) {
            cursor.set(x, rimY - d, z);
            if (!level.isLoaded(cursor)) {
                return d;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) {
                return d;
            }
        }
        return 0;
    }

    /** {@code (x, z)} 列の {@code groundY} の上 1〜3 マスのどこかに水があるか(水中・水面をまたぐアーチの判定)。 */
    private static boolean isWaterColumn(ServerLevel level, int x, int groundY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 3; dy++) {
            cursor.set(x, groundY + dy, z);
            if (level.getBlockState(cursor).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * アーチ(かまぼこ状の盛り上がり)の<b>表面全体</b> — 背だけでなく側面・肩の斜面まで含めて —
     * に草・下草・苗木・蔦を生やす。{@code shell} には {@link CorruptionArch#build} が置いた全ブロックが
     * 入っている想定で、そのうち<b>露出しているブロックだけ</b>を対象にする(内部に埋まったブロックは
     * {@link #isExposed} で弾く)。
     *
     * <p>露出ブロックが {@code giant_overgrown_log} で<b>真上が空気</b>なら、確率
     * {@code corruptionArchGrowthChance} でそのブロックを {@code overgrown_grass_block} へ変えてから
     * その上へ {@link #growUp}(草・背高・苗木の抽選)。外れた場合や真上が塞がっている場合は、
     * 丸太のまま露出面へ {@link #growSurfacePlants}(シダ・苔・壁蔦)。
     *
     * <p>以前は各ステップの中央列の一番上(=背)1マスしか対象にしていなかったため、
     * 盛り上がりの頂上にだけ一筋の草が生え、斜面は丸裸だった。
     */
    private void decorateArchShell(ServerLevel level, List<BlockPos> shell, RandomSource random,
                                   LongOpenHashSet touchedChunks) {
        if (!OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return;
        }
        double growthChance = OvergrownVegetationConfig.COMMON.growthChance.get();
        double grassChance = OvergrownVegetationConfig.COMMON.corruptionArchGrowthChance.get();
        Block logBlock = ModBlocks.GIANT_OVERGROWN_LOG.get();
        BlockState logState = logBlock.defaultBlockState();

        for (BlockPos pos : shell) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(logBlock)) {
                continue;
            }
            if (!isExposed(reader, pos.getX(), pos.getY(), pos.getZ(), exposureScratch)) {
                // 内部に埋まっている(6面すべて塞がっている)ブロックは飾らない
                continue;
            }
            BlockPos above = pos.above();
            boolean aboveIsAir = level.isLoaded(above) && level.getBlockState(above).isAir();
            if (aboveIsAir && grassChance > 0.0D && random.nextDouble() < grassChance) {
                BlockState grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState();
                level.setBlock(pos, grass, Block.UPDATE_ALL);
                touchedChunks.add(new ChunkPos(pos).toLong());
                growUp(level, above, random, grass);
                // 草ブロックに変えた斜面のブロックも、側面には蔦・苔を生やす
                growSurfacePlants(level, pos, grass, growthChance);
            } else {
                growSurfacePlants(level, pos, logState, growthChance);
                // 水中に架かったアーチの露出面には藻・水中植物を生やす
                growUnderwaterPlants(level, pos, logState);
            }
        }
    }

    /**
     * 触手が水でブロックされたとき、進行方向 {@code dir} の対岸を探して橋を架ける。
     * {@code corruptionBridgeMaxLength} マス以内に「水を1マス以上またいだ先の陸地」が見つかれば、
     * 巨大樹の根のアーチと同じ形の橋を {@code head} からそこへ架け、着地点を汚染して足場を作り、
     * そこから汚染を続ける新しい触手を仕込む。
     *
     * <p>橋はどの向きにも架かる(2026-10-10、ユーザー指定「水中のアーチも斜めに。角度は限定しない、対岸に行ければいい」):
     * ランダムな水平の向きで対岸を探すのを {@value #BRIDGE_RANDOM_TRIES} 回まで試し(陸の側を向いた向きは水をまたがないのですぐ外れる)、
     * どれも駄目なら今までどおり {@code dir} へまっすぐ探す(向きを散らしたぶん橋が減らないように)。対岸で汚染を続ける向きはどれでも {@code dir}。
     *
     * @return 橋を架けられたら true
     */
    private boolean tryBridgeAcross(ServerLevel level, BlockPos origin, TendrilNode head, Direction dir,
                                    int backtrackBudget, RandomSource random, LongOpenHashSet touchedChunks) {
        if (bridgesBuilt >= OvergrownVegetationConfig.COMMON.corruptionBridgeMaxCount.get()) {
            return false;
        }
        for (int i = 0; i < BRIDGE_RANDOM_TRIES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            if (tryBridgeAlong(level, origin, head, dir, Math.cos(angle), Math.sin(angle),
                    backtrackBudget, random, touchedChunks)) {
                return true;
            }
        }
        return tryBridgeAlong(level, origin, head, dir, dir.getStepX(), dir.getStepZ(),
                backtrackBudget, random, touchedChunks);
    }

    /** 川の橋でランダムな向きを試す回数(どれも対岸が無ければ触手の向きへまっすぐ)。Claude 仮決め。 */
    private static final int BRIDGE_RANDOM_TRIES = 4;

    /** {@link #tryBridgeAcross} の 1 方向ぶん。{@code (ux, uz)} は対岸を探す水平の向き(長さ 1)。 */
    private boolean tryBridgeAlong(ServerLevel level, BlockPos origin, TendrilNode head, Direction dir, double ux, double uz,
                                   int backtrackBudget, RandomSource random, LongOpenHashSet touchedChunks) {
        int maxSpan = OvergrownVegetationConfig.COMMON.corruptionBridgeMaxLength.get();
        int minT = OvergrownVegetationConfig.COMMON.corruptionArchMinThickness.get();
        int maxT = Math.max(minT, OvergrownVegetationConfig.COMMON.corruptionArchMaxThickness.get());

        int hx = origin.getX() + head.x;
        int hy = origin.getY() + head.y;
        int hz = origin.getZ() + head.z;
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();

        boolean sawWater = false;
        for (int k = 1; k <= maxSpan; k++) {
            int wx = hx + (int) Math.round(ux * k);
            int wz = hz + (int) Math.round(uz * k);

            // head の高さ付近(+2 〜 -4)で、この列の「地表」に相当するブロックを探す。
            BlockState surface = null;
            int surfaceY = 0;
            for (int y = hy + 2; y >= hy - 4; y--) {
                scan.set(wx, y, wz);
                if (!level.isLoaded(scan)) {
                    return false;
                }
                BlockState s = level.getBlockState(scan);
                boolean emptyReplaceable = s.canBeReplaced() && s.getFluidState().isEmpty();
                if (s.isAir() || emptyReplaceable || s.is(BlockTags.LEAVES)) {
                    continue;
                }
                surface = s;
                surfaceY = y;
                break;
            }
            if (surface == null) {
                continue;
            }
            if (surface.getFluidState().is(FluidTags.WATER)) {
                sawWater = true;
                continue;
            }

            // 水以外の地表 = 対岸。水を1マス以上またいでいて、最低幅もあること。
            if (!sawWater || k < 2) {
                return false;
            }
            BlockPos from = new BlockPos(hx, hy, hz);
            BlockPos to = new BlockPos(wx, surfaceY, wz);

            // 太さ・持ち上げは橋の長さ(span = k)から決める(装飾アーチ tryRandomArch と同じ考え方)。
            // 短い橋は細く低く、長い橋ほど太く高くなる。
            double sizeFrac = maxSpan > 2 ? Mth.clamp((k - 2.0D) / (maxSpan - 2.0D), 0.0D, 1.0D) : 0.0D;
            int thickness = minT + (int) Math.round(sizeFrac * (maxT - minT));
            int peakCeil = Math.max(2, Math.round(k * 0.4F));
            double biasedPeak = Math.pow(random.nextDouble(), 1.0D - 0.7D * sizeFrac);
            int peak = 2 + (int) Math.round(biasedPeak * (peakCeil - 2));

            List<BlockPos> shell = new ArrayList<>();
            if (!CorruptionArch.build(level, from, to, peak, thickness,
                    ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState(), touchedChunks, null, shell)) {
                return false;
            }
            bridgesBuilt++;
            decorateArchShell(level, shell, random, touchedChunks);
            seedFarBank(level, origin, to, dir, backtrackBudget, random, touchedChunks);
            return true;
        }
        return false;
    }

    /**
     * 橋の着地点そのものを汚染して足場を作り、そこから進行方向 {@code dir} へ汚染を続ける
     * 新しい触手を1本仕込む。着地点が半径外なら触手は次の tick で即座に枯れるだけ(橋と
     * 汚染した着地点は残る)。
     */
    private void seedFarBank(ServerLevel level, BlockPos origin, BlockPos landPos, Direction dir,
                             int backtrackBudget, RandomSource random, LongOpenHashSet touchedChunks) {
        int nx = landPos.getX() - origin.getX();
        int ny = landPos.getY() - origin.getY();
        int nz = landPos.getZ() - origin.getZ();

        BlockState landState = level.getBlockState(landPos);
        if (!landState.is(ModTags.CORRUPTED) && CorruptionRegistry.canCorrupt(level, landPos, landState)) {
            BlockState corrupted = CorruptionRegistry.corrupt(level, landPos, landState, random);
            if (!CorruptionRegistry.noChange(landState, corrupted)) {
                CorruptionRegistry.placeCorrupted(level, landPos, landState, corrupted, Block.UPDATE_ALL);
                touchedChunks.add(new ChunkPos(landPos).toLong());
                growPlants(level, landPos, corrupted);
                corruptedCount++;
            }
        }

        long offset = BlockPos.asLong(nx, ny, nz);
        if (claimed.add(offset)) {
            tendrils.add(new Tendril(nx, ny, nz, dir, 0, backtrackBudget, false));
        }
    }

    /**
     * {@code (x, z)} 列でアーチの端点を乗せられる「地面」の Y を探す。{@code refY + 3} から
     * {@code refY - 12} まで上から見て、最初に見つかる「空気でも置き換え可能でも液体でも葉でもない
     * 固体ブロック」の Y を返す(水没した地面もOK — 水の列は読み飛ばしてその下の地面を拾う)。
     * 範囲内に地面が無ければ {@link Integer#MIN_VALUE}(= アーチを架けない)。
     */
    private static int findArchAnchorY(ServerLevel level, int x, int z, int refY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = refY + 3; y >= refY - 12; y--) {
            cursor.set(x, y, z);
            if (!level.isLoaded(cursor)) {
                return Integer.MIN_VALUE;
            }
            BlockState s = level.getBlockState(cursor);
            if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * {@code pos} の露出面(空気に接した面)に、面ごとに確率 {@code chance} で装飾植物を生やす。
     * 上面は下草(まれに高さ2ブロック)、下面は垂れ下がる植物、側面は壁の蔦。{@code hostState} が
     * {@link ModTags#GROWTH_HANGING_ONLY}(葉ブロックなど)の場合は下面の垂れ植物のみ。
     * 茂み({@link LeafClumpGrowth})の塊は含まない。
     *
     * <p>{@link #growPlants}(汚染直後)のほか、巨大樹が生えきったあとの表面装飾
     * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue})からも呼ばれる。
     */
    public static void growSurfacePlants(ServerLevel level, BlockPos pos, BlockState hostState, double chance) {
        // 外(巨大樹の装飾・過繁茂の前進・爆発の跡など)から呼ばれても、取り残された植物の破壊でアイテムを落とさない
        CorruptionDropSuppressor.enter();
        try {
            growSurfacePlantsInScope(level, pos, hostState, chance);
        } finally {
            CorruptionDropSuppressor.exit();
        }
    }

    private static void growSurfacePlantsInScope(ServerLevel level, BlockPos pos, BlockState hostState, double chance) {
        if (hostState.isAir() || hostState.is(Blocks.WATER)) { // 溶けた氷の跡の水(2026-10-16)も
            // 雪のように汚染で消えるだけのブロックの跡地には装飾植物を生やさない。
            return;
        }
        if (chance <= 0.0D || !OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return;
        }

        RandomSource random = level.random;
        final boolean hangingOnly = hostState.is(ModTags.GROWTH_HANGING_ONLY);
        BlockPos.MutableBlockPos growthPos = new BlockPos.MutableBlockPos();

        final boolean spawnMoss = OvergrownVegetationConfig.COMMON.spawnMossLayer.get();
        final double mossChance = OvergrownVegetationConfig.COMMON.mossLayerChance.get();
        final boolean mudHost = hostState.is(ModBlocks.OVERGROWN_MUD.get());

        // 洞窟(地表より caveDepthMargin 以上深い場所)の露出面。湿地・菌湿地帯では下草・蔦の代わりに
        // 洞窟専用の植物(微光の胞子嚢・苔の塊)を生やし、まれに構造物(壁の傘・天井のアーチ・菌の柱)を建てる。
        // 密林の洞窟では天井のアーチだけが対象で、植物はいつも通り(CaveGrowth 参照)。
        // 天井のアーチは caveVineArchShare の割合で、根ではなく葉に包まれた巨大な蔦になる。
        // 密林の洞窟では、天井の垂れ植物が光るツタ(グロウベリー仕様)になる確率を地上よりかなり高くする
        // (glowVinesCaveMultiplier)。湿地系の洞窟は洞窟植物に置き換わるので光るツタは生えない。
        final CaveGrowth.Mode cave = CaveGrowth.modeAt(level, pos, hostState);
        if (cave != CaveGrowth.Mode.NONE) {
            CaveGrowth.tryStructure(level, pos, hostState, random, cave);
        }
        // 地下(洞窟)かどうか。光るツタの増量に使う。modeAt が洞窟と判定していればそれを流用し、
        // caveGrowth をオフにしているときだけ深さを単独で見る(天井探しを二重に走らせない)。
        final boolean underground = cave != CaveGrowth.Mode.NONE
                || (!OvergrownVegetationConfig.COMMON.caveGrowth.get()
                        && CaveGrowth.isUnderground(level, pos, hostState));
        final boolean caveSwamp = cave == CaveGrowth.Mode.SWAMP;
        final double plantChance = caveSwamp ? OvergrownVegetationConfig.COMMON.caveGrowthChance.get() : chance;

        for (Direction direction : Direction.values()) {
            if (hangingOnly && direction != Direction.DOWN) {
                continue;
            }
            if (direction == Direction.UP && !caveSwamp && tryAnchorTree(level, pos, hostState)) {
                continue;
            }
            if (mudHost && direction == Direction.UP && !caveSwamp) {
                // 泥の上面は専用ルール(薄い苔 / 胞子キノコ / まれに苗木)。
                growMudTop(level, pos.above(), random, chance);
                continue;
            }

            // 薄く広がる苔(多面)は通常の下草・蔦の抽選(chance)とは独立して判定する。
            // どちらも外れた面は、ブロック状態を読む前にここで安く打ち切る(負荷対策)。
            boolean tryMoss = spawnMoss && mossChance > 0.0D && random.nextDouble() < mossChance;
            boolean tryPlant = random.nextDouble() < plantChance;
            if (!tryMoss && !tryPlant) {
                continue;
            }

            growthPos.setWithOffset(pos, direction);
            if (!level.isLoaded(growthPos)) {
                continue;
            }
            BlockState atGrowth = level.getBlockState(growthPos);
            boolean mossHere = atGrowth.is(ModBlocks.OVERGROWN_MOSS_LAYER.get());
            // 苔は多面なので、既に別方向の苔が付いているマスにも面を足せる。それ以外で空気で
            // ないマスにはこれまでどおり何も生やさない。
            if (!atGrowth.isAir() && !mossHere) {
                continue;
            }

            BlockPos immutableGrowthPos = growthPos.immutable();

            if (tryMoss) {
                tryGrowMossFace(level, immutableGrowthPos, direction.getOpposite());
                mossHere = level.getBlockState(immutableGrowthPos).is(ModBlocks.OVERGROWN_MOSS_LAYER.get());
            }

            // マスが苔で埋まっているなら、同じマスに通常の下草・蔦は置けない(苔を上書きしてしまう)。
            if (!tryPlant || mossHere) {
                continue;
            }

            if (caveSwamp) {
                // 湿地系の洞窟: 床・壁・天井のどの面も洞窟植物(胞子嚢 / 苔の塊)。
                // 光るツタはここでは生やさない(菌の洞窟の明かりは胞子嚢と光る菌糸に任せる)。
                CaveGrowth.placePlant(level, immutableGrowthPos, direction, random);
                continue;
            }

            switch (direction) {
                case UP -> growUp(level, immutableGrowthPos, random, hostState);
                case DOWN -> growDown(level, immutableGrowthPos, random, underground);
                default -> growSide(level, immutableGrowthPos, random, direction);
            }
        }
    }

    /**
     * {@code pos}(host の露出面に隣接する空気マス、または既に薄い苔が付いているマス)へ、
     * host のある方向 {@code attachDir} ぶんの薄い苔({@link ModBlocks#OVERGROWN_MOSS_LAYER})の
     * 面を1つ足す。既に同じ面の苔が付いている / host 面が苔を支えられない / マスが苔でも空気でも
     * ない場合は何もしない。多面ブロックなので、別方向の苔が既にあるマスにはその状態へ面を足す。
     */
    private static void tryGrowMossFace(ServerLevel level, BlockPos pos, Direction attachDir) {
        Block moss = ModBlocks.OVERGROWN_MOSS_LAYER.get();
        BlockState existing = level.getBlockState(pos);
        BooleanProperty faceProp = MultifaceBlock.getFaceProperty(attachDir);

        BlockState next;
        if (existing.is(moss)) {
            if (existing.getValue(faceProp)) {
                return;
            }
            next = existing.setValue(faceProp, true);
        } else if (existing.isAir()) {
            next = moss.defaultBlockState().setValue(faceProp, true);
        } else {
            return;
        }

        BlockPos supportPos = pos.relative(attachDir);
        if (!MultifaceBlock.canAttachTo(level, attachDir, supportPos, level.getBlockState(supportPos))) {
            return;
        }
        level.setBlock(pos, next, Block.UPDATE_ALL);
    }

    /** {@link #growTopUndergrowth} が「盛り上がりの頂上」を探して {@code hostBlock} を下へたどる最大歩数。 */
    private static final int TOP_UNDERGROWTH_SCAN_DEPTH = 14;

    /**
     * {@code (x, z)} 列の上端付近から下へ {@code hostBlock}(例: {@code giant_overgrown_log})を探し、
     * 最初に見つかった「露出した根ブロック」(真上が空気または水源)を、確率 {@code chance} で
     * 汚染された地面と同じように<b>露出面すべて</b>飾る:
     * <ul>
     *     <li>真上が<b>空気</b> — 上面に陸の下草(シダ・苔・背高植物。宿主が原木なので苗木は生えない)、
     *         側面に苔・蔦({@link #growSurfacePlants})。</li>
     *     <li>真上が<b>水源</b> — 上面に水中植物(海草・背高海草・藻柱・スイレン)、側面に藻
     *         ({@link #growUnderwaterPlants})。</li>
     * </ul>
     * 真上が空気でも水でもない(= 隣の盛り上がりの下に埋まっている)場合は何もしない。
     *
     * <p>巨大樹の根・レイドの根のかまぼこ状の盛り上がりを、汚染された地面と同じように苔むして
     * 見せるために、{@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue} の装飾フェーズと
     * {@link com.yayuy.overgrownvegetation.raid.RaidRootGrowth} の根の伸長から呼ばれる。
     *
     * @param groundY 根を這わせたときに記録した足元(地表)の Y。ここから
     *                {@code groundY + }{@value #TOP_UNDERGROWTH_SCAN_DEPTH} の高さを上限に頂上を探す。
     */
    public static void growTopUndergrowth(ServerLevel level, int x, int groundY, int z, Block hostBlock, double chance) {
        // 取り残された植物などの破壊でアイテムを落とさない(CorruptionDropSuppressor)
        CorruptionDropSuppressor.enter();
        try {
            growTopUndergrowthInScope(level, x, groundY, z, hostBlock, chance);
        } finally {
            CorruptionDropSuppressor.exit();
        }
    }

    private static void growTopUndergrowthInScope(ServerLevel level, int x, int groundY, int z, Block hostBlock,
                                                  double chance) {
        if (chance <= 0.0D || !OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = groundY + TOP_UNDERGROWTH_SCAN_DEPTH; y >= groundY - 2; y--) {
            cursor.set(x, y, z);
            if (!level.isLoaded(cursor)) {
                return;
            }
            BlockState state = level.getBlockState(cursor);
            if (!state.is(hostBlock)) {
                continue;
            }
            BlockPos above = cursor.above().immutable();
            if (!level.isLoaded(above)) {
                return;
            }
            BlockState aboveState = level.getBlockState(above);
            boolean air = aboveState.isAir();
            boolean water = aboveState.is(Blocks.WATER) && aboveState.getFluidState().isSource();
            if (!air && !water) {
                return; // 隣の盛り上がりの下などに埋まっている
            }
            if (level.random.nextDouble() < chance) {
                BlockPos crest = cursor.immutable();
                double growthChance = surfaceGrowthChance(level, crest);
                // 露出面すべてを飾る。地上側(空気に接する面)は growSurfacePlants が上面の下草も含めて、
                // 水中側(水に接する面)は growUnderwaterPlants が上面の水草・側面の藻も含めて処理する。
                growSurfacePlants(level, crest, state, growthChance);
                growUnderwaterPlants(level, crest, state);
            }
            return;
        }
    }

    /**
     * 上面に植物を生やす。汚染した草・土の上面だった場合はさらに低い確率で
     * ({@link OvergrownVegetationConfig.Common#treeChance}) 下草の代わりに苗木を植える
     * ({@link #growSapling})。苗木を植えなかった場合は次に「未発達の茂み」を抽選し
     * ({@link OvergrownVegetationConfig.Common#undevelopedBushChance}、既定は苗木より少し低い)、
     * それも外れたら設定された確率で高さ2ブロックの植物になり、残りは高さ1ブロックの下草
     * (立体モデルの丸まったシダを含む。向き・大きさはブロック座標から決まるので設定不要)になる。
     */
    private static void growUp(ServerLevel level, BlockPos pos, RandomSource random, BlockState hostState) {
        if ((isTreeSoil(hostState) || CorruptedSaplingBlock.isCoveredGround(level, pos.below(), hostState))
                && OvergrownVegetationConfig.COMMON.spawnTrees.get()
                && random.nextDouble() < treeChanceAt(level, pos)
                && growSapling(level, pos)) {
            return;
        }

        // 「未発達の茂み」は苗木と違って地面の種類を選ばない(下草の一種なので、
        // 石や砂の上でも他の下草が生えられる場所なら生える)。
        if (OvergrownVegetationConfig.COMMON.spawnUndevelopedBush.get()
                && random.nextDouble() < OvergrownVegetationConfig.COMMON.undevelopedBushChance.get()) {
            growUndevelopedBush(level, pos, random);
            return;
        }

        if (random.nextDouble() < OvergrownVegetationConfig.COMMON.tallGrowthChance.get()) {
            growTallPlant(level, pos, random);
            return;
        }

        BlockState growth = CorruptionGrowth.randomTop(random);
        if (!growth.canSurvive(level, pos)) {
            return;
        }

        level.setBlock(pos, growth, Block.UPDATE_ALL);
    }

    /**
     * 過繁茂している泥({@link ModBlocks#OVERGROWN_MUD})の上面。湿地の地面には薄い苔
     * ({@code swampMudMossShare}、既定 8 割)と胞子キノコ(残り 2 割)しか生えず、
     * 通常どおり {@code treeChance} でまれに苗木が植わる。抽選確率 {@code chance} は面ごとの
     * 通常の下草の確率と同じ(湿地では {@code swampGrowthChanceMultiplier} で下げてある)。
     */
    private static void growMudTop(ServerLevel level, BlockPos pos, RandomSource random, double chance) {
        if (random.nextDouble() >= chance || !level.isLoaded(pos)) {
            return;
        }
        BlockState at = level.getBlockState(pos);
        boolean mossHere = at.is(ModBlocks.OVERGROWN_MOSS_LAYER.get());
        if (!at.isAir() && !mossHere) {
            return;
        }
        if (!mossHere && OvergrownVegetationConfig.COMMON.spawnTrees.get()
                && random.nextDouble() < treeChanceAt(level, pos)
                && growSapling(level, pos)) {
            return;
        }
        if (random.nextDouble() < OvergrownVegetationConfig.COMMON.swampMudMossShare.get()) {
            if (OvergrownVegetationConfig.COMMON.spawnMossLayer.get()) {
                tryGrowMossFace(level, pos, Direction.DOWN);
            }
            return;
        }
        if (mossHere) {
            return;
        }
        BlockState bloom = ModBlocks.OVERGROWN_SPORE_BLOOM.get().defaultBlockState();
        if (!bloom.canSurvive(level, pos)) {
            return;
        }
        level.setBlock(pos, bloom, Block.UPDATE_ALL);
    }

    /** 苗木を植えられるのは汚染された草ブロック・土・砂・泥だけ(石・鉱石などの上には生やさない)。 */
    private static boolean isTreeSoil(BlockState hostState) {
        return hostState.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || hostState.is(ModBlocks.OVERGROWN_DIRT.get())
                || hostState.is(ModBlocks.OVERGROWN_SAND.get()) || hostState.is(ModBlocks.OVERGROWN_MUD.get());
    }

    /**
     * 汚染された苗木を植える。植えた後の成長(木になるまで)はバニラの苗木と同じく
     * ランダムティック任せで、ここでは関与しない({@link com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock} 参照)。
     * 植えられなかったら false(呼び手は下草の抽選へ進む。木が疎らになってから、植えられない列が裸のまま残らないように)。
     */
    private static boolean growSapling(ServerLevel level, BlockPos pos) {
        BlockState sapling = ModBlocks.OVERGROWN_SAPLING.get().defaultBlockState();
        if (!sapling.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, sapling, Block.UPDATE_ALL);
        return true;
    }

    /**
     * 「未発達の茂み」({@link com.yayuy.overgrownvegetation.block.UndevelopedBushBlock})を生やす。
     * 生えたてはたいてい実が無く、まれに最初から実が付いている({@value #UNDEVELOPED_BUSH_BERRY_CHANCE})。
     * 実の付き直しは以降ランダムティック任せ。
     */
    private static void growUndevelopedBush(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState bush = ModBlocks.UNDEVELOPED_BUSH.get().defaultBlockState()
                .setValue(UndevelopedBushBlock.BERRIES, random.nextFloat() < UNDEVELOPED_BUSH_BERRY_CHANCE);
        if (!bush.canSurvive(level, pos)) {
            return;
        }
        level.setBlock(pos, bush, Block.UPDATE_ALL);
    }

    /**
     * 天井へ垂れ植物を生やすと決まった面で、通常の垂れ蔦ではなく「光るツタ」(グロウベリー仕様)の
     * 先端にする確率。地下(洞窟)では {@code glowVinesCaveMultiplier} を掛けて地上よりかなり多くする
     * (上限 1.0)。機能そのものがオフ({@code spawnGlowVines})なら 0。
     */
    private static double glowVinesChanceAt(boolean underground) {
        if (!OvergrownVegetationConfig.COMMON.spawnGlowVines.get()) {
            return 0.0D;
        }
        double chance = OvergrownVegetationConfig.COMMON.glowVinesChance.get();
        if (underground) {
            chance *= OvergrownVegetationConfig.COMMON.glowVinesCaveMultiplier.get();
        }
        return Math.min(1.0D, chance);
    }

    /**
     * 天井の1マスへ「光るツタ」(グロウベリー仕様)の先端を確率で生やす。以降の伸長・実の付き方は
     * バニラのグロウベリーと同じくランダムティック任せ。
     *
     * @return 抽選に当たったか。当たった面は(置ける場所が無くて実際には生えなかった場合も)
     *         光るツタに割り当てられたものとして扱い、呼び出し側は通常の垂れ植物を生やさない。
     */
    private static boolean tryGrowGlowVines(ServerLevel level, BlockPos pos, RandomSource random,
            boolean underground) {
        if (random.nextDouble() >= glowVinesChanceAt(underground)) {
            return false;
        }
        BlockState head = ModBlocks.OVERGROWN_GLOW_VINES.get().defaultBlockState()
                .setValue(OvergrownGlowVines.BERRIES, random.nextFloat() < 0.11F);
        if (head.canSurvive(level, pos)) {
            level.setBlock(pos, head, Block.UPDATE_ALL);
        }
        return true;
    }

    private static void growDown(ServerLevel level, BlockPos pos, RandomSource random, boolean underground) {
        // 一定確率で、通常の垂れ蔦の代わりに「光るツタ」の先端を生やす(地下では地上よりかなり多い)。
        if (tryGrowGlowVines(level, pos, random, underground)) {
            return;
        }

        BlockState growth = CorruptionGrowth.randomHanging(random);
        if (growth.canSurvive(level, pos)) {
            level.setBlock(pos, growth, Block.UPDATE_ALL);
        }
    }

    private static void growSide(ServerLevel level, BlockPos pos, RandomSource random, Direction facing) {
        BlockState growth = CorruptionGrowth.randomSide(random, facing);
        if (growth.canSurvive(level, pos)) {
            level.setBlock(pos, growth, Block.UPDATE_ALL);
        }
        // 壁面に植物が生えたとき、地上より低い確率で茂みの塊も壁から突き出す。
        LeafClumpGrowth.tryGrowOnWall(level, pos, random);
    }

    /**
     * 高さ2ブロックの植物を生やす。下半分を置く位置の真上がロード済みの空気でなければ諦める
     * (バニラの背高草と同じく、下半分と上半分を {@link DoublePlantBlock#placeAt} でまとめて置く)。
     */
    private static void growTallPlant(ServerLevel level, BlockPos lowerPos, RandomSource random) {
        BlockPos upperPos = lowerPos.above();
        if (!level.isLoaded(upperPos) || !level.getBlockState(upperPos).isAir()) {
            return;
        }

        BlockState tall = CorruptionGrowth.randomTall(random);
        if (!tall.canSurvive(level, lowerPos)) {
            return;
        }

        DoublePlantBlock.placeAt(level, tall, lowerPos, Block.UPDATE_ALL);
    }

    // --- 保存 / 読み込み ---

    public void save(CompoundTag tag) {
        CompoundTag data = new CompoundTag();
        data.putBoolean("Finished", finished);
        data.putBoolean("GroundSeeded", groundSeeded);
        data.putInt("Count", corruptedCount);
        data.putInt("BridgesBuilt", bridgesBuilt);
        tag.put("Corruption", data);
    }

    public void load(CompoundTag tag) {
        if (!tag.contains("Corruption")) {
            return;
        }
        CompoundTag data = tag.getCompound("Corruption");
        finished = data.getBoolean("Finished");
        groundSeeded = data.getBoolean("GroundSeeded");
        corruptedCount = data.getInt("Count");
        bridgesBuilt = data.getInt("BridgesBuilt");
        // 触手は保存しない。次の tick で起点の直下から新しい触手を生やし直すことで、
        // 既存の汚染済み領域を踏み台にして本当のフロンティアまで(置換なしで)辿り着く。
    }

    /**
     * 触手が通った経路上の1マス。起点からの相対オフセットと、そこへ着いた時点の進行方向、
     * 直近の露出面のあるマスからの深さ(内部侵食の残り許容量の判定に使う)を持つ。
     */
    private static final class TendrilNode {
        final int x;
        final int y;
        final int z;
        final Direction facing;
        final int interiorDepth;

        TendrilNode(int x, int y, int z, Direction facing, int interiorDepth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.facing = facing;
            this.interiorDepth = interiorDepth;
        }
    }

    /**
     * 1本の触手(汚染の筋)の状態。通ってきた経路をスタックとして丸ごと保持しており、
     * 先端({@link #head()})が行き止まりになった場合は1マスずつ後戻りして
     * (経路そのものは何も置き換えないまま)別の方向を試せる。後戻りできる回数は
     * {@code backtrackBudget} で個体ごとに上限があり、使い切って経路も起点まで
     * 戻りきったら本当に行き止まり(消滅)となる。
     *
     * <p>{@code root} が true の個体は「根」— コア(またはコア無しなら起点)から最初に
     * 放射状に伸ばした少数精鋭の触手で、他の(枝分かれで生まれた)触手が1本でも存在する限り
     * 毎tickの前進権を独占する({@link #tick} のピック処理参照)。これにより見た目としても
     * 「まず根が放射状に far away まで伸びきり、それから根沿いに横向きの枝が湧いて
     * 残りの空間を埋めていく」という順序になる。根自身も枝分かれはする(その枝は
     * {@code root=false})が、他の根がまだ生きている間はその枝は前進権を得られない。
     */
    private static final class Tendril {
        final List<TendrilNode> path = new ArrayList<>();
        int backtrackBudget;
        final boolean root;

        Tendril(int x, int y, int z, Direction facing, int interiorDepth, int backtrackBudget, boolean root) {
            path.add(new TendrilNode(x, y, z, facing, interiorDepth));
            this.backtrackBudget = backtrackBudget;
            this.root = root;
        }

        TendrilNode head() {
            return path.get(path.size() - 1);
        }
    }
}
