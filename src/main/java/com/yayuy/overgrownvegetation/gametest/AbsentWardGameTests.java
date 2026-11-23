package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LedgerSpread;
import com.yayuy.overgrownvegetation.corruption.pushback.AbsentWards;
import com.yayuy.overgrownvegetation.corruption.pushback.AwayMeter;
import com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlockEntity;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.corruption.pushback.VentGuard;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import com.yayuy.overgrownvegetation.survival.vent.VentStock;
import com.yayuy.overgrownvegetation.survival.vent.Ventilators;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * 留守の間も杭・換気機が守る(11-23 夕方 ユーザー決定。{@link AbsentWards})と、換気機のホッパーの口・予備のフィルター。
 * 期待値は設計値({@code push_back.json} の {@code ring}・{@code vent}・{@code absent}、{@code spore_field.json} の {@code vent})から引く。
 *
 * <p>GameTest のチャンクはいつも読み込まれているので、「読み込まれていない機械」は遠く(3 万ブロック先)の機械の無い場所に覚えだけを置いて試し、
 * 時間が経った分は覚えの時刻を昔へずらして試す({@link AbsentWards#ageForTests})。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AbsentWardGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        // 1.20.1: 受け手のメソッドの名前はクラスごとに変える(Forge の EventBus は「クラスの単純名_メソッド名_イベントの単純名」で包みを作って使い回すので、同じパッケージの同じ名前の入れ子のクラスがぶつかり、前の受け手が 2 回呼ばれる)
        @SubscribeEvent
        public static void onAbsentWardGameTests(RegisterGameTestsEvent event) {
            event.register(AbsentWardGameTests.class);
        }

        private Registration() {
        }
    }

    private AbsentWardGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "absent_wards";

    /** テスト場から遠い、読み込まれていないチャンクの真ん中(杭・換気機の場所)。 */
    private static BlockPos farCenter(GameTestHelper helper, int salt) {
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int cx = ((o.getX() + 30000 + salt * 64) >> 4), cz = ((o.getZ() + 30000) >> 4);
        return new BlockPos(cx * 16 + 8, 64, cz * 16 + 8);
    }

    private static double stakeBaseDrain() {
        return DesignData.pushBack().ring.stakeFePerTick / WorldDifficultyData.pushbackEfficiency();
    }

    private static ItemStack fresh() {
        return new ItemStack(ModVent.AIR_FILTER.get());
    }

    private static ItemStack clogged() {
        ItemStack s = fresh();
        OvItemData.set(s, ModDataComponents.MASK_FILTER_USED, (int) Math.ceil(AirFilters.capacity(s)));
        return s;
    }

    // ---------------------------------------------------------------- (a)(b) 杭

    /** 読み込まれていない杭は、覚えた FE が残る間は範囲を守り(発芽を受け取らない)、経った時間の分だけ FE が減り、切れたら守らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void awayStakeGuardsUntilFeRunsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stake = farCenter(helper, 0);
        ChunkPos cp = new ChunkPos(stake);
        PhaseData pd = PhaseData.get(level);
        try {
            helper.assertTrue(level.getChunkSource().getChunkNow(cp.x, cp.z) == null, "遠くのチャンクが読み込まれている(試せない)");
            helper.assertTrue(!RingWards.stakeCoversChunk(level, cp.x, cp.z), "覚えを置く前から守っている");
            int e = 1000;
            double d = stakeBaseDrain();
            AbsentWards.Machine m = AbsentWards.putAwayForTests(level, stake, false, e, d);
            helper.assertTrue(RingWards.stakeCoversChunk(level, cp.x, cp.z), "留守の杭の範囲のチャンクを守らない");
            helper.assertTrue(RingWards.inStakeField(level, stake.offset(3, 0, 3)), "留守の杭の範囲のマスを守らない");
            long[] rejected = LedgerSpread.acceptFieldSprouts(level, new long[]{cp.toLong()});
            helper.assertTrue(rejected.length == 1 && !pd.isOvergrownChunk(cp), "留守の杭の範囲で発芽を受け取った");
            // 半分の時間: FE は経った分だけ減り、まだ守る
            long half = (long) Math.floor(e / d / 2.0D);
            AbsentWards.ageForTests(level, m, half);
            int expect = (int) Math.floor(e - d * half + 1.0E-9D);
            helper.assertTrue(m.energyAt(level.getGameTime()) == expect, "留守の FE の減りが違う: " + m.energyAt(level.getGameTime()) + "(期待 " + expect + ")");
            helper.assertTrue(RingWards.stakeCoversChunk(level, cp.x, cp.z), "FE が残るのに守らなくなった");
            // 止めた分も覚えから払う
            RingWards.resetCooldownForTests(level);
            int before = m.energyAt(level.getGameTime());
            helper.assertTrue(RingWards.blocks(level, stake.offset(2, 0, 0)), "留守の杭が汚染を止めない");
            int pay = (int) Math.ceil(DesignData.pushBack().ring.stakeFePerBlock / WorldDifficultyData.pushbackEfficiency());
            helper.assertTrue(before - m.energyAt(level.getGameTime()) == pay, "留守の杭が止めた分を払わない: " + (before - m.energyAt(level.getGameTime())));
            // 切れるまで経つ: 守らない(LedgerSpread はここで発芽を受け取る)
            AbsentWards.ageForTests(level, m, (long) Math.ceil(e / d) + 20);
            helper.assertTrue(m.energyAt(level.getGameTime()) == 0, "時間が経っても FE が減らない");
            helper.assertTrue(!RingWards.stakeCoversChunk(level, cp.x, cp.z), "FE が切れた留守の杭がまだ守っている");
            helper.assertTrue(!RingWards.inStakeField(level, stake.offset(3, 0, 3)), "FE が切れた留守の杭の範囲のマスを守っている");
            helper.succeed();
        } finally {
            AbsentWards.clearForTests(level, stake);
            pd.forgetOvergrownChunk(cp);
        }
    }

    /**
     * 発電機から足りていた杭・換気機は留守でも切れない: 本物の口から 40 FE/t(汲み上げ器 1 台・線の真上)を入れ続け、途中で杭が 1 回止めて払い、
     * 換気機はフィルターを抜いて止まる間もある。窓を 2 回以上埋めてから留守にして 3 日経たせる。入れなかった杭は切れる。
     * 発電機のつながりと窓の長さはテスト用に決め打ち(自分だけのバッチ)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH + "_fed", timeoutTicks = 200)
    public static void fedMachinesDoNotRunOutAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AwayMeter.windowOverrideForTests = 40;
        AwayMeter.linkedOverrideForTests = true;
        BlockPos fedRel = new BlockPos(1, 2, 1), dryRel = new BlockPos(1, 2, 5), ventRel = new BlockPos(4, 2, 4);
        helper.setBlock(fedRel, ModPushBack.LEY_STAKE.get().defaultBlockState());
        helper.setBlock(dryRel, ModPushBack.LEY_STAKE.get().defaultBlockState());
        helper.setBlock(ventRel, ModVent.VENTILATOR.get().defaultBlockState());
        LeyStakeBlockEntity fed = (LeyStakeBlockEntity) helper.getBlockEntity(fedRel);
        LeyStakeBlockEntity dry = (LeyStakeBlockEntity) helper.getBlockEntity(dryRel);
        VentBlockEntity vent = (VentBlockEntity) helper.getBlockEntity(ventRel);
        dry.setEnergy(DesignData.pushBack().ring.stakeBuffer);
        vent.setFilter(fresh());
        final int[] tick = {0};
        helper.onEachTick(() -> {
            int t = tick[0]++;
            fed.energyPort().receiveEnergy(40, false);
            vent.energyPort().receiveEnergy(40, false);
            if (t == 30) {
                RingWards.resetCooldownForTests(level);
                RingWards.blocks(level, helper.absolutePos(new BlockPos(2, 2, 2))); // 止めて払う
            }
            if (t == 60) vent.setFilter(ItemStack.EMPTY); // 止まる間(FE を使わない)
            if (t == 75) vent.setFilter(fresh());
        });
        helper.runAtTickTime(150, () -> {
            try {
                fed.mirrorForTests();
                dry.mirrorForTests();
                vent.mirrorForTests();
                AbsentWards.Machine mf = AbsentWards.find(level, fed.getBlockPos(), false);
                AbsentWards.Machine md = AbsentWards.find(level, dry.getBlockPos(), false);
                AbsentWards.Machine mv = AbsentWards.find(level, vent.getBlockPos(), true);
                helper.assertTrue(mf.supplied && mv.supplied, "入れ続けた杭・換気機が足りている扱いにならない: 杭 " + mf.supplied + "・換気機 " + mv.supplied
                        + "(FE " + fed.energy() + "・" + vent.energy() + ")");
                helper.assertTrue(!md.supplied, "入れなかった杭が足りている扱い");
                int ef = mf.energyAt(level.getGameTime());
                long days = 24000L * 3;
                AbsentWards.ageForTests(level, mf, days);
                AbsentWards.ageForTests(level, md, days);
                AbsentWards.ageForTests(level, mv, days);
                long now = level.getGameTime();
                helper.assertTrue(mf.guarding(now) && mf.energyAt(now) == ef, "足りていた杭が留守の間に減った・切れた");
                helper.assertTrue(mv.guarding(now), "足りていた換気機が留守の間に切れた");
                helper.assertTrue(!md.guarding(now) && md.energyAt(now) == 0, "入れなかった杭が留守の 3 日で切れない");
                helper.succeed();
            } finally {
                AwayMeter.windowOverrideForTests = 0;
                AwayMeter.linkedOverrideForTests = null;
            }
        });
    }

    // ---------------------------------------------------------------- (a)(b) 換気機

    /**
     * 読み込まれていない換気機は、FE と詰まっていないフィルターがある間は範囲で発芽させない。場から吸うとフィルターが詰まり、予備に替わり、
     * 詰まった物は置き場へ。予備も尽きる・FE が切れると守らない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void awayVentGuardsAndUsesSpares(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos vent = farCenter(helper, 2);
        ChunkPos cp = new ChunkPos(vent);
        SporeFieldTable t = DesignData.sporeField();
        int fe = t.vent().fePerTick();
        try {
            int e = fe * 1000;
            AbsentWards.Machine m = AbsentWards.putAwayForTests(level, vent, true, e, fe);
            m.stock.filter = fresh();
            m.stock.spares[0] = fresh();
            VentGuard.invalidate();
            helper.assertTrue(VentGuard.covers(level, cp.x, cp.z), "留守の換気機のチャンクを守らない");
            long[] rejected = LedgerSpread.acceptFieldSprouts(level, new long[]{cp.toLong()});
            helper.assertTrue(rejected.length == 1, "留守の換気機の範囲で発芽を受け取った");
            // 場の計算に留守の機械も入る(抜ける量はフィルターの残りで切る)
            Ventilators.Snapshot snap = Ventilators.snapshot(level, 1, t);
            int idx = -1;
            for (int i = 0; i < snap.pos().length; i++) if (snap.pos()[i] == vent.asLong()) idx = i;
            helper.assertTrue(idx >= 0 && Math.abs(snap.budget()[idx] - t.vent().capPerUpdate()) < 1e-9, "留守の換気機が場の計算に入らない");
            // 1 枚目を詰まらせる → 予備に替わり、詰まった物は置き場へ(FE は今まで分を減らす)
            AbsentWards.ageForTests(level, m, 100);
            double units = AirFilters.capacity(m.stock.filter) / VentBlockEntity.loadPerUnit(t) + 1.0D;
            int rev = m.revision;
            AbsentWards.applyAway(level, vent, units, 1, t);
            helper.assertTrue(m.revision != rev, "留守の間のフィルターの変化が覚えの版に出ない");
            helper.assertTrue(m.stock.cloggedFilled() == 1 && m.stock.spareFilled() == 0 && !m.stock.filter.isEmpty()
                    && !AirFilters.clogged(m.stock.filter), "詰まった物が置き場へ移り予備が差さらない");
            helper.assertTrue(m.energy == e - fe * 100, "留守の FE が経った分だけ減らない: " + m.energy + "(期待 " + (e - fe * 100) + ")");
            VentGuard.invalidate();
            helper.assertTrue(VentGuard.covers(level, cp.x, cp.z), "予備に替わったのに守らない");
            // 2 枚目も詰まる → 予備なし: 守らない(FE は減らない)
            AbsentWards.applyAway(level, vent, units, 1, t);
            int left = m.energyAt(level.getGameTime());
            AbsentWards.ageForTests(level, m, 1000);
            helper.assertTrue(m.energyAt(level.getGameTime()) == left, "フィルターが尽きて止まったのに FE が減る");
            VentGuard.invalidate();
            helper.assertTrue(!VentGuard.covers(level, cp.x, cp.z), "フィルターが尽きた留守の換気機がまだ守っている");
            // 新しいフィルターでも FE が切れれば守らない
            m.stock.clear();
            m.stock.filter = fresh();
            AbsentWards.ageForTests(level, m, (long) (m.energy / fe) + 5);
            VentGuard.invalidate();
            helper.assertTrue(!VentGuard.covers(level, cp.x, cp.z), "FE が切れた留守の換気機がまだ守っている");
            helper.succeed();
        } finally {
            AbsentWards.clearForTests(level, vent);
            VentGuard.invalidate();
        }
    }

    // ---------------------------------------------------------------- (c) ホッパー

    /** 上のホッパーから予備が入り、差してある 1 枚が詰まると予備に替わり、詰まった物は下のホッパーへ出る。口の向きの決まりも。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void hopperFeedsSparesAndTakesClogged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModVent.VENTILATOR.get().defaultBlockState());
        VentBlockEntity be = (VentBlockEntity) helper.getBlockEntity(rel);
        be.setFilter(fresh());
        // 口の向き: 下は出すだけ(予備は見えない)・横と上は入れるだけ・詰まった物は入らない
        IItemHandler side = be.itemPort(Direction.NORTH);
        IItemHandler down = be.itemPort(Direction.DOWN);
        helper.assertTrue(side.getSlots() == VentStock.spareCount() && down.getSlots() == VentStock.cloggedCount(),
                "口の枠の数が違う: 横 " + side.getSlots() + "・下 " + down.getSlots());
        helper.assertTrue(side.insertItem(0, clogged(), true).getCount() == 1, "詰まったフィルターを予備に入れられる");
        helper.assertTrue(down.insertItem(0, fresh(), true).getCount() == 1, "下の口から入れられる");
        helper.assertTrue(VentStock.spareCount() == 4, "予備の数がユーザー決定の 4 でない: " + VentStock.spareCount());
        // 上のホッパー(一番上の部位の上)に新しいフィルター 2 枚、下のホッパーは空
        BlockPos top = rel.above(3), bottom = rel.below();
        helper.setBlock(top, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        helper.setBlock(bottom, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        ((HopperBlockEntity) helper.getBlockEntity(top)).setItem(0, new ItemStack(ModVent.AIR_FILTER.get(), 2));
        final boolean[] clogNow = {false};
        helper.succeedWhen(() -> {
            if (!clogNow[0]) {
                helper.assertTrue(be.stock().spareFilled() == 2, "上のホッパーから予備が入らない: " + be.stock().spareFilled());
                be.setFilter(clogged()); // 差してある 1 枚が詰まった
                clogNow[0] = true;
            }
            HopperBlockEntity h = (HopperBlockEntity) helper.getBlockEntity(bottom);
            boolean out = false;
            for (int i = 0; i < h.getContainerSize(); i++) if (AirFilters.isFilter(h.getItem(i)) && AirFilters.clogged(h.getItem(i))) out = true;
            helper.assertTrue(out, "詰まったフィルターが下のホッパーへ出ない");
            helper.assertTrue(!be.filter().isEmpty() && !AirFilters.clogged(be.filter()) && be.stock().spareFilled() == 1
                    && be.stock().cloggedFilled() == 0, "予備に替わらない");
        });
    }

    // ---------------------------------------------------------------- (d) 精算が二重にならない

    /** 換気機: 留守の分を精算する(FE・留守の間に詰まったフィルター)。もう一度呼んでも、保存して開き直しても減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ventSettlesOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModVent.VENTILATOR.get().defaultBlockState());
        VentBlockEntity be = (VentBlockEntity) helper.getBlockEntity(rel);
        SporeFieldTable t = DesignData.sporeField();
        int fe = t.vent().fePerTick();
        be.energyPort().receiveEnergy(fe * 50, false);
        be.setFilter(fresh());
        be.stock().spares[0] = fresh();
        be.mirrorForTests();
        AbsentWards.Machine m = AbsentWards.find(level, be.getBlockPos(), true);
        helper.assertTrue(m != null && !m.isAway(level.getGameTime()), "動いている換気機の覚えが無い");
        int e0 = be.energy();
        AbsentWards.ageForTests(level, m, 20);
        AbsentWards.applyAway(level, be.getBlockPos(), AirFilters.capacity(fresh()) / VentBlockEntity.loadPerUnit(t) + 1.0D, 1, t);
        be.settleIfAway();
        int e1 = be.energy();
        helper.assertTrue(e1 == e0 - fe * 20, "精算の FE が違う: " + e0 + " → " + e1 + "(期待 " + (e0 - fe * 20) + ")");
        helper.assertTrue(be.stock().cloggedFilled() == 1 && be.stock().spareFilled() == 0 && !AirFilters.clogged(be.filter()),
                "留守の間に替わったフィルターが精算で戻らない");
        be.settleIfAway();
        helper.assertTrue(be.energy() == e1 && be.stock().cloggedFilled() == 1, "2 回目の精算で変わった");
        // 保存して開き直す: 覚え(保存データ)を読み直すと留守の印が付くが、時間は経っていないので減らない
        CompoundTag tag = be.saveWithFullMetadata();
        AbsentWards.reloadForTests(level);
        be.load(tag);
        helper.assertTrue(AbsentWards.find(level, be.getBlockPos(), true).isAway(level.getGameTime()), "読み直した覚えが留守にならない");
        be.settleIfAway();
        helper.assertTrue(be.energy() == e1 && be.stock().cloggedFilled() == 1 && be.stock().spareFilled() == 0,
                "保存して開き直したら精算が二重になった: FE " + be.energy());
        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    /** 杭: 留守の分(立っているだけの分)を精算する。もう一度・保存して開き直しても減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void stakeSettlesOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModPushBack.LEY_STAKE.get().defaultBlockState());
        LeyStakeBlockEntity be = (LeyStakeBlockEntity) helper.getBlockEntity(rel);
        be.setEnergy(5000);
        be.mirrorForTests();
        AbsentWards.Machine m = AbsentWards.find(level, be.getBlockPos(), false);
        helper.assertTrue(m != null, "杭の覚えが無い");
        long span = 100;
        AbsentWards.ageForTests(level, m, span);
        helper.assertTrue(be.awayTick(level), "留守だった杭を精算しない");
        int expect = (int) Math.floor(5000 - m.drainPerTick * span + 1.0E-9D);
        helper.assertTrue(Math.abs(m.drainPerTick - stakeBaseDrain()) < 1e-9, "留守の杭の使う量が立っているだけの分でない: " + m.drainPerTick);
        helper.assertTrue(be.energy() == expect, "精算の FE が違う: " + be.energy() + "(期待 " + expect + ")");
        helper.assertTrue(!be.awayTick(level) && be.energy() == expect, "2 回目の精算で変わった");
        CompoundTag tag = be.saveWithFullMetadata();
        AbsentWards.reloadForTests(level);
        be.load(tag);
        be.awayTick(level);
        helper.assertTrue(be.energy() == expect, "保存して開き直したら精算が二重になった: " + be.energy());
        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    // ---------------------------------------------------------------- (e) 壊したら忘れる

    /** 杭・換気機(上の部位を壊しても)を壊すと覚えが消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void breakingForgetsTheRecord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stakeRel = new BlockPos(1, 2, 1), ventRel = new BlockPos(4, 2, 4);
        helper.setBlock(stakeRel, ModPushBack.LEY_STAKE.get().defaultBlockState());
        helper.setBlock(ventRel, ModVent.VENTILATOR.get().defaultBlockState());
        LeyStakeBlockEntity stake = (LeyStakeBlockEntity) helper.getBlockEntity(stakeRel);
        VentBlockEntity vent = (VentBlockEntity) helper.getBlockEntity(ventRel);
        stake.mirrorForTests();
        vent.mirrorForTests();
        helper.assertTrue(AbsentWards.find(level, stake.getBlockPos(), false) != null && AbsentWards.find(level, vent.getBlockPos(), true) != null,
                "置いた杭・換気機の覚えが無い");
        helper.setBlock(stakeRel, Blocks.AIR);
        helper.setBlock(ventRel.above(2), Blocks.AIR); // 一番上の部位を壊す → 本体も消える
        helper.assertTrue(AbsentWards.find(level, helper.absolutePos(stakeRel), false) == null, "壊した杭の覚えが残っている");
        helper.assertTrue(AbsentWards.find(level, helper.absolutePos(ventRel), true) == null, "壊した換気機の覚えが残っている");
        helper.succeed();
    }
}
