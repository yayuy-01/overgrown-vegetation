package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>ほかの感染系 MOD</b>(2026-11-21 ユーザー決定「全部敵。殲滅」。正本は Vault「前線と部隊」の 11-21 の節)。
 * <ul>
 *   <li>見分けはタグ {@code overgrown_vegetation:foreign_infection}(Mob・ブロック・バイオームの 3 つ。中身は空で出し、遊ぶ人が足す)。</li>
 *   <li>読み込まれた所で見つけた分(プレイヤーのまわりの Mob・地表のブロック・バイオーム)を、地図の升(4×4 チャンク)に印として付ける。</li>
 *   <li>前線は印の升を広げる部隊の行き先にする(森の中でも)。部隊がいる間は印が減り、消えたら升を森で上書きする(過繁茂の台帳に載せる)。</li>
 *   <li>植物側の Mob は、タグの Mob を見たら襲う。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ForeignInfection extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_foreign_infection";
    public static final SavedData.Factory<ForeignInfection> FACTORY = new SavedData.Factory<>(ForeignInfection::new, (tag, registries) -> load(tag), null);

    public static final TagKey<EntityType<?>> MOBS = TagKey.create(Registries.ENTITY_TYPE, OvergrownVegetation.id("foreign_infection"));
    public static final TagKey<Block> BLOCKS = TagKey.create(Registries.BLOCK, OvergrownVegetation.id("foreign_infection"));
    public static final TagKey<Biome> BIOMES = TagKey.create(Registries.BIOME, OvergrownVegetation.id("foreign_infection"));

    /** 1 回見つけたときに升に足す印・上限・広げる部隊 1 体が 1 回で減らす印【仮】。 */
    static final double MARK_PER_FIND = 0.25D, MARK_MAX = 1.0D, CLEAN_PER_HEAD = 0.01D;
    /** プレイヤー 1 人あたり 1 回に見る地表の柱の数・その広さ【仮】。 */
    static final int SAMPLES = 12, SAMPLE_RADIUS = 96;

    /** 升のキー(ChunkPos.asLong(cellX, cellZ))→ 印(0〜1)。 */
    private final Long2DoubleOpenHashMap marks = new Long2DoubleOpenHashMap();
    public static long found, cleared;

    public static ForeignInfection get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static long cellKey(int cellX, int cellZ) {
        return ChunkPos.asLong(cellX, cellZ);
    }

    public double mark(int cellX, int cellZ) {
        return marks.get(cellKey(cellX, cellZ));
    }

    public Long2DoubleOpenHashMap marks() {
        return marks;
    }

    /** 升に印を足す。 */
    public void add(int cellX, int cellZ, double v) {
        long k = cellKey(cellX, cellZ);
        marks.put(k, Math.min(MARK_MAX, marks.get(k) + v));
        found++;
        setDirty();
    }

    /** 広げる部隊が heads 体いた: 印を減らし、消えたら升を森で上書きする。消えたら true。 */
    public boolean clean(ServerLevel level, int cellX, int cellZ, int heads) {
        long k = cellKey(cellX, cellZ);
        if (!marks.containsKey(k)) return false;
        double v = marks.get(k) - CLEAN_PER_HEAD * Math.max(1, heads);
        if (v > 1.0E-6D) {
            marks.put(k, v);
            setDirty();
            return false;
        }
        marks.remove(k);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) chunks.add(new ChunkPos(cellX * 4 + dx, cellZ * 4 + dz));
        PhaseData.get(level).markOvergrownChunks(level, chunks);
        cleared++;
        setDirty();
        return true;
    }

    // ---------------------------------------------------------------- 見つける

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 200 != 37) return;
        if (!FrontDirector.tickInGameTests && server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        if (!FrontDirector.enabled()) return;
        scan(server.overworld());
    }

    /** 読み込まれた所を見る(テストからも)。 */
    public static void scan(ServerLevel level) {
        ForeignInfection data = get(level.getServer());
        // Mob(読み込まれたもの全部。タグが空なら何もしない)
        for (Entity e : level.getAllEntities()) {
            if (e instanceof LivingEntity && e.isAlive() && e.getType().is(MOBS)) {
                int cx = (Mth.floor(e.getX()) >> 4) >> 2, cz = (Mth.floor(e.getZ()) >> 4) >> 2;
                data.add(cx, cz, MARK_PER_FIND);
            }
        }
        // 地表のブロック・バイオーム(プレイヤーのまわりを抜き取る)
        RandomSource random = level.getRandom();
        for (ServerPlayer p : level.players()) {
            for (int i = 0; i < SAMPLES; i++) {
                int x = Mth.floor(p.getX()) + random.nextInt(2 * SAMPLE_RADIUS + 1) - SAMPLE_RADIUS;
                int z = Mth.floor(p.getZ()) + random.nextInt(2 * SAMPLE_RADIUS + 1) - SAMPLE_RADIUS;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos at = new BlockPos(x, y - 1, z);
                boolean hit = level.getBlockState(at).is(BLOCKS) || level.getBlockState(at.above()).is(BLOCKS) || level.getBiome(at).is(BIOMES);
                if (hit) data.add((x >> 4) >> 2, (z >> 4) >> 2, MARK_PER_FIND);
            }
        }
    }

    // ---------------------------------------------------------------- 戦う

    /** 植物側の Mob(森の軍勢)に「タグの Mob を襲う」を足す。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob m)) return;
        if (!ForestUpkeep.army(m.getType())) return;
        m.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(m, LivingEntity.class, 10, true, false, e -> e.getType().is(MOBS)));
    }

    public static String describe(ServerLevel level) {
        ForeignInfection d = get(level.getServer());
        return String.format(Locale.ROOT, "ほかの感染: 印の升 %d・見つけた %d 回・森で上書きした升 %d", d.marks.size(), found, cleared);
    }

    public void clearForTests() {
        marks.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var e : marks.long2DoubleEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("K", e.getLongKey());
            c.putDouble("V", e.getDoubleValue());
            list.add(c);
        }
        tag.put("Marks", list);
        return tag;
    }

    public static ForeignInfection load(CompoundTag tag) {
        ForeignInfection d = new ForeignInfection();
        for (Tag t : tag.getList("Marks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.marks.put(c.getLong("K"), c.getDouble("V"));
        }
        return d;
    }

    static boolean overworld(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }
}
