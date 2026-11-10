package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>ネザーへの適応</b>(2026-11-09 追記 1。ユーザー決定: 植物が「適応した」のなら、最初はオーバーワールドと同じ植物のはず)。
 *
 * <ul>
 *     <li>ネザーで最初に汚染されてから {@link #ADAPT_TICKS}(10 分。ユーザー決定)は、オーバーワールドと同じ植生
 *     (段階 1 より前の作り: 過繁茂したネザーラック・覆い・オーバーワールドの下草と洞窟の植物)。時計はワールドに 1 つ、ネザーの
 *     ゲーム時刻で数える(サーバーが止まっている間は進まない)。</li>
 *     <li>10 分たつと、新しく汚染された所はすぐ熱水の森になる({@link HeatForestGround#applies})。</li>
 *     <li>前からある汚染(オーバーワールドの植物と、前の作りの地面)は、汚染されたネザーのチャンク
 *     (繁茂・過繁茂のバイオーム)で、プレイヤーのまわりから少しずつ熱水の森の物へ変わる({@link #convertAt})。
 *     対のある植物は対へ、無いものは消して熱水の森の植物を生やし直す。オーバーワールドの植物の {@link #KEEP_PERCENT}% は残す。
 *     ポータルのそばのオーバーワールドの植物は残す(仕様の控えのとおり)。</li>
 * </ul>
 * 置き換わりの速さ(仮決め): 1 チャンクに 1 tick あたり平均 {@link #SAMPLES_PER_VISIT} / {@link #VISIT_INTERVAL} マスを見る。ネザーの 1 チャンク
 * (高さ 128 で約 3 万 2 千マス)を 1 日(24000 tick)でおよそ 8 割見る見込み。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class HeatForestAdaptation extends SavedData {

    /** 最初の汚染から適応するまで(ユーザー決定: 10 分 = 12000 tick)。 */
    public static final long ADAPT_TICKS = 12_000L;
    /** オーバーワールドの植物を残す割合(仮決め: 1〜2 割)。 */
    public static final int KEEP_PERCENT = 15;
    /** 置き換えを見る間隔・1 回にチャンクごとに見るマス・プレイヤーのまわりの半径(仮決め)。 */
    static final int VISIT_INTERVAL = 4, SAMPLES_PER_VISIT = 8, RADIUS_CHUNKS = 6;

    private static final String ID = "overgrown_vegetation_heat_forest_adaptation";
    public static final SavedData.Factory<HeatForestAdaptation> FACTORY =
            new SavedData.Factory<>(HeatForestAdaptation::new, (tag, registries) -> load(tag), null);

    /** 「まだ汚染されていない」の印。ゲーム時刻は小さいワールドで負にもなりうる(GameTest の forceAdapted)ので、-1 などの負の値は使わない(11-09 追記 2)。 */
    public static final long NONE = Long.MIN_VALUE;
    /** ネザーで最初に汚染されたゲーム時刻(まだなら {@link #NONE})。 */
    private long firstCorruption = NONE;

    private HeatForestAdaptation() {
    }

    private static HeatForestAdaptation load(CompoundTag tag) {
        HeatForestAdaptation d = new HeatForestAdaptation();
        d.firstCorruption = tag.getBoolean("started") ? tag.getLong("first") : NONE;
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("started", firstCorruption != NONE);
        if (firstCorruption != NONE) {
            tag.putLong("first", firstCorruption);
        }
        return tag;
    }

    public static HeatForestAdaptation get(ServerLevel nether) {
        return nether.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // 汚染のたびに SavedData の表を引かないよう、ワールドの値を写して持つ(サーバーが止まったら捨てる)
    private static long cachedFirst = NONE;
    private static boolean cacheLoaded;

    private static long first(ServerLevel nether) {
        if (!cacheLoaded) {
            cachedFirst = get(nether).firstCorruption;
            cacheLoaded = true;
        }
        return cachedFirst;
    }

    /** ネザーで汚染が起きた(時計がまだなら動かし始める)。 */
    public static void noteCorruption(ServerLevel nether) {
        if (first(nether) != NONE) {
            return;
        }
        setFirst(nether, nether.getGameTime());
    }

    /** 植物がネザーに適応したか(最初の汚染から 10 分たったか)。 */
    public static boolean adapted(ServerLevel nether) {
        long f = first(nether);
        return f != NONE && nether.getGameTime() - f >= ADAPT_TICKS;
    }

    /** 時計を決めた時刻にする(GameTest・確かめ用。{@link #NONE} で「まだ汚染されていない」)。 */
    public static void setFirst(ServerLevel nether, long gameTime) {
        HeatForestAdaptation d = get(nether);
        d.firstCorruption = gameTime;
        d.setDirty();
        cachedFirst = gameTime;
        cacheLoaded = true;
    }

    /** GameTest 用: もう適応したことにする。 */
    public static void forceAdapted(ServerLevel nether) {
        setFirst(nether, nether.getGameTime() - ADAPT_TICKS - 1);
    }

    /** GameTest 用: 汚染は始まったが、まだ適応していないことにする。 */
    public static void forceEarly(ServerLevel nether) {
        setFirst(nether, nether.getGameTime());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cachedFirst = NONE;
        cacheLoaded = false;
        lastSent = null;
        BIOME_CACHE.clear();
    }

    // ------------------------------------------------------------------ 前からある汚染の置き換え

    /** 前の作りの地面(ネザーの中で見つけたら熱水の森の地面へ)。 */
    private static Set<Block> legacyGround() {
        if (LEGACY_GROUND == null) {
            LEGACY_GROUND = Set.of(ModBlocks.OVERGROWN_NETHERRACK.get(), ModBlocks.OVERGROWN_SOUL_SAND.get(),
                    ModBlocks.OVERGROWN_SOUL_SOIL.get(), ModBlocks.OVERGROWN_BLOCK.get());
        }
        return LEGACY_GROUND;
    }

    /** オーバーワールドの植物と、対になる熱水の森の植物(null = 対が無い → 消して生やし直す)。 */
    private static Map<Block, DeferredBlock<Block>> pairs() {
        if (PAIRS == null) {
            Map<Block, DeferredBlock<Block>> m = new HashMap<>();
            m.put(ModBlocks.OVERGROWN_ZENMAI.get(), ModBlocks.HEAT_ZENMAI);
            m.put(ModBlocks.OVERGROWN_CURLED_FERN.get(), ModBlocks.HEAT_CURLED_FERN);
            m.put(ModBlocks.OVERGROWN_SPORE_BLOOM.get(), ModBlocks.HEAT_SPORE_CAP);
            m.put(ModBlocks.CLUBMOSS.get(), ModBlocks.HEAT_CLUBMOSS);
            m.put(ModBlocks.SELAGINELLA.get(), ModBlocks.HEAT_CLUBMOSS);
            m.put(ModBlocks.LYCOPSID_CARPET.get(), ModBlocks.HEAT_ROOT_NET);
            m.put(ModBlocks.PEAT_LITTER.get(), ModBlocks.HEAT_STEM_LITTER);
            m.put(ModBlocks.PEAT_SPROUTS.get(), ModBlocks.HEAT_FUR_FLOWER);
            m.put(ModBlocks.UNDEVELOPED_BUSH.get(), ModBlocks.HEAT_FRUIT_BUSH);
            m.put(ModBlocks.OVERGROWN_GROWTH.get(), ModBlocks.HEAT_FUR_TUFT);
            m.put(ModBlocks.BEARD_LICHEN.get(), ModBlocks.HEAT_BEARD_LICHEN);
            m.put(ModBlocks.OVERGROWN_DRIP_MOSS.get(), ModBlocks.HEAT_HANGING_MOSS_SHORT);
            m.put(ModBlocks.OVERGROWN_WEEPING_TENDRIL.get(), ModBlocks.HEAT_HANGING_FIDDLEHEAD);
            m.put(ModBlocks.OVERGROWN_SPORE_TENDRIL.get(), ModBlocks.HEAT_BEARD_LICHEN);
            m.put(ModBlocks.WALL_LYCOPSID.get(), ModBlocks.HEAT_WALL_SCALE);
            m.put(ModBlocks.WALL_FERN.get(), ModBlocks.HEAT_WALL_FERN);
            m.put(ModBlocks.WALL_FERN_LARGE.get(), ModBlocks.HEAT_WALL_FERN);
            m.put(ModBlocks.OVERGROWN_SAPLING.get(), ModBlocks.HEAT_TREE_SPROUT); // 11-10: 適応後のオーバーワールド型の苗木は普通の木の芽へ(仮)
            for (DeferredBlock<Block> b : java.util.List.of(ModBlocks.OVERGROWN_TALL_FERN, ModBlocks.OVERGROWN_SPORE_REED, ModBlocks.LYCOPSID_UPRIGHT,
                    ModBlocks.HORSETAIL, ModBlocks.OVERGROWN_MOSS_LAYER, ModBlocks.HANGING_MOSS, ModBlocks.WALL_SPORE_SACS,
                    ModBlocks.WALL_BRACKET_FUNGUS, ModBlocks.AERIAL_ROOT, ModBlocks.HANGING_SPORE_SAC, ModBlocks.OVERGROWN_GLOW_VINES,
                    ModBlocks.OVERGROWN_GLOW_VINES_PLANT, ModBlocks.CEILING_ROOT_NET, ModBlocks.GLOW_TASSEL, ModBlocks.GLOW_TASSEL_TIP,
                    ModBlocks.LEY_ROOT, ModBlocks.FOXFIRE, ModBlocks.PALE_CAVE_FERN, ModBlocks.PALE_CAVE_CAPS, ModBlocks.PALE_CAVE_MOSS,
                    ModBlocks.CAVE_HORSETAIL, ModBlocks.CAVE_MYCELIUM, ModBlocks.DANGLING_ROOTS, ModBlocks.PROTOTAXITES_STEM,
                    ModBlocks.PROTOTAXITES_KNOB, ModBlocks.PROTOTAXITES_CAP, ModBlocks.OVERGROWN_BUSH)) {
                m.put(b.get(), null);
            }
            PAIRS = m;
        }
        return PAIRS;
    }

    private static Set<Block> LEGACY_GROUND;
    private static Map<Block, DeferredBlock<Block>> PAIRS;

    /**
     * 1 マスを見て、前からある汚染なら熱水の森の物へ置き換える。置き換えたら true。
     * 呼ぶのは適応した後のネザーだけ(呼び手が確かめる)。
     */
    public static boolean convertAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        // 地面: 前の作りの過繁茂したネザーラックなど、覆われた地形・鉱石
        BlockState ground = null;
        if (legacyGround().contains(block)) {
            ground = HeatForestGround.groundFor(level, pos);
        } else if ((HeatForestGround.isTerrain(state) || HeatForestGround.oreFor(state) != null) && OvergrownLayerData.isCovered(level, pos)) {
            BlockState ore = HeatForestGround.oreFor(state);
            ground = ore != null ? ore : HeatForestGround.groundFor(level, pos);
        }
        if (ground != null) {
            level.setBlock(pos, ground, Block.UPDATE_ALL);
            // 11-10 木の段階: 置き換えでできた床・天井でも、格子の印の所には大きな植物を置く(前は新しく汚染された所だけだった)
            if (!HeatForestFlora.nearPortal(level, pos)) {
                BlockPos up = pos.above(), down = pos.below();
                if (HeatForestTrees.free(level, up)) {
                    HeatForestTrees.tryUp(level, pos, up, ground, level.random);
                } else if (level.isEmptyBlock(down) && HeatForestGround.openBelow(level, pos, HeatForestGround.HIGH_CEILING) >= HeatForestGround.HIGH_CEILING) {
                    HeatForestTrees.tryDown(level, down, level.random);
                }
            }
            return true;
        }
        // オーバーワールドの木(11-10 木の段階、決: 普通の木 B へ徐々に・15% は残す)。ポータルのそばは残す
        if (HeatForestTrees.isOverworldTrunk(state)) {
            return !HeatForestFlora.nearPortal(level, pos) && HeatForestTrees.convertOverworldTree(level, pos, KEEP_PERCENT);
        }
        // オーバーワールドの植物
        Map<Block, DeferredBlock<Block>> pairs = pairs();
        if (!pairs.containsKey(block)) {
            return false;
        }
        if (Math.floorMod(Mth.getSeed(pos) >> 11, 100) < KEEP_PERCENT || HeatForestFlora.nearPortal(level, pos)) {
            return false; // ところどころ残す・ポータルのそばは残す
        }
        CorruptionDropSuppressor.enter();
        try {
            DeferredBlock<Block> pair = pairs.get(block);
            if (pair != null) {
                BlockState next = pair.get().defaultBlockState();
                if (state.hasProperty(HorizontalDirectionalBlock.FACING) && next.hasProperty(HorizontalDirectionalBlock.FACING)) {
                    next = next.setValue(HorizontalDirectionalBlock.FACING, state.getValue(HorizontalDirectionalBlock.FACING));
                }
                if (next.canSurvive(level, pos)) {
                    level.setBlock(pos, next, Block.UPDATE_ALL);
                    return true;
                }
            }
            // 対が無い(・置けない): 消して、下(無ければ上)のブロックから熱水の森の植物を生やし直す
            boolean onFloor = !(block instanceof MultifaceBlock) || state.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN));
            if (block instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                pos = pos.below();
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos below = pos.below(), above = pos.above();
            BlockState belowState = level.getBlockState(below);
            if (onFloor && belowState.isFaceSturdy(level, below, Direction.UP)) {
                HeatForestFlora.grow(level, below, belowState, 1.0D);
            } else {
                BlockState aboveState = level.getBlockState(above);
                if (aboveState.isFaceSturdy(level, above, Direction.DOWN)) {
                    HeatForestFlora.grow(level, above, aboveState, 1.0D);
                }
            }
            return true;
        } finally {
            CorruptionDropSuppressor.exit();
        }
    }

    // チャンクが汚染されたネザー(繁茂・過繁茂のバイオーム)か。チャンクごとに 30 秒ためる
    private static final Long2LongOpenHashMap BIOME_CACHE = new Long2LongOpenHashMap();
    private static final long BIOME_TTL = 600L;

    private static boolean corruptedChunk(ServerLevel level, ChunkPos pos, long now) {
        long key = pos.toLong();
        long v = BIOME_CACHE.getOrDefault(key, Long.MIN_VALUE);
        if (v != Long.MIN_VALUE && now - (v >> 1) < BIOME_TTL) {
            return (v & 1L) != 0;
        }
        boolean yes = ModBiomes.isOvergrownLike(level.getBiome(pos.getMiddleBlockPosition(NetherSurface.BIOME_SAMPLE_Y)));
        if (BIOME_CACHE.size() > 16384) {
            BIOME_CACHE.clear();
        }
        BIOME_CACHE.put(key, (now << 1) | (yes ? 1L : 0L));
        return yes;
    }

    // ------------------------------------------------------------------ 画面へ送る(覆いの絵を 10 分の前後で分ける。11-10 ユーザー決定)

    /** 最後に全員へ送った「適応したか」(まだ送っていなければ null)。 */
    private static @Nullable Boolean lastSent;

    /** 適応したかが変わったら、全員へ送る(1 秒ごとに見る)。 */
    private static void broadcastIfChanged(ServerLevel nether) {
        boolean now = adapted(nether);
        if (lastSent == null || lastSent != now) {
            lastSent = now;
            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(new com.yayuy.overgrownvegetation.network.HeatForestClockPayload(now));
        }
    }

    private static void sendTo(ServerPlayer player) {
        ServerLevel nether = player.server.getLevel(Level.NETHER);
        if (nether != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new com.yayuy.overgrownvegetation.network.HeatForestClockPayload(adapted(nether)));
        }
    }

    @SubscribeEvent
    public static void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.NETHER) {
            return;
        }
        long now = level.getGameTime();
        if (now % 20L == 7L) {
            broadcastIfChanged(level);
        }
        if (now % VISIT_INTERVAL != 3L || CorruptionSwitch.isOff() || !adapted(level)) {
            return;
        }
        RandomSource random = level.random;
        Set<Long> seen = new HashSet<>();
        int top = NetherSurface.topY(level), bottom = NetherSurface.bottomY(level);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (ServerPlayer player : level.players()) {
            ChunkPos center = player.chunkPosition();
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                    ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                    if (!seen.add(cp.toLong())) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
                    if (chunk == null || !corruptedChunk(level, cp, now)) continue;
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int k = 0; k < SAMPLES_PER_VISIT; k++) {
                        int y = bottom + random.nextInt(Math.max(1, top - bottom));
                        int index = chunk.getSectionIndex(y);
                        if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) continue;
                        at.set(cp.getMinBlockX() + random.nextInt(16), y, cp.getMinBlockZ() + random.nextInt(16));
                        convertAt(level, at);
                    }
                }
            }
        }
    }

    @Nullable
    static Boolean debugAdapted(Level level) {
        return level instanceof ServerLevel s ? adapted(s) : null;
    }
}
