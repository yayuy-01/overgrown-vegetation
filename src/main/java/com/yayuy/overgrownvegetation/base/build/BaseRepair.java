package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseTerminalBlockEntity;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.States;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基地の修理と解体(順 2 の着手順 4 = 整備、2026-10-04)。<b>ユーザー指定</b>: 壁は壊れ、工兵が貯蓄で直す・中の汚染や巨大樹の根は工兵が解体する(10-01)・
 * 根は少し離れた所まで解体する / 補修材で直すのは基地のブロック全部(壁・塀・建物・塔・道を建てたときの計画どおりに。壊された機関砲台・除去装置も置き直す)・
 * <b>プレイヤーが置いたブロックは触らない</b>(10-04)。
 *
 * <p>汚染の対応は多対一で「元のブロック」が分からないので、<b>計画が答え</b>。チャンク 1 つぶんの計画を裏のスレッドで書き出し({@link #plan})、
 * 本体のスレッドで今のブロックと見比べる({@link #compare}):
 * <ul>
 *     <li>計画のブロックのマス: 同じブロックなら良し / 汚染されていれば計画のブロックに戻す(補修材なし)/ 無くなっていれば(空気・草・火・水)
 *     計画のブロックを置く(補修材 1 個、機関砲台・除去装置は {@code upkeepDeviceRepair})/ それ以外はプレイヤーが置いたものとして触らない</li>
 *     <li>計画の無いマス・草木払いだけのマス(地面の下・外の払う幅): 地面より上の汚染の草木・根・死骸は取り除き、地面とその下の汚染された土・石・砂利・草・砂は
 *     元のブロックに戻す(根は土で埋める)。器官・鉱石は触らない</li>
 *     <li>植物の覆い(見た目の苔)は剥がす</li>
 * </ul>
 * 壁の外は、基地を建てるときに草木を払う幅(外周から {@link BasePlan#CLEAR_MARGIN} マス)まで(「少し離れた所まで」の読み、Claude 仮決め)。
 * 外の地面の高さは建てたときの自然の地面で決まって作り直せないので、外では計画のブロックは置かず、外周の 5 マス外の有刺鉄線の輪だけを今の地面の上に置き直す。
 */
public final class BaseRepair {

    /** 列の役割(チャンク 1 つぶんの計画の {@link ChunkPlan#role})。 */
    static final byte PLANNED = 1;
    static final byte MARGIN = 2;
    static final byte WIRE = 4;
    static final byte PERIMETER = 8;

    /** 地面より上で見る高さ(払う幅の列)。 */
    private static final int MARGIN_ABOVE = 24;
    /** 地面より下で見る深さ。 */
    private static final int BELOW = 3;

    /**
     * チャンク 1 つぶんの計画(世界の座標)。{@code cells} = 計画が置くマスの状態の文字列(回した後。草木払いだけのマスは {@link BaseEmitter#SOFT_AIR})、
     * 列ごとに役割・計画の地面の高さ・計画のいちばん上のマス。
     */
    static final class ChunkPlan {
        final long chunk;
        final int level;
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<String> cells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(); // 箱詰めしない表(マスごとに引くので。2026-10-10)
        final byte[] role = new byte[256];
        final int[] target = new int[256];
        final int[] top = new int[256];

        ChunkPlan(long chunk, int level) {
            this.chunk = chunk;
            this.level = level;
        }
    }

    /** チャンク {@code chunk} の計画を書き出す(裏のスレッドで呼んでよい —— 計画と書き出しは純 Java で、読むだけ)。 */
    static ChunkPlan plan(BaseSite site, int lv, boolean purifiers, long chunk) {
        ChunkPlan cp = new ChunkPlan(chunk, lv);
        BaseEmitter em = site.emitter(lv);
        BasePlan plan = em.plan();
        int cx = ChunkPos.getX(chunk);
        int cz = ChunkPos.getZ(chunk);
        int wx0 = cx << 4;
        int wz0 = cz << 4;
        for (int i = 0; i < 256; i++) {
            cp.top[i] = Integer.MIN_VALUE;
        }
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int[] l = site.toLocal(wx0 + dx, wz0 + dz);
                int idx = dx | dz << 4;
                byte role = 0;
                if (plan.inside(l[0], l[1]) || plan.plotAt(l[0], l[1]) != null) {
                    role = PLANNED;
                    if (plan.isPerimeter(l[0], l[1])) {
                        role |= PERIMETER;
                    }
                } else if (plan.isClear(l[0], l[1])) {
                    role = MARGIN;
                    if (plan.isWire(l[0], l[1])) {
                        role |= WIRE;
                    }
                }
                cp.role[idx] = role;
                cp.target[idx] = role != 0 ? em.target(l[0], l[1]) : 0;
            }
        }
        int[] a = site.toLocal(wx0, wz0);
        int[] b = site.toLocal(wx0 + 15, wz0 + 15);
        BaseEmitter.Natural ideal = new BaseEmitter.Natural() {
            @Override
            public int groundY(int x, int z) {
                return em.target(x, z);
            }

            @Override
            public int surfaceY(int x, int z) {
                return em.target(x, z);
            }
        };
        em.emit(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), ideal,
                (x, z) -> true, p -> true, (x, y, z, state) -> {
                    BlockPos w = site.toWorld(x, y, z);
                    int dx = w.getX() - wx0;
                    int dz = w.getZ() - wz0;
                    if (dx < 0 || dx > 15 || dz < 0 || dz > 15) {
                        return;
                    }
                    int idx = dx | dz << 4;
                    if ((cp.role[idx] & PLANNED) == 0) {
                        return; // 外の列の高さは建てたときの自然の地面で決まるので、計画の書き出しを信じない
                    }
                    String s = !purifiers && BasePlacer.isPurifier(state) ? States.AIR : state;
                    cp.cells.put(w.asLong(), s.equals(BaseEmitter.SOFT_AIR) ? s : States.rotate(s, site.rotation));
                    if (!s.equals(BaseEmitter.SOFT_AIR)) {
                        cp.top[idx] = Math.max(cp.top[idx], y);
                    }
                });
        return cp;
    }

    // ---------- 見比べ ----------

    /** チャンクの計画 {@code cp} と今のブロックを見比べて、直すマスを返す(本体のスレッド。チャンクが読み込まれていること)。 */
    static List<UpkeepJob.Fix> compare(ServerLevel level, BaseSite site, ChunkPlan cp) {
        List<UpkeepJob.Fix> out = new ArrayList<>();
        int cx = ChunkPos.getX(cp.chunk);
        int cz = ChunkPos.getZ(cp.chunk);
        LevelChunk lc = level.getChunkSource().getChunkNow(cx, cz);
        if (lc == null) {
            return out;
        }
        init();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int idx = dx | dz << 4;
                byte role = cp.role[idx];
                if (role == 0) {
                    continue;
                }
                int wx = (cx << 4) + dx;
                int wz = (cz << 4) + dz;
                int ground;
                int y0;
                int y1;
                if ((role & PLANNED) != 0) {
                    ground = cp.target[idx];
                    y0 = ground - BELOW;
                    y1 = Math.max(cp.top[idx], ground + BaseEmitter.CLEAR_HEIGHT);
                } else {
                    ground = scanGround(level, lc, wx, wz);
                    if (ground == Integer.MIN_VALUE) {
                        continue;
                    }
                    y0 = ground - BELOW;
                    y1 = ground + MARGIN_ABOVE;
                }
                for (int y = Math.max(minY, y0); y <= Math.min(maxY, y1); y++) {
                    p.set(wx, y, wz);
                    BlockState w = lc.getBlockState(p);
                    String s = (role & PLANNED) != 0 ? cp.cells.get(p.asLong()) : null;
                    UpkeepJob.Fix fix = null;
                    if (s != null && !s.equals(BaseEmitter.SOFT_AIR)) {
                        fix = planned(level, p, w, BasePlacer.state(level, s), (role & PERIMETER) != 0);
                    } else {
                        fix = natural(p, w, y <= ground);
                    }
                    if (fix == null && !w.isAir() && OvergrownLayerData.isCovered(lc, p, w)) {
                        fix = new UpkeepJob.Fix(p.immutable(), UpkeepJob.FixKind.UNCOVER, w, w, 0, 5);
                    }
                    if (fix != null) {
                        out.add(fix);
                    }
                }
                if ((role & WIRE) != 0) {
                    UpkeepJob.Fix wire = wire(lc, wx, ground, wz);
                    if (wire != null) {
                        out.add(wire);
                    }
                }
            }
        }
        return out;
    }

    /** 計画のブロック {@code want} のマス。 */
    @Nullable
    private static UpkeepJob.Fix planned(ServerLevel level, BlockPos p, BlockState w, BlockState want, boolean perimeter) {
        if (w.getBlock() == want.getBlock()) {
            return null;
        }
        if (corrupted(w)) {
            return new UpkeepJob.Fix(p.immutable(), UpkeepJob.FixKind.RESTORE, want, w, 0, want.isAir() ? 5 : 4);
        }
        if (!want.isAir() && missing(w)) {
            return new UpkeepJob.Fix(p.immutable(), UpkeepJob.FixKind.RESTORE, want, w, cost(want), perimeter ? 2 : 3);
        }
        return null; // プレイヤーが置いたもの・自然のもの(草など)は触らない
    }

    /** 計画の無いマス・草木払いだけのマス。{@code underground} = 地面とその下。 */
    @Nullable
    private static UpkeepJob.Fix natural(BlockPos p, BlockState w, boolean underground) {
        if (w.isAir() || !w.getFluidState().isEmpty()) {
            return null; // 水の中の汚染の草(汚染水草など)は水ごとなので触らない
        }
        Block b = w.getBlock();
        Block back = GROUND.get(b);
        if (back != null) {
            return underground ? new UpkeepJob.Fix(p.immutable(), UpkeepJob.FixKind.REVERT, back.defaultBlockState(), w, 0, 4) : null;
        }
        if (REMOVE.contains(b)) {
            return underground
                    ? new UpkeepJob.Fix(p.immutable(), UpkeepJob.FixKind.REVERT, Blocks.DIRT.defaultBlockState(), w, 0, 5)
                    : new UpkeepJob.Fix(p.immutable(), UpkeepJob.FixKind.REMOVE, Blocks.AIR.defaultBlockState(), w, 0, 5);
        }
        return null;
    }

    /** 外周の 5 マス外の有刺鉄線の輪: 今の地面の上が空いていれば置き直す(地面が自然の地面のときだけ)。 */
    @Nullable
    private static UpkeepJob.Fix wire(LevelChunk lc, int wx, int ground, int wz) {
        BlockPos at = new BlockPos(wx, ground + 1, wz);
        BlockState below = lc.getBlockState(at.below());
        if (!NATURAL_GROUND.contains(below.getBlock()) && !GROUND.containsKey(below.getBlock())) {
            return null;
        }
        BlockState w = lc.getBlockState(at);
        if (w.is(ModBlocks.BARBED_WIRE.get()) || !missing(w)) {
            return null;
        }
        return new UpkeepJob.Fix(at, UpkeepJob.FixKind.RESTORE, ModBlocks.BARBED_WIRE.get().defaultBlockState(), w, 1, 3);
    }

    /**
     * 外の列の地面: 上から、空気・水・当たり判定の無いもの・汚染の草木と根を飛ばした最初のブロック(根の上に立たない)。見つからなければ
     * {@link Integer#MIN_VALUE}。
     */
    static int scanGround(ServerLevel level, LevelChunk lc, int wx, int wz) {
        int top = lc.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(wx, top, wz);
        int floor = Math.max(level.getMinBuildHeight(), top - 64);
        for (int y = top; y >= floor; y--) {
            p.setY(y);
            BlockState s = lc.getBlockState(p);
            if (s.isAir() || !s.getFluidState().isEmpty() || !s.blocksMotion() || s.canBeReplaced() || REMOVE.contains(s.getBlock())
                    || s.is(net.minecraft.tags.BlockTags.LEAVES)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /** 汚染されたブロックか(タグ {@code corrupted} の、器官・鉱石・結晶を除いたもの)。 */
    static boolean corrupted(BlockState s) {
        init();
        return !s.isAir() && s.is(ModTags.CORRUPTED) && !KEEP.contains(s.getBlock()) && !s.getBlock().getDescriptionId().endsWith("_ore");
    }

    /** 無くなったとみなすか: 空気・置き換えられるもの(草・雪・火)・水や溶岩だけのマス。 */
    static boolean missing(BlockState s) {
        return s.isAir() || s.getBlock() instanceof LiquidBlock || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /** 計画のブロック {@code want} を置き直す補修材の数(機関砲台・除去装置は設計データ、ほかは 1)。 */
    static int cost(BlockState want) {
        if (want.is(ModBlocks.GUN_TURRET.get())) {
            return DesignData.defense().baseUpkeepDeviceRepair("turret");
        }
        if (want.is(ModBlocks.SPORE_PURIFIER.get())) {
            return DesignData.defense().baseUpkeepDeviceRepair("purifier");
        }
        return 1;
    }

    // ---------- 直す ----------

    /**
     * 1 マス直す。見比べたときから変わっていれば(プレイヤーが先に直した・置いた)何もしない。直したら真。
     */
    static boolean apply(ServerLevel level, BaseSite site, UpkeepJob.Fix fix) {
        BlockPos p = fix.pos();
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) {
            return false;
        }
        BlockState now = level.getBlockState(p);
        if (now.getBlock() != fix.expected().getBlock()) {
            return false;
        }
        switch (fix.kind()) {
            case UNCOVER -> {
                return OvergrownLayerData.uncover(level, p);
            }
            case REMOVE -> {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), BasePlacer.FLAGS);
                OvergrownLayerData.uncover(level, p);
                return true;
            }
            case REVERT -> {
                place(level, p, fix.state());
                OvergrownLayerData.uncover(level, p);
                return true;
            }
            default -> {
                // 汚染されたチェスト・樽の中身は移す(元の容器は、置き換えると中身をばらまく)
                List<ItemStack> keep = now.hasBlockEntity() ? BaseWarehouse.drain(level, p) : List.of();
                place(level, p, fix.state());
                if (!keep.isEmpty()) {
                    BaseWarehouse.fill(level, p, keep);
                }
                if (level.getBlockEntity(p) instanceof BaseTerminalBlockEntity t) {
                    t.setSiteId(site.id);
                }
                OvergrownLayerData.uncover(level, p);
                return true;
            }
        }
    }

    /**
     * ブロックを置く(近所に知らせない —— 建てたときと同じ。扉の片割れが落ちない)。金網・塀・鉄格子はつながりを今の隣で決め直し、隣のつながりも直す。
     */
    static void place(ServerLevel level, BlockPos p, BlockState st) {
        if (connects(st)) {
            st = Block.updateFromNeighbourShapes(st, level, p);
        }
        level.setBlock(p, st, BasePlacer.FLAGS);
        if (connects(st)) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                BlockState ns = level.getBlockState(n);
                if (connects(ns)) {
                    BlockState fixed = Block.updateFromNeighbourShapes(ns, level, n);
                    if (fixed != ns) {
                        level.setBlock(n, fixed, BasePlacer.FLAGS);
                    }
                }
            }
        }
    }

    private static boolean connects(BlockState s) {
        return s.getBlock() instanceof CrossCollisionBlock || s.getBlock() instanceof WallBlock;
    }

    // ---------- ブロックの表 ----------

    /** 計画の無いマスで元に戻す汚染された地面(多対一なので、自然の地面としていちばんありそうなもの)。 */
    private static final Map<Block, Block> GROUND = new HashMap<>();
    /** 取り除く汚染の草木・根・木・死骸。 */
    private static final Set<Block> REMOVE = new HashSet<>();
    /** 見比べで触らない(器官・結晶・殻・ポータルの枠)。 */
    private static final Set<Block> KEEP = new HashSet<>();
    /** 有刺鉄線の輪を置き直してよい地面。 */
    private static final Set<Block> NATURAL_GROUND = new HashSet<>();
    private static boolean ready;

    private static void init() {
        if (ready) {
            return;
        }
        String[][] ground = {
                {"overgrown_grass_block", "minecraft:grass_block"}, {"overgrown_dirt", "minecraft:dirt"},
                {"overgrown_gravel", "minecraft:gravel"}, {"overgrown_sand", "minecraft:sand"},
                {"overgrown_stone", "minecraft:stone"}, {"overgrown_deepslate", "minecraft:deepslate"},
                {"overgrown_andesite", "minecraft:andesite"}, {"overgrown_diorite", "minecraft:diorite"},
                {"overgrown_granite", "minecraft:granite"}, {"overgrown_cobblestone", "minecraft:cobblestone"},
                {"overgrown_cobbled_deepslate", "minecraft:cobbled_deepslate"}, {"overgrown_mud", "minecraft:mud"},
                {"overgrown_block", "minecraft:dirt"}, {"glowing_mycelium", "minecraft:dirt"},
        };
        for (String[] g : ground) {
            Block from = mod(g[0]);
            Block to = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(g[1]));
            if (from != Blocks.AIR && to != Blocks.AIR) {
                GROUND.put(from, to);
            }
        }
        for (String id : new String[]{"overgrown_growth", "overgrown_bush", "overgrown_sapling", "giant_overgrown_sapling",
                "overgrown_spore_bloom", "overgrown_zenmai", "undeveloped_bush", "large_undeveloped_fruit", "overgrown_creeping_vine",
                "overgrown_shelf_lichen", "overgrown_thorn_vine", "overgrown_weeping_tendril", "overgrown_drip_moss",
                "overgrown_spore_tendril", "overgrown_tall_fern", "overgrown_spore_reed", "overgrown_curled_fern", "overgrown_moss_layer",
                "overgrown_glow_vines", "overgrown_glow_vines_plant", "overgrown_liana", "overgrown_log", "overgrown_wood",
                "overgrown_trunk_block", "overgrown_leaves", "overgrown_swamp_log", "overgrown_swamp_wood", "overgrown_swamp_leaves", "giant_overgrown_log",
                "giant_overgrown_cambium", "hardened_giant_trunk", "overgrown_cow_corpse", "overgrown_pig_corpse",
                "overgrown_sheep_corpse", "overgrown_creeper_corpse", "overgrown_spider_corpse", "overgrown_villager_corpse", "overgrown_zombie_corpse", "overgrown_baby_zombie_corpse", "overgrown_skeleton_corpse", "overgrown_enderman_corpse", "overgrown_chicken_corpse", "overgrown_piglin_corpse", "overgrown_piglin_brute_corpse", "overgrown_zombified_piglin_corpse", "overgrown_wither_skeleton_corpse", "overgrown_hoglin_corpse", "overgrown_zoglin_corpse", "overgrown_strider_corpse", "overgrown_iron_golem_corpse", "overgrown_warden_corpse", "fungal_pillar", "fungal_cap",
                "cave_spore_sac", "cave_moss_clump", "overgrown_mud_slab"}) {
            Block b = mod(id);
            if (b != Blocks.AIR) {
                REMOVE.add(b);
            }
        }
        // 覆われたブロック(2026-10-05 夜から汚染の置き換え先。元の森の幹も覆われた原木になる): 原木・木は過繁茂した原木と同じく払い、
        // 地面の岩(凝灰岩・砂岩など。以前は苔石・過繁茂した砂になっていた)は元へ戻す。
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            BlockState vanilla = entry.vanilla().defaultBlockState();
            if (vanilla.is(BlockTags.LOGS)) {
                REMOVE.add(entry.block().get());
            } else if (vanilla.is(BlockTags.BASE_STONE_OVERWORLD) || vanilla.is(Blocks.SANDSTONE) || vanilla.is(Blocks.RED_SANDSTONE)) {
                GROUND.put(entry.block().get(), entry.vanilla());
            }
        }
        for (String id : new String[]{"ley_vein", "overgrown_husk", "energy_crystal",
                "overgrown_end_portal_frame"}) {
            Block b = mod(id);
            if (b != Blocks.AIR) {
                KEEP.add(b);
            }
        }
        NATURAL_GROUND.addAll(List.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.DIRT_PATH, Blocks.GRAVEL,
                Blocks.SAND, Blocks.RED_SAND, Blocks.STONE, Blocks.MUD, Blocks.SNOW_BLOCK, Blocks.MOSS_BLOCK));
        ready = true;
    }

    private static Block mod(String path) {
        return BuiltInRegistries.BLOCK.get(OvergrownVegetation.id(path));
    }

    private BaseRepair() {
    }
}
