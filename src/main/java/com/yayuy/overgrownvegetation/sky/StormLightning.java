package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CloudStrikePayload;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * 積乱雲から地面への落雷(2026-11-16 weather-system。ユーザー決定「汚染の森への落雷は積乱雲の雷に合わせて森へも落とす。雷雨のたびに森が光って
 * 胞子を吹き上げるのを遠くから見られるように」)。
 *
 * <p>バニラの雷雨の間(オーバーワールド)、1 秒ごとに、プレイヤーから {@link #RANGE} ブロック以内の育った積乱雲(塔 kind 0・入道雲でない・育ち
 * {@link #MIN_MATURITY} 以上。{@link CloudSky#stormsNear} = サーバーとクライアントで同じ式と種)ごとに、確率 {@link #PER_STORM_PER_SECOND} で 1 回落とす。
 * 場所は雲の胴の内側(胴の半径 × {@link #INNER})から {@link #SAMPLES} 点を引き、巨大樹({@link #TREE_BIAS})・汚染の森({@link #FOREST_BIAS})を選びやすくする。
 * 汚染の森(台帳の過繁茂のチャンク)なら胞子を吹き上げる({@link WorldWeather#STRIKE_EMIT}・{@link WorldWeather#STRIKE_LIFT})。
 * バニラの雷のエンティティは出さない(火・ダメージ・チャンクの読み込みが要らない。読み込まれていない遠くの森にも落とせる)。
 * 見た目は {@link CloudStrikePayload}(2000 ブロック以内)。数はすべて【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class StormLightning {

    /** 調べる間隔(tick)。 */
    static final int EVERY = 20;
    /** 育った積乱雲 1 つが 1 秒に落とす確率(平均 20 秒に 1 回)【仮】。 */
    public static final double PER_STORM_PER_SECOND = 1.0D / 20.0D;
    /** 落とす積乱雲の育ちの下限【仮】。 */
    public static final float MIN_MATURITY = 0.5F;
    /** 雲を探す距離・知らせる距離(ブロック)。 */
    static final double RANGE = 1500.0D, SEND_RANGE = 2000.0D;
    /** 雲の胴の半径の何倍の内側に落とすか・場所の候補の数。 */
    static final double INNER = 0.7D;
    static final int SAMPLES = 8;
    /** 候補に巨大樹(根元から {@link #TREE_REACH} ブロック)・汚染の森があるとき、それを選ぶ確率【仮】。 */
    public static final double TREE_BIAS = 0.5D, FOREST_BIAS = 0.6D;
    static final double TREE_REACH = 48.0D;

    /** 測り(/ov weather で出す): 落とした数・森に落ちた数・巨大樹に落ちた数・調べにかかった時間の合計(ns)と回数。 */
    static long strikes, forestStrikes, treeStrikes, checkNanos, checks;
    /** 確かめ用(11-18): 落とすたびに、どの雲のどこへ落としたかをログに書く。 */
    public static volatile boolean devLog;

    private StormLightning() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY != 0) return;
        ServerLevel level = server.overworld();
        if (!level.isThundering() || level.players().isEmpty()) return;
        long t0 = System.nanoTime();
        RandomSource rnd = level.getRandom();
        List<Long> done = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            for (CloudField.Storm s : CloudSky.stormsNear(level, p.getX(), p.getZ(), 1.0F, RANGE)) {
                if (s.kind() != 0 || s.congestus() || s.maturity() < MIN_MATURITY || done.contains(s.id())) continue;
                done.add(s.id());
                if (rnd.nextDouble() < PER_STORM_PER_SECOND) strikeUnder(level, s, rnd, null);
            }
        }
        checkNanos += System.nanoTime() - t0;
        checks++;
    }

    /** 落雷の場所(世界の x, z)と、巨大樹・汚染の森を選んだか。 */
    public record Spot(double x, double z, boolean tree, boolean forest) {
    }

    /**
     * 円(中心 cx, cz・半径 r)の中から {@link #SAMPLES} 点を引き、巨大樹(trees = 根元の x, z を詰めた値)の近くを {@link #TREE_BIAS}、汚染の森
     * (forestChunk にチャンクの long を渡す)を {@link #FOREST_BIAS} で選ぶ。どちらも無い・選ばなかったときは最初の点。素の計算(GameTest からも)。
     */
    public static Spot choose(double cx, double cz, double r, RandomSource rnd, long[] trees, LongPredicate forestChunk) {
        double[] xs = new double[SAMPLES], zs = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0D, d = r * Math.sqrt(rnd.nextDouble());
            xs[i] = cx + Math.cos(a) * d;
            zs[i] = cz + Math.sin(a) * d;
        }
        // 巨大樹: 円の中(+ 届く距離)にある根元
        for (long tr : trees) {
            int tx = (int) (tr >> 32), tz = (int) tr;
            if (Math.hypot(tx - cx, tz - cz) <= r + TREE_REACH && rnd.nextDouble() < TREE_BIAS) {
                return new Spot(tx + 0.5D, tz + 0.5D, true, forestChunk.test(ChunkPos.asLong(tx >> 4, tz >> 4)));
            }
        }
        for (int i = 0; i < SAMPLES; i++) {
            if (forestChunk.test(ChunkPos.asLong(((int) Math.floor(xs[i])) >> 4, ((int) Math.floor(zs[i])) >> 4))) {
                if (rnd.nextDouble() < FOREST_BIAS) return new Spot(xs[i], zs[i], false, true);
                break;
            }
        }
        return new Spot(xs[0], zs[0], false, forestChunk.test(ChunkPos.asLong(((int) Math.floor(xs[0])) >> 4, ((int) Math.floor(zs[0])) >> 4)));
    }

    /** 積乱雲 s の下へ 1 回落とす。forced = 確かめ用(true = 森を選ぶ・false = 森を避ける・null = ふつう)。 */
    public static Spot strikeUnder(ServerLevel level, CloudField.Storm s, RandomSource rnd, Boolean forced) {
        long gt = level.getGameTime();
        double cx = s.x() + CloudField.offsetX(gt), cz = s.z() + CloudField.offsetZ(gt);
        double r = s.bodyRadius() * INNER;
        PhaseData pd = PhaseData.get(level);
        LongPredicate forest = pd::isOvergrownKey;
        Spot spot;
        if (forced == null) {
            spot = choose(cx, cz, r, rnd, livingTrees(pd), forest);
        } else {
            spot = null;
            for (int i = 0; i < 64 && spot == null; i++) {
                Spot c = choose(cx, cz, r, rnd, new long[0], k -> false);
                boolean f = forest.test(ChunkPos.asLong(((int) Math.floor(c.x())) >> 4, ((int) Math.floor(c.z())) >> 4));
                if (f == forced) spot = new Spot(c.x(), c.z(), false, f);
            }
            if (spot == null) spot = new Spot(cx, cz, false, false);
        }
        if (devLog) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("STRIKE cloud ({}, {}) inner {} top {} -> ({}, {}) dist {}", Math.round(cx), Math.round(cz), Math.round(r), s.topY(),
                    Math.round(spot.x()), Math.round(spot.z()), Math.round(Math.hypot(spot.x() - cx, spot.z() - cz)));
        }
        strikeAt(level, s.id(), spot, rnd.nextLong());
        return spot;
    }

    /** 場所 spot に落とす(汚染の森なら胞子を吹き上げ、知らせを送る)。 */
    public static void strikeAt(ServerLevel level, long stormId, Spot spot, long seed) {
        strikeAt(level, stormId, spot, seed, true);
    }

    /** burst = false なら胞子は吹き上げず知らせだけ(確かめ用の雲なしの落雷)。 */
    static void strikeAt(ServerLevel level, long stormId, Spot spot, long seed, boolean burst) {
        BlockPos pos = BlockPos.containing(spot.x(), 0, spot.z());
        int hitY = Integer.MIN_VALUE, groundY = Integer.MIN_VALUE;
        if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunk(level, pos.getX() >> 4, pos.getZ() >> 4)) {
            hitY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
            groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        }
        if (spot.forest() && burst) {
            SporeFieldServer.lightningBurst(pos, WorldWeather.STRIKE_EMIT, WorldWeather.STRIKE_LIFT);
            forestStrikes++;
        }
        if (spot.tree()) treeStrikes++;
        strikes++;
        CloudStrikePayload payload = new CloudStrikePayload(stormId, spot.x(), spot.z(), hitY, groundY, CloudField.LOW_BASE, spot.forest() ? 1.0F : 0.0F, seed);
        double r2 = SEND_RANGE * SEND_RANGE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - spot.x(), dz = p.getZ() - spot.z();
            if (dx * dx + dz * dz <= r2) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /** 生きている巨大樹の根元(x, z を詰めた値)。 */
    static long[] livingTrees(PhaseData pd) {
        long[] all = pd.giantTreeOriginSnapshot();
        int n = 0;
        long[] out = new long[all.length];
        for (long tr : all) if (!pd.isGiantTreeDead(tr)) out[n++] = tr;
        return java.util.Arrays.copyOf(out, n);
    }

    /** 確かめ用(/ov weather strike): 打った人の前方の近い積乱雲から 1 回落とす。雲が無ければ 200 ブロック先に雲なしで落とす。 */
    public static String debugStrike(ServerPlayer player, boolean forest) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) return "オーバーワールドだけ";
        RandomSource rnd = level.getRandom();
        CloudField.Storm best = null;
        double bestD = Double.MAX_VALUE;
        long gt = level.getGameTime();
        net.minecraft.world.phys.Vec3 look = player.getLookAngle();
        for (CloudField.Storm s : CloudSky.stormsNear(level, player.getX(), player.getZ(), 1.0F, RANGE)) {
            if (s.kind() != 0 || s.congestus()) continue;
            double dx = s.x() + CloudField.offsetX(gt) - player.getX(), dz = s.z() + CloudField.offsetZ(gt) - player.getZ();
            double d = Math.hypot(dx, dz);
            if (d > 1e-6 && (dx * look.x + dz * look.z) / d < 0.3D) continue; // 前方(左右 70 度ほど)
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        Spot spot;
        if (best != null) {
            spot = strikeUnder(level, best, rnd, forest);
        } else {
            double x = player.getX() + look.x * 200.0D, z = player.getZ() + look.z * 200.0D;
            boolean f = PhaseData.get(level).isOvergrownKey(ChunkPos.asLong(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4));
            spot = new Spot(x, z, false, forest || f);
            strikeAt(level, 0L, spot, rnd.nextLong(), f); // 本当に汚染の森のときだけ胞子を吹き上げる
        }
        return String.format("落雷: (%.0f, %.0f) %s%s%s", spot.x(), spot.z(), best != null ? "積乱雲 " + best.id() : "雲なし",
                spot.forest() ? "・汚染の森" : "", spot.tree() ? "・巨大樹" : "");
    }

    /** 測りの要約(/ov weather)。 */
    public static String describe() {
        return String.format("雲の落雷 %d 回(森 %d・巨大樹 %d)・調べ 1 回 %.3f ms", strikes, forestStrikes, treeStrikes,
                checks == 0 ? 0.0D : checkNanos / 1e6D / checks);
    }
}
