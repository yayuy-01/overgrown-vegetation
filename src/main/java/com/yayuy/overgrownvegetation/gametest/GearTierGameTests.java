package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.gear.FiberGrassSpread;
import com.yayuy.overgrownvegetation.gear.FilterMask;
import com.yayuy.overgrownvegetation.gear.FungalOre;
import com.yayuy.overgrownvegetation.gear.GearSpores;
import com.yayuy.overgrownvegetation.gear.GearTierTable;
import com.yayuy.overgrownvegetation.gear.GreatswordItem;
import com.yayuy.overgrownvegetation.gear.MacheteItem;
import com.yayuy.overgrownvegetation.gear.ModGear;
import com.yayuy.overgrownvegetation.gear.PiercingCrossbowItem;
import com.yayuy.overgrownvegetation.gear.SporeShots;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.decon.Decon;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 強くなる道 段 1・段 2(2026-11-17): 遠征服・札鎧の暴露の倍率とフィルターの減り・山刀の刈り・胞子弾の燻り・両手剣の薙ぎ・クロスボウの貫き・
 * 鉱石のでき方(本物の装飾の口を通す)・繊維の草の生え方。期待値は {@code gear_tiers.json}({@link GearTierTable})から引く。
 * テスト場は繁茂でも地脈の上でもないので、バイオーム・地脈を見ない口で確かめ、鉱石は確率と地脈の距離だけ一時的に開けて本物の口を通す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GearTierGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GearTierGameTests.class);
        }

        private Registration() {
        }
    }

    private GearTierGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "gear_tiers";
    private static final double EPS = 1.0E-6D;

    private static GearTierTable t() {
        return DesignData.gearTiers();
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        SporeInfectionEffects.setPlayerStage(player, 0);
        SporeExposureTracker.setDose(player, 0.0D, 0L);
        for (EquipmentSlot s : EquipmentSlot.values()) player.setItemSlot(s, ItemStack.EMPTY);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        return player;
    }

    private static void clear(FakePlayer player) {
        SporeExposureTracker.setDose(player, 0.0D, 0L);
        for (EquipmentSlot s : EquipmentSlot.values()) player.setItemSlot(s, ItemStack.EMPTY);
    }

    /**
     * 遠征服: 3 部位(頭なし)で暴露が 1 − 3 × cut 倍、頭巾のフィルターが残る間は 0 でフィルターは filterWear 倍で減る、
     * フィルターが詰まると 4 部位の 1 − 4 × cut 倍。札鎧は遠征服より胞子に弱い。頭巾・兜はマスクとして除染に通る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void suitCutsExposureAndSlowsFilter(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_gear_suit");
        ServerLevel level = helper.getLevel();
        SporeFieldTable sf = DesignData.sporeField();
        try {
            double cut = t().suit.exposureCutPerPiece;
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModGear.EXPEDITION_COAT.get()));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModGear.EXPEDITION_TROUSERS.get()));
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModGear.EXPEDITION_BOOTS.get()));
            for (int i = 0; i < 10; i++) SporeExposureTracker.tickWith(level, player, 20, 50.0D);
            double want = 500.0D * (1.0D - 3.0D * cut);
            if (Math.abs(SporeExposureTracker.dose(player) - want) > 1.0E-3D) {
                helper.fail("3 部位で暴露が " + want + " でない: " + SporeExposureTracker.dose(player));
                return;
            }
            SporeExposureTracker.setDose(player, 0.0D, 0L);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModGear.EXPEDITION_HOOD.get()));
            ItemStack hood = player.getItemBySlot(EquipmentSlot.HEAD);
            for (int i = 0; i < 10; i++) SporeExposureTracker.tickWith(level, player, 20, 50.0D);
            int wantUsed = (int) Math.floor(500.0D * t().suit.filterWear + EPS);
            if (SporeExposureTracker.dose(player) != 0.0D || Math.abs(ProtectiveMaskItem.used(hood) - wantUsed) > 1) {
                helper.fail("頭巾: 暴露 " + SporeExposureTracker.dose(player) + "(0 のはず)・フィルター " + ProtectiveMaskItem.used(hood) + "(" + wantUsed + " のはず)");
                return;
            }
            hood.set(ModDataComponents.MASK_FILTER_USED, (int) Math.ceil(sf.maskCapacity()) + 1);
            for (int i = 0; i < 10; i++) SporeExposureTracker.tickWith(level, player, 20, 50.0D);
            want = 500.0D * (1.0D - 4.0D * cut);
            if (Math.abs(SporeExposureTracker.dose(player) - want) > 1.0E-3D) {
                helper.fail("詰まった頭巾 + 4 部位で暴露が " + want + " でない: " + SporeExposureTracker.dose(player));
                return;
            }
            if (!FilterMask.isMask(hood) || !Decon.holdsFilter(hood) || !Decon.holdsFilter(new ItemStack(ModGear.PLATE_HELMET.get()))) {
                helper.fail("頭巾・兜がフィルターを持つ物として扱われない");
                return;
            }
            clear(player);
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                player.setItemSlot(s, new ItemStack(s == EquipmentSlot.CHEST ? ModGear.PLATE_CHESTPLATE.get()
                        : s == EquipmentSlot.LEGS ? ModGear.PLATE_LEGGINGS.get() : ModGear.PLATE_BOOTS.get()));
            }
            double plate = GearSpores.exposureFactor(player);
            if (Math.abs(plate - (1.0D - 3.0D * t().plate.exposureCutPerPiece)) > EPS || plate <= 1.0D - 3.0D * cut) {
                helper.fail("札鎧の倍率がおかしい(遠征服より胞子に弱いはず): " + plate);
                return;
            }
            helper.succeed();
        } finally {
            clear(player);
        }
    }

    /** 山刀: 刈れる植物を速く刈り、まわりの同じ植物をまとめて刈る(上限 clearMax)。刈れない物は残す。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void macheteClearsUndergrowth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(3, 2, 3);
        int placed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                helper.setBlock(origin.offset(dx, 0, dz), Blocks.COBWEB);
                placed++;
            }
        }
        helper.setBlock(origin.offset(0, 1, 0), Blocks.STONE);
        ItemStack machete = new ItemStack(ModGear.MACHETE.get());
        float speed = machete.getDestroySpeed(Blocks.COBWEB.defaultBlockState());
        if (Math.abs(speed - t().machete.clearSpeed) > 1.0E-4F) {
            helper.fail("刈る速さが " + t().machete.clearSpeed + " でない: " + speed);
            return;
        }
        int n = MacheteItem.clearAround(level, helper.absolutePos(origin), null);
        int want = Math.min(placed, t().machete.clearMax);
        if (n != want) {
            helper.fail("まとめて刈った数が " + want + " でない: " + n);
            return;
        }
        if (!helper.getBlockState(origin.offset(0, 1, 0)).is(Blocks.STONE)) {
            helper.fail("刈れない石まで壊した");
            return;
        }
        helper.succeed();
    }

    /** 胞子弾: 当たった所のまわりの植物側だけ燃える。弓の印で半径・時間が伸びる。矢 1 本につき燻りは 1 回。胞子弾の矢は撃つと印が付く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporeShotSmoldersPlantSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob plant = helper.spawnWithNoFreeWill(ModEntities.FALCIMNES.get(), new BlockPos(2, 2, 3));
        plant.setNoAi(true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(4, 2, 3));
        husk.setNoAi(true);
        if (!PlantSideRules.isPlantSide(plant) || PlantSideRules.isPlantSide(husk)) {
            helper.fail("植物側の判定が思った通りでない(テストの前提)");
            return;
        }
        Arrow arrow = EntityType.ARROW.create(level);
        SporeShots.markSpore(arrow);
        SporeShots.markBow(arrow);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        double r = t().sporeShot.bowSmolderRadius;
        if (r < 1.2D) {
            helper.succeed(); // 半径を縮めた設計値ではこの配置で確かめられない
            return;
        }
        int n = SporeShots.impact(level, arrow, at, null);
        if (n != 1 || plant.getRemainingFireTicks() <= 0 || husk.getRemainingFireTicks() > 0) {
            helper.fail("燻り: 燃えた数 " + n + "(1 のはず)・植物側の火 " + plant.getRemainingFireTicks() + "・ハスクの火 " + husk.getRemainingFireTicks());
            return;
        }
        if (SporeShots.impact(level, arrow, at, null) != 0) {
            helper.fail("同じ矢で 2 度燻った");
            return;
        }
        FakePlayer shooter = player(helper, "ov_gear_archer");
        AbstractArrow shot = ((net.minecraft.world.item.ArrowItem) ModGear.SPORE_ARROW.get())
                .createArrow(level, new ItemStack(ModGear.SPORE_ARROW.get()), shooter, new ItemStack(ModGear.SPORE_BOW.get()));
        shot = ModGear.SPORE_BOW.get().customArrow(shot, new ItemStack(ModGear.SPORE_ARROW.get()), new ItemStack(ModGear.SPORE_BOW.get()));
        if (!SporeShots.isSpore(shot) || !SporeShots.fromBow(shot)) {
            helper.fail("胞子弾の弓で撃った胞子弾の矢に印が付かない");
            return;
        }
        if (!new ItemStack(ModGear.SPORE_ARROW.get()).is(net.minecraft.tags.ItemTags.ARROWS)) {
            helper.fail("胞子弾の矢が矢のタグに無い(弓で撃てない)");
            return;
        }
        helper.succeed();
    }

    /** 両手剣: 前の半円の相手だけ斬る(後ろは斬らない・上限 sweepMaxTargets)。オフハンドを止める。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void greatswordSweepsFrontHalf(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_gear_sword");
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModGear.GREATSWORD.get()));
            // 向き 0 = 南(+z)。前に 3 体・後ろに 2 体
            BlockPos[] front = {new BlockPos(2, 2, 5), new BlockPos(3, 2, 5), new BlockPos(4, 2, 5)};
            BlockPos[] back = {new BlockPos(2, 2, 1), new BlockPos(4, 2, 1)};
            List<Husk> fronts = new java.util.ArrayList<>();
            List<Husk> backs = new java.util.ArrayList<>();
            for (BlockPos p : front) fronts.add(spawnHusk(helper, p));
            for (BlockPos p : back) backs.add(spawnHusk(helper, p));
            Husk target = fronts.remove(1);
            int n = GreatswordItem.sweep(player, target);
            int want = Math.min(fronts.size(), t().greatsword.sweepMaxTargets);
            boolean backHit = backs.stream().anyMatch(h -> h.getHealth() < h.getMaxHealth());
            boolean targetHit = target.getHealth() < target.getMaxHealth();
            if (n != want || backHit || targetHit) {
                helper.fail("薙ぎ: 斬った数 " + n + "(" + want + " のはず)・後ろを斬った " + backHit + "・狙った相手を二重に斬った " + targetHit);
                return;
            }
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            if (!GreatswordItem.blocksOffhand(player)) {
                helper.fail("両手剣でオフハンドの盾が止まらない");
                return;
            }
            helper.succeed();
        } finally {
            clear(player);
        }
    }

    private static Husk spawnHusk(GameTestHelper helper, BlockPos p) {
        Husk h = helper.spawnWithNoFreeWill(EntityType.HUSK, p);
        h.setNoAi(true);
        return h;
    }

    /** クロスボウ: 太い矢は貫く数と重さが設計値どおり。装填は chargeTicks。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void crossbowBoltPierces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = player(helper, "ov_gear_xbow");
        Arrow arrow = EntityType.ARROW.create(level);
        double base = arrow.getBaseDamage();
        PiercingCrossbowItem.heavyBolt(arrow);
        if (arrow.getPierceLevel() != Math.min(127, t().crossbow.pierce)) {
            helper.fail("貫く数が " + t().crossbow.pierce + " でない: " + arrow.getPierceLevel());
            return;
        }
        if (Math.abs(arrow.getBaseDamage() - base * t().crossbow.damageMultiplier) > EPS) {
            helper.fail("太い矢の重さが違う: " + arrow.getBaseDamage());
            return;
        }
        int charge = PiercingCrossbowItem.chargeTicks(new ItemStack(ModGear.PIERCING_CROSSBOW.get()), player);
        if (charge != t().crossbow.chargeTicks) {
            helper.fail("装填が " + t().crossbow.chargeTicks + " tick でない: " + charge);
            return;
        }
        helper.succeed();
    }

    /**
     * 菌集鉱: 汚染された岩(過繁茂の石・深層岩)だけが変わる。本物の装飾の口({@code growSurfacePlants}。植物の確率 0 でも)を通る。
     * 汚染の骨粉の装飾し直しでは増えない。確率と地脈の距離はこのテストの同期の間だけ開ける。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fungalOreFormsFromCorruptedRock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.Ore o = t().ore;
        double chance = o.chance, reach = o.leyReach;
        int vmin = o.veinMin, vmax = o.veinMax;
        try {
            o.chance = 1.0D;
            o.leyReach = 1.0E9D;
            o.veinMin = 1;
            o.veinMax = 1;
            BlockPos stone = new BlockPos(2, 2, 2);
            BlockPos deep = new BlockPos(4, 2, 4);
            BlockPos plain = new BlockPos(2, 2, 4);
            helper.setBlock(stone, ModBlocks.OVERGROWN_STONE.get());
            helper.setBlock(deep, ModBlocks.OVERGROWN_DEEPSLATE.get());
            helper.setBlock(plain, Blocks.STONE);
            for (BlockPos p : new BlockPos[]{stone, deep, plain}) {
                BlockState s = helper.getBlockState(p);
                CorruptionSpreader.growSurfacePlants(level, helper.absolutePos(p), s, 0.0D);
            }
            if (!helper.getBlockState(stone).is(ModGear.FUNGAL_ORE.get()) || !helper.getBlockState(deep).is(ModGear.DEEPSLATE_FUNGAL_ORE.get())
                    || !helper.getBlockState(plain).is(Blocks.STONE)) {
                helper.fail("鉱石: 石 " + helper.getBlockState(stone) + "・深層岩 " + helper.getBlockState(deep) + "・汚染されていない石 " + helper.getBlockState(plain));
                return;
            }
            BlockPos again = new BlockPos(3, 2, 2);
            helper.setBlock(again, ModBlocks.OVERGROWN_STONE.get());
            FungalOre.suppressed = true;
            try {
                CorruptionSpreader.growSurfacePlants(level, helper.absolutePos(again), helper.getBlockState(again), 0.0D);
            } finally {
                FungalOre.suppressed = false;
            }
            if (!helper.getBlockState(again).is(ModBlocks.OVERGROWN_STONE.get())) {
                helper.fail("骨粉の装飾し直しで鉱石ができた");
                return;
            }
        } finally {
            o.chance = chance;
            o.leyReach = reach;
            o.veinMin = vmin;
            o.veinMax = vmax;
        }
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(ModGear.FUNGAL_ORE.get().defaultBlockState(), level,
                helper.absolutePos(new BlockPos(2, 2, 2)), null, null, new ItemStack(Items.IRON_PICKAXE));
        if (drops.stream().noneMatch(d -> d.is(ModGear.RAW_FUNGAL_METAL.get()))) {
            helper.fail("菌集鉱から原石が落ちない: " + drops);
            return;
        }
        helper.succeed();
    }

    /** 繊維の草: しっかりした面の上に生え、混み具合の上限で止まる。刈ると草の繊維が 1〜2(ルートテーブル)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fiberGrassGrowsAndDropsFiber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.FiberGrass f = t().fiberGrass;
        int placed = 0;
        for (int x = 1; x <= 5 && placed < f.crowdMax; x++) {
            if (FiberGrassSpread.tryPlace(level, helper.absolutePos(new BlockPos(x, 2, 3)), false)) placed++;
        }
        if (placed != f.crowdMax) {
            helper.fail("繊維の草が " + f.crowdMax + " 本生えない: " + placed);
            return;
        }
        if (f.crowdRadius >= 2 && FiberGrassSpread.tryPlace(level, helper.absolutePos(new BlockPos(3, 2, 4)), false)) {
            helper.fail("混み具合の上限を超えて生えた");
            return;
        }
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(ModGear.FIBER_GRASS.get().defaultBlockState(), level,
                helper.absolutePos(new BlockPos(1, 2, 3)), null);
        int n = drops.stream().filter(d -> d.is(ModItems.PLANT_FIBER.get())).mapToInt(ItemStack::getCount).sum();
        if (n < 1 || n > 2) { // ルートテーブルの 1〜2
            helper.fail("繊維の数が 1〜2 でない: " + n);
            return;
        }
        helper.succeed();
    }
}
