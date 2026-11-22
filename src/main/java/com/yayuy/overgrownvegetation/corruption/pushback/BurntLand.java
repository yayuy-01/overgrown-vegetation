package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;

/**
 * <b>焼けた荒地</b>(10-08 のユーザー指定を 11-18 夜に胞子焼きの筒へ引き継いだ。前の版は parked/purify/BurntLand)。
 *
 * <ul>
 *   <li><b>なる</b>: 筒の火で地面の汚染が 3 割【仮】を切って台帳から外れたチャンク({@link LedgerShrink} の {@code Cause.FIRE})。
 *   昔の「半分を下回ったら」は 11-18 夜の「3 割で外す」へまとめた。バイオームを焼けた荒地に塗る。</li>
 *   <li><b>焼けている間 = 防火帯</b>(ユーザー指定 10-08): 胞子の発芽を受け取らない・ブロックの汚染の関門が断る・萌芽体が湧かない。</li>
 *   <li><b>戻る</b>({@code fire.burntDays}【仮 1 日】。11-18 夜 ユーザー「散布と同じように 1 日」、10-08 の 3 日から): 枯れた土と同じく、台帳に載っていれば森のまま・外れていれば世界生成のときのバイオームへ(残った汚染は消さない)。
 *   読み込まれていなければ台帳から消して「戻す待ち」へ(この時点で普通の土地 = また飲まれうる)、読み込まれたときに戻す。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BurntLand extends SavedData {

    public static final String ID = "overgrown_vegetation_burnt_land";
    /** 元のバイオームに戻るまで(汚染の暦)。11-18 夜 ユーザー「散布と同じように 1 日」(10-08 の 3 日から。数は仮 = {@code fire.burntDays})。 */
    public static long revertTicks() {
        return (long) (com.yayuy.overgrownvegetation.data.DesignData.pushBack().fire.burntDays * 24000L);
    }
    private static final int CHECK_INTERVAL = 200;

    public static final SavedData.Factory<BurntLand> FACTORY = new SavedData.Factory<>(BurntLand::new, (tag, r) -> load(tag), null);

    private final Long2LongOpenHashMap since = new Long2LongOpenHashMap();
    private final LongOpenHashSet pendingRevert = new LongOpenHashSet();

    @Nullable
    private static BurntLand cached;
    @Nullable
    private static MinecraftServer cachedServer;

    static {
        LedgerShrink.addListener((level, chunk, cause) -> cause == LedgerShrink.Cause.FIRE && flip(level, chunk));
    }

    /** 登録の口(MOD の構築子から。static の初期化で台帳から外す知らせを受け取る)。 */
    public static void init() {
    }

    public static BurntLand get(MinecraftServer server) {
        BurntLand d = cached;
        if (d != null && cachedServer == server) return d;
        d = server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
        cached = d;
        cachedServer = server;
        return d;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cached = null;
        cachedServer = null;
    }

    private static BurntLand load(CompoundTag tag) {
        BurntLand d = new BurntLand();
        long[] k = tag.getLongArray("chunks"), t = tag.getLongArray("since");
        for (int i = 0; i < Math.min(k.length, t.length); i++) d.since.put(k[i], t[i]);
        for (long l : tag.getLongArray("pendingRevert")) d.pendingRevert.add(l);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] k = new long[since.size()], t = new long[since.size()];
        int i = 0;
        for (Long2LongMap.Entry e : since.long2LongEntrySet()) {
            k[i] = e.getLongKey();
            t[i++] = e.getLongValue();
        }
        tag.putLongArray("chunks", k);
        tag.putLongArray("since", t);
        tag.putLongArray("pendingRevert", pendingRevert.toLongArray());
        return tag;
    }

    /** そのマスのチャンクが焼けた荒地か(汚染の関門から毎回。軽く)。 */
    public static boolean isBurnt(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && isBurntKey(level.getServer(), ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public static boolean isBurntKey(MinecraftServer server, long key) {
        BurntLand d = get(server);
        return !d.since.isEmpty() && d.since.containsKey(key);
    }

    /** 焼けた荒地にする(条件は見ない)。 */
    public static boolean flip(ServerLevel level, LevelChunk chunk) {
        if (level.dimension() != Level.OVERWORLD) return false;
        BurntLand d = get(level.getServer());
        long key = chunk.getPos().toLong();
        OvergrownBiomeConverter.paintWhole(level, chunk, ModBiomes.BURNT_WASTELAND);
        d.since.put(key, PhaseData.get(level).clock(level.getServer()));
        d.pendingRevert.remove(key);
        d.setDirty();
        OvergrownVegetation.LOGGER.info("焼けた荒地: チャンク {}({} 日で元のバイオームへ)", chunk.getPos(), revertTicks() / 24000.0D);
        return true;
    }

    /** 元へ戻す(読み込まれていること)。 */
    public static void revert(ServerLevel level, LevelChunk chunk) {
        BurntLand d = get(level.getServer());
        long key = chunk.getPos().toLong();
        // 11-18 夜 ユーザー: 戻り方は枯らし薬の枯れた土と同じ —— 台帳に載っていれば森のまま、外れていれば世界生成のときのバイオーム(残った汚染は消さない)
        if (level.getBiome(chunk.getPos().getMiddleBlockPosition(level.getSeaLevel())).is(ModBiomes.BURNT_WASTELAND)
                && !PhaseData.get(level).isOvergrownChunk(chunk.getPos())) {
            OvergrownBiomeConverter.restoreOriginal(level, chunk);
        }
        boolean had = d.since.containsKey(key);
        d.since.remove(key);
        if (had | d.pendingRevert.remove(key)) d.setDirty();
    }


    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % CHECK_INTERVAL != 0) return;
        BurntLand d = get(level.getServer());
        if (d.since.isEmpty()) return;
        long clock = PhaseData.get(level).clock(level.getServer());
        int reverted = 0;
        for (ObjectIterator<Long2LongMap.Entry> it = d.since.long2LongEntrySet().fastIterator(); it.hasNext() && reverted < 4; ) {
            Long2LongMap.Entry e = it.next();
            if (clock - e.getLongValue() < revertTicks()) continue;
            long key = e.getLongKey();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            it.remove();
            d.setDirty();
            if (chunk != null) {
                d.pendingRevert.add(key); // revert が消す
                revert(level, chunk);
                reverted++;
            } else {
                d.pendingRevert.add(key);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        BurntLand d = get(level.getServer());
        if (d.pendingRevert.isEmpty() || !d.pendingRevert.contains(event.getChunk().getPos().toLong())) return;
        if (event.getChunk() instanceof LevelChunk chunk) {
            level.getServer().execute(() -> revert(level, chunk)); // 読み込みの中では世界を書き換えない
        }
    }

    /** テスト用: 焼けた時刻をずらす(期限が来たことにする)。 */
    public static void ageForTests(ServerLevel level, ChunkPos pos, long ticks) {
        BurntLand d = get(level.getServer());
        if (d.since.containsKey(pos.toLong())) d.since.put(pos.toLong(), d.since.get(pos.toLong()) - ticks);
    }

    /** テスト用: 今すぐ戻す時刻の見回り(CHECK_INTERVAL を待たない)。 */
    public static void checkNowForTests(ServerLevel level, ChunkPos pos) {
        BurntLand d = get(level.getServer());
        long clock = PhaseData.get(level).clock(level.getServer());
        long key = pos.toLong();
        if (d.since.containsKey(key) && clock - d.since.get(key) >= revertTicks()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                d.since.remove(key);
                revert(level, chunk);
            }
        }
    }
}
