package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.block.DeflectingStakeBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受け流しの杭の囲い(2026-10-16、⑭)。ユーザー指定: 4 本で囲った四角(一辺 {@link #MAX_SIDE} まで)を守る(中は汚染が広がらず G1 も入らない)・すり減る。
 * <ul>
 *     <li>読み込まれている杭の座標を次元ごとに持ち(杭のブロックエンティティが出入りで登録)、杭が増減したら囲いを数え直す。</li>
 *     <li>囲い = 4 隅に杭がある、軸に沿った長方形(一辺 2〜24)。高さは 4 本の杭の低い方 −{@link #BELOW} 〜 高い方 +{@link #ABOVE}(仮決め)。</li>
 *     <li>汚染を止めた数が {@link #WEAR_HITS} たまるごとに 4 本のどれかが 1 段すり減り、4 段目で折れる(囲いが消える。仮決め)。</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
public final class StakeWards {

    public static final int MAX_SIDE = 24;
    public static final int BELOW = 8;
    public static final int ABOVE = 16;
    /** 止めた数がこれだけたまるごとに 4 本のどれかが 1 段すり減る(仮決め。数えるのは汚染の可否の問い合わせ = 試しの探索も入る)。 */
    public static final int WEAR_HITS = 400;
    /** すり減りを見る間隔(tick)。 */
    private static final int WEAR_INTERVAL = 200;
    /** 4 本の杭の高さの差がこれ以内なら同じ囲い。 */
    private static final int MAX_DY = 6;

    public record Ward(int minX, int minZ, int maxX, int maxZ, int minY, int maxY, List<BlockPos> corners,
                       java.util.concurrent.atomic.AtomicInteger hits) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ
                    && pos.getY() >= minY && pos.getY() <= maxY;
        }

        public BlockPos center() {
            return new BlockPos((minX + maxX) / 2, (minY + BELOW + maxY - ABOVE) / 2, (minZ + maxZ) / 2);
        }
    }

    private static final Map<ResourceKey<Level>, Set<BlockPos>> STAKES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, List<Ward>> WARDS = new ConcurrentHashMap<>();

    private StakeWards() {
    }

    public static void register(Level level, BlockPos pos) {
        if (STAKES.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable())) {
            rebuild(level.dimension());
        }
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = STAKES.get(level.dimension());
        if (set != null && set.remove(pos)) {
            rebuild(level.dimension());
        }
    }

    public static void clearAll() {
        STAKES.clear();
        WARDS.clear();
    }

    private static void rebuild(ResourceKey<Level> dim) {
        Set<BlockPos> set = STAKES.getOrDefault(dim, Set.of());
        List<Ward> wards = new ArrayList<>();
        List<BlockPos> list = new ArrayList<>(set);
        for (BlockPos a : list) {
            for (BlockPos b : list) {
                int dx = b.getX() - a.getX();
                int dz = b.getZ() - a.getZ();
                if (dx < 2 || dz < 2 || dx > MAX_SIDE || dz > MAX_SIDE || Math.abs(b.getY() - a.getY()) > MAX_DY) {
                    continue;
                }
                BlockPos c = find(list, a.getX(), b.getZ(), a.getY());
                BlockPos d = find(list, b.getX(), a.getZ(), a.getY());
                if (c == null || d == null) {
                    continue;
                }
                int minY = Math.min(Math.min(a.getY(), b.getY()), Math.min(c.getY(), d.getY()));
                int maxY = Math.max(Math.max(a.getY(), b.getY()), Math.max(c.getY(), d.getY()));
                wards.add(new Ward(a.getX(), a.getZ(), b.getX(), b.getZ(), minY - BELOW, maxY + ABOVE, List.of(a, b, c, d),
                        new java.util.concurrent.atomic.AtomicInteger()));
            }
        }
        WARDS.put(dim, wards);
    }

    @Nullable
    private static BlockPos find(List<BlockPos> list, int x, int z, int y) {
        for (BlockPos p : list) {
            if (p.getX() == x && p.getZ() == z && Math.abs(p.getY() - y) <= MAX_DY) {
                return p;
            }
        }
        return null;
    }

    /** {@code pos} を守っている囲い(無ければ null)。 */
    @Nullable
    public static Ward wardAt(Level level, BlockPos pos) {
        List<Ward> wards = WARDS.get(level.dimension());
        if (wards == null || wards.isEmpty()) {
            return null;
        }
        for (Ward w : wards) {
            if (w.contains(pos)) {
                return w;
            }
        }
        return null;
    }

    public static boolean protects(Level level, BlockPos pos) {
        return wardAt(level, pos) != null;
    }

    public static List<Ward> wards(Level level) {
        return WARDS.getOrDefault(level.dimension(), List.of());
    }

    /**
     * 汚染の可否の関門({@code CorruptionRegistry#canCorrupt})から呼ぶ。囲いの中なら止めた数を 1 足して true。
     * 何千回も呼ばれるので、囲いの無い次元はすぐ抜け、物を作らない。
     */
    public static boolean blocks(Level level, BlockPos pos) {
        Ward ward = wardAt(level, pos);
        if (ward == null) {
            return false;
        }
        ward.hits().incrementAndGet();
        return true;
    }

    /** {@link #WEAR_INTERVAL} ごとに、止めた数のたまった囲いの杭をすり減らす。 */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % WEAR_INTERVAL != 0) {
            return;
        }
        List<Ward> wards = WARDS.get(level.dimension());
        if (wards == null || wards.isEmpty()) {
            return;
        }
        for (Ward ward : List.copyOf(wards)) {
            if (ward.hits().get() >= WEAR_HITS) {
                ward.hits().addAndGet(-WEAR_HITS);
                wear(level, ward.corners().get(level.random.nextInt(ward.corners().size())));
            }
        }
    }

    /** 杭を 1 段すり減らす(最後は折れる)。 */
    public static void wear(ServerLevel level, BlockPos stake) {
        BlockState state = level.getBlockState(stake);
        if (!state.is(ModBlocks.DEFLECTING_STAKE.get())) {
            return;
        }
        int wear = state.getValue(DeflectingStakeBlock.WEAR);
        if (wear >= DeflectingStakeBlock.MAX_WEAR) {
            level.destroyBlock(stake, false);
            level.playSound(null, stake, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
        } else {
            level.setBlock(stake, state.setValue(DeflectingStakeBlock.WEAR, wear + 1), Block.UPDATE_ALL);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, stake.getX() + 0.5D, stake.getY() + 1.0D, stake.getZ() + 0.5D, 4, 0.2D, 0.2D, 0.2D, 0.0D);
        }
    }
}
