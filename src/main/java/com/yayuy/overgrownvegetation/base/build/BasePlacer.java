package com.yayuy.overgrownvegetation.base.build;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.BaseTerminalBlockEntity;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.Facing;
import com.yayuy.overgrownvegetation.base.plan.Pal;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import com.yayuy.overgrownvegetation.base.plan.States;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 配置計画を世界へ置く(2026-10-01)。チャンク 1 つぶんずつ({@link BaseEmitter} をそのチャンクの局所の箱で回す)。
 * 置き方は近所に知らせない(隣のブロックの形を直さない・水を流さない)—— つながりは計画の側で決めてある。
 *
 * <p>{@link BaseEmitter#SOFT_AIR} のマスは、草木・雪・つながっていない小物(当たり判定の無いもの)だけ払う。
 */
public final class BasePlacer {

    /** 置き方: 見た目をクライアントへ送る + 近所の形を直さない。 */
    public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final Map<String, BlockState> STATES = new ConcurrentHashMap<>();

    /** 状態の文字列 → ブロック状態(読めなければ空気。1 回だけログに出す)。 */
    public static BlockState state(LevelAccessor level, String s) {
        return STATES.computeIfAbsent(s, k -> {
            try {
                return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), k, false).blockState();
            } catch (CommandSyntaxException e) {
                OvergrownVegetation.LOGGER.warn("基地の設計図のブロックが読めない: {} ({})", k, e.getMessage());
                return Blocks.AIR.defaultBlockState();
            }
        });
    }

    /**
     * 草木払いで消してよいか(空気・置き換えられる草・丸太・葉・当たり判定の無い小物・竹・サボテン)。
     * 水・溶岩は消さない(水は「置き換えられる」ので、除かないと払うだけで川が消える。2026-10-01 ユーザー指摘)。
     */
    public static boolean softClearable(BlockState s) {
        if (!s.getFluidState().isEmpty()) {
            return false;
        }
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || !s.blocksMotion()
                || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.SNOW);
    }

    /**
     * チャンク {@code chunk} に、基地 {@code site} のレベル {@code planLevel} の計画を置く。置いたブロックの数を返す。
     * {@code columns}・{@code plots} は差分用({@link BaseEmitter#emit} と同じ)。
     */
    public static int placeChunk(ServerLevel level, BaseSite site, int planLevel, ChunkPos chunk,
                                 BaseEmitter.ColumnFilter columns, Predicate<Plot> plots) {
        return emitChunk(level, site, planLevel, chunk, new Natural(level, site, Heightmap.Types.WORLD_SURFACE, 0), columns, plots);
    }

    /** 世界生成の自然の地表を読み始める高さ(世界生成の高さ地図 + これ。木の天辺まで見る —— 高さ地図に木が載っているとは限らない)。 */
    private static final int GEN_SCAN_ABOVE = 40;

    /**
     * 世界生成(順 2 の着手順 2、2026-10-04): チャンク {@code chunk} の分を、生成中の領域 {@code level} へ書き出す({@code base/gen/AlliedBasePiece})。
     * 置き方は {@link #placeChunk} と同じ(計画・書き出し・最初の弾)。地表は高さ地図の世界生成用(生成中も正しい)から読み、木と草は払う。
     * 胞子除去装置は置かない({@code site} は {@link BaseSite#forEmission} の「除去装置なし」)。管理端末には番号を入れない(使ったときに近くの基地を探す)。
     * 読むのはチャンクと周り 1 マス、書くのはチャンクの中だけ(生成中の領域の決まり)。
     */
    public static int placeGenerated(WorldGenLevel level, BaseSite site, ChunkPos chunk) {
        return emitChunk(level, site, site.level(), chunk, new Natural(level, site, Heightmap.Types.WORLD_SURFACE_WG, GEN_SCAN_ABOVE),
                (x, z) -> true, p -> true);
    }

    private static int emitChunk(LevelAccessor level, BaseSite site, int planLevel, ChunkPos chunk, BaseEmitter.Natural natural,
                                 BaseEmitter.ColumnFilter columns, Predicate<Plot> plots) {
        BaseEmitter em = site.emitter(planLevel);
        int wx0 = chunk.getMinBlockX();
        int wz0 = chunk.getMinBlockZ();
        int wx1 = wx0 + 15;
        int wz1 = wz0 + 15;
        int[] a = site.toLocal(wx0, wz0);
        int[] b = site.toLocal(wx1, wz1);
        int[] count = {0};
        List<BlockPos> terminals = new ArrayList<>();
        BlockState terminal = BaseBlocks.BASE_TERMINAL.get().defaultBlockState();
        boolean purifiers = site.purifiers();
        em.emit(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]),
                natural, columns, plots, (x, y, z, state) -> {
                    BlockPos p = site.toWorld(x, y, z);
                    if (p.getX() < wx0 || p.getX() > wx1 || p.getZ() < wz0 || p.getZ() > wz1
                            || y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                        return;
                    }
                    // フェーズ 2 までの基地は胞子除去装置の場所を空ける(飛ばすと、そのマスに元からあったものが残る)
                    String s = !purifiers && isPurifier(state) ? States.AIR : state;
                    if (s.equals(BaseEmitter.SOFT_AIR)) {
                        BlockState cur = level.getBlockState(p);
                        if (!cur.isAir() && softClearable(cur)) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                            count[0]++;
                        }
                        return;
                    }
                    BlockState st = state(level, States.rotate(s, site.rotation));
                    replace(level, p, st);
                    count[0]++;
                    if (st.is(terminal.getBlock()) || st.is(ModBlocks.GUN_TURRET.get()) || st.is(ModBlocks.SPORE_PURIFIER.get())) {
                        terminals.add(p);
                    }
                });
        for (BlockPos p : terminals) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof BaseTerminalBlockEntity t) {
                if (site.id > 0) { // 書き出し用の基地(世界生成)は番号 0
                    t.setSiteId(site.id);
                }
            } else if (be instanceof GunTurretBlockEntity turret) {
                supply(turret);
            } else if (be instanceof SporeDeviceBlockEntity device) {
                supply(device);
            }
        }
        return count[0];
    }

    /** 設計図の状態の文字列が胞子除去装置か。 */
    static boolean isPurifier(String state) {
        return States.id(state).equals(PURIFIER_ID);
    }

    private static final String PURIFIER_ID = States.id(Pal.sporePurifier(Facing.SOUTH));

    /**
     * チャンク {@code chunk} に、基地のレベル {@code planLevel} の計画の胞子除去装置だけを置く(ほかのブロックには触らない)。
     * フェーズ 2 から 1 日たって、除去装置の無い基地へ後から置く({@link BasePurifiers})。最初の燃料とフィルターも入れる。
     * もう除去装置のあるマスは同じ状態を置くだけなので、中身はそのまま。置いた数を返す。
     */
    public static int placePurifiers(ServerLevel level, BaseSite site, int planLevel, ChunkPos chunk) {
        BaseEmitter em = site.emitter(planLevel);
        int wx0 = chunk.getMinBlockX();
        int wz0 = chunk.getMinBlockZ();
        int wx1 = wx0 + 15;
        int wz1 = wz0 + 15;
        int[] a = site.toLocal(wx0, wz0);
        int[] b = site.toLocal(wx1, wz1);
        List<BlockPos> placed = new ArrayList<>();
        em.emit(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]),
                new Natural(level, site, Heightmap.Types.WORLD_SURFACE, 0), (x, z) -> true, p -> true, (x, y, z, s) -> {
                    if (!isPurifier(s)) {
                        return;
                    }
                    BlockPos p = site.toWorld(x, y, z);
                    if (p.getX() < wx0 || p.getX() > wx1 || p.getZ() < wz0 || p.getZ() > wz1
                            || y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                        return;
                    }
                    replace(level, p, state(level, States.rotate(s, site.rotation)));
                    placed.add(p);
                });
        for (BlockPos p : placed) {
            if (level.getBlockEntity(p) instanceof SporeDeviceBlockEntity device) {
                supply(device);
            }
        }
        return placed.size();
    }

    /**
     * {@code p} を {@code st} に置き換える。機関砲台・胞子除去装置を別のブロックに置き換えるときは、先に中身を空にする ——
     * ブロックが変わると弾・燃料・フィルターが地面にばらまかれた(ユーザー指摘 2026-10-02「拡張時に機関砲や胞子除去装置が消されたとき中身がぶちまける」)。
     * 中身は消える(新しい外周の設備には、置いたときに最初の弾・燃料・フィルターが入る {@link #supply})。
     */
    public static void replace(LevelAccessor level, BlockPos p, BlockState st) {
        if (!level.getBlockState(p).is(st.getBlock())) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof GunTurretBlockEntity turret) {
                turret.clearContent();
            } else if (be instanceof SporeDeviceBlockEntity device) {
                device.clearContent();
            }
        }
        level.setBlock(p, st, FLAGS);
    }

    /** 基地の機関砲台の最初の弾(弾倉が空のときだけ 2 スタック。補給は貯蓄の仕組みができてから)。 */
    static void supply(GunTurretBlockEntity turret) {
        turret.setBaseSupplied(true); // 基地の砲台は電池なしで動く(2026-10-16 ユーザー指定)
        if (turret.ammoCount() > 0) {
            return;
        }
        turret.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
        turret.setItem(1, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
    }

    /** 基地の胞子除去装置は電池なしで動く(2026-10-24、除去装置の電池化。機関砲台と同じ)。最初のフィルター 1(空のときだけ)。 */
    static void supply(SporeDeviceBlockEntity device) {
        device.setBaseSupplied(true);
        if (device.getItem(SporeDeviceBlockEntity.SLOT_FILTER).isEmpty()) {
            device.setItem(SporeDeviceBlockEntity.SLOT_FILTER, new ItemStack(ModItems.SPORE_FILTER.get()));
        }
    }

    /**
     * 自然の地表。高さ地図の上から下へ、木(丸太・葉)・草・雪・水を飛ばして最初の固いブロックを地面にする
     * (実行中の世界には木が生えているので、高さ地図の天辺は地面ではない)。動いている世界は {@code WORLD_SURFACE}、
     * 世界生成の途中は {@code WORLD_SURFACE_WG} + {@code above}(生成中の領域の高さ地図に木が載っているとは限らないので、上から余分に見る)。
     */
    static final class Natural implements BaseEmitter.Natural {
        private final LevelReader level;
        private final BaseSite site;
        private final Heightmap.Types heightmap;
        private final int above;
        private final Map<Long, int[]> cache = new java.util.HashMap<>();

        Natural(LevelReader level, BaseSite site, Heightmap.Types heightmap, int above) {
            this.level = level;
            this.site = site;
            this.heightmap = heightmap;
            this.above = above;
        }

        private int[] scan(int lx, int lz) {
            return cache.computeIfAbsent(((long) lx << 32) ^ (lz & 0xFFFFFFFFL), k -> {
                BlockPos w = site.toWorld(lx, 0, lz);
                int top = Math.min(level.getMaxBuildHeight() - 1, level.getHeight(heightmap, w.getX(), w.getZ()) - 1 + above);
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(w.getX(), top, w.getZ());
                int surface = Integer.MIN_VALUE;
                int min = level.getMinBuildHeight();
                for (int y = top; y > min; y--) {
                    p.setY(y);
                    BlockState s = level.getBlockState(p);
                    if (!s.getFluidState().isEmpty() && surface == Integer.MIN_VALUE) {
                        surface = y;
                    }
                    if (s.isAir() || softClearable(s) || !s.getFluidState().isEmpty() && !s.blocksMotion()) { // 水は飛ばして水底を地面にする
                        continue;
                    }
                    return new int[]{y, Math.max(y, surface)};
                }
                return new int[]{min, min};
            });
        }

        @Override
        public int groundY(int x, int z) {
            return scan(x, z)[0];
        }

        @Override
        public int surfaceY(int x, int z) {
            return scan(x, z)[1];
        }
    }

    private BasePlacer() {
    }
}
