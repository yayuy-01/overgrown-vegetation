package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ExpeditionSpec;
import com.yayuy.overgrownvegetation.data.FatigueSpec;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModExpedition;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.FatigueTracker;
import com.yayuy.overgrownvegetation.survival.expedition.ExpeditionDrops;
import com.yayuy.overgrownvegetation.survival.expedition.SleepingBagBlock;
import com.yayuy.overgrownvegetation.survival.expedition.SporeEaterSpread;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * サバイバルの背骨 段 4(2026-11-16): 濃い所の素材(胞子喰い茸の生える決まり・G5 の硬い樹皮片)・焼いた胞子・菌糸のフィルターの持ち・寝袋。
 * 期待値は設計値({@code spore_field.json} の {@code expedition}・{@code fatigue}・{@code vent})から引く。
 * テスト場は過繁茂のバイオームでないので、茸の「過繁茂だけ」は生えないことを、ほかの決まりはバイオームを見ない口で確かめる。
 * 偽プレイヤーは tick しないので、寝袋は横になる口と tick の受け口を直に叩く。ネザーで横になれること・時刻が進むままなことは Claude 用クライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ExpeditionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ExpeditionGameTests.class);
        }

        private Registration() {
        }
    }

    private ExpeditionGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_expedition";
    private static final double EPS = 1.0E-6D;

    private static ExpeditionSpec spec() {
        return DesignData.sporeField().expedition();
    }

    private static FakePlayer player(GameTestHelper helper, String name, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        reset(player);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(rel));
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    private static void reset(FakePlayer player) {
        if (player.isSleeping()) player.stopSleeping();
        SporeInfectionEffects.setPlayerStage(player, 0);
        SporeExposureTracker.setDose(player, 0.0D, 0L);
        FatigueTracker.set(player, 0.0D);
    }

    // ---------------------------------------------------------------- 胞子喰い茸

    /**
     * 過繁茂でないテスト場には生えない。バイオームを見ない口では: 空の下(空の光 15)には生えず、屋根の下の暗がり(13 以下)には生える・
     * 地面が無ければ生えない・まわりに上限の本数があれば生えない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void mushroomGrowsOnlyInOvergrownShade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ExpeditionSpec s = spec();
        BlockPos shade = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos open = helper.absolutePos(new BlockPos(5, 2, 5));
        // 屋根: shade のまわり 5×5 を 1 段上で覆う(横からの光も届きにくく)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(new BlockPos(2 + dx, 3, 2 + dz), Blocks.STONE);
            }
        }
        helper.succeedWhen(() -> {
            if (level.getBrightness(LightLayer.SKY, shade) > s.mushroomMaxSkyLight()) {
                helper.fail("屋根の下の空の光がまだ高い: " + level.getBrightness(LightLayer.SKY, shade));
            }
            if (SporeEaterSpread.canGrowAt(level, shade, true)) {
                helper.fail("過繁茂でないテスト場に生える");
            }
            if (level.getBrightness(LightLayer.SKY, open) > s.mushroomMaxSkyLight() && SporeEaterSpread.canGrowAt(level, open, false)) {
                helper.fail("空の下(空の光 " + level.getBrightness(LightLayer.SKY, open) + ")に生える");
            }
            if (!SporeEaterSpread.canGrowAt(level, shade, false)) {
                helper.fail("屋根の下の暗がりに生えない(空の光 " + level.getBrightness(LightLayer.SKY, shade) + ")");
            }
            if (SporeEaterSpread.canGrowAt(level, shade.above(4), false)) {
                helper.fail("宙に生える");
            }
            // 混み具合: まわりに上限の本数を置くと生えない
            BlockState m = ModExpedition.SPORE_EATER_MUSHROOM.get().defaultBlockState();
            for (int i = 0; i < s.mushroomCrowdMax(); i++) {
                level.setBlock(helper.absolutePos(new BlockPos(1 + i, 2, 1)), m, 3);
            }
            if (s.mushroomCrowdRadius() >= 2 && SporeEaterSpread.canGrowAt(level, shade, false)) {
                helper.fail("まわりに " + s.mushroomCrowdMax() + " 本あっても生える");
            }
        });
    }

    /** 茸を壊すと 1〜2 個(ルートテーブル)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void mushroomDropsOneOrTwo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState m = ModExpedition.SPORE_EATER_MUSHROOM.get().defaultBlockState();
        for (int i = 0; i < 20; i++) {
            level.setBlock(at, m, 3);
            var drops = net.minecraft.world.level.block.Block.getDrops(m, level, at, null);
            int n = drops.stream().filter(d -> d.is(ModExpedition.SPORE_EATER_MUSHROOM_ITEM.get())).mapToInt(ItemStack::getCount).sum();
            if (n < 1 || n > 2) {
                helper.fail("茸のドロップが 1〜2 個でない: " + n);
                return;
            }
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- G5 の硬い樹皮片

    /** G5 は確率どおりに硬い樹皮片を落とし、ドロップ増加で確率が上がる。G5 でない Mob は落とさない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void g5DropsHardBark(GameTestHelper helper) {
        ExpeditionSpec s = spec();
        Mob g5 = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(3, 2, 3));
        Mob other = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 2, 5));
        try {
            double c = s.barkDropChance();
            if (c > 0.0D && !ExpeditionDrops.rollDrop(g5, 0, c - 1.0E-4D).is(ModExpedition.HARD_BARK_SHARD.get())) {
                helper.fail("G5 が確率の内側で樹皮片を落とさない");
                return;
            }
            if (c < 1.0D && !ExpeditionDrops.rollDrop(g5, 0, c + 1.0E-4D).isEmpty()) {
                helper.fail("G5 が確率の外側でも落とす");
                return;
            }
            double c3 = c + s.barkLootingBonus() * 3;
            if (c3 < 1.0D && s.barkLootingBonus() > 0.0D && !ExpeditionDrops.rollDrop(g5, 3, c3 - 1.0E-4D).is(ModExpedition.HARD_BARK_SHARD.get())) {
                helper.fail("ドロップ増加 3 で確率が上がらない");
                return;
            }
            if (!ExpeditionDrops.rollDrop(other, 3, 0.0D).isEmpty()) {
                helper.fail("G5 でない Mob が素材を落とす");
                return;
            }
            helper.succeed();
        } finally {
            g5.discard();
            other.discard();
        }
    }

    /** G4 は確率どおりに休眠嚢を落とし、G5 は落とさない(G5 は樹皮片)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void g4DropsDormancySac(GameTestHelper helper) {
        ExpeditionSpec s = spec();
        Mob g4 = helper.spawnWithNoFreeWill(ModEntities.ARBORA.get(), new BlockPos(3, 2, 3));
        try {
            double c = s.cureDropChance();
            if (c > 0.0D && !ExpeditionDrops.rollDrop(g4, 0, c - 1.0E-4D).is(ModExpedition.DORMANCY_SAC.get())) {
                helper.fail("G4 が確率の内側で休眠嚢を落とさない");
                return;
            }
            if (c < 1.0D && !ExpeditionDrops.rollDrop(g4, 0, c + 1.0E-4D).isEmpty()) {
                helper.fail("G4 が確率の外側でも落とす");
                return;
            }
            helper.succeed();
        } finally {
            g4.discard();
        }
    }

    // ---------------------------------------------------------------- 駆胞薬

    /** 1 本で 1 段下がる・繰り返せば 0 まで治る(暴露量も 0)・飲むたびに疲労がたまる・感染していなければ効かない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void cureDraughtLowersOneStageDownToZero(GameTestHelper helper) {
        FakePlayer p = player(helper, "ov_cure", new BlockPos(3, 2, 3));
        double cost = spec().cureFatigue();
        try {
            SporeInfectionEffects.setPlayerStage(p, 3);
            for (int expect = 2; expect >= 0; expect--) {
                if (!com.yayuy.overgrownvegetation.survival.expedition.CureDraughtItem.apply(p)) {
                    helper.fail("感染" + (expect + 1) + " で薬が効かない");
                    return;
                }
                if (SporeInfectionEffects.stageOf(p) != expect) {
                    helper.fail("1 本で 1 段下がらない: " + SporeInfectionEffects.stageOf(p) + "(" + expect + " のはず)");
                    return;
                }
            }
            if (SporeExposureTracker.dose(p) != 0.0D) {
                helper.fail("治ったのに暴露量が残る: " + SporeExposureTracker.dose(p));
                return;
            }
            if (Math.abs(FatigueTracker.value(p) - Math.min(100.0D, cost * 3)) > 1.0E-4D) {
                helper.fail("3 本で疲労が " + cost * 3 + " たまらない: " + FatigueTracker.value(p));
                return;
            }
            if (com.yayuy.overgrownvegetation.survival.expedition.CureDraughtItem.apply(p)) {
                helper.fail("感染していないのに効く");
                return;
            }
            // 重い段(感染10)からも 1 本 1 段
            SporeInfectionEffects.setPlayerStage(p, 10);
            com.yayuy.overgrownvegetation.survival.expedition.CureDraughtItem.apply(p);
            if (SporeInfectionEffects.stageOf(p) != 9) {
                helper.fail("感染10 から 1 本で 9 にならない: " + SporeInfectionEffects.stageOf(p));
                return;
            }
            helper.succeed();
        } finally {
            reset(p);
        }
    }

    /** 作業台: 焼いた胞子 2 + 胞子喰い茸か休眠嚢 1 + 水入り瓶 → 駆胞薬。どちらも無ければ作れない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void cureDraughtCraftsWithMushroomOrSac(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack water = net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                net.minecraft.world.item.Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER);
        ItemStack roast = new ItemStack(ModExpedition.ROASTED_SPORES.get());
        for (ItemStack reagent : new ItemStack[]{new ItemStack(ModExpedition.SPORE_EATER_MUSHROOM_ITEM.get()), new ItemStack(ModExpedition.DORMANCY_SAC.get())}) {
            var in = net.minecraft.world.item.crafting.CraftingInput.of(2, 2, java.util.List.of(roast.copy(), roast.copy(), reagent, water.copy()));
            var r = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, level);
            if (r.isEmpty() || !r.get().value().assemble(in, level.registryAccess()).is(ModExpedition.CURE_DRAUGHT.get())) {
                helper.fail(reagent + " で駆胞薬が作れない");
                return;
            }
        }
        var none = net.minecraft.world.item.crafting.CraftingInput.of(2, 2, java.util.List.of(roast.copy(), roast.copy(), roast.copy(), water.copy()));
        var bad = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, none, level);
        if (bad.isPresent() && bad.get().value().assemble(none, level.registryAccess()).is(ModExpedition.CURE_DRAUGHT.get())) {
            helper.fail("濃い所の素材なしで駆胞薬が作れる");
            return;
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 焼いた胞子

    /** 胞子をかまど・燻製器で焼くと焼いた胞子になる(除染で出る胞子の使い道)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporesRoastInFurnaceAndSmoker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SingleRecipeInput in = new SingleRecipeInput(new ItemStack(ModItems.SPORE.get()));
        Optional<? extends RecipeHolder<?>> smelt = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, in, level);
        Optional<? extends RecipeHolder<?>> smoke = level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, in, level);
        if (smelt.isEmpty() || smoke.isEmpty()) {
            helper.fail("胞子を焼くレシピが無い: かまど " + smelt.isPresent() + " 燻製器 " + smoke.isPresent());
            return;
        }
        ItemStack a = smelt.get().value().getResultItem(level.registryAccess());
        ItemStack b = smoke.get().value().getResultItem(level.registryAccess());
        if (!a.is(ModExpedition.ROASTED_SPORES.get()) || !b.is(ModExpedition.ROASTED_SPORES.get())) {
            helper.fail("焼いた結果が焼いた胞子でない: " + a + " / " + b);
            return;
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 菌糸のフィルター

    /**
     * 菌糸のフィルターの容量はふつうの {@code superiorFilterCapacity} 倍。マスクへ入れても・取り出しても・洗っても菌糸のフィルターのまま。
     * 詰まりの量はふつうのフィルターと同じ式で減る(深い所で長もちする)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void mycelialFilterLastsLongerAndKeepsGrade(GameTestHelper helper) {
        double mul = spec().superiorFilterCapacity();
        ItemStack plain = new ItemStack(ModVent.AIR_FILTER.get());
        ItemStack myc = new ItemStack(ModExpedition.MYCELIAL_FILTER.get());
        if (!AirFilters.isFilter(myc) || AirFilters.grade(myc) != 1) {
            helper.fail("菌糸のフィルターがフィルターとして扱われない");
            return;
        }
        if (Math.abs(AirFilters.capacity(myc) - AirFilters.capacity(plain) * mul) > EPS) {
            helper.fail("容量が " + mul + " 倍でない: " + AirFilters.capacity(myc) + " / " + AirFilters.capacity(plain));
            return;
        }
        // ふつうのフィルターが詰まり切る量を吸っても、まだ残る
        int plainCap = (int) Math.ceil(AirFilters.capacity(plain));
        AirFilters.absorb(myc, plainCap);
        AirFilters.absorb(plain, plainCap);
        if (!AirFilters.clogged(plain) || AirFilters.clogged(myc)) {
            helper.fail("同じ量を吸って、ふつうは詰まり菌糸は残る、にならない: ふつう " + AirFilters.clogged(plain) + " 菌糸 " + AirFilters.clogged(myc));
            return;
        }
        // マスクへ入れる → マスクの容量も 3 倍・取り出すと菌糸のフィルター
        ItemStack mask = new ItemStack(ModItems.PROTECTIVE_MASK.get());
        AirFilters.copyState(myc, mask);
        if (AirFilters.grade(mask) != 1 || Math.abs(AirFilters.capacity(mask) - AirFilters.capacity(myc)) > EPS
                || AirFilters.used(mask) != plainCap) {
            helper.fail("マスクに入れると格・容量・詰まりが写らない: 格 " + AirFilters.grade(mask) + " 容量 " + AirFilters.capacity(mask));
            return;
        }
        ItemStack back = AirFilters.asFilterItem(mask);
        if (!back.is(ModExpedition.MYCELIAL_FILTER.get()) || AirFilters.used(back) != plainCap) {
            helper.fail("マスクから取り出すと菌糸のフィルターでない: " + back);
            return;
        }
        // ふつうのフィルターを入れ直すとマスクの格は 0 に戻る
        AirFilters.copyState(new ItemStack(ModVent.AIR_FILTER.get()), mask);
        if (AirFilters.grade(mask) != 0 || mask.has(ModExpedition.FILTER_GRADE)) {
            helper.fail("ふつうのフィルターに入れ替えても格が残る");
            return;
        }
        // 洗った後の容量 = 3 倍 × 傷み
        ItemStack washed = new ItemStack(ModExpedition.MYCELIAL_FILTER.get());
        washed.set(ModVent.FILTER_WASHES, 1);
        double expect = AirFilters.baseCapacity() * mul * (1.0D - DesignData.sporeField().vent().filterWashWear());
        if (Math.abs(AirFilters.capacity(washed) - Math.max(0.0D, expect)) > EPS) {
            helper.fail("1 回洗った容量が違う: " + AirFilters.capacity(washed) + "(" + expect + " のはず)");
            return;
        }
        if (!washed.has(ModVent.FILTER_WASHES) || plain.getOrDefault(ModDataComponents.MASK_FILTER_USED, 0) != plainCap) {
            helper.fail("詰まり・洗った回数の欄が読めない");
            return;
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 寝袋

    /**
     * 寝袋に横になれる・眠りの続き(ブロックが寝床として残る)が効く・夜でも「深く眠った」に届かせない(時刻を飛ばさない)・
     * 疲労は昼の休みと同じ速さで戻る・帰りの地点は変わらない・遠いと横になれない・起きれば寝袋は空く。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sleepingBagRestsWithoutSkippingTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FatigueSpec fs = DesignData.sporeField().fatigue();
        BlockPos bagRel = new BlockPos(3, 2, 3);
        helper.setBlock(bagRel, ModExpedition.SLEEPING_BAG.get().defaultBlockState().setValue(SleepingBagBlock.FACING, Direction.EAST));
        BlockPos bag = helper.absolutePos(bagRel);
        FakePlayer far = player(helper, "ov_bag_far", new BlockPos(3, 2, 3));
        far.moveTo(far.getX() + 20.0D, far.getY(), far.getZ());
        FakePlayer p = player(helper, "ov_bag_sleeper", new BlockPos(2, 2, 3));
        try {
            if (SleepingBagBlock.tryLieDown(far, bag) || far.isSleeping()) {
                helper.fail("20 ブロック先から横になれる");
                return;
            }
            var spawnBefore = p.getRespawnPosition();
            if (!SleepingBagBlock.tryLieDown(p, bag) || !p.isSleeping() || !SleepingBagBlock.isInBag(p)) {
                helper.fail("寝袋に横になれない");
                return;
            }
            if (!level.getBlockState(bag).getValue(SleepingBagBlock.OCCUPIED)) {
                helper.fail("寝ても寝袋が使用中にならない");
                return;
            }
            if (!level.getBlockState(bag).isBed(level, bag, p)) {
                helper.fail("寝袋が寝床として扱われない(すぐ起こされる)");
                return;
            }
            if (!java.util.Objects.equals(spawnBefore, p.getRespawnPosition())) {
                helper.fail("寝袋で帰りの地点が変わった: " + p.getRespawnPosition());
                return;
            }
            // 2 人目は横になれない
            FakePlayer second = player(helper, "ov_bag_second", new BlockPos(4, 2, 3));
            if (SleepingBagBlock.tryLieDown(second, bag)) {
                helper.fail("使用中の寝袋に 2 人目が横になれる");
                return;
            }
            // 夜でも昼でも「深く眠った」(100)に届かせない
            Field f = Player.class.getDeclaredField("sleepCounter");
            f.setAccessible(true);
            f.setInt(p, 100);
            FatigueTracker.onPlayerTick(new PlayerTickEvent.Post(p));
            if (f.getInt(p) >= 100 || p.isSleepingLongEnough()) {
                helper.fail("寝袋で「深く眠った」になる(時刻を飛ばしてしまう)");
                return;
            }
            // 疲労は昼の休みと同じ速さで戻る
            FatigueTracker.set(p, 60.0D);
            FatigueTracker.step(p, 400, 0.0D, 0, false);
            double expect = Math.max(0.0D, 60.0D - fs.restPerSecond() * 20.0D);
            if (Math.abs(FatigueTracker.value(p) - expect) > 1.0E-4D) {
                helper.fail("寝袋の 20 秒で疲労が " + expect + " にならない: " + FatigueTracker.value(p));
                return;
            }
            // 起きれば寝袋は空く
            p.stopSleepInBed(true, true);
            if (p.isSleeping() || level.getBlockState(bag).getValue(SleepingBagBlock.OCCUPIED)) {
                helper.fail("起きても寝袋が空かない");
                return;
            }
            helper.succeed();
        } catch (ReflectiveOperationException e) {
            helper.fail("sleepCounter に届かない: " + e);
        } finally {
            reset(p);
            reset(far);
        }
    }

    /** まわり 8 ブロック(上下 5)に敵(バニラの敵・植物側の感染させる側)がいると横になれない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sleepingBagRefusesWithEnemiesNear(GameTestHelper helper) {
        if (!spec().sleepingBagNeedsSafety()) {
            helper.succeed();
            return;
        }
        BlockPos bagRel = new BlockPos(3, 2, 3);
        helper.setBlock(bagRel, ModExpedition.SLEEPING_BAG.get().defaultBlockState());
        BlockPos bag = helper.absolutePos(bagRel);
        FakePlayer p = player(helper, "ov_bag_unsafe", new BlockPos(2, 2, 3));
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        LivingEntity g5 = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(5, 2, 5));
        LivingEntity zombie = null;
        try {
            if (SleepingBagBlock.tryLieDown(p, bag) || p.isSleeping()) {
                helper.fail("G5 がそばにいても横になれる");
                return;
            }
            g5.discard();
            zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 2, 1));
            if (SleepingBagBlock.tryLieDown(p, bag) || p.isSleeping()) {
                helper.fail("ゾンビがそばにいても横になれる");
                return;
            }
            zombie.discard();
            zombie = null;
            if (!SleepingBagBlock.tryLieDown(p, bag)) {
                helper.fail("敵がいなくなっても横になれない");
                return;
            }
            helper.succeed();
        } finally {
            if (g5.isAlive()) g5.discard();
            if (zombie != null) zombie.discard();
            reset(p);
        }
    }
}
