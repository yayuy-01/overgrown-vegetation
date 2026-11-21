package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.entity.PartEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <b>部位(PartEntity)の升目の索引</b>(2026-11-18 大群のための軽量化 B の ①。見た目・遊びは変わらない)。
 *
 * <h2>なぜ</h2>
 * <p>NeoForge は {@code Level#getEntities} の 2 つ(ぶつかり探し・押し合い・標的探しなど、ほとんどの「近くのエンティティ探し」)の
 * 最後に、<b>世界じゅうの部位を 1 つずつ</b>箱と比べる輪を足している。この MOD の {@code HitPart}(G2 の空の種・ファルキムネス・
 * フィロセンス)は部位なので、重さは「探す回数 × 部位の数」= 群れの 2 乗になる。11-18 の測り(F7 の前線・Mob 470〜600 体)で
 * サーバースレッドの約 23%。
 *
 * <h2>どうするか</h2>
 * <p>その輪に渡す一覧を、探す箱の近くの升目(16 ブロック四方・高さは見ない)に入っている部位だけに絞る。比べる中身(箱が重なるか・
 * 条件)はバニラの輪がそのまま行うので、答えは同じ(並び順だけ変わりうる)。索引は tick ごと(ゲーム時間が変わったら)と、
 * 部位の数が変わったとき、大きく動いた部位があったとき({@link #moved})に作り直す。1 tick の中で部位が動く分は、
 * 探す箱を {@value #MARGIN} ブロック広げて拾う({@code HitPartSet} は 1 tick に 1 回置き直す。それより大きく動いたら作り直す)。
 *
 * <p>部位が少ない({@value #MIN_PARTS} 未満)・探す箱が広すぎるときは今までどおり全部を渡す。
 * GameTest のサーバーでは絞らず全部を渡し、絞った一覧が取りこぼしていないかを数える({@link #mismatches})。
 */
public final class PartIndex {

    /**
     * 部位を持つエンティティが入った・出たら、次に探すときに作り直す(11-18 見直し ④6。前は数だけで見ていたので、同じ tick に
     * 1 つ消えて 1 つ増えると古い索引のままだった)。
     */
    @net.neoforged.fml.common.EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
    public static final class Events {
        private Events() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
            if (event.getEntity().isMultipartEntity() && event.getLevel() instanceof Holder h) h.overgrown_vegetation$partIndex().dirty = true;
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
            if (event.getEntity().isMultipartEntity() && event.getLevel() instanceof Holder h) h.overgrown_vegetation$partIndex().dirty = true;
        }
    }

    /** Level に持たせる口(Mixin が足す)。 */
    public interface Holder {
        PartIndex overgrown_vegetation$partIndex();
    }

    static final double MARGIN = 3.0D;
    /** {@link #moved} で作り直しにする 1 回の移動(ブロック)。{@link #MARGIN} より小さく。 */
    static final double REBUILD_MOVE = 2.0D;
    static final int MIN_PARTS = 16;
    /** 升目の数がこれを超える広い箱は全部を渡す。 */
    static final int MAX_CELLS = 64;

    private static long mismatches;
    private static long checks;

    private final Long2ObjectOpenHashMap<List<PartEntity<?>>> cells = new Long2ObjectOpenHashMap<>();
    private long builtTime = Long.MIN_VALUE;
    private int builtSize = -1;
    private double maxHalf;
    private boolean dirty;
    private Boolean gameTest;

    /** 探す箱 {@code box} について、NeoForge の輪に渡す部位の一覧を返す。 */
    public Collection<PartEntity<?>> candidates(Level level, Collection<PartEntity<?>> all, AABB box) {
        int size = all.size();
        if (size < MIN_PARTS || !OvergrownVegetationConfig.COMMON.partIndexEnabled.get()) {
            return all;
        }
        if (this.gameTest == null) {
            this.gameTest = level instanceof ServerLevel server && server.getServer() instanceof GameTestServer;
        }
        long time = level.getGameTime();
        if (this.dirty || time != this.builtTime || size != this.builtSize) {
            this.rebuild(all, time);
        }
        double r = this.maxHalf + MARGIN;
        int x0 = Mth.floor(box.minX - r) >> 4, x1 = Mth.floor(box.maxX + r) >> 4;
        int z0 = Mth.floor(box.minZ - r) >> 4, z1 = Mth.floor(box.maxZ + r) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CELLS) {
            return all;
        }
        List<PartEntity<?>> found = null;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                List<PartEntity<?>> cell = this.cells.get(key(x, z));
                if (cell != null) {
                    if (found == null) found = new ArrayList<>();
                    found.addAll(cell);
                }
            }
        }
        List<PartEntity<?>> result = found == null ? Collections.emptyList() : found;
        if (this.gameTest) {
            check(all, result, box);
            return all;
        }
        return result;
    }

    /** GameTest 用: 検算の入れ替えをせず、絞った一覧そのものを返す(同じ索引を使い回す)。 */
    public Collection<PartEntity<?>> filteredForTest(Level level, Collection<PartEntity<?>> all, AABB box) {
        Boolean was = this.gameTest;
        this.gameTest = false;
        try {
            return this.candidates(level, all, box);
        } finally {
            this.gameTest = was;
        }
    }

    private void rebuild(Collection<PartEntity<?>> all, long time) {
        this.cells.clear();
        double half = 0.0D;
        for (PartEntity<?> p : all) {
            AABB b = p.getBoundingBox();
            half = Math.max(half, Math.max(b.getXsize(), b.getZsize()) * 0.5D);
            int cx = Mth.floor((b.minX + b.maxX) * 0.5D) >> 4;
            int cz = Mth.floor((b.minZ + b.maxZ) * 0.5D) >> 4;
            this.cells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>(4)).add(p);
        }
        this.maxHalf = half;
        this.builtTime = time;
        this.builtSize = all.size();
        this.dirty = false;
    }

    /**
     * 部位を置き直した({@code HitPartSet#place})。前の位置から {@value #REBUILD_MOVE} ブロックより動いたか、
     * 大きさが索引を作ったときより大きくなったら、次に探すときに作り直す。
     */
    public static void moved(Level level, PartEntity<?> part, double fromX, double fromZ) {
        if (!(level instanceof Holder holder)) return;
        PartIndex index = holder.overgrown_vegetation$partIndex();
        if (index.dirty) return;
        double dx = part.getX() - fromX, dz = part.getZ() - fromZ;
        AABB b = part.getBoundingBox();
        if (dx * dx + dz * dz > REBUILD_MOVE * REBUILD_MOVE || Math.max(b.getXsize(), b.getZsize()) * 0.5D > index.maxHalf) {
            index.dirty = true;
        }
    }

    private static void check(Collection<PartEntity<?>> all, List<PartEntity<?>> result, AABB box) {
        Set<PartEntity<?>> got = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        got.addAll(result);
        for (PartEntity<?> p : all) {
            if (p.getBoundingBox().intersects(box)) {
                checks++;
                if (!got.contains(p)) mismatches++;
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** GameTest の調べ用。 */
    public String describe(Level level) {
        return "built " + this.builtTime + " now " + level.getGameTime() + " size " + this.builtSize + " dirty " + this.dirty + " half " + this.maxHalf + " cells " + this.cells.size();
    }

    /** GameTest の検算: 箱に重なる部位のうち、絞った一覧に入っていなかった数(0 であること)。 */
    public static long mismatches() {
        return mismatches;
    }

    /** GameTest の検算: 箱に重なる部位を確かめた数。 */
    public static long checks() {
        return checks;
    }

    public static void resetCounts() {
        mismatches = 0;
        checks = 0;
    }
}
