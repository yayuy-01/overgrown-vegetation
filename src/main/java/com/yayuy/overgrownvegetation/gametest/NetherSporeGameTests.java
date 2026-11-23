package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherJitter;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ネザーの胞子の仕組み(寒暖差の風、2026-11-11)のテスト。
 * <ul>
 *     <li>風は冷たい升から熱い升へ吹く(浮遊)</li>
 *     <li>森の中(温度が揃っている所)は凪ぐ = 温度が無いときと同じ運ばれ方</li>
 *     <li>満月の夜はふつうの夜の fullMoonMul 倍(3 倍)放出する</li>
 *     <li>上限(起点から maxRadiusChunks)の外の発芽は受け取らない</li>
 *     <li>Mob などのふだんの汚染は、ネザーの対応表どおりにブロックを変え、そのチャンクの段階の時計を始める</li>
 *     <li>境目のまだら: どの列も持ち主はちょうど 1 つで、持ち主の範囲はチャンクの四角ではない</li>
 * </ul>
 * 場の計算のテストはネザーの値の写し({@link NetherSporeFieldServer#netherParams})で場を作り、温度は差し替える(ワールドを読まない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherSporeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterNetherSporeGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(NetherSporeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int BASE = 18_000;
    /** 昼(地上の正午)。夜の放出の違いを避ける。 */
    private static final long NOON = 6000;

    private static SporeField field(SporeField.Thermal thermal) {
        SporeField f = new SporeField(7, NetherSporeFieldServer.netherParams());
        f.underground = false;
        f.thermal = thermal;
        return f;
    }

    private static void step(SporeField f, long tick, long dayTime) {
        f.step(new SporeField.Conditions(tick, dayTime, false, false, false), new PrevailingWind.Sample());
    }

    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void windBlowsFromColdToHot(GameTestHelper helper) {
        if (NetherSporeFieldServer.netherParams().thermalWindPerDay <= 0) {
            helper.fail("ネザーの場に寒暖差の風が無い(nether_spread.json の spores.thermalWindPerDay)");
            return;
        }
        // x < 0 が冷たい(森)、x ≥ 0 が熱い(溶岩の海)。冷たい側の縁 (−1, 0) に浮遊を置く
        SporeField f = field((cx, cz) -> cx < 0 ? 0f : 1f);
        f.emitPoint(-1, 0, 1.0);
        step(f, 1_000_000, NOON);
        double hot = f.air(0, 0), cold = f.air(-2, 0);
        if (!(hot > cold * 1.5)) {
            helper.fail(String.format("熱い隣へ多く運ばれていない: 熱い側 %.4f・冷たい側 %.4f", hot, cold));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void forestInteriorIsCalm(GameTestHelper helper) {
        // 温度が揃っている(森の奥)なら、風の強さを 0 にしたときと同じ運ばれ方(刻みの数は同じにして比べる)
        SporeField calm = field((cx, cz) -> 0.1f);
        SporeField.Params noWind = NetherSporeFieldServer.netherParams();
        noWind.thermalWindPerDay = 0;
        SporeField none = new SporeField(7, noWind);
        none.underground = false;
        none.thermal = (cx, cz) -> 0.1f;
        calm.emitPoint(0, 0, 1.0);
        none.emitPoint(0, 0, 1.0);
        step(calm, 1_000_000, NOON);
        step(none, 1_000_000, NOON);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}}) {
            double a = calm.air(d[0], d[1]), b = none.air(d[0], d[1]);
            if (Math.abs(a - b) > 1e-6 * Math.max(1, b)) {
                helper.fail(String.format("温度が揃っているのに風が吹いた: (%d, %d) %.6f と %.6f", d[0], d[1], a, b));
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void fullMoonNightReleasesMore(GameTestHelper helper) {
        SporeField.Params p = NetherSporeFieldServer.netherParams();
        double[] air = new double[2];
        long[] nights = {8L * PrevailingWind.DAY + 18000, 9L * PrevailingWind.DAY + 18000}; // 8 日目 = 満月の夜、9 日目 = ふつうの夜
        for (int k = 0; k < 2; k++) {
            SporeField f = field((cx, cz) -> 0.1f);
            long tick = 100L * PrevailingWind.DAY;
            f.setCorrupted(0, 0, tick - 10L * PrevailingWind.DAY); // 育ちきった升
            step(f, tick, nights[k]);
            double sum = 0;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) sum += f.air(dx, dz);
            air[k] = sum;
        }
        if (!SporeField.fullMoon(nights[0]) || SporeField.fullMoon(nights[1])) {
            helper.fail("満月の日の決め方が違う");
            return;
        }
        double ratio = air[0] / Math.max(1e-12, air[1]);
        if (Math.abs(ratio - p.fullMoonMul) > 0.25 * p.fullMoonMul) {
            helper.fail(String.format("満月の夜の放出がふつうの夜の %.2f 倍(%.1f 倍のはず)", ratio, p.fullMoonMul));
            return;
        }
        helper.succeed();
    }

    /** 満月の夜だけ発芽しやすい(しきい値 × fullMoonGermMul): しきい値に届かない降り積もりが、満月の夜には発芽し、ふつうの夜には発芽しない。 */
    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void fullMoonNightSproutsEasier(GameTestHelper helper) {
        SporeField.Params p = NetherSporeFieldServer.netherParams();
        if (p.fullMoonGermMul >= 1) {
            helper.fail("満月の夜の発芽の倍率が 1 以上(nether_spread.json の spores.fullMoonGermMul)");
            return;
        }
        double dep = p.germThreshold * (1 + p.fullMoonGermMul) / 2; // 満月のしきい値とふつうのしきい値の間
        boolean[] sprouted = new boolean[2];
        long[] nightStart = {8L * PrevailingWind.DAY + 13500, 9L * PrevailingWind.DAY + 13500};
        for (int k = 0; k < 2; k++) {
            SporeField f = field((cx, cz) -> 0.1f);
            long tick = 100L * PrevailingWind.DAY;
            // 発芽は確率なので、離れた 20 升で見る(1 升が 1 夜で発芽しない見込みは 2 割ほど)
            for (int s = 0; s < 90 && !sprouted[k]; s++) { // 夜 9000 tick
                for (int c = 0; c < 20; c++) {
                    f.depositPoint(c * 4, 5, Math.max(0, dep - f.deposited(c * 4, 5))); // 降り積もりを保つ
                }
                step(f, tick + s * p.stepTicks, nightStart[k] + s * p.stepTicks);
                for (int c = 0; c < 20; c++) sprouted[k] |= f.isCorrupted(c * 4, 5);
            }
        }
        if (!sprouted[0] || sprouted[1]) {
            helper.fail("満月の夜の発芽が違う: 満月 " + sprouted[0] + "・ふつう " + sprouted[1]);
            return;
        }
        helper.succeed();
    }

    /** 湖の一押し: 放出の倍率(湖のチャンク)がそのまま放出に掛かる。ネザーの値の倍率は 1 より大きい。 */
    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void lakeChunksEmitMore(GameTestHelper helper) {
        double mul = DesignData.netherSpread().lakeEmitMul;
        if (mul <= 1 || DesignData.netherSpread().lakeBoostMaxChunks <= 0) {
            helper.fail("湖の一押しが無い(nether_spread.json の lakeEmitMul・lakeBoostMaxChunks)");
            return;
        }
        double[] air = new double[2];
        for (int k = 0; k < 2; k++) {
            final float m = k == 0 ? (float) mul : 1f;
            SporeField f = field(new SporeField.Thermal() {
                public float temperature(int cx, int cz) {
                    return 0.1f;
                }

                public float emitFactor(int cx, int cz) {
                    return m;
                }
            });
            long tick = 100L * PrevailingWind.DAY;
            f.setCorrupted(0, 0, tick - 10L * PrevailingWind.DAY);
            step(f, tick, 9L * PrevailingWind.DAY + 18000); // ふつうの夜(放出が多い時刻で比べる)
            double sum = 0;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) sum += f.air(dx, dz);
            air[k] = sum;
        }
        double ratio = air[0] / Math.max(1e-12, air[1]);
        if (Math.abs(ratio - mul) > 0.25 * mul) {
            helper.fail(String.format("湖のチャンクの放出が %.2f 倍(%.1f 倍のはず)", ratio, mul));
            return;
        }
        helper.succeed();
    }

    /** 段階 1(繁茂の前)の放出(2026-11-12): 汚染されて間もない升も少し出し、時間とともに増え、段階 2 より少ない。オーバーワールドの値は 0 のまま。 */
    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void stage1EmitsALittle(GameTestHelper helper) {
        SporeField.Params p = NetherSporeFieldServer.netherParams();
        if (p.earlyEmit <= 0) {
            helper.fail("ネザーの段階 1 の放出が 0(nether_spread.json の spores.earlyEmit)");
            return;
        }
        if (DesignData.sporeField().field().earlyEmit != 0) {
            helper.fail("オーバーワールドの場に段階 1 の放出が入った");
            return;
        }
        long night = 9L * PrevailingWind.DAY + 18000;
        long tick = 100L * PrevailingWind.DAY;
        long span = (long) (p.daysToThriving * PrevailingWind.DAY);
        double[] air = new double[4];
        long[] ages = {0, span / 3, span * 9 / 10, span + 100}; // 起き立て・段階 1 の途中・段階 1 の終わり・段階 2
        for (int k = 0; k < ages.length; k++) {
            SporeField f = field((cx, cz) -> 0.1f);
            f.setCorrupted(0, 0, tick - ages[k]);
            step(f, tick, night);
            double sum = 0;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) sum += f.air(dx, dz);
            air[k] = sum;
        }
        if (!(air[1] > 0 && air[2] > air[1] && air[3] > air[2])) {
            helper.fail(String.format("段階 1 の放出が 0 から少しずつ増えて段階 2 より少なくなっていない: %.5f / %.5f / %.5f / %.5f", air[0], air[1], air[2], air[3]));
            return;
        }
        helper.succeed();
    }

    /** ネザーで点の放出・降り積もり(死骸・寄生体・汚染の球など)がネザーの場に入る(2026-11-12。前はオーバーワールドだけで、ネザーでは捨てていた)。 */
    @GameTest(template = "gametest_empty7", batch = "nether_spore", timeoutTicks = 100)
    public static void netherPointSourcesReachTheNetherField(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int cx = 1_250_000 + nether.getRandom().nextInt(1000) * 4, cz = -1_250_000;
        BlockPos pos = new BlockPos(cx * 16 + 8, 80, cz * 16 + 8);
        NetherSporeFieldServer.watchForTests(cx, cz);
        Double before = NetherSporeFieldServer.withFieldForTests(f -> f.air(cx, cz) + f.deposited(cx, cz));
        if (before == null) {
            helper.fail("ネザーの胞子の場が動いていない");
            return;
        }
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(nether, pos, 2.0);
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.depositPoint(nether, pos.east(16), 0.05); // しきい値より下(発芽して 0 に戻らないように)
        NetherSporeFieldServer.catchUpForTests(nether, DesignData.sporeField().updateTicks(), false); // 入力を取り込んで 1 回分
        double air = NetherSporeFieldServer.withFieldForTests(f -> {
            double s = 0;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) s += f.air(cx + dx, cz + dz) + f.low(cx + dx, cz + dz);
            return s;
        });
        double dep = NetherSporeFieldServer.withFieldForTests(f -> f.deposited(cx + 1, cz));
        NetherSporeFieldServer.withFieldForTests(f -> {
            f.extractAround(cx, cz, 3, Double.POSITIVE_INFINITY);
            f.clearDepositAround(cx, cz, 3);
            return null;
        });
        if (air <= 1e-4) {
            helper.fail("ネザーの点の放出が場に入らない: " + air);
            return;
        }
        if (dep <= 1e-4) {
            helper.fail("ネザーの点の降り積もりが場に入らない: " + dep);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void noSproutsBeyondTheLimit(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int r = DesignData.netherSpread().maxRadiusChunks;
        if (r <= 0) {
            helper.succeed(); // 上限なしの設定
            return;
        }
        int ox = BASE + nether.getRandom().nextInt(4000) * 8, oz = -BASE;
        NetherLedger.reachForTests(nether, new ChunkPos(ox, oz), 0);
        long inside = ChunkPos.asLong(ox + 5, oz), outside = ChunkPos.asLong(ox + r * 3, oz);
        long[] rejected = NetherLedger.acceptFieldSprouts(nether, new long[]{inside, outside});
        NetherLedgerData d = NetherLedgerData.get(nether);
        if (!d.isReached(inside)) {
            helper.fail("上限の中の発芽を受け取らない");
            return;
        }
        if (d.isReached(outside) || rejected.length != 1 || rejected[0] != outside) {
            helper.fail("上限の外の発芽を受け取った");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "nether_spore", timeoutTicks = 100)
    public static void ordinaryCorruptionStartsTheClock(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int ox = BASE + nether.getRandom().nextInt(4000) * 8, oz = -BASE - 400;
        NetherLedger.reachForTests(nether, new ChunkPos(ox, oz), 0); // 上限の中にする起点
        ChunkPos target = new ChunkPos(ox + 4, oz);
        nether.getChunk(target.x, target.z);
        BlockPos pos = target.getMiddleBlockPosition(100);
        nether.setBlock(pos, Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
        nether.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (NetherLedgerData.get(nether).isReached(target.toLong())) {
            helper.fail("前提: 汚染の前からそのチャンクが森になっている");
            return;
        }
        NoisyCorruptionBurst.single(nether, pos);
        if (!nether.getBlockState(pos).is(ModBlocks.OVERGROWN_NETHERRACK.get())) {
            helper.fail("ネザーラックが覆われたネザーラックにならない: " + nether.getBlockState(pos));
            return;
        }
        if (!NetherLedgerData.get(nether).isReached(target.toLong())) {
            helper.fail("ふだんの汚染で段階の時計が始まらない");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "nether_spore")
    public static void edgesAreNotChunkSquares(GameTestHelper helper) {
        var t = DesignData.netherSpread();
        if (t.edgeJitterBlocks <= 0) {
            helper.succeed(); // まだらを切った設定
            return;
        }
        long seed = 99;
        ChunkPos mid = new ChunkPos(3, -7);
        Long2IntOpenHashMap owners = new Long2IntOpenHashMap();
        int outside = 0, missing = 256;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos c = new ChunkPos(mid.x + dx, mid.z + dz);
                int[] cols = NetherJitter.ownedColumns(seed, c, t.edgeJitterBlocks, t.edgeJitterScale);
                for (int i = 0; i + 1 < cols.length; i += 2) {
                    long k = BlockPos.asLong(cols[i], 0, cols[i + 1]);
                    owners.addTo(k, 1);
                    if (dx == 0 && dz == 0) {
                        boolean in = (cols[i] >> 4) == mid.x && (cols[i + 1] >> 4) == mid.z;
                        if (in) missing--;
                        else outside++;
                    }
                }
            }
        }
        // 真ん中のチャンクの 16×16 の列は、どれも持ち主がちょうど 1 つ
        for (int x = mid.getMinBlockX(); x <= mid.getMaxBlockX(); x++) {
            for (int z = mid.getMinBlockZ(); z <= mid.getMaxBlockZ(); z++) {
                int n = owners.get(BlockPos.asLong(x, 0, z));
                if (n != 1) {
                    helper.fail("列 (" + x + ", " + z + ") の持ち主が " + n + " つ");
                    return;
                }
            }
        }
        if (outside == 0 || missing == 0) {
            helper.fail("持ち主の範囲がチャンクの四角のまま(外 " + outside + "・欠け " + missing + ")");
            return;
        }
        helper.succeed();
    }

    @javax.annotation.Nullable
    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("テスト用サーバーにネザーが無い");
        return nether;
    }

    private NetherSporeGameTests() {
    }
}
