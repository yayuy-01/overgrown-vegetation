package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.TwistingVinesPlantBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汚染で支えを失った植物を、<b>その場で音・粒子・ドロップ無しで消す</b>(2026-10-06、ユーザー指定)。
 *
 * <p>汚染の置き換え({@link CorruptionRegistry#placeCorrupted})は形状更新を打ち切る({@code UPDATE_KNOWN_SHAPE})ので、
 * 過繁茂した地面の上のバニラの草花・竹・サトウキビ、汚染された原木のココアなどは、生き残れないままその場に残っていた。
 * それが後で別の処理の更新(隣への植物の設置・砂の腐化・予定 tick など)を受けた瞬間に壊れ、1 件ごとに
 * {@code levelEvent 2001}(破壊音 + 破片の粒子)がクライアントへ飛び、{@link CorruptionDropSuppressor} の範囲外なのでアイテムも落ちていた。
 * 計測(2026-10-06): ワールドに入った瞬間・飛行中に 1 秒に数百件(音の上限 247 を超えて「Maximum sound pool size」がログに並ぶ。
 * 破片の粒子は 1 件で最大 64 個)。10/5 のセーブに落ちていたアイテム 876 個の大半もこれ(小麦の種・サトウキビ・苔のカーペット・昆布 …)。
 *
 * <p>置き換えた(または消した)マスの 6 方向の隣から、<b>植物</b>({@link #isPlantLike})で<b>もう生き残れない</b>
 * ({@code canSurvive} が false)ものを探して消し、消したマスの隣も同じように見る —— 竹・サトウキビの茎、ツタの連なり、
 * 二段植物の上半分まで追う。水の中の植物(昆布・海草・水浸しのもの)は水に戻す。
 * 生き残れる植物には触らない(汚染しても残る植物の見た目は変わらない)。
 *
 * <ul>
 *     <li>消すときは近隣更新も形状更新もしない({@link #FLAGS}、竹の茎を消す {@code clearColumnPlant} と同じ)。
 *     連鎖はこちらで追う。近隣更新を出すと、縁では隣のチャンクを読みにいってサーバースレッドが止まりうる。</li>
 *     <li>読み込みが済んでいないチャンクにかかるマスは見ない({@link LoadedChunks})。そこに残ったものは従来どおり後で壊れる。</li>
 *     <li>ブロックエンティティを持つものは消さない(蔦のアンカーなど、外すときの後始末を持つ)。</li>
 *     <li>1 回に消すのは {@link #MAX_CLEARED} マスまで。</li>
 *     <li>サーバーのワールドだけ。生成中の領域({@code WorldGenRegion})では何もしない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class StrandedPlants {

    /** 1 回の呼び出しで消すマスの上限(巨大なツタの網などで跳ねないため)。仮決め 2026-10-06。 */
    private static final int MAX_CLEARED = 256;
    /** 音・粒子・ドロップ・近隣更新・形状更新なし。クライアントへの再送だけ。 */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final Direction[] DIRECTIONS = Direction.values();
    /** ブロックごとの「植物か」の答え(置き換え可能のタグを含む。タグもブロックごとなので状態によらない)。 */
    private static final Map<Block, Boolean> PLANT_LIKE = new ConcurrentHashMap<>();

    /**
     * {@code origin} の隣から、支えを失った植物を静かに消す。消したマスの数を返す。
     * {@code origin} 自身は見ない(置いたばかりの汚染ブロック、または消したばかりのマス)。
     */
    public static int clearAround(LevelAccessor level, BlockPos origin) {
        if (!(level instanceof ServerLevel server) || nothingToClear(server, origin)) {
            return 0;
        }
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(origin.immutable());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        LevelChunk chunk = null; // 直前に読んだチャンク(隣はほとんど同じチャンクなので、毎回引き直さない)
        int cleared = 0;
        while (!frontier.isEmpty() && cleared < MAX_CLEARED) {
            BlockPos from = frontier.poll();
            for (Direction direction : DIRECTIONS) {
                at.setWithOffset(from, direction);
                if (at.getY() < server.getMinBuildHeight() || at.getY() >= server.getMaxBuildHeight()) {
                    continue;
                }
                int cx = at.getX() >> 4;
                int cz = at.getZ() >> 4;
                if (chunk == null || chunk.getPos().x != cx || chunk.getPos().z != cz) {
                    chunk = server.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue; // 読み込まれていない(読み込みの途中も含む)
                    }
                }
                // 植物でないものは、横隣のチャンクの読み込みを確かめる前に落とす(2026-10-06、③。結果は同じ —— 以前は先に確かめていた)。
                BlockState state = chunk.getBlockState(at);
                if (state.isAir() || state.hasBlockEntity() || !isPlantLike(state)
                        || !readable(server, at)) {
                    continue;
                }
                // 汚染されたマスから生える洞窟の植物は、過繁茂した石の上・下でも生き残れるが消す(2026-10-16 ユーザー指定。
                // 当たったマスは CorruptionRegistry#corruptsToAir で消える)。連なる段はここで支えを失って続けて消える。
                boolean growsFromCorruption = from.equals(origin) && growsFrom(state, direction);
                if (!growsFromCorruption && state.canSurvive(server, at)) {
                    continue;
                }
                server.setBlock(at, state.getFluidState().createLegacyBlock(), FLAGS);
                frontier.add(at.immutable());
                if (++cleared >= MAX_CLEARED) {
                    break;
                }
            }
        }
        return cleared;
    }

    /**
     * 速い道(2026-10-06、③): {@code origin} の 6 方向の隣が全部 {@code origin} と同じ区画(16×16×16 のセクション)の中にあり、
     * その区画の種類表(パレット)に「消えうる植物」が 1 つも無ければ、隣を 1 マスずつ読まなくても何も消えないと分かる。
     * 地下の全層置換では置き換えのほとんどがこれで終わる(計測で置き換えの時間の 4 割が隣の読み直しだった)。
     */
    private static boolean nothingToClear(ServerLevel level, BlockPos origin) {
        int lx = origin.getX() & 15;
        int ly = origin.getY() & 15;
        int lz = origin.getZ() & 15;
        if (lx == 0 || lx == 15 || ly == 0 || ly == 15 || lz == 0 || lz == 15) {
            return false; // 隣が別の区画にかかる
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(origin.getX() >> 4, origin.getZ() >> 4);
        if (chunk == null) {
            return false;
        }
        int index = chunk.getSectionIndex(origin.getY());
        if (index < 0 || index >= chunk.getSectionsCount()) {
            return false;
        }
        return !chunk.getSection(index).maybeHas(StrandedPlants::mayBeStranded);
    }

    /** {@link #clearUnanchored} が 1 つの塊として見るマスの上限。超えたら触らない(地面につながった下草の広がりなど)。仮決め 2026-10-16。 */
    private static final int MAX_CLUSTER = 512;

    /**
     * 消した木の跡(2026-10-16、ユーザー報告「木を消す処理の後に汚染された下草などが空中に浮いてしまう」)。
     *
     * <p>{@link #clearAround} は {@code canSurvive} が偽の植物しか消さないが、汚染された下草({@code overgrown_growth})は
     * 生き残りの判定を持たない(付けるとバニラの形状更新で前から浮いているものが一斉に壊れる —— 10-06 の症状)。茂みの塊も同じ。
     * そこで {@code removed}(消したマス)の隣から、つながった植物の塊ごとに「消したマス以外の固いものに 1 つでも触れているか」を見て、
     * どこにも触れていない塊だけを静かに消す(水に浸かったものは水に戻す)。
     */
    public static int clearUnanchored(ServerLevel level, it.unimi.dsi.fastutil.longs.LongSet removed) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        int cleared = 0;
        it.unimi.dsi.fastutil.longs.LongIterator it = removed.iterator();
        while (it.hasNext()) {
            long from = it.nextLong();
            for (Direction direction : DIRECTIONS) {
                at.set(from).move(direction);
                long start = at.asLong();
                if (removed.contains(start) || seen.contains(start) || !looseDecoration(level, at)) {
                    continue;
                }
                // 1 つの塊を集めながら、外の固いものに触れているかを見る。
                it.unimi.dsi.fastutil.longs.LongArrayList cluster = new it.unimi.dsi.fastutil.longs.LongArrayList();
                ArrayDeque<Long> frontier = new ArrayDeque<>();
                frontier.add(start);
                cluster.add(start);
                it.unimi.dsi.fastutil.longs.LongOpenHashSet members = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                members.add(start);
                boolean anchored = false;
                while (!frontier.isEmpty() && !anchored) {
                    long cell = frontier.poll();
                    for (Direction d : DIRECTIONS) {
                        side.set(cell).move(d);
                        long n = side.asLong();
                        if (removed.contains(n)) {
                            continue;
                        }
                        if (!readable(level, side)) {
                            anchored = true; // 読めない所には触れない(つながっているかもしれない)
                            break;
                        }
                        BlockState state = level.getBlockState(side);
                        if (state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock || state.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                            continue; // 空気・ただの水・見えない光(残る蔦の垂れ蔦の明かり)
                        }
                        if (state.hasBlockEntity() || !isPlantLike(state)) {
                            anchored = true;
                            break;
                        }
                        if (members.add(n)) {
                            cluster.add(n);
                            frontier.add(n);
                            if (cluster.size() > MAX_CLUSTER) {
                                anchored = true;
                                break;
                            }
                        }
                    }
                }
                // 決まった塊は二度と見ない(途中で触れているのが分かった塊の残りは、別の入口からもう一度たどれば同じ答えになる)。
                seen.addAll(members);
                if (anchored) {
                    continue;
                }
                for (int i = 0; i < cluster.size(); i++) {
                    at.set(cluster.getLong(i));
                    level.setBlock(at, level.getBlockState(at).getFluidState().createLegacyBlock(), FLAGS);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /** 塊の始まりにしてよいマス: 読める・植物で、ブロックエンティティを持たない。 */
    private static boolean looseDecoration(ServerLevel level, BlockPos pos) {
        if (!readable(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.is(net.minecraft.world.level.block.Blocks.LIGHT) && !state.hasBlockEntity() && isPlantLike(state);
    }

    /**
     * 汚染されたマスから {@code direction} の向きに生えている、汚染で消す洞窟の植物か(2026-10-16 ユーザー指定)。
     * 天井から垂れるもの(グロウベリー・垂れ根・胞子の花・しだれツタ)と、床から伸びるもの(ねじれツタ・大きなドリップリーフ)。
     */
    private static boolean growsFrom(BlockState state, Direction direction) {
        Block block = state.getBlock();
        if (direction == Direction.DOWN) {
            return block instanceof CaveVines || block instanceof HangingRootsBlock || block instanceof SporeBlossomBlock
                    || block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock;
        }
        return direction == Direction.UP
                && (block instanceof TwistingVinesBlock || block instanceof TwistingVinesPlantBlock
                || block instanceof BigDripleafBlock || block instanceof BigDripleafStemBlock);
    }

    /** {@link #clearAround} が消しうるブロックか(生き残れるかどうかは見ない)。 */
    private static boolean mayBeStranded(BlockState state) {
        return !state.isAir() && !state.hasBlockEntity() && isPlantLike(state);
    }

    /** 植物か: バニラの植物の種類・置き換え可能(草・ツタなど)・この MOD の植物(当たり判定が満杯でない、BE を持たない)。 */
    static boolean isPlantLike(BlockState state) {
        Boolean known = PLANT_LIKE.get(state.getBlock());
        return known != null ? known : PLANT_LIKE.computeIfAbsent(state.getBlock(), StrandedPlants::computePlantLike);
    }

    /** タグを結び直したら(データパックの再読み込み・ワールドを開いたとき)答えを捨てる(置き換え可能のタグを答えに含めているため)。 */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        PLANT_LIKE.clear();
    }

    private static boolean computePlantLike(Block block) {
        if (block.defaultBlockState().is(BlockTags.REPLACEABLE)) {
            return true;
        }
        if (block instanceof BushBlock || block instanceof SugarCaneBlock || block instanceof CactusBlock
                || block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock || block instanceof GrowingPlantBlock
                || block instanceof VineBlock || block instanceof MultifaceBlock || block instanceof CocoaBlock
                || block instanceof CarpetBlock || block instanceof SporeBlossomBlock || block instanceof HangingRootsBlock
                || block instanceof BigDripleafBlock || block instanceof BigDripleafStemBlock
                || block instanceof ChorusPlantBlock || block instanceof ChorusFlowerBlock) {
            return true;
        }
        if (!OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        return !state.hasBlockEntity() && !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /**
     * そのマスと、{@code canSurvive} が読みうる横隣のチャンクが読み込み済みか。チャンクの縁のマスだけ隣のチャンクも見る
     * (読み込みの途中のチャンクを読むと、サーバースレッドが読み込みを待って止まる)。
     */
    private static boolean readable(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (!LoadedChunks.loaded(level, cx, cz)) {
            return false;
        }
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        return (lx != 0 || LoadedChunks.loaded(level, cx - 1, cz))
                && (lx != 15 || LoadedChunks.loaded(level, cx + 1, cz))
                && (lz != 0 || LoadedChunks.loaded(level, cx, cz - 1))
                && (lz != 15 || LoadedChunks.loaded(level, cx, cz + 1));
    }

    private StrandedPlants() {
    }
}
