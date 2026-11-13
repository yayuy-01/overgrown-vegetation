package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.LeySpec;
import com.yayuy.overgrownvegetation.ley.LeyBatteryHolder;
import com.yayuy.overgrownvegetation.ley.LeyBatteryItem;
import com.yayuy.overgrownvegetation.ley.LeyFlowerSpread;
import com.yayuy.overgrownvegetation.ley.LeyGrid;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyPylonBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyWires;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;

/**
 * 地脈の最小限(2026-11-13 サバイバルの背骨 段 2b)。線の場・汲み上げ器の効率・電池・柱と線の網・線の決まり・保存・花の増え方。
 *
 * <p>汲み上げ器の効率は座標で決まるので、線の上と線から遠い所を探して置く必要がある。テスト場(7×7)では選べないので、
 * テスト場から 3000 ブロック以上離れた<b>遠くの専用チャンク</b>の空中(y = 200)を使う(置く前に更地へ戻す。gametest-far-chunk-entities)。
 * GameTest の世界はシード 0。期待値は設計値({@code spore_field.json} の {@code ley})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyGameTests.class);
        }

        private Registration() {
        }
    }

    private LeyGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_ley";
    private static final int Y = 200;

    private static LeySpec spec() {
        return DesignData.sporeField().ley();
    }

    /** テスト場から {@code offset} ブロック離れた所の起点(テストごとに違う offset を使う)。 */
    private static BlockPos far(GameTestHelper helper, int offset) {
        BlockPos a = helper.absolutePos(BlockPos.ZERO);
        return new BlockPos(a.getX() + 3000 + offset, Y, a.getZ() + 3000);
    }

    /** 起点から輪を広げて、線までの距離が {@code max} 以下の列を探す。 */
    private static BlockPos findNear(ServerLevel level, BlockPos origin, double max) {
        long seed = level.getSeed();
        for (int r = 0; r <= 400; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r
                            && LeyLines.distanceToLine(seed, origin.getX() + dx, origin.getZ() + dz) <= max) {
                        return new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                    }
                }
            }
        }
        throw new IllegalStateException("線が見つからない: " + origin);
    }

    /** 起点から輪を広げて、線までの距離が {@code min} 以上の列を探す。 */
    private static BlockPos findFar(ServerLevel level, BlockPos origin, double min) {
        long seed = level.getSeed();
        for (int r = 0; r <= 400; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r
                            && LeyLines.distanceToLine(seed, origin.getX() + dx, origin.getZ() + dz) >= min) {
                        return new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                    }
                }
            }
        }
        throw new IllegalStateException("線から遠い所が見つからない: " + origin);
    }

    /** {@code center} のまわり半径 r を、y = Y−1〜Y+2 だけ空気に戻す(チャンクを読む)。 */
    private static void clear(ServerLevel level, BlockPos center, int r) {
        for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
            for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, 2, r))) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }

    private static <T extends BlockEntity> T place(ServerLevel level, BlockPos pos, BlockState state, Class<T> type) {
        level.setBlock(pos, state, 3);
        BlockEntity be = level.getBlockEntity(pos);
        if (!type.isInstance(be)) {
            throw new IllegalStateException("中身が無い: " + pos + " " + be);
        }
        return type.cast(be);
    }

    private static void fill(LeyPumpBlockEntity pump, int atLeast) {
        for (int i = 0; i < 2_000_000 && pump.energy() < atLeast; i++) {
            if (pump.generate() == 0 && pump.energy() >= spec().pumpBuffer()) {
                break;
            }
        }
    }

    // ---- 線の場 ----

    /** 同じシードなら同じ線(何度測っても・別の起点から来ても同じ値)。別のシードでは違う。網目なので、ある範囲には線の上も線から遠い所もある。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sameSeedSameLines(GameTestHelper helper) {
        long seed = 1234567L;
        int onLine = 0;
        int farOff = 0;
        int differ = 0;
        for (int x = -300; x <= 300; x += 7) {
            for (int z = -300; z <= 300; z += 7) {
                double a = LeyLines.distanceToLine(seed, x, z);
                double b = LeyLines.distanceToLine(seed, x, z);
                helper.assertTrue(a == b, "同じシード・同じ座標で値が違う: " + a + " / " + b);
                if (a <= 2.0) {
                    onLine++;
                }
                if (a >= 50.0) {
                    farOff++;
                }
                if (Math.abs(LeyLines.distanceToLine(seed + 1, x, z) - a) > 1.0e-9) {
                    differ++;
                }
            }
        }
        helper.assertTrue(onLine > 0 && farOff > 0, "線の上 " + onLine + "・遠い所 " + farOff);
        helper.assertTrue(differ > 100, "シードを変えても線が変わらない: " + differ);
        helper.succeed();
    }

    // ---- 汲み上げ器 ----

    /** 線の真上の汲み上げ器は設計値どおり(効率 ≒ 100%)、線から遠い所は少なく汲む。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void pumpYieldsMoreOnLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LeySpec spec = spec();
        BlockPos base = far(helper, 0);
        BlockPos onLine = findNear(level, base, 0.6);
        BlockPos offLine = findFar(level, base, spec.pumpReach() * 0.9);
        clear(level, onLine, 1);
        clear(level, offLine, 1);
        LeyPumpBlockEntity near = place(level, onLine, ModLey.PUMP.get().defaultBlockState(), LeyPumpBlockEntity.class);
        LeyPumpBlockEntity away = place(level, offLine, ModLey.PUMP.get().defaultBlockState(), LeyPumpBlockEntity.class);
        int ticks = 100;
        for (int i = 0; i < ticks; i++) {
            near.generate();
            away.generate();
        }
        double effNear = LeyLines.efficiency(level.getSeed(), onLine.getX(), onLine.getZ());
        double effFar = LeyLines.efficiency(level.getSeed(), offLine.getX(), offLine.getZ());
        helper.assertTrue(effNear > 0.99, "線の上の効率 " + effNear);
        int expectedNear = (int) Math.floor(spec.pumpFePerTick() * effNear * ticks);
        helper.assertTrue(Math.abs(near.energy() - expectedNear) <= 1, "線の上で " + near.energy() + " FE(期待 " + expectedNear + ")");
        helper.assertTrue(away.energy() < near.energy() * 0.25, "遠い所 " + away.energy() + " FE・効率 " + effFar + "(線の上 " + near.energy() + ")");
        helper.assertTrue(effFar >= spec.pumpMinEfficiency() - 1.0e-9, "下限より低い: " + effFar);
        level.removeBlock(onLine, false);
        level.removeBlock(offLine, false);
        helper.succeed();
    }

    // ---- 電池 ----

    /** 電池は FE の口から入れて出せる(1 回に出し入れできる量は設計値まで)。汲み上げ器に差すと、中が半分を越えた余りで充電される。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void batteryChargesAndDischarges(GameTestHelper helper) {
        LeySpec spec = spec();
        ItemStack battery = new ItemStack(ModLey.BATTERY.get());
        IEnergyStorage cap = battery.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(cap != null, "電池に FE の口が無い");
        int in = cap.receiveEnergy(spec.batteryMaxTransfer() * 3, false);
        helper.assertTrue(in == spec.batteryMaxTransfer(), "1 回に入った量 " + in);
        in += cap.receiveEnergy(spec.batteryMaxTransfer(), false);
        helper.assertTrue(LeyBatteryItem.stored(battery) == in, "たまった量 " + LeyBatteryItem.stored(battery) + " / " + in);
        int out = cap.extractEnergy(100, false);
        helper.assertTrue(out == 100 && LeyBatteryItem.stored(battery) == in - 100, "取り出し " + out + "・残り " + LeyBatteryItem.stored(battery));

        ServerLevel level = helper.getLevel();
        BlockPos at = findNear(level, far(helper, 2000), 0.6);
        clear(level, at, 1);
        LeyPumpBlockEntity pump = place(level, at, ModLey.PUMP.get().defaultBlockState().setValue(LeyBatteryHolder.BATTERY, true), LeyPumpBlockEntity.class);
        ItemStack empty = new ItemStack(ModLey.BATTERY.get());
        pump.setBatteryRaw(empty);
        fill(pump, spec.pumpBuffer() / 2 + 1000);
        LeyPumpBlockEntity.serverTick(level, at, level.getBlockState(at), pump);
        int charged = LeyBatteryItem.stored(pump.battery());
        helper.assertTrue(charged > 0, "汲み上げ器の電池が充電されない(中 " + pump.energy() + ")");
        helper.assertTrue(pump.energy() >= spec.pumpBuffer() / 2 - spec.pumpMaxOutput(), "半分より多く電池へ回した: 中 " + pump.energy());
        level.removeBlock(at, false);
        helper.succeed();
    }

    // ---- 柱と線 ----

    /** 汲み上げ器 → 柱 → 柱(20 先)→ 柱(さらに 20 先)に掛けた電池へ、網で FE が届く。網の数え方(出す 1・ためる 1・柱 3)も見る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void wiresCarryEnergyFar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = findNear(level, far(helper, 4000), 0.6);
        clear(level, base.offset(20, 0, 0), 24);
        BlockPos pumpPos = base;
        BlockPos a = base.offset(1, 0, 0);
        BlockPos b = a.offset(20, 0, 0);
        BlockPos c = b.offset(20, 0, 0);
        LeyPumpBlockEntity pump = place(level, pumpPos, ModLey.PUMP.get().defaultBlockState(), LeyPumpBlockEntity.class);
        place(level, a, ModLey.PYLON.get().defaultBlockState(), LeyPylonBlockEntity.class);
        place(level, b, ModLey.PYLON.get().defaultBlockState(), LeyPylonBlockEntity.class);
        LeyPylonBlockEntity end = place(level, c, ModLey.PYLON.get().defaultBlockState().setValue(LeyBatteryHolder.BATTERY, true), LeyPylonBlockEntity.class);
        end.setBatteryRaw(new ItemStack(ModLey.BATTERY.get()));
        for (BlockPos[] pair : new BlockPos[][]{{pumpPos, a}, {a, b}, {b, c}}) {
            LeyWires.Fail fail = LeyWires.check(level, pair[0], pair[1]);
            helper.assertTrue(fail == null, "張れない: " + fail + " " + pair[0] + " → " + pair[1]);
            LeyWires.connect(level, pair[0], pair[1]);
        }
        fill(pump, 5000);
        LeyGrid.Network net = LeyGrid.collect(level, c, new HashSet<>());
        helper.assertTrue(net.pylons.size() == 3 && net.producers.size() == 1 && net.buffers.size() == 1 && net.consumers.isEmpty(),
                "網の数え方: 柱 " + net.pylons.size() + "・出す " + net.producers.size() + "・ためる " + net.buffers.size() + "・受け取る " + net.consumers.size());
        int moved = 0;
        for (int i = 0; i < 5; i++) {
            moved += LeyGrid.collect(level, a, new HashSet<>()).transfer();
        }
        int stored = LeyBatteryItem.stored(end.battery());
        helper.assertTrue(stored > 0 && stored == moved, "40 ブロック先の電池に届かない: " + stored + " / 運んだ " + moved);
        helper.assertTrue(moved <= spec().pumpMaxOutput() * 5, "汲み上げ器の出す上限を越えた: " + moved);
        for (BlockPos p : new BlockPos[]{pumpPos, a, b, c}) {
            level.removeBlock(p, false);
        }
        helper.succeed();
    }

    /** 線の決まり: 長すぎる・機械どうし・同じ線・柱の数の上限・間にブロック。柱を壊すと相手の柱の線も消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void wireLimits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LeySpec spec = spec();
        BlockPos o = far(helper, 6000);
        clear(level, o.offset(12, 0, 0), spec.wireMaxLength() + 4);
        BlockState pylon = ModLey.PYLON.get().defaultBlockState();
        BlockPos hub = o;
        place(level, hub, pylon, LeyPylonBlockEntity.class);
        BlockPos tooFar = hub.offset(spec.wireMaxLength() + 1, 0, 0);
        place(level, tooFar, pylon, LeyPylonBlockEntity.class);
        helper.assertTrue(LeyWires.check(level, hub, tooFar) == LeyWires.Fail.TOO_LONG, "長すぎるのに張れる");
        BlockPos justRight = hub.offset(spec.wireMaxLength(), 0, 0);
        level.removeBlock(tooFar, false);
        place(level, justRight, pylon, LeyPylonBlockEntity.class);
        helper.assertTrue(LeyWires.check(level, hub, justRight) == null, "上限ちょうどで張れない: " + LeyWires.check(level, hub, justRight));

        BlockPos p1 = hub.offset(0, 0, 3);
        BlockPos p2 = hub.offset(0, 0, 5);
        place(level, p1, ModLey.PUMP.get().defaultBlockState(), LeyPumpBlockEntity.class);
        place(level, p2, ModLey.PUMP.get().defaultBlockState(), LeyPumpBlockEntity.class);
        helper.assertTrue(LeyWires.check(level, p1, p2) == LeyWires.Fail.NO_PYLON, "機械どうしで張れる");

        BlockPos wallTarget = hub.offset(-6, 0, 0);
        place(level, wallTarget, pylon, LeyPylonBlockEntity.class);
        level.setBlock(hub.offset(-3, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(LeyWires.check(level, hub, wallTarget) == LeyWires.Fail.BLOCKED, "石をはさんで張れる");
        level.removeBlock(hub.offset(-3, 0, 0), false);
        helper.assertTrue(LeyWires.check(level, hub, wallTarget) == null, "石をどけても張れない");

        LeyWires.connect(level, hub, justRight);
        helper.assertTrue(LeyWires.check(level, hub, justRight) == LeyWires.Fail.ALREADY, "同じ線をもう一度張れる");
        int made = 1;
        for (int i = 1; made < spec.wireMaxPerPylon(); i++) {
            BlockPos p = hub.offset(i, 0, -2);
            place(level, p, pylon, LeyPylonBlockEntity.class);
            helper.assertTrue(LeyWires.check(level, hub, p) == null, "上限の前で張れない: " + i);
            LeyWires.connect(level, hub, p);
            made++;
        }
        BlockPos extra = hub.offset(0, 0, -6);
        place(level, extra, pylon, LeyPylonBlockEntity.class);
        helper.assertTrue(LeyWires.check(level, hub, extra) == LeyWires.Fail.FULL, "柱の線の上限を越えて張れる");

        LeyPylonBlockEntity right = (LeyPylonBlockEntity) level.getBlockEntity(justRight);
        helper.assertTrue(right.hasLink(hub), "相手の柱が線を持っていない");
        level.removeBlock(hub, false);
        helper.assertTrue(!right.hasLink(hub), "柱を壊しても相手の線が残る");
        clear(level, o.offset(12, 0, 0), spec.wireMaxLength() + 4);
        helper.succeed();
    }

    /**
     * 受け取るだけの口(2c の換気の機械の形)への配り方: 欲しい量を汲み上げ器から集め、足りない分はためる側(電池)から出す。
     * 2 台の受け取る側には均等に分ける。網 1 つで 1 tick に運ぶ量は {@code wireNetworkPerTick} まで。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void consumersGetPumpThenBattery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LeySpec spec = spec();
        BlockPos at = findNear(level, far(helper, 12000), 0.6);
        clear(level, at, 1);
        LeyPumpBlockEntity pump = place(level, at, ModLey.PUMP.get().defaultBlockState(), LeyPumpBlockEntity.class);
        fill(pump, spec.pumpBuffer());
        int pumpBefore = pump.energy();
        ItemStack battery = new ItemStack(ModLey.BATTERY.get());
        battery.set(ModLey.ENERGY.get(), 50_000);
        int perConsumer = spec.pumpMaxOutput();
        EnergyStorage c1 = new EnergyStorage(1_000_000, perConsumer, 0);
        EnergyStorage c2 = new EnergyStorage(1_000_000, perConsumer, 0);
        LeyGrid.Network grid = new LeyGrid.Network();
        grid.producers.add(pump.energyPort());
        grid.consumers.add(c1);
        grid.consumers.add(c2);
        grid.buffers.add(battery.getCapability(Capabilities.EnergyStorage.ITEM));
        int moved = grid.transfer();
        int fromPump = pumpBefore - pump.energy();
        int fromBattery = 50_000 - LeyBatteryItem.stored(battery);
        helper.assertTrue(c1.getEnergyStored() == perConsumer && c2.getEnergyStored() == perConsumer,
                "受け取る側に均等に届かない: " + c1.getEnergyStored() + " / " + c2.getEnergyStored());
        helper.assertTrue(fromPump == spec.pumpMaxOutput(), "汲み上げ器から " + fromPump);
        helper.assertTrue(fromBattery == 2 * perConsumer - spec.pumpMaxOutput(), "足りない分が電池から出ない: " + fromBattery);
        helper.assertTrue(moved == 2 * perConsumer, "運んだ量 " + moved);

        EnergyStorage big = new EnergyStorage(10_000_000, 10_000_000, 10_000_000, 10_000_000);
        LeyGrid.Network flood = new LeyGrid.Network();
        flood.buffers.add(big);
        java.util.List<EnergyStorage> sinks = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            EnergyStorage s = new EnergyStorage(10_000_000, 10_000_000, 0);
            sinks.add(s);
            flood.consumers.add(s);
        }
        int movedFlood = flood.transfer();
        int got = sinks.stream().mapToInt(EnergyStorage::getEnergyStored).sum();
        helper.assertTrue(movedFlood == spec.wireNetworkPerTick() && got == spec.wireNetworkPerTick(),
                "網の 1 tick の上限が効かない: " + movedFlood + " / " + got);
        level.removeBlock(at, false);
        helper.succeed();
    }

    // ---- 保存 ----

    /** 柱の線と電池・汲み上げ器の中の FE と電池が、保存して読み直しても同じ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void saveAndRestore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = far(helper, 8000);
        clear(level, o, 4);
        BlockState pylonState = ModLey.PYLON.get().defaultBlockState().setValue(LeyBatteryHolder.BATTERY, true);
        LeyPylonBlockEntity pylon = place(level, o, pylonState, LeyPylonBlockEntity.class);
        LeyPumpBlockEntity pump = place(level, o.offset(2, 0, 0), ModLey.PUMP.get().defaultBlockState().setValue(LeyBatteryHolder.BATTERY, true),
                LeyPumpBlockEntity.class);
        LeyWires.connect(level, o, o.offset(2, 0, 0));
        ItemStack b1 = new ItemStack(ModLey.BATTERY.get());
        b1.set(ModLey.ENERGY.get(), 12345);
        pylon.setBatteryRaw(b1);
        ItemStack b2 = new ItemStack(ModLey.BATTERY.get());
        b2.set(ModLey.ENERGY.get(), 777);
        pump.setBatteryRaw(b2);
        fill(pump, 3000);
        int energy = pump.energy();

        CompoundTag pt = pylon.saveWithFullMetadata(level.registryAccess());
        CompoundTag mt = pump.saveWithFullMetadata(level.registryAccess());
        BlockEntity p2 = BlockEntity.loadStatic(o, pylonState, pt, level.registryAccess());
        BlockEntity m2 = BlockEntity.loadStatic(o.offset(2, 0, 0), level.getBlockState(o.offset(2, 0, 0)), mt, level.registryAccess());
        helper.assertTrue(p2 instanceof LeyPylonBlockEntity, "柱が読めない");
        helper.assertTrue(m2 instanceof LeyPumpBlockEntity, "汲み上げ器が読めない");
        LeyPylonBlockEntity rp = (LeyPylonBlockEntity) p2;
        LeyPumpBlockEntity rm = (LeyPumpBlockEntity) m2;
        helper.assertTrue(rp.links().equals(pylon.links()) && rp.hasLink(o.offset(2, 0, 0)), "線が戻らない: " + rp.links());
        helper.assertTrue(LeyBatteryItem.stored(rp.battery()) == 12345, "柱の電池 " + LeyBatteryItem.stored(rp.battery()));
        helper.assertTrue(rm.energy() == energy, "汲み上げ器の中 " + rm.energy() + " / " + energy);
        helper.assertTrue(LeyBatteryItem.stored(rm.battery()) == 777, "汲み上げ器の電池 " + LeyBatteryItem.stored(rm.battery()));
        clear(level, o, 4);
        helper.succeed();
    }

    // ---- 地脈の花 ----

    /** 遊んでいる間の花の増え方: 草の床を広く敷いても、花は線の帯の中にだけ生え、混み合う所には増えない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void flowersGrowOnlyOnLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = findNear(level, far(helper, 10000), 0.6);
        int r = 12;
        clear(level, center, r + 1);
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, -1, r))) {
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        LeySpec base = spec();
        LeySpec many = new LeySpec(base.cellSize(), base.warp(), base.flowerBand(), base.flowerWorldgenTries(), 1, 4000, r,
                base.flowerCrowdRadius(), base.flowerCrowdMax(), base.pumpFePerTick(), base.pumpReach(), base.pumpMinEfficiency(),
                base.pumpBuffer(), base.pumpMaxOutput(), base.batteryCapacity(), base.batteryMaxTransfer(), base.wireMaxLength(),
                base.wireMaxPerPylon(), base.wireNetworkPerTick(), base.wireNeedsClearPath());
        BlockPos ground = center.below();
        // 高さの地図はこの空中の床を答える(y = Y)。花の高さの探し方を合わせるため、まわりの柱の上を空にしておく(clear 済み)。
        int placed = LeyFlowerSpread.spreadAround(level, center, many, RandomSource.create(42L));
        int inBand = 0;
        int outBand = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, 0, -r), center.offset(r, 0, r))) {
            if (level.getBlockState(p).is(ModLey.FLOWER.get())) {
                if (LeyLines.inFlowerBand(level.getSeed(), p.getX(), p.getZ())) {
                    inBand++;
                } else {
                    outBand++;
                }
            }
        }
        helper.assertTrue(placed > 0 && inBand == placed, "置いた " + placed + "・帯の中 " + inBand + "(床 " + ground + ")");
        helper.assertTrue(outBand == 0, "帯の外に生えた: " + outBand);
        int crowdLimit = (2 * base.flowerCrowdRadius() + 1) * (2 * base.flowerCrowdRadius() + 1);
        helper.assertTrue(placed < crowdLimit * 6, "混み具合が効いていない: " + placed);
        clear(level, center, r + 1);
        helper.succeed();
    }
}
