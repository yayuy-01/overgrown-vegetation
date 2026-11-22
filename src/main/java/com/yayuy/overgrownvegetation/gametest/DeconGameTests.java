package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DeconSpec;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.survival.decon.Decon;
import com.yayuy.overgrownvegetation.survival.decon.DeconRecipe;
import com.yayuy.overgrownvegetation.survival.decon.HandWash;
import com.yayuy.overgrownvegetation.survival.decon.WasherBlockEntity;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 除染(2026-11-14 サバイバルの背骨 段 3a): 大釜の手洗い(水が減る)・洗い場(FE が無いと止まる・まとめて洗う・上に落ちた物を吸う)・
 * 覆われた○○ 11 種の変換・フィルターを洗うと容量が減り、回数を超えると洗えない・保存と復元。期待値は設計値({@code spore_field.json} の
 * {@code decon}・{@code vent})と変換表のレシピから引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class DeconGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(DeconGameTests.class);
        }

        private Registration() {
        }
    }

    private DeconGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_decon";

    private static DeconSpec spec() {
        return DesignData.sporeField().decon();
    }

    /** 決定 31: 覆われた○○ → 元のバニラの素材。 */
    static Map<Item, Item> expectedConversions() {
        Map<Item, Item> m = new LinkedHashMap<>();
        m.put(ModItems.OVERGROWN_ROTTEN_FLESH.get(), Items.ROTTEN_FLESH);
        m.put(ModItems.OVERGROWN_BEEF.get(), Items.BEEF);
        m.put(ModItems.OVERGROWN_LEATHER.get(), Items.LEATHER);
        m.put(ModItems.OVERGROWN_PORKCHOP.get(), Items.PORKCHOP);
        m.put(ModItems.OVERGROWN_MUTTON.get(), Items.MUTTON);
        m.put(ModItems.OVERGROWN_WOOL.get(), Items.WHITE_WOOL);
        m.put(ModItems.OVERGROWN_CHICKEN.get(), Items.CHICKEN);
        m.put(ModItems.OVERGROWN_FEATHER.get(), Items.FEATHER);
        m.put(ModItems.OVERGROWN_BONE.get(), Items.BONE);
        m.put(ModItems.OVERGROWN_SPIDER_EYE.get(), Items.SPIDER_EYE);
        m.put(ModItems.OVERGROWN_ENDER_PEARL.get(), Items.ENDER_PEARL);
        return m;
    }

    static int count(Player player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    static BlockPos cauldron(GameTestHelper helper, int level) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, level));
        return helper.absolutePos(rel);
    }

    static int waterLevel(GameTestHelper helper, BlockPos abs) {
        var state = helper.getLevel().getBlockState(abs);
        return state.is(Blocks.WATER_CAULDRON) ? state.getValue(LayeredCauldronBlock.LEVEL) : 0;
    }

    static ItemStack usedFilter(int used) {
        ItemStack f = new ItemStack(ModVent.AIR_FILTER.get());
        f.set(ModDataComponents.MASK_FILTER_USED, used);
        return f;
    }

    // ---------------------------------------------------------------- 変換表

    /** 11 種すべてに変換表があり、元のバニラの素材 1 つと胞子(平均 0 より多い)になる。洗うと実際にその素材が出る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void elevenCoveredItemsConvert(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (Map.Entry<Item, Item> e : expectedConversions().entrySet()) {
            ItemStack in = new ItemStack(e.getKey());
            Optional<net.minecraft.world.item.crafting.RecipeHolder<DeconRecipe>> r = Decon.recipe(level, in);
            if (r.isEmpty()) {
                helper.fail("変換表が無い: " + BuiltInRegistries.ITEM.getKey(e.getKey()));
                return;
            }
            DeconRecipe recipe = r.get().value();
            if (!recipe.result().is(e.getValue()) || recipe.result().getCount() != 1 || recipe.spores() <= 0.0F) {
                helper.fail(BuiltInRegistries.ITEM.getKey(e.getKey()) + " → " + recipe.result() + "・胞子 " + recipe.spores());
                return;
            }
            var out = Decon.washOne(level, in.copy(), level.random, 0);
            if (out.isEmpty() || !out.get(0).is(e.getValue())) {
                helper.fail("洗った結果が違う: " + BuiltInRegistries.ITEM.getKey(e.getKey()) + " → " + out);
                return;
            }
        }
        // 変換表に無い物・きれいな物は洗わない
        if (Decon.kind(level, new ItemStack(Items.DIRT)) != Decon.Kind.NONE
                || Decon.kind(level, new ItemStack(Items.BEEF)) != Decon.Kind.NONE
                || Decon.kind(level, new ItemStack(ModVent.AIR_FILTER.get())) != Decon.Kind.NONE) {
            helper.fail("洗う物でない物を洗う物と見た");
            return;
        }
        helper.succeed();
    }

    /** 胞子の数は平均どおり(整数部は必ず、端数は確率)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporeCountMatchesMean(GameTestHelper helper) {
        var rnd = net.minecraft.util.RandomSource.create(1114L);
        int n = 4000, sum = 0;
        for (int i = 0; i < n; i++) sum += Decon.sporeCount(0.5F, rnd);
        double mean = sum / (double) n;
        if (Math.abs(mean - 0.5D) > 0.05D) {
            helper.fail("胞子の平均 " + mean + "(0.5 のはず)");
            return;
        }
        for (int i = 0; i < 50; i++) {
            int c = Decon.sporeCount(1.0F, rnd);
            if (c != 1) {
                helper.fail("平均 1.0 で " + c + " 個");
                return;
            }
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 手洗い

    /** 大釜で 1 つずつ洗える。素材は handWorkPerLevel ÷ handWorkMaterial 個ごとに水が 1 段減り、水が無くなると洗えない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void handWashUsesWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = cauldron(helper, 3);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getPersistentData().remove(HandWash.WORK_KEY);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.OVERGROWN_BEEF.get(), 64));
        int perLevel = (int) Math.ceil(spec().handWorkPerLevel() / (double) Math.max(1, spec().handWorkMaterial()));
        int washed = 0;
        for (int i = 0; i < perLevel; i++) {
            if (!HandWash.wash(level, pos, player, InteractionHand.MAIN_HAND)) {
                helper.fail("手洗いが " + i + " 回目で断られた");
                return;
            }
            washed++;
        }
        if (waterLevel(helper, pos) != 2) {
            helper.fail(perLevel + " 回洗って水が " + waterLevel(helper, pos) + " 段(2 のはず)");
            return;
        }
        if (count(player, Items.BEEF) != washed || player.getMainHandItem().getCount() != 64 - washed) {
            helper.fail("牛肉 " + count(player, Items.BEEF) + "・手の残り " + player.getMainHandItem().getCount());
            return;
        }
        // 水を使い切る → もう洗えない
        int guard = 0;
        while (waterLevel(helper, pos) > 0 && guard++ < 100) HandWash.wash(level, pos, player, InteractionHand.MAIN_HAND);
        if (helper.getLevel().getBlockState(pos).is(Blocks.WATER_CAULDRON) || !helper.getLevel().getBlockState(pos).is(Blocks.CAULDRON)) {
            helper.fail("水を使い切っても空の大釜にならない: " + helper.getLevel().getBlockState(pos));
            return;
        }
        if (HandWash.wash(level, pos, player, InteractionHand.MAIN_HAND)) {
            helper.fail("空の大釜で洗えた");
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    /**
     * フィルターを洗う: 詰まりが 0 に戻り、洗った回数が 1 増え、容量が vent.filterWashWear ずつ減る。洗える回数(vent.filterWashesMax)を使い切ると
     * もう洗えない。フィルター 1 枚は handWorkOther の重み(既定で水 1 段)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void handWashFilterWearsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = cauldron(helper, 3);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getPersistentData().remove(HandWash.WORK_KEY);
        var vent = DesignData.sporeField().vent();
        int max = vent.filterWashesMax();
        double base = AirFilters.baseCapacity();
        ItemStack filter = usedFilter(5000);
        for (int w = 1; w <= max; w++) {
            if (waterLevel(helper, pos) == 0) {
                helper.setBlock(new BlockPos(3, 2, 3), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
            }
            int before = waterLevel(helper, pos);
            player.setItemInHand(InteractionHand.MAIN_HAND, filter);
            if (!HandWash.wash(level, pos, player, InteractionHand.MAIN_HAND)) {
                helper.fail(w + " 回目の洗いが断られた");
                return;
            }
            filter = player.getMainHandItem();
            double cap = base * (1.0D - w * vent.filterWashWear());
            if (AirFilters.used(filter) != 0 || AirFilters.washes(filter) != w || Math.abs(AirFilters.capacity(filter) - cap) > 1e-6) {
                helper.fail(w + " 回目: 詰まり " + AirFilters.used(filter) + "・回数 " + AirFilters.washes(filter) + "・容量 "
                        + AirFilters.capacity(filter) + "(" + cap + " のはず)");
                return;
            }
            int expectDrop = spec().handWorkOther() / spec().handWorkPerLevel();
            if (expectDrop >= 1 && before - waterLevel(helper, pos) < expectDrop) {
                helper.fail("フィルターを洗っても水が減らない: " + before + " → " + waterLevel(helper, pos));
                return;
            }
            filter.set(ModDataComponents.MASK_FILTER_USED, 3000); // また使って汚れた
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        player.setItemInHand(InteractionHand.MAIN_HAND, filter);
        if (!Decon.filterWornOut(filter) || Decon.kind(level, filter) != Decon.Kind.NONE
                || HandWash.wash(level, pos, player, InteractionHand.MAIN_HAND)) {
            helper.fail("洗える回数 " + max + " を超えて洗えた");
            return;
        }
        // きれいなフィルターは洗わない(水を無駄にしない)
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModVent.AIR_FILTER.get()));
        if (HandWash.wash(level, pos, player, InteractionHand.MAIN_HAND)) {
            helper.fail("きれいなフィルターを洗った");
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    // ---------------------------------------------------------------- 地脈の洗い場

    static WasherBlockEntity placeWasher(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModDecon.WASHER.get().defaultBlockState());
        if (!(helper.getBlockEntity(rel) instanceof WasherBlockEntity be)) {
            throw new IllegalStateException("洗い場の中身が無い");
        }
        return be;
    }

    static int outputs(WasherBlockEntity be, Item item) {
        int n = 0;
        for (int i = WasherBlockEntity.INPUTS; i < WasherBlockEntity.INPUTS + WasherBlockEntity.OUTPUTS; i++) {
            ItemStack s = be.items().getStackInSlot(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** FE が無いと止まる。FE を入れるとまとめて速く洗う(machineTicksPerItem tick に 1 つ)。FE は使った分だけ減る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void washerNeedsPowerAndWashesBatch(GameTestHelper helper) {
        WasherBlockEntity be = placeWasher(helper, new BlockPos(3, 2, 3));
        int n = 8;
        ItemStack rest = be.insert(new ItemStack(ModItems.OVERGROWN_LEATHER.get(), n));
        if (!rest.isEmpty()) {
            helper.fail("洗う前の枠に入らなかった: " + rest);
            return;
        }
        if (!be.insert(new ItemStack(Items.DIRT, 4)).is(Items.DIRT)) {
            helper.fail("洗えない物を受け取った");
            return;
        }
        for (int t = 0; t < 40; t++) be.tickOnce();
        if (outputs(be, Items.LEATHER) != 0) {
            helper.fail("FE が無いのに洗った");
            return;
        }
        DeconSpec s = spec();
        int ticks = n * s.machineTicksPerItem() + 2;
        be.energyPort().receiveEnergy(s.machineEnergyBuffer(), false);
        int startFe = be.energy();
        for (int t = 0; t < ticks; t++) be.tickOnce();
        if (outputs(be, Items.LEATHER) != n) {
            helper.fail(ticks + " tick で革 " + outputs(be, Items.LEATHER) + "(" + n + " のはず)");
            return;
        }
        int used = startFe - be.energy();
        int expect = n * s.machineTicksPerItem() * s.machineFePerTick();
        if (used < expect || used > expect + 2 * s.machineFePerTick()) {
            helper.fail("使った FE " + used + "(約 " + expect + " のはず)");
            return;
        }
        // 洗う物が無くなったら FE を使わない
        int idle = be.energy();
        for (int t = 0; t < 20; t++) be.tickOnce();
        if (be.energy() != idle) {
            helper.fail("洗う物が無いのに FE を使った");
            return;
        }
        // 取り出す側の口からだけ出せる
        if (!be.itemPort().extractItem(0, 1, true).isEmpty() || be.itemPort().extractItem(WasherBlockEntity.INPUTS, 64, false).isEmpty()) {
            helper.fail("物の口の出し入れの向きが違う");
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    /** 洗い場の上に落ちた洗える物を吸い込み、洗えない物は残す。フィルターも洗える(回数が増える)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void washerPicksUpDroppedItems(GameTestHelper helper) {
        WasherBlockEntity be = placeWasher(helper, new BlockPos(3, 2, 3));
        be.energyPort().receiveEnergy(spec().machineEnergyBuffer(), false);
        ServerLevel level = helper.getLevel();
        BlockPos above = helper.absolutePos(new BlockPos(3, 3, 3));
        ItemEntity bone = new ItemEntity(level, above.getX() + 0.5D, above.getY() + 0.1D, above.getZ() + 0.5D,
                new ItemStack(ModItems.OVERGROWN_BONE.get(), 2));
        ItemEntity dirt = new ItemEntity(level, above.getX() + 0.5D, above.getY() + 0.1D, above.getZ() + 0.5D, new ItemStack(Items.DIRT));
        ItemEntity filter = new ItemEntity(level, above.getX() + 0.5D, above.getY() + 0.1D, above.getZ() + 0.5D, usedFilter(9000));
        for (ItemEntity e : new ItemEntity[]{bone, dirt, filter}) {
            e.setNoPickUpDelay();
            e.setDeltaMovement(0, 0, 0);
            level.addFreshEntity(e);
        }
        if (!spec().machinePickup()) {
            helper.succeed();
            return;
        }
        // 吸い込みは 10 tick に 1 回なので、本物の tick で進める
        helper.runAfterDelay(12 + 3 * spec().machineTicksPerItem() + 4, () -> {
        if (outputs(be, Items.BONE) != 2 || !dirt.isAlive() || bone.isAlive()) {
            helper.fail("吸い込み: 骨 " + outputs(be, Items.BONE) + "・土が残る " + dirt.isAlive() + "・覆われた骨が残る " + bone.isAlive());
            return;
        }
        ItemStack washed = ItemStack.EMPTY;
        for (int i = WasherBlockEntity.INPUTS; i < WasherBlockEntity.INPUTS + WasherBlockEntity.OUTPUTS; i++) {
            if (AirFilters.isFilter(be.items().getStackInSlot(i))) washed = be.items().getStackInSlot(i);
        }
        if (washed.isEmpty() || AirFilters.used(washed) != 0 || AirFilters.washes(washed) != 1) {
            helper.fail("フィルターが洗われていない: " + washed);
            return;
        }
        dirt.discard();
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
        });
    }

    /** 中の FE・物・進みを保存し、読み戻せる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void washerSavesAndLoads(GameTestHelper helper) {
        WasherBlockEntity be = placeWasher(helper, new BlockPos(3, 2, 3));
        be.energyPort().receiveEnergy(1234, false);
        be.insert(new ItemStack(ModItems.OVERGROWN_WOOL.get(), 5));
        be.items().setStackInSlot(WasherBlockEntity.INPUTS + 2, new ItemStack(Items.WHITE_WOOL, 3));
        ServerLevel level = helper.getLevel();
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        WasherBlockEntity copy = new WasherBlockEntity(be.getBlockPos(), be.getBlockState());
        copy.loadWithComponents(tag, level.registryAccess());
        if (copy.energy() != 1234 || !copy.items().getStackInSlot(0).is(ModItems.OVERGROWN_WOOL.get())
                || copy.items().getStackInSlot(0).getCount() != 5 || copy.items().getStackInSlot(WasherBlockEntity.INPUTS + 2).getCount() != 3) {
            helper.fail("保存と復元で変わった: FE " + copy.energy() + "・" + copy.items().getStackInSlot(0));
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    /** 洗い場の作り方はバニラの材料だけ(汚染の前から作れる)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void washerRecipeIsVanillaOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var holder = level.getRecipeManager().byKey(OvergrownVegetation.id("ley_washer"));
        if (holder.isEmpty()) {
            helper.fail("洗い場の作り方が無い");
            return;
        }
        for (var ing : holder.get().value().getIngredients()) {
            for (ItemStack s : ing.getItems()) {
                if (!BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("minecraft")) {
                    helper.fail("バニラでない材料: " + s);
                    return;
                }
            }
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 洗い場の形(2026-11-22 ユーザーの手直し版の模型)

    /** 形の (x, z) の柱の上端(当たる箱が無ければ 0)。 */
    static double topAt(net.minecraft.world.phys.shapes.VoxelShape shape, double x, double z) {
        double top = 0.0D;
        for (net.minecraft.world.phys.AABB box : shape.toAabbs()) {
            if (box.minX <= x && x <= box.maxX && box.minZ <= z && z <= box.maxZ) top = Math.max(top, box.maxY);
        }
        return top;
    }

    /**
     * 前(扉)を置いた人へ向ける。当たり判定は真ん中・前がすのこの高さ 12/16 で、後ろの立て管だけ上まで(4 つの向きで回る)。
     * すのこの上に立った人(足が 12/16)の装備を洗い、すのこの上(12/16)に置いた物を吸う。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void washerFacesPlacerAndWorksOnSlats(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floorRel = new BlockPos(3, 1, 3);
        BlockPos rel = floorRel.above();
        BlockPos abs = helper.absolutePos(rel);
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        net.minecraft.world.phys.Vec3 standAt = net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(rel.south(2)));
        placer.moveTo(standAt.x, standAt.y, standAt.z, 180.0F, 30.0F); // 北を向く
        ItemStack stack = new ItemStack(ModDecon.WASHER_ITEM.get());
        placer.setItemInHand(InteractionHand.MAIN_HAND, stack);
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(floorRel)).add(0.0D, 0.5D, 0.0D),
                net.minecraft.core.Direction.UP, helper.absolutePos(floorRel), false);
        var result = stack.useOn(new net.minecraft.world.item.context.UseOnContext(placer, InteractionHand.MAIN_HAND, hit));
        var state = helper.getBlockState(rel);
        if (!result.consumesAction() || !state.is(ModDecon.WASHER.get())
                || state.getValue(com.yayuy.overgrownvegetation.survival.decon.WasherBlock.FACING) != net.minecraft.core.Direction.SOUTH) {
            helper.fail("置いた洗い場の前が置いた人(南)を向かない: " + result + "・" + state);
            return;
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var c = state.setValue(com.yayuy.overgrownvegetation.survival.decon.WasherBlock.FACING, d).getCollisionShape(level, abs);
            net.minecraft.core.Direction back = d.getOpposite();
            double mid = topAt(c, 0.5D, 0.5D);
            double front = topAt(c, 0.5D + 0.4D * d.getStepX(), 0.5D + 0.4D * d.getStepZ());
            double pipe = topAt(c, 0.5D + 0.47D * back.getStepX(), 0.5D + 0.47D * back.getStepZ());
            if (Math.abs(mid - 0.75D) > 1.0e-6 || Math.abs(front - 0.75D) > 1.0e-6 || Math.abs(pipe - 1.0D) > 1.0e-6) {
                helper.fail(d + " 向きの当たり判定: 真ん中 " + mid + "・前 " + front + "・後ろの立て管 " + pipe + "(0.75・0.75・1 のはず)");
                return;
            }
        }
        WasherBlockEntity be = (WasherBlockEntity) level.getBlockEntity(abs);
        be.energyPort().receiveEnergy(spec().machineEnergyBuffer(), false);

        // すのこの上に立った人の装備を洗う: 立った人の体の箱(足 = 当たり判定の上端 12/16)が「上に立っている」と見る箱に重なる。
        // 床に立って横から寄った人(となりの列)は重ならない。(ワールドに入れた偽プレイヤーは MOD の通信でサーバーを落とすので、箱で確かめる)
        var standBox = com.yayuy.overgrownvegetation.survival.decon.GearShower.standBox(abs);
        var dims = net.minecraft.world.entity.EntityType.PLAYER.getDimensions();
        var onSlats = dims.makeBoundingBox(abs.getX() + 0.5D, abs.getY() + mid(state, level, abs), abs.getZ() + 0.5D);
        var beside = dims.makeBoundingBox(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 1.3D + 1.0e-3);
        if (!onSlats.intersects(standBox) || beside.intersects(standBox)) {
            helper.fail("上に立つ判定: すのこの上 " + onSlats.intersects(standBox) + "(true のはず)・横に立つ " + beside.intersects(standBox));
            return;
        }

        // すのこの上に置いた物を吸う(落ちてくる途中ではなく、すのこの上に置いた位置から)
        if (!spec().machinePickup()) {
            helper.setBlock(rel, Blocks.AIR);
            helper.succeed();
            return;
        }
        ItemEntity bone = new ItemEntity(level, abs.getX() + 0.5D, abs.getY() + mid(state, level, abs), abs.getZ() + 0.5D,
                new ItemStack(ModItems.OVERGROWN_BONE.get()));
        bone.setNoPickUpDelay();
        bone.setDeltaMovement(0.0D, 0.0D, 0.0D);
        level.addFreshEntity(bone);
        helper.runAfterDelay(12, () -> {
            boolean inside = false;
            for (int i = 0; i < WasherBlockEntity.INPUTS + WasherBlockEntity.OUTPUTS; i++) {
                ItemStack s = be.items().getStackInSlot(i);
                if (s.is(ModItems.OVERGROWN_BONE.get()) || s.is(Items.BONE)) inside = true;
            }
            if (bone.isAlive() || !inside) {
                helper.fail("すのこの上の物を吸わない: 残る " + bone.isAlive() + "(高さ " + (bone.getY() - abs.getY()) + ")・中にある " + inside);
                return;
            }
            helper.setBlock(rel, Blocks.AIR);
            helper.succeed();
        });
    }

    /** 真ん中のすのこの高さ(当たり判定から)。 */
    private static double mid(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, BlockPos abs) {
        return topAt(state.getCollisionShape(level, abs), 0.5D, 0.5D);
    }

    /**
     * 槽の中に描く物: 洗う前の枠の先頭から {@link WasherBlockEntity#SHOWN_MAX} 枠まで・1 つずつ。数が減るだけなら同じ顔ぶれ(送り直さない)。
     * クライアントへ送る中身は描く物だけで、受け取った側の FE などを消さない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void washerShowsUpToThreeItems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WasherBlockEntity be = placeWasher(helper, new BlockPos(3, 2, 3));
        be.insert(new ItemStack(ModItems.OVERGROWN_LEATHER.get(), 5));
        be.insert(new ItemStack(ModItems.OVERGROWN_BONE.get(), 2));
        java.util.List<ItemStack> two = be.computeShown();
        if (two.size() != 2 || two.get(0).getCount() != 1 || !two.get(0).is(ModItems.OVERGROWN_LEATHER.get())) {
            helper.fail("描く物(2 枠): " + two);
            return;
        }
        if (!WasherBlockEntity.sameItems(two, java.util.List.of(new ItemStack(ModItems.OVERGROWN_LEATHER.get(), 3), new ItemStack(ModItems.OVERGROWN_BONE.get(), 9)))
                || WasherBlockEntity.sameItems(two, java.util.List.of(new ItemStack(ModItems.OVERGROWN_LEATHER.get()), new ItemStack(Items.BONE)))) {
            helper.fail("顔ぶれの比べ方が違う(数は見ず、種類は見る)");
            return;
        }
        be.items().setStackInSlot(2, new ItemStack(ModItems.OVERGROWN_WOOL.get(), 3));
        be.items().setStackInSlot(3, usedFilter(9000));
        java.util.List<ItemStack> shown = be.computeShown();
        if (shown.size() != WasherBlockEntity.SHOWN_MAX) {
            helper.fail("描く物は " + WasherBlockEntity.SHOWN_MAX + " つまでのはず: " + shown);
            return;
        }
        CompoundTag tag = be.getUpdateTag(level.registryAccess());
        if (tag.contains("energy") || tag.contains("items") || !tag.contains("shown")) {
            helper.fail("クライアントへ描く物以外も送る: " + tag.getAllKeys());
            return;
        }
        WasherBlockEntity copy = new WasherBlockEntity(be.getBlockPos(), be.getBlockState());
        copy.energyPort().receiveEnergy(500, false);
        copy.handleUpdateTag(tag, level.registryAccess());
        if (copy.shown().size() != WasherBlockEntity.SHOWN_MAX || !WasherBlockEntity.sameItems(copy.shown(), shown) || copy.energy() != 500) {
            helper.fail("受け取った側: 描く物 " + copy.shown() + "・FE " + copy.energy() + "(500 のまま のはず)");
            return;
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.succeed();
    }

    /**
     * 読み込み直した後でも、チャンクと一緒に送った物を覚えている(11-22 見直し 11)。覚えていないと、止まっている間に槽を空にしても
     * 「変わっていない」と見て送らず、クライアントに浮いた物が残る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void washerRemembersShownAfterReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        WasherBlockEntity be = placeWasher(helper, rel);
        be.insert(new ItemStack(ModItems.OVERGROWN_LEATHER.get(), 4));
        CompoundTag saved = be.saveWithoutMetadata(level.registryAccess());
        // 置き直して保存した中身を読み込む(枠の読み込みは onContentsChanged を通らないので、shown は空のまま = 開き直した直後と同じ)
        helper.setBlock(rel, Blocks.AIR);
        WasherBlockEntity loaded = placeWasher(helper, rel);
        loaded.loadWithComponents(saved, level.registryAccess());
        if (!loaded.shown().isEmpty() || loaded.computeShown().size() != 1) {
            helper.fail("読み込んだ直後の形が想定と違う: shown " + loaded.shown() + "・中身 " + loaded.computeShown());
            return;
        }
        loaded.getUpdateTag(level.registryAccess()); // チャンクを送るときと同じ
        if (loaded.shown().size() != 1) {
            helper.fail("送った物を覚えていない: " + loaded.shown());
            return;
        }
        loaded.items().setStackInSlot(0, ItemStack.EMPTY);
        if (!loaded.shown().isEmpty()) {
            helper.fail("槽を空にしても送り直さない: " + loaded.shown());
            return;
        }
        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }
}
