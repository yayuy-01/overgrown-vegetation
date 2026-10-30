package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <b>霧の向こうの巨大樹のシルエット</b>(2026-10-15 夜。ユーザー「巨大樹は大きすぎて、過繁茂したバイオームの中だと霧に隠れて木全体が見えなくなる。
 * シルエットだけでも見えるように」)。
 *
 * <h2>何が起きていたか</h2>
 * 巨大樹は高さ 120〜240・傘の半径 50〜80 ブロック。汚染地域の霧({@link CorruptionAtmosphere})は 96 ブロック(極相は 48)で
 * 完全に霧の色になり、その先のチャンクは描くこと自体をやめている。根元に立っても傘は霧の向こうで、空が見えていた。
 *
 * <h2>やり方</h2>
 * 巨大樹の形(幹・枝・根の木部と、傘の葉)をブロック 1 つずつ写し取り、2 ブロックの升にまとめた 1 つの立体にして、<b>本物の木が霧で 6 割ほど隠れる距離から先だけ</b>
 * その立体と入れ替えて描く。入れ替えた先は、本物の木と同じ霧の掛かり方から始めて、遠いほどゆっくり霧の色へ寄せる(遠くの影が霞んで残る)。
 * 入れ替わる所では両方の色がほぼ同じなので、境目は見えない(少しの幅で重ねて溶かす)。距離の測り方はバニラの霧と同じ。
 * 描くのは自分のシェーダー({@code shaders/core/giant_silhouette}。バニラのシェーダーは上書きしない)。形は一度だけ作って GPU に載せておく。
 *
 * <h2>巨大樹の見つけ方</h2>
 * <ol>
 *     <li>描画距離の中の読み込まれたチャンクを順に見回り(毎 tick {@value #CHUNKS_PER_TICK} チャンク)、区画(16×16×16)ごとに巨大樹の木部
 *     ({@code giant_overgrown_log} など)を含むかを調べる(区画の中身の一覧を見るだけなので軽い)。</li>
 *     <li>木部を含む区画のつながり(隣り合う 26 方向)を 1 本の木とする。小さすぎるもの(高さ 3 区画未満)は巨大樹ではないとして捨てる。</li>
 *     <li>木部の区画を 1 つずつ写し取り(毎 tick {@value #SECTIONS_PER_TICK} 区画)、幹の先の位置と、枝の届く半径から傘の範囲を決めて、
 *     その範囲の葉を写し取る。枝の途中の葉の房は、木部と同じ区画にある葉(根元から 36 ブロックより上。下はまわりのふつうの木の葉と見分けられない)として拾う。
 *     <b>地中・洞窟の中の木部は写さない</b>: 上に土・岩(木の一部でない不透明なブロック)がある木部(根)と、地面に乗った幹のまわりの地面より下の木部
 *     (幹の列の上は幹なので、まわりの列の地面の高さで見る。{@code Scan#removeBuried})(2026-10-16 ユーザー指摘「巨大樹を霧の中でも見せる仕組みが地下でも働き、
 *     霧の中で浮いてしまう。地下の根などは対象外に」。地下の霧は本物のブロックをバニラの霧より手前で隠すが、シルエットはバニラの霧しか知らないので、
 *     洞窟の霧の中・描かれていない地中に根の形だけが黒く浮いた)。</li>
 *     <li>写した形の外に面した面だけを、同じ向き・同じ材質の面をまとめて大きな四角にし(別スレッド)、GPU に載せる。</li>
 *     <li>木は伸びたり燃えたりするので、1 分ごとに写し直し、形が変わっていたら作り直す。</li>
 * </ol>
 * 区画の中身の一覧が大きい(256 種類を超える)区画は「含むかも」と答えるので、木部の無い区画を写しに行くことがある(何も見つからず形は作られない。無駄だけ)。
 * 設定 {@code giantTreeSilhouette}。数値は仮決め。Sodium・Iris との相性は未確認(シェーダーパックでは霧の作りが違う)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GiantTreeSilhouettes {

    private static final int CHUNKS_PER_TICK = 6;
    private static final int SECTIONS_PER_TICK = 8;
    /** 地中の見分けで 1 歩に見る列の数(1 tick に {@value #SECTIONS_PER_TICK} 歩)。 */
    private static final int COLUMNS_PER_STEP = 64;
    /** 地面に乗った幹のまわりの地面の高さ: 縁の列の地面の低い方からこの割合の所。仮決め。 */
    private static final double RIM_LOW = 0.2;
    /** 写し直す間隔(tick)。 */
    private static final int RESCAN_TICKS = 1200;
    /** シルエットに入れ替える距離: バニラの霧の始めから終わりまでのこの割合(霧の掛かり方はその smoothstep = 約 0.6)。 */
    private static final float CUT_AT = 0.55F;
    /** 入れ替わる所で重ねて溶かす幅(ブロック)。 */
    private static final float CUT_BAND = 8.0F;
    /** カメラへ寄せる割合(本物の木と同じ面で奥行きが揃うので、ほんの少し手前に描いて競り合わないようにする)。 */
    private static final float PULL = 0.995F;

    private static final byte EMPTY = 0;
    private static final byte WOOD = 1;
    private static final byte LEAF = 2;

    private static ClientLevel trackedLevel;
    private static final LongOpenHashSet WOOD_SECTIONS = new LongOpenHashSet();
    private static final List<Tree> TREES = new ArrayList<>();
    private static int sweepIndex;
    /** 見回りの外へ出た区画を忘れるまでの tick。 */
    private static int forgetIn;
    private static boolean dirty;
    private static int clusterIn;
    private static long ticks;
    private static boolean broken;
    /** 木部・葉の色(テクスチャの平均。最初に描くときに求める)。 */
    private static int woodColor = -1;
    private static int leafColor = -1;

    /** 1 本の巨大樹。 */
    private static final class Tree {
        LongOpenHashSet sections;
        Scan scan;
        boolean needsScan = true;
        long scannedAt;
        long builtSignature = Long.MIN_VALUE;
        CompletableFuture<Mesh> pending;
        VertexBuffer vbo;
        int originX;
        int originY;
        int originZ;
        int quads;
        /** 前の写し取りで地中・洞窟の中として外した木部のブロック数(テストクライアントのログ用)。 */
        int buried;

        Tree(LongOpenHashSet sections) {
            this.sections = sections;
        }

        void dispose() {
            if (this.vbo != null) {
                this.vbo.close();
                this.vbo = null;
            }
            this.pending = null;
        }
    }

    // ------------------------------------------------------------------ 見回り・まとめ

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != trackedLevel) {
            clearAll();
            trackedLevel = level;
        }
        if (level == null || player == null || broken) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.giantTreeSilhouette.get()) {
            if (!TREES.isEmpty() || !WOOD_SECTIONS.isEmpty()) {
                clearAll();
            }
            return;
        }
        ticks++;
        try {
            sweep(level, player);
            if (dirty && --clusterIn <= 0) {
                recluster();
                dirty = false;
                clusterIn = 20;
            }
            scanStep(level);
            uploadFinished();
        } catch (RuntimeException e) {
            broken = true;
            clearAll();
            OvergrownVegetation.LOGGER.error("巨大樹のシルエットで例外が出たので、このセッションでは描かない", e);
        }
        if (DEV_LOG && ticks % 100 == 0 && !TREES.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Tree t : TREES) {
                sb.append(String.format(" [区画 %d・面 %d・地中 %d・%s]", t.sections.size(), t.quads, t.buried,
                        t.vbo != null ? "描画中" : t.scan != null ? "写し取り中" : "待ち"));
            }
            OvergrownVegetation.LOGGER.info("GiantTreeSilhouettes 木 {}{}", TREES.size(), sb);
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static void clearAll() {
        for (Tree t : TREES) {
            t.dispose();
        }
        TREES.clear();
        WOOD_SECTIONS.clear();
        dirty = false;
        sweepIndex = 0;
    }

    /** 描画距離の中のチャンクを順に見回り、木部を含む区画を覚え直す。読み込まれていないチャンクの区画は忘れる。 */
    private static void sweep(ClientLevel level, LocalPlayer player) {
        int r = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
        int side = r * 2 + 1;
        int total = side * side;
        int pcx = player.chunkPosition().x;
        int pcz = player.chunkPosition().z;
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int index = Math.floorMod(sweepIndex++, total);
            inspectChunk(level, pcx - r + index % side, pcz - r + index / side);
        }
        // 一周ぶん進んだら、見回りの外へ出た区画を忘れる。
        if (--forgetIn <= 0) {
            forgetIn = total / CHUNKS_PER_TICK + 1;
            int minX = pcx - r - 2;
            int maxX = pcx + r + 2;
            int minZ = pcz - r - 2;
            int maxZ = pcz + r + 2;
            boolean removed = WOOD_SECTIONS.removeIf(key -> {
                int x = SectionPos.x(key);
                int z = SectionPos.z(key);
                return x < minX || x > maxX || z < minZ || z > maxZ;
            });
            dirty |= removed;
        }
    }

    private static void inspectChunk(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        int count = level.getSectionsCount();
        int minSection = level.getMinSection();
        for (int i = 0; i < count; i++) {
            long key = SectionPos.asLong(cx, minSection + i, cz);
            boolean has = false;
            if (chunk != null) {
                LevelChunkSection section = chunk.getSection(i);
                has = !section.hasOnlyAir() && section.maybeHas(GiantTreeSilhouettes::isWood);
            }
            if (has ? WOOD_SECTIONS.add(key) : WOOD_SECTIONS.remove(key)) {
                dirty = true;
            }
        }
    }

    static boolean isWood(BlockState state) {
        return state.is(ModBlocks.GIANT_OVERGROWN_LOG.get()) || state.is(ModBlocks.GIANT_OVERGROWN_CAMBIUM.get())
                || state.is(ModBlocks.HARDENED_GIANT_TRUNK.get());
    }

    static boolean isLeaf(BlockState state) {
        return state.is(ModBlocks.OVERGROWN_LEAVES.get()) || state.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get());
    }

    /** 地面・岩(木の一部でない不透明なブロック)。これが上にある木部は地中・洞窟の中。 */
    static boolean isGround(BlockState state) {
        return state.canOcclude() && !isWood(state) && !TreeCanopyGroundFinder.isTreePart(state);
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int columnX(long column) {
        return (int) (column >> 32);
    }

    private static int columnZ(long column) {
        return (int) column;
    }

    /** 木部の区画のつながりを木にまとめ直す。前からある木と重なれば同じ木として引き継ぐ(形が変わったら写し直す)。 */
    private static void recluster() {
        LongOpenHashSet seen = new LongOpenHashSet();
        List<LongOpenHashSet> components = new ArrayList<>();
        for (long start : WOOD_SECTIONS) {
            if (!seen.add(start)) {
                continue;
            }
            LongOpenHashSet component = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            queue.enqueue(start);
            component.add(start);
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            while (!queue.isEmpty()) {
                long key = queue.dequeueLong();
                int x = SectionPos.x(key);
                int y = SectionPos.y(key);
                int z = SectionPos.z(key);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = SectionPos.asLong(x + dx, y + dy, z + dz);
                            if (WOOD_SECTIONS.contains(next) && seen.add(next)) {
                                component.add(next);
                                queue.enqueue(next);
                            }
                        }
                    }
                }
            }
            // 高さ 3 区画(48 ブロック)に届かないものは巨大樹ではない(置かれた原木・育ち始めの苗木)。
            if (maxY - minY >= 2) {
                components.add(component);
            }
        }
        List<Tree> next = new ArrayList<>();
        for (LongOpenHashSet component : components) {
            Tree match = null;
            int best = 0;
            for (Tree t : TREES) {
                if (next.contains(t)) {
                    continue;
                }
                int overlap = 0;
                for (long key : component) {
                    if (t.sections.contains(key)) {
                        overlap++;
                    }
                }
                if (overlap > best) {
                    best = overlap;
                    match = t;
                }
            }
            if (match == null) {
                next.add(new Tree(component));
            } else {
                if (!match.sections.equals(component)) {
                    // 描画距離の端でチャンクが出入りするたびに写し直さない: 1 割より大きく変わったときだけすぐ写し直し、
                    // 小さな違いは 1 分ごとの写し直しに任せる。
                    int changed = component.size() + match.sections.size() - 2 * best;
                    match.sections = component;
                    if (changed > Math.max(4, component.size() / 10)) {
                        match.needsScan = true;
                        match.scan = null;
                    }
                }
                next.add(match);
            }
        }
        for (Tree t : TREES) {
            if (!next.contains(t)) {
                t.dispose();
            }
        }
        TREES.clear();
        TREES.addAll(next);
    }

    // ------------------------------------------------------------------ 写し取り

    /** 1 本の木を何 tick かに分けて写し取る。 */
    private static final class Scan {
        final Tree tree;
        final LongArrayList woodList;
        int woodIndex;
        LongArrayList leafList;
        int leafIndex;
        /** 区画ごとの中身(0 = 空・1 = 木部・2 = 葉)。 */
        final Long2ObjectOpenHashMap<byte[]> voxels = new Long2ObjectOpenHashMap<>();
        /** 木部のある列の一番下の木部の高さ・その上にある一番高い地面の高さ({@link #measureColumn})。 */
        Long2IntOpenHashMap lowestWood;
        final Long2IntOpenHashMap rockAbove = new Long2IntOpenHashMap();
        /** 一番下の木部が地面に乗っている列(幹・地を這う根。{@link #removeBuried})。 */
        final LongOpenHashSet resting = new LongOpenHashSet();
        LongArrayList columns;
        int columnIndex;
        int buried;
        int topY = Integer.MIN_VALUE;
        double axisX;
        double axisZ;
        int baseY;
        double crownRadius;
        boolean done;

        Scan(Tree tree) {
            this.tree = tree;
            this.woodList = new LongArrayList(tree.sections);
            this.rockAbove.defaultReturnValue(Integer.MIN_VALUE);
        }

        /** 1 区画ぶん(地中の見分けは {@value #COLUMNS_PER_STEP} 列ぶん)進める。 */
        void step(ClientLevel level) {
            if (this.woodIndex < this.woodList.size()) {
                copySection(level, this.woodList.getLong(this.woodIndex++), true, false);
                return;
            }
            if (this.columns == null) {
                listColumns();
                return;
            }
            if (this.columnIndex < this.columns.size()) {
                for (int i = 0; i < COLUMNS_PER_STEP && this.columnIndex < this.columns.size(); i++) {
                    measureColumn(level, this.columns.getLong(this.columnIndex++));
                }
                return;
            }
            if (this.leafList == null) {
                removeBuried(level);
                planCrown();
                return;
            }
            if (this.leafIndex < this.leafList.size()) {
                copySection(level, this.leafList.getLong(this.leafIndex++), false, true);
                return;
            }
            this.done = true;
        }

        private void copySection(ClientLevel level, long key, boolean wood, boolean crownLeavesOnly) {
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
            int index = sy - level.getMinSection();
            if (chunk == null || index < 0 || index >= level.getSectionsCount()) {
                return;
            }
            LevelChunkSection section = chunk.getSection(index);
            if (section.hasOnlyAir() || !section.maybeHas(s -> isWood(s) || isLeaf(s))) {
                return;
            }
            byte[] data = this.voxels.get(key);
            boolean any = false;
            if (data == null) {
                data = new byte[4096];
            }
            double reach = this.crownRadius + 4.0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        byte value = EMPTY;
                        if (wood && isWood(state)) {
                            value = WOOD;
                        } else if (isLeaf(state)) {
                            if (crownLeavesOnly) {
                                double dx = (sx << 4) + x + 0.5 - this.axisX;
                                double dz = (sz << 4) + z + 0.5 - this.axisZ;
                                if (dx * dx + dz * dz <= reach * reach) {
                                    value = LEAF;
                                }
                            } else {
                                value = LEAF;
                            }
                        }
                        if (value != EMPTY) {
                            data[(y << 8) | (z << 4) | x] = value;
                            any = true;
                        }
                    }
                }
            }
            if (any) {
                this.voxels.put(key, data);
            }
        }

        /** 写した木部の列を並べ、列ごとの一番下の木部の高さを求める。 */
        private void listColumns() {
            this.lowestWood = new Long2IntOpenHashMap();
            this.lowestWood.defaultReturnValue(Integer.MAX_VALUE);
            for (Long2ObjectMap.Entry<byte[]> e : this.voxels.long2ObjectEntrySet()) {
                long key = e.getLongKey();
                int ox = SectionPos.x(key) << 4;
                int oy = SectionPos.y(key) << 4;
                int oz = SectionPos.z(key) << 4;
                byte[] data = e.getValue();
                for (int i = 0; i < 4096; i++) {
                    if (data[i] == WOOD) {
                        long column = column(ox + (i & 15), oz + ((i >> 4) & 15));
                        int wy = oy + (i >> 8);
                        if (wy < this.lowestWood.get(column)) {
                            this.lowestWood.put(column, wy);
                        }
                    }
                }
            }
            this.columns = new LongArrayList(this.lowestWood.keySet());
        }

        /**
         * 列の一番下の木部より上で、一番高い地面({@link #isGround})の高さを {@link #rockAbove} に入れる。それより下の木部は上に土・岩がある(地中・洞窟の中)。
         * 上に地面が無く、一番下の木部のすぐ下が地面なら、その列は地面に乗っている({@link #resting}。地面を突き抜けた幹・地を這う根)。
         */
        private void measureColumn(ClientLevel level, long column) {
            int x = columnX(column);
            int z = columnZ(column);
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk == null) {
                return;
            }
            int low = this.lowestWood.get(column);
            int top = Math.min(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15), level.getMaxBuildHeight() - 1);
            int rock = Integer.MIN_VALUE;
            int y = low + 1;
            while (y <= top) {
                LevelChunkSection section = chunk.getSection(level.getSectionIndex(y));
                if (section.hasOnlyAir()) {
                    y = (SectionPos.blockToSectionCoord(y) + 1) << 4;
                    continue;
                }
                if (isGround(section.getBlockState(x & 15, y & 15, z & 15))) {
                    rock = y;
                }
                y++;
            }
            if (rock != Integer.MIN_VALUE) {
                this.rockAbove.put(column, rock);
                return;
            }
            int below = low - 1;
            if (below >= level.getMinBuildHeight()
                    && isGround(chunk.getSection(level.getSectionIndex(below)).getBlockState(x & 15, below & 15, z & 15))) {
                this.resting.add(column);
            }
        }

        /**
         * 地中・洞窟の中の木部を消す(2026-10-16)。(1) 上に地面がある木部(土の中の根・洞窟を横切る根)。(2) 地面に乗った列のつながり(幹と地を這う根)は、
         * 列の上に地面が無い(幹が地面を突き抜けている)ので、まわりの列の地面の高さ(縁の列の低い方から {@value #RIM_LOW} の所)以下を地中とする
         * —— 幹の地中の部分と、幹が洞窟を抜ける所。縁の低い方を取るのは、坂・崖の際の木で見えている幹まで消さないため。
         */
        private void removeBuried(ClientLevel level) {
            Long2IntOpenHashMap groundAt = new Long2IntOpenHashMap();
            groundAt.defaultReturnValue(Integer.MIN_VALUE);
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            LongArrayList starts = new LongArrayList(this.resting);
            for (int s = 0; s < starts.size(); s++) {
                long start = starts.getLong(s);
                if (!seen.add(start)) {
                    continue;
                }
                LongArrayList region = new LongArrayList();
                LongOpenHashSet rim = new LongOpenHashSet();
                queue.enqueue(start);
                while (!queue.isEmpty()) {
                    long c = queue.dequeueLong();
                    region.add(c);
                    int cx = columnX(c);
                    int cz = columnZ(c);
                    for (int d = 0; d < 4; d++) {
                        long n = column(cx + (d == 0 ? 1 : d == 1 ? -1 : 0), cz + (d == 2 ? 1 : d == 3 ? -1 : 0));
                        if (this.resting.contains(n)) {
                            if (seen.add(n)) {
                                queue.enqueue(n);
                            }
                        } else {
                            rim.add(n);
                        }
                    }
                }
                int[] grounds = new int[rim.size()];
                int count = 0;
                LongArrayList rimList = new LongArrayList(rim);
                for (int r = 0; r < rimList.size(); r++) {
                    int g = surfaceGround(level, columnX(rimList.getLong(r)), columnZ(rimList.getLong(r)));
                    if (g != Integer.MIN_VALUE) {
                        grounds[count++] = g;
                    }
                }
                if (count == 0) {
                    continue;
                }
                Arrays.sort(grounds, 0, count);
                int ground = grounds[(int) (count * RIM_LOW)];
                for (int r = 0; r < region.size(); r++) {
                    groundAt.put(region.getLong(r), ground);
                }
            }
            this.topY = Integer.MIN_VALUE;
            LongArrayList emptied = new LongArrayList();
            for (Long2ObjectMap.Entry<byte[]> e : this.voxels.long2ObjectEntrySet()) {
                long key = e.getLongKey();
                int ox = SectionPos.x(key) << 4;
                int oy = SectionPos.y(key) << 4;
                int oz = SectionPos.z(key) << 4;
                byte[] data = e.getValue();
                boolean any = false;
                for (int i = 0; i < 4096; i++) {
                    if (data[i] == WOOD) {
                        int wy = oy + (i >> 8);
                        long c = column(ox + (i & 15), oz + ((i >> 4) & 15));
                        if (wy < this.rockAbove.get(c) || wy <= groundAt.get(c)) {
                            data[i] = EMPTY;
                            this.buried++;
                            continue;
                        }
                        this.topY = Math.max(this.topY, wy);
                    }
                    any |= data[i] != EMPTY;
                }
                if (!any) {
                    emptied.add(key);
                }
            }
            for (int i = 0; i < emptied.size(); i++) {
                this.voxels.remove(emptied.getLong(i));
            }
        }

        /** 列の一番上の地面({@link #isGround})の高さ。上から見る。読み込まれていない・無ければ {@code Integer.MIN_VALUE}。 */
        private static int surfaceGround(ClientLevel level, int x, int z) {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk == null) {
                return Integer.MIN_VALUE;
            }
            int min = level.getMinBuildHeight();
            int y = Math.min(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15), level.getMaxBuildHeight() - 1);
            while (y >= min) {
                LevelChunkSection section = chunk.getSection(level.getSectionIndex(y));
                if (section.hasOnlyAir()) {
                    y = (SectionPos.blockToSectionCoord(y) << 4) - 1;
                    continue;
                }
                if (isGround(section.getBlockState(x & 15, y & 15, z & 15))) {
                    return y;
                }
                y--;
            }
            return Integer.MIN_VALUE;
        }

        /** 木部を写し終えたら、幹の先・根元・傘の範囲を決め、傘の葉を写す区画を並べる。木部の区画の低い所の葉は捨てる。 */
        private void planCrown() {
            if (this.topY == Integer.MIN_VALUE) {
                this.leafList = new LongArrayList();
                return;
            }
            // 幹の先(一番上から 6 ブロック以内の木部)の平均の位置を幹の軸とする。
            double sumX = 0.0;
            double sumZ = 0.0;
            int n = 0;
            for (Long2ObjectMap.Entry<byte[]> e : this.voxels.long2ObjectEntrySet()) {
                long key = e.getLongKey();
                int baseYs = SectionPos.y(key) << 4;
                if (baseYs + 15 < this.topY - 6) {
                    continue;
                }
                byte[] data = e.getValue();
                for (int i = 0; i < 4096; i++) {
                    if (data[i] == WOOD && baseYs + (i >> 8) >= this.topY - 6) {
                        sumX += (SectionPos.x(key) << 4) + (i & 15) + 0.5;
                        sumZ += (SectionPos.z(key) << 4) + ((i >> 4) & 15) + 0.5;
                        n++;
                    }
                }
            }
            this.axisX = sumX / Math.max(1, n);
            this.axisZ = sumZ / Math.max(1, n);
            // 傘を支える枝の届く半径(幹の先から 60 ブロック以内の木部の、軸からの水平距離の最大)と、根元(軸から 14 ブロック以内の木部の一番下)。
            double reach = 0.0;
            int base = this.topY;
            for (Long2ObjectMap.Entry<byte[]> e : this.voxels.long2ObjectEntrySet()) {
                long key = e.getLongKey();
                int ox = SectionPos.x(key) << 4;
                int oy = SectionPos.y(key) << 4;
                int oz = SectionPos.z(key) << 4;
                byte[] data = e.getValue();
                for (int i = 0; i < 4096; i++) {
                    if (data[i] != WOOD) {
                        continue;
                    }
                    int wy = oy + (i >> 8);
                    double dx = ox + (i & 15) + 0.5 - this.axisX;
                    double dz = oz + ((i >> 4) & 15) + 0.5 - this.axisZ;
                    double d2 = dx * dx + dz * dz;
                    if (wy >= this.topY - 60) {
                        reach = Math.max(reach, d2);
                    }
                    if (d2 <= 14.0 * 14.0) {
                        base = Math.min(base, wy);
                    }
                }
            }
            this.baseY = base;
            this.crownRadius = Math.min(120.0, Math.max(16.0, Math.sqrt(reach) + 10.0));
            // 木部の区画の葉は、根元から 36 ブロックより上だけ(枝の途中の葉の房)。下の葉はまわりのふつうの木の葉。
            for (Long2ObjectMap.Entry<byte[]> e : this.voxels.long2ObjectEntrySet()) {
                int oy = SectionPos.y(e.getLongKey()) << 4;
                byte[] data = e.getValue();
                for (int i = 0; i < 4096; i++) {
                    if (data[i] == LEAF && oy + (i >> 8) < this.baseY + 36) {
                        data[i] = EMPTY;
                    }
                }
            }
            // 傘の範囲(軸から半径 + 4、高さは 幹の先 − 半径の 0.55 倍 − 12 から 幹の先 + 16)の区画。
            this.leafList = new LongArrayList();
            double r = this.crownRadius + 4.0;
            int minSx = SectionPos.blockToSectionCoord(this.axisX - r);
            int maxSx = SectionPos.blockToSectionCoord(this.axisX + r);
            int minSz = SectionPos.blockToSectionCoord(this.axisZ - r);
            int maxSz = SectionPos.blockToSectionCoord(this.axisZ + r);
            int minSy = SectionPos.blockToSectionCoord(this.topY - this.crownRadius * 0.55 - 12.0);
            int maxSy = SectionPos.blockToSectionCoord(this.topY + 16);
            for (int sx = minSx; sx <= maxSx; sx++) {
                for (int sz = minSz; sz <= maxSz; sz++) {
                    for (int sy = minSy; sy <= maxSy; sy++) {
                        long key = SectionPos.asLong(sx, sy, sz);
                        if (!this.tree.sections.contains(key)) {
                            this.leafList.add(key);
                        }
                    }
                }
            }
        }

        /** 写した形の目印(形が前と同じなら作り直さない)。 */
        long signature() {
            long h = 1125899906842597L;
            LongArrayList keys = new LongArrayList(this.voxels.keySet());
            keys.sort(null);
            for (long key : keys) {
                h = 31 * h + key;
                byte[] data = this.voxels.get(key);
                for (byte b : data) {
                    h = 31 * h + b;
                }
            }
            return h;
        }
    }

    private static void scanStep(ClientLevel level) {
        Tree target = null;
        for (Tree t : TREES) {
            if (t.scan != null) {
                target = t;
                break;
            }
        }
        if (target == null) {
            for (Tree t : TREES) {
                if (t.pending == null && (t.needsScan || ticks - t.scannedAt > RESCAN_TICKS)) {
                    target = t;
                    target.scan = new Scan(t);
                    break;
                }
            }
        }
        if (target == null) {
            return;
        }
        Scan scan = target.scan;
        for (int i = 0; i < SECTIONS_PER_TICK && !scan.done; i++) {
            scan.step(level);
        }
        if (!scan.done) {
            return;
        }
        target.scan = null;
        target.needsScan = false;
        target.scannedAt = ticks;
        target.buried = scan.buried;
        long signature = scan.signature();
        if (signature == target.builtSignature || scan.voxels.isEmpty()) {
            return;
        }
        target.builtSignature = signature;
        int wood = materialColor(true);
        int leaf = materialColor(false);
        Long2ObjectOpenHashMap<byte[]> voxels = scan.voxels;
        target.pending = CompletableFuture.supplyAsync(() -> Mesher.build(voxels, wood, leaf), Util.backgroundExecutor());
    }

    // ------------------------------------------------------------------ 面をまとめる(別スレッド)

    /** できあがった形(木の基準点からの四角の並び)。 */
    private static final class Mesh {
        final int originX;
        final int originY;
        final int originZ;
        /** 四角ごとに 4 頂点 × (x, y, z) と色 1 つ。 */
        final float[] positions;
        final int[] colors;
        final int quads;

        Mesh(int originX, int originY, int originZ, float[] positions, int[] colors, int quads) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.positions = positions;
            this.colors = colors;
            this.quads = quads;
        }
    }

    /**
     * 外に面した面を、向きと面の高さごとの板に集め、同じ材質の隣り合う面を大きな四角にまとめる(よくある「貪欲法」)。
     * 面の明るさはバニラのブロックの面と同じ割合(上 1・下 0.5・南北 0.8・東西 0.6)で色に焼き込む。
     */
    private static final class Mesher {
        /** 向き(0 = −Y, 1 = +Y, 2 = −Z, 3 = +Z, 4 = −X, 5 = +X)の法線の軸と符号・明るさ。 */
        private static final int[] AXIS = {1, 1, 2, 2, 0, 0};
        private static final int[] SIGN = {-1, 1, -1, 1, -1, 1};
        private static final float[] SHADE = {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};

        /** 写した形をまとめる升の大きさ(ブロック)。1 ブロックのままだと、斜めに走る根・枝・葉の房の段々で面が 25 万にもなった(2026-10-15 夜)。 */
        private static final int CELL = 2;

        static Mesh build(Long2ObjectOpenHashMap<byte[]> voxels, int woodColor, int leafColor) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (long key : voxels.keySet()) {
                minX = Math.min(minX, SectionPos.x(key) << 4);
                minY = Math.min(minY, SectionPos.y(key) << 4);
                minZ = Math.min(minZ, SectionPos.z(key) << 4);
            }
            // 2 ブロックの升へまとめる: 木部が 1 つでもあれば木部(細い枝を消さない)、葉は 2 つ以上で葉(ばらけた 1 枚は捨てる)。
            // 升の位置は木の隅(minX, minY, minZ)からの升の番号(0 以上)。
            it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap cells = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap leafCounts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            for (Long2ObjectMap.Entry<byte[]> e : voxels.long2ObjectEntrySet()) {
                long key = e.getLongKey();
                int ox = (SectionPos.x(key) << 4) - minX;
                int oy = (SectionPos.y(key) << 4) - minY;
                int oz = (SectionPos.z(key) << 4) - minZ;
                byte[] data = e.getValue();
                for (int i = 0; i < 4096; i++) {
                    byte m = data[i];
                    if (m == EMPTY) {
                        continue;
                    }
                    long cell = cellKey((ox + (i & 15)) / CELL, (oy + (i >> 8)) / CELL, (oz + ((i >> 4) & 15)) / CELL);
                    if (m == WOOD) {
                        cells.put(cell, WOOD);
                    } else if (leafCounts.addTo(cell, 1) + 1 >= 2 && cells.get(cell) != WOOD) {
                        cells.put(cell, LEAF);
                    }
                }
            }
            // 面を集める: 板(向き・面の位置)ごとに、その上の升(u, v)と材質。
            Map<Long, LongArrayList> planes = new java.util.HashMap<>();
            for (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry e : cells.long2ByteEntrySet()) {
                long cell = e.getLongKey();
                byte m = e.getByteValue();
                int x = (int) (cell >>> 40);
                int y = (int) ((cell >>> 20) & 0xFFFFF);
                int z = (int) (cell & 0xFFFFF);
                for (int dir = 0; dir < 6; dir++) {
                    int axis = AXIS[dir];
                    int s = SIGN[dir];
                    int nx = x + (axis == 0 ? s : 0);
                    int ny = y + (axis == 1 ? s : 0);
                    int nz = z + (axis == 2 ? s : 0);
                    if (nx >= 0 && ny >= 0 && nz >= 0 && cells.containsKey(cellKey(nx, ny, nz))) {
                        continue;
                    }
                    int plane = (axis == 0 ? x : axis == 1 ? y : z) + (s > 0 ? 1 : 0);
                    int u = axis == 0 ? y : x;
                    int v = axis == 2 ? y : z;
                    long planeKey = ((long) dir << 32) | (plane & 0xFFFFFFFFL);
                    planes.computeIfAbsent(planeKey, k -> new LongArrayList())
                            .add(((long) (u & 0x1FFFFF) << 22) | ((long) (v & 0x1FFFFF) << 1) | (m == LEAF ? 1 : 0));
                }
            }
            FloatList positions = new FloatList();
            IntList colors = new IntList();
            for (Map.Entry<Long, LongArrayList> e : planes.entrySet()) {
                int dir = (int) (e.getKey() >>> 32);
                int plane = (int) (long) e.getKey();
                greedy(dir, plane, e.getValue(), positions, colors, woodColor, leafColor);
            }
            return new Mesh(minX, minY, minZ, positions.toArray(), colors.toArray(), colors.size());
        }

        /** 升の番号(各 0〜2^20)を 1 つの long に詰める。 */
        private static long cellKey(int x, int y, int z) {
            return ((long) x << 40) | ((long) y << 20) | z;
        }

        /** 1 枚の板の面を大きな四角にまとめて書き足す。 */
        private static void greedy(int dir, int plane, LongArrayList cells, FloatList positions, IntList colors,
                                   int woodColor, int leafColor) {
            int minU = Integer.MAX_VALUE;
            int minV = Integer.MAX_VALUE;
            int maxU = Integer.MIN_VALUE;
            int maxV = Integer.MIN_VALUE;
            for (int i = 0; i < cells.size(); i++) {
                long c = cells.getLong(i);
                int u = signed21((int) (c >>> 22) & 0x1FFFFF);
                int v = signed21((int) ((c >>> 1) & 0x1FFFFF));
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            int w = maxU - minU + 1;
            int h = maxV - minV + 1;
            byte[] grid = new byte[w * h];
            for (int i = 0; i < cells.size(); i++) {
                long c = cells.getLong(i);
                int u = signed21((int) (c >>> 22) & 0x1FFFFF) - minU;
                int v = signed21((int) ((c >>> 1) & 0x1FFFFF)) - minV;
                grid[v * w + u] = (byte) ((c & 1) != 0 ? LEAF : WOOD);
            }
            int axis = AXIS[dir];
            for (int v = 0; v < h; v++) {
                for (int u = 0; u < w; ) {
                    byte m = grid[v * w + u];
                    if (m == EMPTY) {
                        u++;
                        continue;
                    }
                    int width = 1;
                    while (u + width < w && grid[v * w + u + width] == m) {
                        width++;
                    }
                    int height = 1;
                    outer:
                    while (v + height < h) {
                        for (int k = 0; k < width; k++) {
                            if (grid[(v + height) * w + u + k] != m) {
                                break outer;
                            }
                        }
                        height++;
                    }
                    for (int dv = 0; dv < height; dv++) {
                        for (int k = 0; k < width; k++) {
                            grid[(v + dv) * w + u + k] = EMPTY;
                        }
                    }
                    emit(dir, axis, plane, minU + u, minV + v, width, height, m == LEAF ? leafColor : woodColor,
                            positions, colors);
                    u += width;
                }
            }
        }

        private static int signed21(int value) {
            return value;
        }

        /** 四角 1 つ(外から見て左回り = 表)。u・v は板の上の 2 つの軸(X 向きの板は u = y・v = z、Y は u = x・v = z、Z は u = x・v = y)。 */
        private static void emit(int dir, int axis, int plane, int u0, int v0, int du, int dv, int color,
                                 FloatList positions, IntList colors) {
            float u1 = u0 + du;
            float v1 = v0 + dv;
            float p = plane;
            float[][] corners;
            switch (dir) {
                case 0 -> corners = new float[][]{{u0, p, v0}, {u1, p, v0}, {u1, p, v1}, {u0, p, v1}};           // −Y (u = x, v = z)
                case 1 -> corners = new float[][]{{u0, p, v0}, {u0, p, v1}, {u1, p, v1}, {u1, p, v0}};           // +Y
                case 2 -> corners = new float[][]{{u0, v0, p}, {u0, v1, p}, {u1, v1, p}, {u1, v0, p}};           // −Z (u = x, v = y)
                case 3 -> corners = new float[][]{{u0, v0, p}, {u1, v0, p}, {u1, v1, p}, {u0, v1, p}};           // +Z
                case 4 -> corners = new float[][]{{p, u0, v0}, {p, u0, v1}, {p, u1, v1}, {p, u1, v0}};           // −X (u = y, v = z)
                default -> corners = new float[][]{{p, u0, v0}, {p, u1, v0}, {p, u1, v1}, {p, u0, v1}};          // +X
            }
            for (float[] c : corners) {
                positions.add(c[0] * CELL);
                positions.add(c[1] * CELL);
                positions.add(c[2] * CELL);
            }
            float shade = SHADE[dir];
            int r = Math.round(((color >> 16) & 0xFF) * shade);
            int g = Math.round(((color >> 8) & 0xFF) * shade);
            int b = Math.round((color & 0xFF) * shade);
            colors.add(0xFF000000 | (r << 16) | (g << 8) | b);
        }
    }

    /** 伸びる float の並び(箱詰めしない)。 */
    private static final class FloatList {
        private float[] data = new float[4096];
        private int size;

        void add(float v) {
            if (this.size == this.data.length) {
                this.data = java.util.Arrays.copyOf(this.data, this.size * 2);
            }
            this.data[this.size++] = v;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(this.data, this.size);
        }
    }

    /** 伸びる int の並び。 */
    private static final class IntList {
        private int[] data = new int[1024];
        private int size;

        void add(int v) {
            if (this.size == this.data.length) {
                this.data = java.util.Arrays.copyOf(this.data, this.size * 2);
            }
            this.data[this.size++] = v;
        }

        int size() {
            return this.size;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(this.data, this.size);
        }
    }

    /** できあがった形を GPU に載せる(描画のスレッドで)。 */
    private static void uploadFinished() {
        for (Tree t : TREES) {
            if (t.pending == null || !t.pending.isDone()) {
                continue;
            }
            Mesh mesh = t.pending.getNow(null);
            t.pending = null;
            if (mesh == null || mesh.quads == 0) {
                continue;
            }
            ByteBufferBuilder bytes = new ByteBufferBuilder(mesh.quads * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
            try {
                BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                float[] p = mesh.positions;
                for (int q = 0; q < mesh.quads; q++) {
                    int color = mesh.colors[q];
                    for (int k = 0; k < 4; k++) {
                        int at = (q * 4 + k) * 3;
                        builder.addVertex(p[at], p[at + 1], p[at + 2]).setColor(color);
                    }
                }
                MeshData data = builder.buildOrThrow();
                if (t.vbo == null) {
                    t.vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                t.vbo.bind();
                t.vbo.upload(data);
                VertexBuffer.unbind();
                t.originX = mesh.originX;
                t.originY = mesh.originY;
                t.originZ = mesh.originZ;
                t.quads = mesh.quads;
            } finally {
                bytes.close();
            }
        }
    }

    /** 木部・葉の色(ブロックのテクスチャの、透けていない画素の平均。葉は林冠の陰を見込んで少し暗く)。 */
    private static int materialColor(boolean wood) {
        int cached = wood ? woodColor : leafColor;
        if (cached != -1) {
            return cached;
        }
        int color = wood ? 0x3B3426 : 0x2F4A22;
        try {
            BlockState state = wood ? ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState() : ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            NativeImage image = sprite.contents().getOriginalImage();
            long r = 0;
            long g = 0;
            long b = 0;
            long n = 0;
            int w = sprite.contents().width();
            int h = sprite.contents().height();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    if ((abgr >>> 24) < 128) {
                        continue;
                    }
                    r += abgr & 0xFF;
                    g += (abgr >> 8) & 0xFF;
                    b += (abgr >> 16) & 0xFF;
                    n++;
                }
            }
            if (n > 0) {
                float dim = wood ? 0.9F : 0.8F;
                color = (Math.round(r / (float) n * dim) << 16) | (Math.round(g / (float) n * dim) << 8) | Math.round(b / (float) n * dim);
            }
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.warn("巨大樹のシルエットの色をテクスチャから求められなかったので、決め打ちの色にする", e);
        }
        if (wood) {
            woodColor = color;
        } else {
            leafColor = color;
        }
        return color;
    }

    // ------------------------------------------------------------------ 描く

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS || broken || TREES.isEmpty()) {
            return;
        }
        ShaderInstance shader = ModShaders.giantSilhouette;
        // シェーダーパック使用中は描かない(2026-10-23): この MOD の霧が掛からず本物の木がパックの霧の中に見えているので、霧の色に合わせた影絵が
        // その手前に黒い塊・黒い線として出た(Complementary で確認)
        if (shader == null || !OvergrownVegetationConfig.COMMON.giantTreeSilhouette.get() || ShaderPacks.active()) {
            return;
        }
        try {
            render(event, shader);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("巨大樹のシルエットの描画で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void render(RenderLevelStageEvent event, ShaderInstance shader) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level != trackedLevel) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        // 空の光が届かない閉じた洞窟の中では描かない(空と同じ。地上の幹が、まだ描かれていない範囲から霧の中へのぞかないように。2026-10-16)。
        if (false) {
            return;
        }
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        if (fogEnd <= fogStart + 1.0F) {
            return;
        }
        float cutEnd = fogStart + CUT_AT * (fogEnd - fogStart);
        float fogAtCut = CUT_AT * CUT_AT * (3.0F - 2.0F * CUT_AT);
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float brightness = level.getSkyDarken(partialTick);
        Vec3 cam = camera.getPosition();
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix()).scale(PULL);
        Matrix4f projection = event.getProjectionMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        shader.safeGetUniform("CutStart").set(Math.max(0.0F, cutEnd - CUT_BAND));
        shader.safeGetUniform("CutEnd").set(cutEnd);
        shader.safeGetUniform("FogAtCut").set(fogAtCut);
        shader.safeGetUniform("HazeEnd").set(Math.max(fogEnd * 3.2F, cutEnd + 160.0F));
        shader.safeGetUniform("Brightness").set(brightness);
        // 描画距離の端へ近づくほど薄くする(水平の距離で。高い所の傘まで薄くしない): 形は読み込まれたチャンクからしか写せないので、その先で木が切れて見えないように。
        float farEnd = mc.options.getEffectiveRenderDistance() * 16.0F - 8.0F;
        shader.safeGetUniform("FarStart").set(farEnd - 40.0F);
        shader.safeGetUniform("FarEnd").set(farEnd);
        for (Tree t : TREES) {
            if (t.vbo == null) {
                continue;
            }
            shader.safeGetUniform("Origin").set((float) (t.originX - cam.x), (float) (t.originY - cam.y), (float) (t.originZ - cam.z));
            t.vbo.bind();
            t.vbo.drawWithShader(modelView, projection, shader);
        }
        VertexBuffer.unbind();
        RenderSystem.disableBlend();
    }

    private GiantTreeSilhouettes() {
    }
}
