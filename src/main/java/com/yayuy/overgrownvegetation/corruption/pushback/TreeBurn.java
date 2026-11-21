package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>木がゆっくり燃える(段 B)</b>(10-09 朝のユーザー指定を 11-18 夜に胞子焼きの筒へ引き継いだ。前の版は parked/purify/TreeBurn・VanillaFire)。
 *
 * <ul>
 *   <li><b>燃えにくく燃え移りにくい</b>(ユーザー 10-09 朝): この MOD の木・葉・下草・汚染の地面は、ふつうの火の表で燃え移りやすさ 0、
 *   燃え尽きやすさ 原木 2・葉 10・下草 15・汚染の地面 5(バニラは原木 5・葉 60・草 100)。火は隣へ飛び移らず、付いた火が隣を 1 マスずつゆっくり燃やす。</li>
 *   <li><b>汚染の地面</b>が燃え尽きると燻る灰ブロックになる(空気にしない)。{@code mixin/FireBlockHiveGuardMixin}。</li>
 *   <li><b>幹が焼け切れたら</b>(ユーザー 10-09 朝の 3 択): 原木が燃え尽きるたびに隣の原木のつながり(26 方向・{@value #MAX_LOGS} まで)を見て、
 *   どれも固いブロックに触れていなければ、その原木と葉づたいに {@value #LEAF_REACH} マスの葉を丸ごと燃やす(火を付けて、原木 25〜40 秒・葉 5〜10 秒で燃え落ちる)。
 *   根元の切り株は残る。汚染されていない木も同じ。</li>
 *   <li>建物の原木(樹皮を剥いだ原木を含む・木材や柵に接する・{@value #MAX_LOGS} を超える)と巨大樹は丸ごと燃やさない(巨大樹は 1 マスずつ燃えるだけ)。</li>
 * </ul>
 * 燃え落ちる途中の仕事は保存しない(読み直した後は、残った火がまた原木を燃やし尽くしたときに始め直す)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TreeBurn {

    private static final int MAX_LOGS = 320;
    private static final int LEAF_REACH = 7;
    private static final int MAX_LEAVES = 4096;
    private static final int IGNITE_STAGGER = 50;
    private static final int LOG_BURN = 500, LOG_BURN_RANGE = 300;
    private static final int LEAF_BURN = 100, LEAF_BURN_RANGE = 100;
    private static final int PER_TICK = 512;

    /** 燃え落ちる途中のマス(時刻つき)。 */
    private record Ev(long pos, long at, boolean ignite) {
    }

    private static final List<Ev> EVENTS = new ArrayList<>();
    private static final LongOpenHashSet CLAIMED = new LongOpenHashSet();

    private TreeBurn() {
    }

    /** 火で燻る灰になる汚染の土(過繁茂の草・土・覆われたブロック)。11-21 に胞子焼きの筒(gear/SporeBurnerItem)を消したので、判定をここへ移した。 */
    public static boolean scorches(BlockState s) {
        return s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_DIRT.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_BLOCK.get());
    }

    // ------------------------------------------------------------------ 燃えにくさの表

    /** サーバーが動き始めたら(タグが読めるので)この MOD のブロックの燃え方を表に入れる。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        FireBlock fire = (FireBlock) Blocks.FIRE;
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(b).getNamespace())) continue;
            BlockState s = b.defaultBlockState();
            int burn;
            if (s.is(BlockTags.LOGS)) burn = 2;
            else if (isLeaf(s)) burn = 10;
            else if (s.is(com.yayuy.overgrownvegetation.ModTags.CLEARABLE_BRUSH)) burn = 15;
            else if (scorches(s)) burn = 5;
            else continue;
            fire.setFlammable(b, 0, burn);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EVENTS.clear();
        CLAIMED.clear();
    }

    public static boolean isWood(BlockState s) {
        return s.is(BlockTags.LOGS);
    }

    /** 葉(バニラのタグ・葉のブロック・この MOD の「…leaves…」。汚染の葉はバニラのタグに入っていない)。 */
    public static boolean isLeaf(BlockState s) {
        if (s.is(BlockTags.LEAVES) || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) return true;
        net.minecraft.resources.ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        return OvergrownVegetation.MODID.equals(id.getNamespace()) && id.getPath().contains("leaves");
    }

    private static boolean isGiant(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().contains("giant");
    }

    // ------------------------------------------------------------------ 幹が焼け切れたら

    /** 原木が燃え尽きた(火の mixin から)。隣の原木のつながりが地面から離れていれば丸ごと燃やす。 */
    public static void onLogConsumed(ServerLevel level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockPos n = pos.offset(dx, dy, dz);
            if (CLAIMED.contains(n.asLong()) || !level.isLoaded(n)) continue;
            BlockState s = level.getBlockState(n);
            if (isWood(s) && !isGiant(s)) tryFall(level, n);
        }
    }

    private static void tryFall(ServerLevel level, BlockPos start) {
        LongOpenHashSet logs = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        logs.add(start.asLong());
        q.enqueue(start.asLong());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!q.isEmpty()) {
            BlockPos p = BlockPos.of(q.dequeueLong());
            BlockState ps = level.getBlockState(p);
            if (BuiltInRegistries.BLOCK.getKey(ps.getBlock()).getPath().startsWith("stripped_")) return; // 建物
            for (Direction d : Direction.values()) {
                m.setWithOffset(p, d);
                if (!level.isLoaded(m)) return; // 読み込まれていない所は待つ
                BlockState s = level.getBlockState(m);
                if (s.is(BlockTags.PLANKS) || s.is(BlockTags.FENCES) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS)) return; // 建物
                if (supports(s, level, m)) return; // 地面・岩など木でも火でもない固いブロックに触れている = 支えがある
            }
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = p.offset(dx, dy, dz);
                if (logs.contains(n.asLong()) || !level.isLoaded(n)) continue;
                BlockState s = level.getBlockState(n);
                if (isWood(s)) {
                    if (isGiant(s) || logs.size() >= MAX_LOGS) return;
                    logs.add(n.asLong());
                    q.enqueue(n.asLong());
                }
            }
        }
        // 葉づたいに LEAF_REACH まで
        LongOpenHashSet leaves = new LongOpenHashSet();
        LongArrayFIFOQueue lq = new LongArrayFIFOQueue();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap dist = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        for (long l : logs) {
            lq.enqueue(l);
            dist.put(l, 0);
        }
        while (!lq.isEmpty() && leaves.size() < MAX_LEAVES) {
            long l = lq.dequeueLong();
            int dd = dist.get(l);
            if (dd >= LEAF_REACH) continue;
            BlockPos p = BlockPos.of(l);
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                long nk = n.asLong();
                if (dist.containsKey(nk) || !level.isLoaded(n)) continue;
                if (isLeaf(level.getBlockState(n))) {
                    dist.put(nk, dd + 1);
                    leaves.add(nk);
                    lq.enqueue(nk);
                }
            }
        }
        long now = level.getGameTime();
        var r = level.random;
        for (long l : logs) schedule(l, now + r.nextInt(IGNITE_STAGGER), LOG_BURN + r.nextInt(LOG_BURN_RANGE));
        for (long l : leaves) schedule(l, now + r.nextInt(IGNITE_STAGGER), LEAF_BURN + r.nextInt(LEAF_BURN_RANGE));
        OvergrownVegetation.LOGGER.debug("木が焼け切れて燃え落ちる: 原木 {}・葉 {}({})", logs.size(), leaves.size(), start);
    }

    private static void schedule(long pos, long igniteAt, int burn) {
        if (!CLAIMED.add(pos)) return;
        EVENTS.add(new Ev(pos, igniteAt, true));
        EVENTS.add(new Ev(pos, igniteAt + burn, false));
    }

    /** 支えになるブロックか(木・葉・火・空気・流体・植物でない、上の面がしっかりした物)。 */
    private static boolean supports(BlockState s, ServerLevel level, BlockPos pos) {
        if (s.isAir() || isWood(s) || isLeaf(s) || s.getBlock() instanceof BaseFireBlock || !s.getFluidState().isEmpty()) return false;
        return s.isCollisionShapeFullBlock(level, pos);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (EVENTS.isEmpty() || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        long now = level.getGameTime();
        int done = 0;
        LongArrayList finished = new LongArrayList();
        for (int i = 0; i < EVENTS.size() && done < PER_TICK; ) {
            Ev e = EVENTS.get(i);
            if (e.at > now) {
                i++;
                continue;
            }
            EVENTS.remove(i);
            done++;
            BlockPos p = BlockPos.of(e.pos);
            if (!level.isLoaded(p)) {
                finished.add(e.pos);
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (!isWood(s) && !isLeaf(s)) {
                finished.add(e.pos);
                continue;
            }
            if (e.ignite) {
                // 外側の空いた面にふつうの火(見た目と、まわりへ燃え移るきっかけ)
                for (Direction d : Direction.values()) {
                    BlockPos f = p.relative(d);
                    if (level.random.nextInt(3) == 0 && level.getBlockState(f).isAir() && BaseFireBlock.canBePlacedAt(level, f, d)) {
                        level.setBlock(f, BaseFireBlock.getState(level, f), Block.UPDATE_ALL);
                        break;
                    }
                }
            } else {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                finished.add(e.pos);
            }
        }
        for (long f : finished) CLAIMED.remove(f);
    }

    /** テスト用: 燃え落ちる途中のマスの数。 */
    public static int pendingForTests() {
        return CLAIMED.size();
    }

    /** テスト用: 今すぐ全部燃え落ちさせる。 */
    public static void finishForTests(ServerLevel level) {
        for (Ev e : EVENTS) {
            if (!e.ignite) {
                BlockPos p = BlockPos.of(e.pos);
                BlockState s = level.getBlockState(p);
                if (isWood(s) || isLeaf(s)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        EVENTS.clear();
        CLAIMED.clear();
    }

    /** テスト用・コマンド用: このマスの原木が燃え尽きたことにする。 */
    public static void consumeForTests(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        onLogConsumed(level, pos);
    }
}
