package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyFlowerBlock;
import com.yayuy.overgrownvegetation.block.LeyMeterBlockEntity;
import com.yayuy.overgrownvegetation.block.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.item.SporeMeterItem;
import com.yayuy.overgrownvegetation.phase.LeyOmen;
import com.yayuy.overgrownvegetation.phase.LeyOmenAvoidVeinGoal;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 地脈技術の 4 段目(2026-10-24): F1 の前の前兆・未知の試料・地脈計・胞子濃度計。
 * このバッチの間だけ前兆を強制オンにする({@link BeforeBatch} / {@link AfterBatch}。バッチは順番に走るので、ほかのバッチは前兆を見ない)。
 * <ul>
 *     <li>前兆の間は汲み上げ器の効率が 5 秒ごとに ±40% の範囲で揺れる({@link #pumpEfficiencyJittersDuringOmen})。</li>
 *     <li>地脈の花が前兆の間にしおれ、前兆が終わると戻る。しおれた花の品は状態を持ち、研究ポイントは半分({@link #flowerWiltsAndRecovers})。</li>
 *     <li>汲み上げ器の雫のおよそ 5% が未知の試料になり、素手の右クリックで雫と一緒に出てくる。試料は研究ポイント 5、
 *     ノード「未知の試料」が胞子濃度計を、「地脈技術」が地脈計を開く({@link #pumpMakesUnknownSamples})。</li>
 *     <li>地脈計の値は線の上で 100%、置いた計器は場所の値を覚え、前兆の間は不安定({@link #leyMeterReadsFullOnTheLine})。</li>
 *     <li>胞子濃度計が持っている間の値を返す({@link #sporeMeterReturnsReadings})。</li>
 *     <li>家畜が離れる向きは線から遠ざかる向き・軽い地鳴りの震源は線の上({@link #animalsLeaveAndQuakesSitOnTheLine})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyOmenGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyOmenGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "ley_omen";
    private static LeyOmen.Mode before = LeyOmen.Mode.AUTO;

    @BeforeBatch(batch = BATCH)
    public static void omenOn(ServerLevel level) {
        before = LeyOmen.override(level);
        LeyOmen.setOverride(level, LeyOmen.Mode.ON);
    }

    @AfterBatch(batch = BATCH)
    public static void omenBack(ServerLevel level) {
        LeyOmen.setOverride(level, before);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void pumpEfficiencyJittersDuringOmen(GameTestHelper helper) {
        if (!LeyOmen.isActive()) {
            helper.fail("強制オンなのに前兆になっていない");
            return;
        }
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_PUMP.get());
        LeyPumpBlockEntity pump = (LeyPumpBlockEntity) helper.getBlockEntity(rel);
        BlockPos pos = helper.absolutePos(rel);
        double jitter = DesignData.phases().omen().pumpJitter();
        int window = DesignData.phases().omen().pumpJitterTicks();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            double j = LeyOmen.pumpJitter(pos, (long) i * window);
            if (j < 1.0D - jitter - 1.0E-9D || j > 1.0D + jitter + 1.0E-9D) {
                helper.fail("揺れが ±" + jitter + " を越えた: " + j);
                return;
            }
            if (LeyOmen.pumpJitter(pos, (long) i * window + window - 1) != j) {
                helper.fail("同じ 5 秒の窓の中で揺れが変わった");
                return;
            }
            min = Math.min(min, j);
            max = Math.max(max, j);
        }
        if (min > 0.85D || max < 1.15D) {
            helper.fail("揺れが小さすぎる: " + min + "〜" + max);
            return;
        }
        long now = helper.getLevel().getGameTime();
        double expect = Math.max(LeyPumpBlockEntity.MIN_EFFICIENCY, pump.efficiency() * LeyOmen.pumpJitter(pos, now));
        if (Math.abs(pump.currentEfficiency() - expect) > 1.0E-9D) {
            helper.fail("今の効率に揺れが掛かっていない: " + pump.currentEfficiency() + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void flowerWiltsAndRecovers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = new BlockPos(2, 1, 2);
        helper.setBlock(ground, Blocks.GRASS_BLOCK);
        helper.setBlock(ground.above(), ModBlocks.LEY_FLOWER.get());
        BlockPos abs = helper.absolutePos(ground.above());
        RandomSource random = RandomSource.create(7L);
        // ブロックのランダム tick(前兆はオン)で、いずれしおれる
        for (int i = 0; i < 400 && !level.getBlockState(abs).getValue(LeyFlowerBlock.WILTED); i++) {
            level.getBlockState(abs).randomTick(level, abs, random);
        }
        BlockState wilted = level.getBlockState(abs);
        if (!wilted.is(ModBlocks.LEY_FLOWER.get()) || !wilted.getValue(LeyFlowerBlock.WILTED)) {
            helper.fail("前兆の間にランダム tick 400 回でしおれない: " + wilted);
            return;
        }
        // 前兆でなければ戻る(移り変わりの関数で。前兆はこのバッチの間ずっとオン)
        BlockState s = wilted;
        for (int i = 0; i < 400 && s.getValue(LeyFlowerBlock.WILTED); i++) {
            s = LeyFlowerBlock.wiltStep(s, false, 1.0D, random);
        }
        if (s.getValue(LeyFlowerBlock.WILTED)) {
            helper.fail("前兆が終わっても戻らない");
            return;
        }
        if (LeyFlowerBlock.wiltStep(wilted.setValue(LeyFlowerBlock.WILTED, false), false, 1.0D, RandomSource.create(1L))
                .getValue(LeyFlowerBlock.WILTED)) {
            helper.fail("前兆でないのにしおれた");
            return;
        }
        // 壊したときの品: しおれた花は状態を持ち、研究ポイントは半分。元の花は状態を持たず満点
        List<ItemStack> wiltedDrops = Block.getDrops(wilted, level, abs, null);
        List<ItemStack> freshDrops = Block.getDrops(wilted.setValue(LeyFlowerBlock.WILTED, false), level, abs, null);
        if (wiltedDrops.size() != 1 || !wiltedDrops.get(0).is(ModItems.LEY_FLOWER.get()) || !LeyFlowerBlock.isWilted(wiltedDrops.get(0))) {
            helper.fail("しおれた花の品が違う: " + wiltedDrops);
            return;
        }
        if (freshDrops.size() != 1 || LeyFlowerBlock.isWilted(freshDrops.get(0))
                || freshDrops.get(0).has(net.minecraft.core.component.DataComponents.BLOCK_STATE)) {
            helper.fail("元の花の品に状態が付いた(ふつうの花と重ならない): " + freshDrops);
            return;
        }
        int full = DesignData.research().pointsFor(freshDrops.get(0));
        int half = DesignData.research().pointsFor(wiltedDrops.get(0));
        if (half != full / 2 || full <= 0) {
            helper.fail("しおれた花のポイントが半分でない: " + half + " / " + full);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void pumpMakesUnknownSamples(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_PUMP.get());
        LeyPumpBlockEntity pump = (LeyPumpBlockEntity) helper.getBlockEntity(rel);
        if (!LeyOmen.samplesOpen()) {
            helper.fail("前兆の間なのに試料が混じらない時期");
            return;
        }
        int n = 2000;
        for (int i = 0; i < n; i++) {
            pump.addDrop(level);
        }
        int samples = pump.samples();
        double chance = DesignData.phases().omen().sampleChance();
        double mean = n * chance, sd = Math.sqrt(n * chance * (1.0D - chance));
        if (Math.abs(samples - mean) > 4.0D * sd) {
            helper.fail("試料の割合が違う: " + samples + " / " + n + "(期待 " + mean + " ± " + 4.0D * sd + ")");
            return;
        }
        // 素手の右クリックで雫と一緒に試料も出てくる
        pump.setStored(3);
        BlockPos pos = helper.absolutePos(rel);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        level.getBlockState(pos).useWithoutItem(level, player, hit);
        int gotSamples = player.getInventory().countItem(ModItems.UNKNOWN_SAMPLE.get());
        int gotDrops = player.getInventory().countItem(ModItems.LEY_DROP.get());
        if (gotSamples != samples || gotDrops != 3 || pump.stored() != 0) {
            helper.fail("取り出しが違う: 試料 " + gotSamples + "(期待 " + samples + ")・雫 " + gotDrops + "・中に残る " + pump.stored());
            return;
        }
        // 研究: 試料は 5 点、「未知の試料」が胞子濃度計を、「地脈技術」が地脈計を開く
        var research = DesignData.research();
        if (research.pointsFor(new ItemStack(ModItems.UNKNOWN_SAMPLE.get())) != 5) {
            helper.fail("未知の試料の研究ポイントが 5 でない: " + research.pointsFor(new ItemStack(ModItems.UNKNOWN_SAMPLE.get())));
            return;
        }
        String meterNode = research.nodeForRecipe(OvergrownVegetation.id("spore_meter"));
        String leyMeterNode = research.nodeForRecipe(OvergrownVegetation.id("ley_meter"));
        var node = research.node("unknown_sample");
        if (!"unknown_sample".equals(meterNode) || !"ley_technology".equals(leyMeterNode) || node == null || node.cost() != 5) {
            helper.fail("研究のつながりが違う: 胞子濃度計 = " + meterNode + "・地脈計 = " + leyMeterNode + "・ノード " + node);
            return;
        }
        for (String id : new String[]{"spore_meter", "ley_meter"}) {
            ResourceLocation rid = OvergrownVegetation.id(id);
            if (level.getRecipeManager().byKey(rid).isEmpty()) {
                helper.fail("レシピ " + rid + " が無い");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void leyMeterReadsFullOnTheLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int[] p = LeyVeinField.nearestLinePoint(seed, origin.getX(), origin.getZ(), 400);
        if (p == null) {
            helper.fail("近くに地脈の線が無い");
            return;
        }
        // 線の真ん中(線までの距離がいちばん小さい整数の点)を近くで探す
        int bx = p[0], bz = p[1];
        double best = LeyVeinField.distanceToLine(seed, bx, bz);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = LeyVeinField.distanceToLine(seed, p[0] + dx, p[1] + dz);
                if (d < best) {
                    best = d;
                    bx = p[0] + dx;
                    bz = p[1] + dz;
                }
            }
        }
        double onLine = LeyPumpBlockEntity.efficiencyAt(level, new BlockPos(bx, 64, bz));
        if (Math.round(onLine * 100.0D) != 100) {
            helper.fail("線の上で 100% にならない: " + onLine * 100.0D + "%(線まで " + best + ")");
            return;
        }
        // 置いた計器は場所の値を覚え、前兆の間は不安定
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_METER.get());
        LeyMeterBlockEntity meter = (LeyMeterBlockEntity) helper.getBlockEntity(rel);
        meter.refreshNow();
        double here = LeyPumpBlockEntity.efficiencyAt(level, helper.absolutePos(rel));
        if (Math.abs(meter.flow() - here) > 1.0E-9D || !meter.isUnstable()) {
            helper.fail("地脈計の値が違う: " + meter.flow() + "(期待 " + here + ")・不安定 " + meter.isUnstable());
            return;
        }
        double r = meter.reading();
        double jitter = DesignData.phases().omen().pumpJitter();
        if (r < here * (1.0D - jitter) - 1.0E-9D || r > here * (1.0D + jitter) + 1.0E-9D) {
            helper.fail("前兆の間の読みが揺れの幅の外: " + r + "(" + here + ")");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporeMeterReturnsReadings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        double[] v = SporeMeterItem.readings(level, pos);
        if (v.length < 1 || v.length > 2) {
            helper.fail("値の数が違う: " + v.length);
            return;
        }
        for (double x : v) {
            if (!Double.isFinite(x) || x < 0.0D) {
                helper.fail("値がおかしい: " + java.util.Arrays.toString(v));
                return;
            }
        }
        String line = SporeMeterItem.heldLine(level, pos).getString();
        if (line.isBlank()) {
            helper.fail("持っている間の表示が空");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void animalsLeaveAndQuakesSitOnTheLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int[] p = LeyVeinField.nearestLinePoint(seed, origin.getX(), origin.getZ(), 400);
        if (p == null) {
            helper.fail("近くに地脈の線が無い");
            return;
        }
        // 線の近く(真ん中から 4 ブロックほど)で、離れる向きへ STEP 進むと線から遠くなる
        int tested = 0;
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                int x = p[0] + dx, z = p[1] + dz;
                double d = LeyVeinField.distanceToLine(seed, x, z);
                if (d < 2.0D || d > LeyOmenAvoidVeinGoal.avoidDistance() - 1.0D) {
                    continue;
                }
                Vec3 away = LeyOmenAvoidVeinGoal.awayFromLine(seed, x, z);
                if (away == null) {
                    helper.fail("線の近く(" + d + ")なのに離れる向きが出ない");
                    return;
                }
                double after = LeyVeinField.distanceToLine(seed, x + (int) Math.round(away.x * LeyOmenAvoidVeinGoal.STEP), z + (int) Math.round(away.z * LeyOmenAvoidVeinGoal.STEP));
                if (after <= d) {
                    helper.fail("離れる向きへ進んでも線から遠くならない: " + d + " → " + after);
                    return;
                }
                tested++;
            }
        }
        double far = DesignData.climax().leyVein().cellSize() * 0.4D;
        if (tested == 0) {
            helper.fail("確かめる点が無かった");
            return;
        }
        // 軽い地鳴りの震源: 線の点の近くから 16〜48 ブロックで探すと、線の上
        BlockPos at = LeyOmen.findLinePointNear(level, new BlockPos(p[0] + 20, 64, p[1]), 16, 48, RandomSource.create(3L));
        if (at == null || !LeyVeinField.isVeinAt(seed, at.getX(), at.getZ())) {
            helper.fail("地鳴りの震源が線の上でない: " + at);
            return;
        }
        if (LeyOmenAvoidVeinGoal.awayFromLine(seed, p[0] + (int) far, p[1]) != null
                && LeyVeinField.distanceToLine(seed, p[0] + (int) far, p[1]) > LeyOmenAvoidVeinGoal.avoidDistance()) {
            helper.fail("線から遠い所でも離れようとする");
            return;
        }
        helper.succeed();
    }
}
