package com.yayuy.overgrownvegetation.ecosystem.life;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.FallenLogBlock;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.ecosystem.EcoLedger;
import com.yayuy.overgrownvegetation.ecosystem.EcoSpawns;
import com.yayuy.overgrownvegetation.ecosystem.EcoTable;
import com.yayuy.overgrownvegetation.ecosystem.Ecosystem;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 棲み処と台帳からの自然湧き(2026-11-18、行動の段。全体管理 11-18 了承の ecosystem-plan.md)。サーバーで 2 秒おきに、プレイヤーごとに:
 * <ol>
 *   <li><b>後付け</b>: まわりの読み込み済みの過繁茂のチャンクを 1 つ見て、倒木の端を洞に変える(1 チャンク 1 回。座標の乱数で決まる)。</li>
 *   <li><b>洞</b>: まわり 48 ブロックの洞(POI)を見て、まだ詰めていなければ升の頭数 ÷ 満員 の確率で中身を詰め、住む種の起きている時間なら出す。</li>
 *   <li><b>洞を持たない種</b>(プロトファスマ・幼虫・ステノディクティア成虫・メガネウラ・コエルロサウラウス)は住む所(梢の葉の上・林冠の上・地面)へ直に湧かせる。</li>
 * </ol>
 * 生きている数の上限はプレイヤーのまわり 96 ブロックで G2 16・G3 6 × 升の頭数 ÷ 満員【仮】。環境生物は F1 から湧く(11-18 ユーザー)。湧くのは過繁茂の内側(台帳に載り縁でない)だけ。
 * 入る・出る・遠くで消えるは台帳を減らさない(減るのはプレイヤーが倒したときだけ = {@link Ecosystem})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EcoDens {

    /** 洞・台帳から湧いた個体の印。 */
    public static final String DEN_TAG = "ov_eco_life";
    public static final int INTERVAL = 40;
    public static final int DEN_RADIUS = 48, COUNT_RADIUS = 96, CAP_G2 = 16, CAP_G3 = 6;
    /** 空になってから詰め直すまで(1 日)【仮】。 */
    public static final long RESTOCK_TICKS = 24000L;
    /** GameTest で回すか(既定は回さない。テストは手で呼ぶ)。 */
    public static boolean tickInGameTests;
    /** 確かめ用の数(/ov perf eco den): 回った回数・後付けで見た・汚染でない・読み込みなし・洞にした端。 */
    public static final long[] STATS = new long[10];
    /** 確かめ用: 洞にならなかった端の前のブロック(種類ごとの数)。 */
    public static final java.util.Map<String, Integer> BLOCKED = new java.util.concurrent.ConcurrentHashMap<>();

    private EcoDens() {
    }

    // ================================================================ 種の表【仮・ecosystem-plan.md】

    /** 洞に住む種: id・グループ・重み・群れの下限・上限。 */
    record Den(String id, int group, int weight, int min, int max) {
    }

    static final List<Den> DEN_SPECIES = List.of(
            new Den("giant_snail", 2, 2, 2, 3),
            new Den("arthropleura", 2, 1, 1, 1),
            new Den("hylonomus", 3, 2, 2, 3),
            new Den("petrolacosaurus", 3, 1, 1, 2),
            new Den("ianthasaurus", 3, 1, 1, 1),
            new Den("pulmonoscorpius", 3, 1, 1, 1),
            new Den("phalangiotarbus", 3, 1, 1, 1));

    enum Habitat { LEAF_TOP, ABOVE_CANOPY, GROUND }

    /** 洞を持たない種: id・グループ・重み・住む所・群れの下限・上限。 */
    record Free(String id, int group, int weight, Habitat habitat, int min, int max) {
    }

    static final List<Free> FREE_SPECIES = List.of(
            new Free("protophasma", 2, 2, Habitat.LEAF_TOP, 1, 3),
            new Free("stenodictya_nymph", 2, 2, Habitat.LEAF_TOP, 2, 4),
            new Free("stenodictya_lobata", 2, 1, Habitat.ABOVE_CANOPY, 1, 3),
            new Free("meganeura", 3, 1, Habitat.ABOVE_CANOPY, 1, 1),
            new Free("coelurosauravus", 3, 1, Habitat.GROUND, 1, 1));

    @Nullable
    static EntityType<?> type(String id) {
        ResourceLocation rl = OvergrownVegetation.id(id);
        return BuiltInRegistries.ENTITY_TYPE.containsKey(rl) ? BuiltInRegistries.ENTITY_TYPE.get(rl) : null;
    }

    // ================================================================ tick

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tickInGameTests && level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        if (!DesignData.ecosystem().enabled || level.getGameTime() % INTERVAL != 0) return;
        STATS[0]++;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            scanOne(level, p.chunkPosition(), level.getRandom());
            int[] live = live(level, p.getX(), p.getZ());
            tickDens(level, p.blockPosition(), live);
            tryFree(level, p, live, level.getRandom());
        }
    }

    /** まわりの G2・G3(行動の段の生き物)の数 [2]・[3]。 */
    public static int[] live(ServerLevel level, double x, double z) {
        int[] out = new int[4];
        AABB box = new AABB(x - COUNT_RADIUS, level.getMinBuildHeight(), z - COUNT_RADIUS, x + COUNT_RADIUS, level.getMaxBuildHeight(), z + COUNT_RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && Ecosystem.residentGroup(e) != 0)) {
            out[Ecosystem.residentGroup(e)]++;
        }
        return out;
    }

    /** 頭数 ÷ 満員(0〜1)。 */
    public static double lively(ServerLevel level, int group, BlockPos at) {
        EcoTable t = DesignData.ecosystem();
        double h = EcoLedger.get(level).heads(group, at.getX() >> 4, at.getZ() >> 4, EcoLedger.now(level));
        return Math.max(0.0D, Math.min(1.0D, h / t.full(group)));
    }

    static int cap(ServerLevel level, int group, BlockPos at) {
        return (int) Math.round((group == 3 ? CAP_G3 : CAP_G2) * lively(level, group, at));
    }

    static boolean inside(ServerLevel level, BlockPos at) {
        return EcoSpawns.inside(PhaseData.get(level), at.getX() >> 4, at.getZ() >> 4);
    }

    // ================================================================ 洞

    static void tickDens(ServerLevel level, BlockPos center, int[] live) {
        PoiManager poi = level.getPoiManager();
        RandomSource random = level.getRandom();
        long now = level.getGameTime();
        poi.getInRange(h -> h.is(EcoBlocks.DEN_POI.getKey()), center, DEN_RADIUS, PoiManager.Occupancy.ANY).forEach(rec -> {
            BlockPos pos = rec.getPos();
            if (!(level.getBlockEntity(pos) instanceof EcoDenBlockEntity den)) return;
            BlockState state = level.getBlockState(pos);
            if (!state.is(EcoBlocks.DEN.get())) return;
            stockIfDue(level, den, pos, now, random);
            tryRelease(level, den, pos, state, live);
        });
    }

    /** まだ詰めていない・空のまま 1 日たった洞に、升の頭数に合わせて中身を詰める。 */
    static void stockIfDue(ServerLevel level, EcoDenBlockEntity den, BlockPos pos, long now, RandomSource random) {
        if (den.count() > 0) return;
        boolean first = den.stockedAt < 0L;
        boolean stale = den.emptySince >= 0L && now - den.emptySince >= RESTOCK_TICKS && now - den.stockedAt >= RESTOCK_TICKS;
        if (!first && !stale) return;
        den.stockedAt = now;
        if (!inside(level, pos)) {
            den.setChanged();
            return;
        }
        stockWith(den, now, random, lively(level, 2, pos), lively(level, 3, pos));
    }

    /** 詰める決まり: G2・G3 を満員の比で選び、その升の 頭数 ÷ 満員 の確率で、選んだ種の群れを詰める(狩られた升の洞は空のまま)。詰めた数を返す。 */
    public static int stockWith(EcoDenBlockEntity den, long now, RandomSource random, double lively2, double lively3) {
        EcoTable t = DesignData.ecosystem();
        int group = random.nextInt(t.fullG2 + t.fullG3) < t.fullG2 ? 2 : 3;
        den.stockedAt = now;
        if (random.nextDouble() >= (group == 3 ? lively3 : lively2)) {
            den.setChanged();
            return 0;
        }
        Den pick = pickDen(group, random);
        EntityType<?> type = pick != null ? type(pick.id) : null;
        if (type == null) return 0;
        int n = pick.min + random.nextInt(pick.max - pick.min + 1);
        den.stock(type, n, now);
        return den.count();
    }

    @Nullable
    static Den pickDen(int group, RandomSource random) {
        int total = 0;
        for (Den d : DEN_SPECIES) if (d.group == group) total += d.weight;
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Den d : DEN_SPECIES) {
            if (d.group != group) continue;
            r -= d.weight;
            if (r < 0) return d;
        }
        return null;
    }

    /** 住む種の起きている時間で、まわりの数が上限より少なく、入口の前が空いていれば、中の群れを出す。 */
    static void tryRelease(ServerLevel level, EcoDenBlockEntity den, BlockPos pos, BlockState state, int[] live) {
        String sp = den.species();
        if (sp == null) return;
        EntityType<?> type = type(sp);
        if (type == null) return;
        int group = groupOf(sp);
        if (!EcoBehavior.of(sp).awake(level)) return;
        if (live[group] + den.count() > cap(level, group, pos)) return;
        BlockPos front = pos.relative(state.getValue(EcoDenBlock.FACING));
        if (!open(level, front, level.getBlockState(front))) return;
        Direction out = state.getValue(EcoDenBlock.FACING);
        live[group] += den.releaseAll(level, EcoDenBlock.mouth(pos, state), EcoDenBlock.outYaw(state), m -> crawlOut(m, out));
    }

    /** 出た個体を入口より奥へ戻し、exit_den を流しながら外へ這い出させる(アニメの無い種はそのまま)。 */
    public static void crawlOut(Mob m, Direction out) {
        if (!(m instanceof EcoLife life) || !(m instanceof net.minecraft.world.entity.PathfinderMob pm)) return;
        double back = EcoGoals.exitLength(pm);
        double[] g = EcoLife.DEN_GAIT.get(EcoLife.id(m));
        if (back <= 0.0D || g == null) return;
        m.setPos(m.getX() - out.getStepX() * back, m.getY(), m.getZ() - out.getStepZ() * back);
        life.ecoMind().exitDir = out;
        life.ecoMind().exiting = (int) g[0];
    }

    static int groupOf(String id) {
        for (Den d : DEN_SPECIES) if (d.id.equals(id)) return d.group;
        for (Free f : FREE_SPECIES) if (f.id.equals(id)) return f.group;
        return 2;
    }

    /** 個体を洞にしまう(入れたら true。呼んだ側は何もしなくてよい = ここで消す)。 */
    public static boolean enter(Mob mob, BlockPos pos) {
        if (!(mob.level().getBlockEntity(pos) instanceof EcoDenBlockEntity den)) return false;
        String sp = den.species();
        if (sp != null && !sp.equals(EcoLife.id(mob))) return false;   // 1 つの洞に 1 種
        if (!den.admit(mob)) return false;
        mob.discard();
        return true;
    }

    /** 入れる洞(空か同じ種で、空きがある)を近い順に 1 つ。 */
    @Nullable
    public static BlockPos findDen(ServerLevel level, Mob mob, int radius) {
        String sp = EcoLife.id(mob);
        return level.getPoiManager().findClosest(h -> h.is(EcoBlocks.DEN_POI.getKey()), p -> {
            if (!(level.getBlockEntity(p) instanceof EcoDenBlockEntity den) || den.full()) return false;
            String s = den.species();
            return s == null || s.equals(sp);
        }, mob.blockPosition(), radius, PoiManager.Occupancy.ANY).orElse(null);
    }

    // ================================================================ 洞を持たない種

    static void tryFree(ServerLevel level, ServerPlayer p, int[] live, RandomSource random) {
        long chunk = EcoSpawns.pickChunk(level, p.getX(), p.getZ(), 24.0D, 64.0D, random);
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        if (!com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, cx, cz)) return;
        BlockPos col = new BlockPos((cx << 4) + random.nextInt(16), 0, (cz << 4) + random.nextInt(16));
        if (!inside(level, col)) return;
        int total = 0;
        for (Free f : FREE_SPECIES) total += f.weight;
        int r = random.nextInt(total);
        Free pick = FREE_SPECIES.get(0);
        for (Free f : FREE_SPECIES) {
            r -= f.weight;
            if (r < 0) {
                pick = f;
                break;
            }
        }
        if (live[pick.group] >= cap(level, pick.group, col)) return;
        if (random.nextDouble() >= lively(level, pick.group, col)) return;
        EntityType<?> type = type(pick.id);
        if (type == null) return;
        BlockPos at = habitat(level, col, pick.habitat);
        if (at == null || !level.isPositionEntityTicking(at)) return;
        if (p.distanceToSqr(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D) < 24.0D * 24.0D) return;
        int n = pick.min + random.nextInt(pick.max - pick.min + 1);
        for (int i = 0; i < n; i++) {
            BlockPos q = i == 0 ? at : habitat(level, at.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2), pick.habitat);
            if (q == null) continue;
            Mob m = com.yayuy.overgrownvegetation.spawn.EnemySpawns.create(level, type, q, MobSpawnType.NATURAL, random);
            if (m == null) continue;
            m.addTag(DEN_TAG);
            if (level.addFreshEntity(m)) live[pick.group]++;
        }
    }

    /** 住む所: 梢の葉の上・林冠の上 3 ブロック・地面。無ければ null。 */
    @Nullable
    static BlockPos habitat(ServerLevel level, BlockPos col, Habitat h) {
        int x = col.getX(), z = col.getZ();
        if (!com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, x >> 4, z >> 4)) return null;
        switch (h) {
            case LEAF_TOP, ABOVE_CANOPY -> {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                BlockPos below = new BlockPos(x, top - 1, z);
                if (!level.getBlockState(below).is(BlockTags.LEAVES)) return null;
                return h == Habitat.LEAF_TOP ? below.above() : below.above(4);
            }
            default -> {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos at = new BlockPos(x, top, z);
                return level.getBlockState(at).getCollisionShape(level, at).isEmpty() ? at : null;
            }
        }
    }

    // ================================================================ 後付け(今あるワールドの倒木)

    /** プレイヤーのまわり 3 チャンクで、まだ見ていない過繁茂のチャンクを 1 つ見て、倒木の端を洞にする。 */
    static void scanOne(ServerLevel level, ChunkPos center, RandomSource random) {
        Scanned data = Scanned.get(level);
        PhaseData phase = PhaseData.get(level);
        for (int i = 0; i < 6; i++) {
            int cx = center.x + random.nextInt(7) - 3, cz = center.z + random.nextInt(7) - 3;
            long key = ChunkPos.asLong(cx, cz);
            if (data.done.contains(key)) continue;
            if (!phase.isOvergrownKey(key)) {
                STATS[2]++;
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                STATS[3]++;
                continue;
            }
            STATS[1]++;
            STATS[4] += convertChunk(level, chunk);
            data.done.add(key);
            data.setDirty();
            return;
        }
    }

    /** チャンクの中の倒木の端を、座標の乱数で洞に変える。変えた数を返す。 */
    public static int convertChunk(ServerLevel level, LevelChunk chunk) {
        Block log = ModBlocks.FALLEN_LOG.get();
        int n = 0;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection s = sections[si];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> st.is(log))) continue;
            int baseY = level.getSectionYFromSectionIndex(si) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = s.getBlockState(x, y, z);
                        if (!st.is(log) || st.getValue(FallenLogBlock.ROOT)) continue;
                        p.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                        if (tryMakeDen(level, p.immutable(), st)) n++;
                    }
                }
            }
        }
        return n;
    }

    /** 倒木の 1 マスが端なら(軸の片側が倒木でなく、入口の前が空いている)、座標の乱数で洞にする。 */
    public static boolean tryMakeDen(ServerLevel level, BlockPos pos, BlockState st) {
        return tryMakeDen(level, pos, st, DesignData.ecosystem().denChance);
    }

    /** 確率を決めて試す(GameTest は 1 で)。 */
    public static boolean tryMakeDen(ServerLevel level, BlockPos pos, BlockState st, double chance) {
        Direction.Axis axis = st.getValue(FallenLogBlock.AXIS);
        for (Direction d : new Direction[]{Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE), Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE)}) {
            BlockPos front = pos.relative(d);
            BlockState fs = level.getBlockState(front);
            if (fs.is(ModBlocks.FALLEN_LOG.get()) || !fs.getFluidState().isEmpty()) continue;
            if (!level.getBlockState(pos.relative(d.getOpposite())).is(ModBlocks.FALLEN_LOG.get())) continue;   // 1 マスだけの倒木は洞にしない
            STATS[5]++;
            long h = net.minecraft.util.Mth.getSeed(pos.getX(), pos.getY() * 31 + d.ordinal(), pos.getZ()) ^ level.getSeed();
            double u = ((h >>> 11) & ((1L << 24) - 1)) / (double) (1L << 24);
            if (u >= chance) {
                STATS[6]++;
                continue;
            }
            if (!open(level, front, fs)) {
                // 塞がった端(11-18 ユーザー「洞をまあ増やしておこう」): 前の 1 マスが植物・柔らかい土なら空けて洞にする。石・建物・中身のあるブロックは空けない
                if (!clearable(fs)) {
                    STATS[7]++;
                    BLOCKED.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(fs.getBlock()).getPath(), 1, Integer::sum);
                    continue;
                }
                if (!level.getBlockState(front.below()).isFaceSturdy(level, front.below(), Direction.UP)) {
                    STATS[8]++;
                    continue;
                }
                STATS[9]++;
                level.setBlock(front, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.setBlock(pos, EcoBlocks.DEN.get().defaultBlockState().setValue(EcoDenBlock.FACING, d), Block.UPDATE_ALL);
            return true;
        }
        return false;
    }

    /** 入口の前が通れるか: 当たりが無いか、敷物のように低い(0.25 ブロック以下。林床の苔・泥炭の敷物)。 */
    static boolean open(ServerLevel level, BlockPos p, BlockState s) {
        var shape = s.getCollisionShape(level, p);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.25D;
    }

    /** 洞の入口を作るために空けてよいブロック: 葉・下草・山刀で刈れる植物・花・苗・柔らかい土(過繁茂の草と土・泥炭を含む)。中身(ブロックエンティティ)のある物は空けない。 */
    static boolean clearable(BlockState s) {
        if (s.hasBlockEntity() || s.isAir()) return false;
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.REPLACEABLE) || s.is(com.yayuy.overgrownvegetation.gear.MacheteItem.CLEARABLE)
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.DIRT)
                || s.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || s.is(ModBlocks.OVERGROWN_DIRT.get()) || s.is(ModBlocks.PEAT.get());
    }

    /** 倒木を置いた直後(FloorGrowth): 両端を {@link #tryMakeDen} にかける。 */
    public static void onFallenLogPlaced(ServerLevel level, List<BlockPos> cells) {
        if (cells.isEmpty()) return;
        for (BlockPos end : new BlockPos[]{cells.get(0), cells.get(cells.size() - 1)}) {
            BlockState st = level.getBlockState(end);
            if (st.is(ModBlocks.FALLEN_LOG.get()) && !st.getValue(FallenLogBlock.ROOT)) tryMakeDen(level, end, st);
        }
    }

    /** 後付けで見たチャンク(保存)。 */
    public static final class Scanned extends SavedData {
        final LongOpenHashSet done = new LongOpenHashSet();

        static Scanned get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Scanned::new, Scanned::load, null), "overgrown_vegetation_eco_dens");
        }

        static Scanned load(CompoundTag tag, HolderLookup.Provider registries) {
            Scanned s = new Scanned();
            for (long k : tag.getLongArray("Done")) s.done.add(k);
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("Done", this.done.toLongArray());
            return tag;
        }
    }
}
