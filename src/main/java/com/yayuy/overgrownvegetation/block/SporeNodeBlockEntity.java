package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.nest.NestNodes;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SporeNodeBlock} の中身(2026-09-20)。噴出孔の「何本目が何 % まで育ったか」を持つのはここで、
 * 地上の {@link SporeVentBlockEntity} は言われたとおりの % を配るだけ。
 *
 * <p>時計は<b>ゲーム内時間</b>({@link Level#getGameTime})で数える(ユーザー指定 2026-09-19)。
 * チャンクが読み込まれていない間も進むので、戻ってきたときにはそのぶん育っている。
 */
public class SporeNodeBlockEntity extends BlockEntity {

    /** 地上へ噴出孔を出す候補地(巣の設計が決めた地点)。使った順に消費する。 */
    private final List<BlockPos> ventSites = new ArrayList<>();
    /** 実際に地上へ出した噴出孔。 */
    private final List<BlockPos> vents = new ArrayList<>();
    /** 最後に育てたゲーム内時間。 */
    private long lastGrowth = Long.MIN_VALUE;
    /** 台帳へ登録済みか。 */
    private boolean registered;
    /** 今の候補地で噴出孔を置こうとして失敗した回数。 */
    private int ventTries;

    /** 1 つの候補地で噴出孔の設置を試す回数。これを超えたら次の候補地へ。 */
    private static final int VENT_TRIES = 3;

    public SporeNodeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPORE_NODE.get(), pos, state);
    }

    /** 巣を掘り終えたときに候補地を渡す({@code nest/NestContents})。 */
    public void setVentSites(List<BlockPos> sites) {
        ventSites.clear();
        for (BlockPos site : sites) {
            ventSites.add(site.immutable());
        }
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SporeNodeBlockEntity node) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!node.registered) {
            NestNodes.register(level, pos);
            node.registered = true;
        }
        NestTable t = DesignData.nest();
        long now = level.getGameTime();
        if (node.lastGrowth == Long.MIN_VALUE) {
            node.lastGrowth = now;
            return;
        }
        int interval = t.ventGrowthIntervalTicks();
        long steps = (now - node.lastGrowth) / interval;
        if (steps <= 0) {
            return;
        }
        node.lastGrowth += steps * (long) interval;
        for (long i = 0; i < steps && i < 64; i++) {     // まとめて進める(離れていた間のぶん)
            node.grow(serverLevel, t);
        }
        node.setChanged();
    }

    /**
     * 1 段階ぶん育てる。育ちきっていない噴出孔があればそれを 1% 上げ、
     * 全部育ちきっていて本数に余裕があれば次の 1 本を生やす。
     */
    private void grow(ServerLevel level, NestTable t) {
        for (BlockPos vent : vents) {
            if (level.getBlockEntity(vent) instanceof SporeVentBlockEntity be
                    && be.percent() < t.ventBonusPerVentPercent()) {
                be.setPercent(level, be.percent() + 1);
                return;
            }
        }
        if (vents.size() >= t.ventMaxCount() || ventSites.isEmpty()) {
            return;
        }
        BlockPos site = ventSites.get(0);
        if (!level.isLoaded(site)) {
            return;                                 // まだ読み込まれていない。候補地は消費しない
        }
        BlockPos placed = placeVent(level, site);
        if (placed == null) {
            if (++ventTries < VENT_TRIES) {
                return;                             // まだ諦めない。候補地は残しておく
            }
            ventTries = 0;
            ventSites.remove(0);                    // どうしても置けない。次の候補地へ
            return;
        }
        ventTries = 0;
        ventSites.remove(0);
        vents.add(placed);
    }

    /**
     * 噴出孔を 1 本置く。載せるのは<b>柱のてっぺん</b>(2026-09-20 にユーザー指定で
     * 中身の詰まった柱へ作り直したぶん)。
     *
     * <p>{@code site} は設計が出した柱の先端なので、そこから上下に少し探して
     * <b>柱の上の最初の空きマス</b>へ置く。柱がまだ積まれていなければ(チャンクが
     * 落ちていた等)周りの地面の高さに落として置く —— 以前の挙動。
     */
    private BlockPos placeVent(ServerLevel level, BlockPos site) {
        BlockPos top = topOfColumn(level, site);
        if (top == null) {
            int ground = groundAround(level, site);
            if (ground == Integer.MIN_VALUE) {
                return null;
            }
            top = new BlockPos(site.getX(), ground, site.getZ());
        }
        BlockState here = level.getBlockState(top);
        if (!here.isAir() && !here.canBeReplaced()) {
            return null;
        }
        level.setBlock(top, ModBlocks.SPORE_VENT.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(top) instanceof SporeVentBlockEntity be) {
            be.setPercent(level, 1);
            be.setSource(worldPosition);
        }
        return top;
    }

    /**
     * {@code site} のあたりで<b>柱のてっぺんの 1 つ上</b>(= 最初の空きマス)を探す。
     * 柱が見つからなければ null。
     */
    @javax.annotation.Nullable
    private static BlockPos topOfColumn(ServerLevel level, BlockPos site) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 3; dy >= -3; dy--) {
            cursor.set(site.getX(), site.getY() + dy, site.getZ());
            if (!level.isLoaded(cursor)) {
                return null;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.canBeReplaced()) {
                continue;
            }
            // 何か詰まっているマスの 1 つ上が置き場所
            BlockPos above = cursor.above();
            BlockState over = level.getBlockState(above);
            return over.isAir() || over.canBeReplaced() ? above : null;
        }
        return null;
    }

    /**
     * 周り(半径 3 の 8 方向)の地表の高さの中央値。柱が積まれていないときの保険。
     * 1 本も読み込まれていなければ {@link Integer#MIN_VALUE}。
     */
    private static int groundAround(ServerLevel level, BlockPos site) {
        int[] dx = {3, -3, 0, 0, 2, 2, -2, -2};
        int[] dz = {0, 0, 3, -3, 2, -2, 2, -2};
        int[] heights = new int[dx.length];
        int n = 0;
        for (int i = 0; i < dx.length; i++) {
            BlockPos probe = new BlockPos(site.getX() + dx[i], site.getY(), site.getZ() + dz[i]);
            if (!level.isLoaded(probe)) {
                continue;
            }
            heights[n++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    probe.getX(), probe.getZ());
        }
        if (n == 0) {
            return Integer.MIN_VALUE;
        }
        int[] used = java.util.Arrays.copyOf(heights, n);
        java.util.Arrays.sort(used);
        return used[n / 2];
    }

    /** ノードが壊れたとき、育てた噴出孔も枯らす。 */
    public void wither(Level level) {
        for (BlockPos vent : vents) {
            if (level.getBlockState(vent).is(ModBlocks.SPORE_VENT.get())) {
                level.setBlockAndUpdate(vent, Blocks.AIR.defaultBlockState());
            }
        }
        vents.clear();
    }

    @Override
    public void setRemoved() {
        if (level != null) {
            NestNodes.unregister(level, worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("LastGrowth", lastGrowth);
        tag.put("VentSites", toLongs(ventSites));
        tag.put("Vents", toLongs(vents));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        lastGrowth = tag.contains("LastGrowth") ? tag.getLong("LastGrowth") : Long.MIN_VALUE;
        fromLongs(tag.getLongArray("VentSites"), ventSites);
        fromLongs(tag.getLongArray("Vents"), vents);
    }

    private static LongArrayTag toLongs(List<BlockPos> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).asLong();
        }
        return new LongArrayTag(out);
    }

    private static void fromLongs(long[] in, List<BlockPos> out) {
        out.clear();
        for (long key : in) {
            out.add(BlockPos.of(key));
        }
    }
}
