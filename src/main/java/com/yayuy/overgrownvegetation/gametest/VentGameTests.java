package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.data.VentSpec;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import com.yayuy.overgrownvegetation.survival.room.RoomAir;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import com.yayuy.overgrownvegetation.survival.vent.VentBlock;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import com.yayuy.overgrownvegetation.survival.vent.Ventilators;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 換気の機械とフィルター(2026-11-14 サバイバルの背骨 段 2c)。場から上限つきで抜く・範囲の外は下げない・嵐で抜き切れない・
 * FE とフィルターが無いと止まる・抜いた分だけフィルターが詰まる・過繁茂の中の部屋が機械で安全側に変わる・マスクのフィルター交換・保存と復元・
 * 作り方がバニラの材料だけ。期待値は設計値({@code spore_field.json} の {@code vent})から引く。
 *
 * <p>場の式は自分だけの {@link SporeField} で回す(世界の場はほかのテストと共有のため)。部屋のテストは下支えを試すので、
 * テスト場のまわりのチャンクを過繁茂として読ませる({@link SporeConcentration#OVERGROWN_FOR_TESTS})—— 自分だけのバッチで走らせ、終わりに外す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VentGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(VentGameTests.class);
        }

        private Registration() {
        }
    }

    private VentGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_vent";
    private static final String BATCH_ROOM = "survival_vent_room";

    private static SporeFieldTable table() {
        return DesignData.sporeField();
    }

    private static VentSpec spec() {
        return table().vent();
    }

    // ---------------------------------------------------------------- 場の式(自分だけの場)

    /** 過繁茂の広い森(31×31 チャンク、10 日前に汚染)を、一定の東風・晴れで {@code days} 日回した場。 */
    static SporeField overgrownForest(double days) {
        SporeField.Params p = table().field();
        SporeField f = new SporeField(1114L, p);
        for (int x = -15; x <= 15; x++) for (int z = -15; z <= 15; z++) f.setCorrupted(x, z, -10L * PrevailingWind.DAY);
        run(f, null, 0L, days, false);
        return f;
    }

    /** {@code days} 日回す。{@code vent} = 吸い込み口のチャンク(null = なし)。抜いた量の合計を返す。 */
    static double run(SporeField f, int[] vent, long from, double days, boolean thunder) {
        int stepTicks = table().updateTicks();
        int steps = (int) Math.round(days * PrevailingWind.DAY / stepTicks);
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.dirZ = 0;
        wind.strength = thunder ? 3 : 1;
        double taken = 0;
        long t0 = from + 10L * PrevailingWind.DAY;
        for (int k = 1; k <= steps; k++) {
            long t = t0 + (long) k * stepTicks;
            f.step(new SporeField.Conditions(t, 6000L + (long) k * stepTicks, thunder, thunder, false), wind);
            if (vent != null) taken += f.extractAround(vent[0], vent[1], spec().radiusChunks(), spec().capPerUpdate());
        }
        return taken;
    }

    /** チャンクの濃度(%、下支えなし) = 浮遊 + 林床 ÷ fullAt。 */
    static double percent(SporeField f, int cx, int cz) {
        return (f.air(cx, cz) + f.low(cx, cz)) / table().fullAt() * 100.0D;
    }

    /** 機械のまわり 3×3 チャンクの平均(%)。 */
    static double around(SporeField f, int cx, int cz) {
        double s = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s += percent(f, cx + dx, cz + dz);
        return s / 9.0D;
    }

    /** 過繁茂の森の真ん中で、機械は 1 回に上限までしか抜かず、まわりを過繁茂の下支え(25%)より下げる。範囲の外(4 チャンク先)は下がらない(場全体を 0 にする膜ではない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ventPullsCappedWithinRange(GameTestHelper helper) {
        SporeField f = overgrownForest(2.0D);
        double before = around(f, 0, 0);
        int stepTicks = table().updateTicks();
        int steps = PrevailingWind.DAY / stepTicks;
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.strength = 1;
        double cap = spec().capPerUpdate();
        for (int k = 1; k <= steps; k++) {
            long t = 12L * PrevailingWind.DAY + (long) k * stepTicks;
            f.step(new SporeField.Conditions(t, 6000L + (long) k * stepTicks, false, false, false), wind);
            double got = f.extractAround(0, 0, spec().radiusChunks(), cap);
            if (got > cap + 1e-9) {
                helper.fail("1 回に上限 " + cap + " を超えて抜いた: " + got);
                return;
            }
        }
        double after = around(f, 0, 0);
        double floor = table().floorFor(2);
        if (!(after < floor)) {
            helper.fail(String.format("機械のまわりが過繁茂の下支え %.0f%% より下がらない: 前 %.1f%% → 後 %.1f%%", floor, before, after));
            return;
        }
        double far = percent(f, 0, 5);
        if (!(far > floor)) {
            helper.fail(String.format("範囲の外(5 チャンク先)まで下支えより下がった: %.1f%%", far));
            return;
        }
        helper.succeed();
    }

    /** 嵐の流れ込みは上限を超え、機械は上限いっぱいまで抜いても抜き切れない(まわりは晴れの日より濃い)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void stormOverwhelmsVent(GameTestHelper helper) {
        SporeField f = overgrownForest(2.0D);
        int[] at = {0, 0};
        run(f, at, 2L * PrevailingWind.DAY, 1.0D, false);
        double calm = around(f, 0, 0);
        double cap = spec().capPerUpdate();
        int stepTicks = table().updateTicks();
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.strength = 3;
        double taken = 0;
        int n = 60;
        for (int k = 1; k <= n; k++) {
            long t = 13L * PrevailingWind.DAY + (long) k * stepTicks;
            f.step(new SporeField.Conditions(t, 6000L + (long) k * stepTicks, true, true, false), wind);
            taken += f.extractAround(0, 0, spec().radiusChunks(), cap);
        }
        double stormy = around(f, 0, 0);
        if (taken < cap * n * 0.95D) {
            helper.fail(String.format("嵐でも上限まで使っていない: %.4f / %.4f", taken, cap * n));
            return;
        }
        if (!(stormy > calm)) {
            helper.fail(String.format("嵐の間もまわりが晴れの日より濃くならない: 晴れ %.1f%% 嵐 %.1f%%", calm, stormy));
            return;
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 機械(ブロックの中身)

    private static VentBlockEntity placeVent(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModVent.VENTILATOR.get().defaultBlockState());
        BlockEntity be = helper.getBlockEntity(rel);
        if (!(be instanceof VentBlockEntity v)) {
            throw new IllegalStateException("換気機の中身が無い");
        }
        return v;
    }

    private static ItemStack freshFilter() {
        return new ItemStack(ModVent.AIR_FILTER.get());
    }

    /** FE が無い・フィルターが無い・詰まっていると止まる。動いている間は 1 tick に fePerTick を使う。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void ventNeedsPowerAndFilter(GameTestHelper helper) {
        VentBlockEntity vent = placeVent(helper, new BlockPos(3, 2, 3));
        int fe = spec().fePerTick();
        if (vent.energyPort().canExtract() || !vent.energyPort().canReceive()) {
            helper.fail("FE の口が受け取りだけになっていない");
            return;
        }
        vent.setFilter(freshFilter());
        if (vent.tickOnce() || vent.running()) {
            helper.fail("FE が無いのに動いた");
            return;
        }
        int got = vent.energyPort().receiveEnergy(fe * 10, false);
        if (got != fe * 10) {
            helper.fail("FE を受け取らない: " + got);
            return;
        }
        vent.setFilter(ItemStack.EMPTY);
        if (vent.tickOnce()) {
            helper.fail("フィルターが無いのに動いた");
            return;
        }
        vent.setFilter(freshFilter());
        int e0 = vent.energy();
        if (!vent.tickOnce() || !vent.running() || vent.energy() != e0 - fe) {
            helper.fail("FE とフィルターがあるのに動かない・使う量が違う: " + e0 + " → " + vent.energy());
            return;
        }
        ItemStack clogged = freshFilter();
        clogged.set(ModDataComponents.MASK_FILTER_USED, (int) Math.ceil(AirFilters.capacity(clogged)));
        vent.setFilter(clogged);
        if (vent.tickOnce() || vent.running()) {
            helper.fail("詰まったフィルターで動いた");
            return;
        }
        // 見た目: 動いている間は running の状態
        vent.setFilter(freshFilter());
        vent.tickOnce();
        if (!helper.getBlockState(new BlockPos(3, 2, 3)).getValue(VentBlock.RUNNING)) {
            helper.fail("動いているのに見た目が止まったまま");
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    /** 抜いた量の分だけフィルターが詰まり、1 回に抜ける量はフィルターの残りで切られる。上限のうち使った割合を覚える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void filterCloggesByExtracted(GameTestHelper helper) {
        VentBlockEntity vent = placeVent(helper, new BlockPos(3, 2, 3));
        SporeFieldTable t = table();
        vent.setFilter(freshFilter());
        double per = VentBlockEntity.loadPerUnit(t);
        double units = spec().capPerUpdate() * 0.5D;
        vent.onExtracted(units, 1, t);
        int expect = (int) (units * per);
        if (Math.abs(AirFilters.used(vent.filter()) - expect) > 1) {
            helper.fail("詰まりが抜いた量と合わない: " + AirFilters.used(vent.filter()) + "(期待 " + expect + ")");
            return;
        }
        if (Math.abs(vent.budgetUnits(3, t) - spec().capPerUpdate() * 3) > 1e-9) {
            helper.fail("新しいフィルターなのに 3 回分の上限を抜けない: " + vent.budgetUnits(3, t));
            return;
        }
        ItemStack almost = freshFilter();
        almost.set(ModDataComponents.MASK_FILTER_USED, (int) AirFilters.capacity(almost) - 100);
        vent.setFilter(almost);
        double b = vent.budgetUnits(60, t);
        if (Math.abs(b - 100.0D / per) > 1e-6) {
            helper.fail("残りの少ないフィルターで抜ける量が残りで切られていない: " + b + "(期待 " + 100.0D / per + ")");
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    /** 中の FE・フィルター(詰まりと洗った回数)を保存し、読み戻せる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ventSavesAndLoads(GameTestHelper helper) {
        VentBlockEntity vent = placeVent(helper, new BlockPos(3, 2, 3));
        vent.energyPort().receiveEnergy(1234, false);
        ItemStack f = freshFilter();
        f.set(ModDataComponents.MASK_FILTER_USED, 4321);
        f.set(ModVent.FILTER_WASHES.get(), 2);
        vent.setFilter(f);
        ServerLevel level = helper.getLevel();
        CompoundTag tag = vent.saveWithFullMetadata(level.registryAccess());
        VentBlockEntity copy = new VentBlockEntity(vent.getBlockPos(), vent.getBlockState());
        copy.loadWithComponents(tag, level.registryAccess());
        if (copy.energy() != 1234 || AirFilters.used(copy.filter()) != 4321 || AirFilters.washes(copy.filter()) != 2) {
            helper.fail("保存と復元で変わった: FE " + copy.energy() + "・詰まり " + AirFilters.used(copy.filter())
                    + "・洗った回数 " + AirFilters.washes(copy.filter()));
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    // ---------------------------------------------------------------- 部屋(決定 22 とのつながり)

    /**
     * 過繁茂の中の閉じた部屋は暴露量がゆっくりたまる(2a)。そばで換気機が回っていれば下支えが外れ、外の値が
     * 場の値だけになって(テスト場の場は空)たまらなくなる。機械が止まれば戻る。嵐で抜き切れないのは {@link #stormOverwhelmsVent}(場の値そのものが高い)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH_ROOM, timeoutTicks = 60)
    public static void ventMakesOvergrownRoomSafe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = helper.absolutePos(new BlockPos(3, 3, 3));
        ChunkPos c0 = new ChunkPos(inside);
        it.unimi.dsi.fastutil.longs.LongArrayList marked = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                long k = ChunkPos.asLong(c0.x + dx, c0.z + dz);
                if (SporeConcentration.OVERGROWN_FOR_TESTS.add(k)) marked.add(k);
            }
        BlockPos ventRel = new BlockPos(0, 2, 0);
        try {
            // 板材の小屋(中 1×2×1)。床は石の床(y=1)
            for (int x = 2; x <= 4; x++)
                for (int y = 2; y <= 5; y++)
                    for (int z = 2; z <= 4; z++) {
                        boolean wall = x == 2 || x == 4 || z == 2 || z == 4 || y == 5;
                        helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    }
            SporeConcentration.clearCacheForTests();
            SporeConcentration.Reading r0 = SporeConcentration.reading(level, inside);
            if (!(r0.roomSeepPerSecond() > 0.0D)) {
                helper.fail(String.format("過繁茂の部屋でたまらない(前提): 読み %.2f%%・たまり %.3f", r0.percent(), r0.roomSeepPerSecond()));
                return;
            }
            VentBlockEntity vent = placeVent(helper, ventRel);
            vent.energyPort().receiveEnergy(spec().energyBuffer(), false);
            vent.setFilter(freshFilter());
            vent.tickOnce();
            SporeConcentration.clearCacheForTests();
            SporeConcentration.Reading r1 = SporeConcentration.reading(level, inside);
            double outside = SporeConcentration.percentAt(level, helper.absolutePos(new BlockPos(6, 2, 6)));
            if (r1.roomSeepPerSecond() != 0.0D || !(outside < table().floorFor(2))) {
                helper.fail(String.format("換気機が回っているのに部屋にたまる: たまり %.3f・外 %.1f%%", r1.roomSeepPerSecond(), outside));
                return;
            }
            ChunkPos cv = new ChunkPos(vent.getBlockPos());
            if (!Ventilators.covers(level, cv.x + 2, cv.z, spec()) || Ventilators.covers(level, cv.x + 3, cv.z, spec())) {
                helper.fail("範囲が設計値(" + spec().radiusChunks() + " チャンク)と合わない");
                return;
            }
            vent.setFilter(ItemStack.EMPTY); // 止まる → 下支えが戻る
            vent.tickOnce();
            SporeConcentration.clearCacheForTests();
            if (!(SporeConcentration.reading(level, inside).roomSeepPerSecond() > 0.0D)) {
                helper.fail("換気機が止まっても部屋が安全なまま");
                return;
            }
            helper.succeed();
        } finally {
            for (long k : marked) SporeConcentration.OVERGROWN_FOR_TESTS.remove(k);
            SporeConcentration.clearCacheForTests();
            helper.setBlock(ventRel, Blocks.AIR);
        }
    }

    // ---------------------------------------------------------------- マスクと作り方

    /** 詰まったマスクにフィルターを重ねると入れ替わる: マスクは新品に、古いフィルター(詰まりそのまま)が手か持ち物に戻る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void maskFilterSwaps(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack mask = new ItemStack(ModItems.PROTECTIVE_MASK.get());
        int cap = (int) Math.ceil(AirFilters.capacity(mask));
        mask.set(ModDataComponents.MASK_FILTER_USED, cap);
        if (ProtectiveMaskItem.remaining(mask) > 0.0D) {
            helper.fail("前提: マスクが詰まっていない");
            return;
        }
        // つかんでいるのが 2 枚: 1 枚がマスクへ、古いのは持ち物へ
        ItemStack[] hand = {new ItemStack(ModVent.AIR_FILTER.get(), 2)};
        SlotAccess access = SlotAccess.of(() -> hand[0], s -> hand[0] = s);
        ProtectiveMaskItem.swapFilter(mask, hand[0], player, access);
        if (ProtectiveMaskItem.used(mask) != 0 || hand[0].getCount() != 1) {
            helper.fail("2 枚から 1 枚入らない: マスクの詰まり " + ProtectiveMaskItem.used(mask) + "・手 " + hand[0].getCount());
            return;
        }
        boolean found = false;
        for (ItemStack s : player.getInventory().items) {
            if (AirFilters.isFilter(s) && AirFilters.used(s) == cap) found = true;
        }
        if (!found) {
            helper.fail("古いフィルターが持ち物に戻っていない");
            return;
        }
        // つかんでいるのが 1 枚: 手に古いフィルターが残る
        mask.set(ModDataComponents.MASK_FILTER_USED, 777);
        ProtectiveMaskItem.swapFilter(mask, hand[0], player, access);
        if (ProtectiveMaskItem.used(mask) != 0 || !AirFilters.isFilter(hand[0]) || AirFilters.used(hand[0]) != 777) {
            helper.fail("1 枚の入れ替えで手に古いフィルターが残らない: 手の詰まり " + AirFilters.used(hand[0]));
            return;
        }
        helper.succeed();
    }

    /** 換気機は機械の 4 点(つるはし・石以上・正しい道具で落ちる・自分を落とすルート)がそろっている(memory machine-blocks-pickaxe-stone)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ventDropsWithStonePickaxe(GameTestHelper helper) {
        var state = ModVent.VENTILATOR.get().defaultBlockState();
        if (!state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) || !state.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL)
                || !state.requiresCorrectToolForDrops()) {
            helper.fail("換気機がつるはし・石以上・正しい道具で落ちる、になっていない");
            return;
        }
        if (!new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE).isCorrectToolForDrops(state)
                || new ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE).isCorrectToolForDrops(state)) {
            helper.fail("石のつるはしで落ちない・木のつるはしで落ちる");
            return;
        }
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, state);
        var drops = net.minecraft.world.level.block.Block.getDrops(state, helper.getLevel(), helper.absolutePos(rel), helper.getBlockEntity(rel),
                null, new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        helper.setBlock(rel, Blocks.AIR);
        if (drops.stream().noneMatch(s -> s.is(ModVent.VENTILATOR_ITEM.get()))) {
            helper.fail("石のつるはしで壊しても換気機が落ちない: " + drops);
            return;
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 3 ブロックの機械(2026-11-22)

    /** 部位がそろっているか(下 = 中身あり、上 2 つ = 中身なし、向きは同じ)。食い違いを返す(そろっていれば null)。 */
    private static String checkWhole(GameTestHelper helper, BlockPos lowerRel, net.minecraft.core.Direction facing) {
        VentBlock.Part[] parts = VentBlock.Part.values();
        for (int i = 0; i < 3; i++) {
            BlockPos p = lowerRel.above(i);
            var s = helper.getBlockState(p);
            if (!s.is(ModVent.VENTILATOR.get()) || s.getValue(VentBlock.PART) != parts[i] || s.getValue(VentBlock.FACING) != facing) {
                return "高さ " + i + " が " + parts[i] + "(" + facing + ")でない: " + s;
            }
            if ((helper.getLevel().getBlockEntity(helper.absolutePos(p)) instanceof VentBlockEntity) != (i == 0)) {
                return "中身は下だけのはずが、高さ " + i + " で違う";
            }
            if (s.getShape(helper.getLevel(), helper.absolutePos(p)).isEmpty()) {
                return "高さ " + i + " に当たり判定が無い";
            }
        }
        return null;
    }

    private static java.util.List<net.minecraft.world.entity.item.ItemEntity> dropsNear(GameTestHelper helper, BlockPos rel) {
        return helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(rel)).inflate(2.0D, 3.0D, 2.0D));
    }

    private static int countOf(java.util.List<net.minecraft.world.entity.item.ItemEntity> items, net.minecraft.world.item.Item item) {
        return items.stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    /**
     * 下を置くと上 2 つが付き(当たり判定 3 ブロック分・中身は下だけ)、どこを壊しても全部が消えて落とすのは 1 つだけ。
     * 中のフィルターは下と一緒に出てくる。上・中・下のどれを壊しても同じ。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ventThreeBlocksBreakTogether(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        for (int broken = 0; broken < 3; broken++) {
            var facing = net.minecraft.core.Direction.from2DDataValue(broken + 1); // 北・東・南
            helper.setBlock(rel, ModVent.VENTILATOR.get().defaultBlockState().setValue(VentBlock.FACING, facing));
            String bad = checkWhole(helper, rel, facing);
            if (bad != null) {
                helper.fail("置いた直後: " + bad);
                return;
            }
            if (!(helper.getBlockEntity(rel) instanceof VentBlockEntity be)) {
                helper.fail("中身が無い");
                return;
            }
            be.setFilter(freshFilter());
            // どの部位のルートも換気機 1 つ(道具の決まりはどこを掘っても同じ)
            var partState = helper.getBlockState(rel.above(broken));
            var loot = net.minecraft.world.level.block.Block.getDrops(partState, helper.getLevel(), helper.absolutePos(rel.above(broken)),
                    helper.getLevel().getBlockEntity(helper.absolutePos(rel.above(broken))), null, new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
            if (loot.size() != 1 || !loot.get(0).is(ModVent.VENTILATOR_ITEM.get()) || loot.get(0).getCount() != 1) {
                helper.fail("高さ " + broken + " のルートが換気機 1 つでない: " + loot);
                return;
            }
            dropsNear(helper, rel).forEach(net.minecraft.world.entity.Entity::discard);
            helper.getLevel().destroyBlock(helper.absolutePos(rel.above(broken)), true);
            for (int i = 0; i < 3; i++) {
                if (!helper.getBlockState(rel.above(i)).isAir()) {
                    helper.fail("高さ " + broken + " を壊しても高さ " + i + " が残った: " + helper.getBlockState(rel.above(i)));
                    return;
                }
            }
            var drops = dropsNear(helper, rel);
            int vents = countOf(drops, ModVent.VENTILATOR_ITEM.get());
            int filters = countOf(drops, ModVent.AIR_FILTER.get());
            drops.forEach(net.minecraft.world.entity.Entity::discard);
            if (vents != 1 || filters != 1) {
                helper.fail("高さ " + broken + " を壊して落ちたのが換気機 " + vents + "・フィルター " + filters + "(どちらも 1 のはず)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * アイテムで置く: 上に 2 マスの空きがないと置けず、アイテムは減らない。空ければ置けて、正面(facing)は置いた人の方を向く。
     * 置いた人が南にいて北を向いている → 機械の正面は南。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ventNeedsRoomAbove(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos floorRel = new BlockPos(3, 1, 3);
        BlockPos rel = floorRel.above();
        BlockPos stoneRel = rel.above(2);
        net.minecraft.world.phys.Vec3 standAt = net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(rel.south(2)));
        player.moveTo(standAt.x, standAt.y, standAt.z, 180.0F, 30.0F); // 北を向く
        ItemStack stack = new ItemStack(ModVent.VENTILATOR_ITEM.get(), 2);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(floorRel)).add(0.0D, 0.5D, 0.0D),
                net.minecraft.core.Direction.UP, helper.absolutePos(floorRel), false);
        java.util.function.Supplier<net.minecraft.world.InteractionResult> use = () -> stack.useOn(
                new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit));

        helper.setBlock(stoneRel, Blocks.STONE); // 2 マス上がふさがっている
        var r1 = use.get();
        if (r1.consumesAction() || !helper.getBlockState(rel).isAir() || stack.getCount() != 2) {
            helper.fail("上がふさがっているのに置けた・アイテムが減った: " + r1 + "・" + helper.getBlockState(rel) + "・数 " + stack.getCount());
            return;
        }
        helper.setBlock(stoneRel, Blocks.AIR);
        var r2 = use.get();
        if (!r2.consumesAction() || stack.getCount() != 1) {
            helper.fail("空いているのに置けない: " + r2 + "・数 " + stack.getCount());
            return;
        }
        String bad = checkWhole(helper, rel, net.minecraft.core.Direction.SOUTH);
        if (bad != null) {
            helper.fail("置いた機械: " + bad);
            return;
        }
        helper.setBlock(rel, Blocks.AIR);
        if (!helper.getBlockState(rel.above(2)).isAir()) {
            helper.fail("下を消しても上が残った");
            return;
        }
        helper.succeed();
    }

    /** フィルター・換気機・胞子濃度計は、汚染の前から作れる(材料がバニラだけ)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void recipesUseVanillaOnly(GameTestHelper helper) {
        var manager = helper.getLevel().getServer().getRecipeManager();
        for (String id : new String[]{"air_filter", "ley_ventilator", "spore_meter"}) {
            var holder = manager.byKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, id));
            if (holder.isEmpty()) {
                helper.fail("作り方が読み込まれていない: " + id);
                return;
            }
            RecipeHolder<?> r = holder.get();
            for (Ingredient in : r.value().getIngredients()) {
                for (ItemStack s : in.getItems()) {
                    if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("minecraft")) {
                        helper.fail(id + " の材料にバニラでない物: " + s);
                        return;
                    }
                }
            }
        }
        helper.succeed();
    }
}
