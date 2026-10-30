package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.HerbCropBlock;
import com.yayuy.overgrownvegetation.block.MortarBlockEntity;
import com.yayuy.overgrownvegetation.effect.MedicineEffects;
import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.item.MedicineItem;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 乳鉢・薬草・薬(2026-10-16、サバイバル用アイテム。ユーザー指定)。
 * <ul>
 *     <li>薬草を入れて長押しの回数だけすりつぶすと粉になる・入れられない物は入らない({@link #grindingTurnsHerbIntoPowder})。</li>
 *     <li>組み合わせが合えば紙で薬が取れて中身が空く・合わなければ取れず中身も残る({@link #mixingNeedsTheExactCombination})。</li>
 *     <li>薬 5 つの効き目({@link #medicinesWork})。</li>
 *     <li>月光草は夜に咲いている間だけ薬草が採れる・野生の株は草の上でも枯れない({@link #moonlightHerbOnlyAtNight})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MortarGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MortarGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "mortar";

    private static MortarBlockEntity mortar(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.MORTAR.get());
        return (MortarBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void grindingTurnsHerbIntoPowder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MortarBlockEntity m = mortar(helper, new BlockPos(3, 2, 3));
        if (MortarBlockEntity.accepts(level, new ItemStack(Items.DIRT))) {
            helper.fail("土まで乳鉢に入る");
            return;
        }
        if (!MortarBlockEntity.accepts(level, new ItemStack(ModItems.MOONLIGHT_HERB.get())) || !m.insert(new ItemStack(ModItems.MOONLIGHT_HERB.get()))) {
            helper.fail("月光草が乳鉢に入らない");
            return;
        }
        int steps = 0;
        while (m.contents().get(0).is(ModItems.MOONLIGHT_HERB.get()) && steps < 100) {
            if (!m.grindStep(level)) {
                break;
            }
            steps++;
        }
        if (!m.contents().get(0).is(ModItems.MOONLIGHT_POWDER.get())) {
            helper.fail("すりつぶしても月光草の粉にならない: " + m.contents());
            return;
        }
        if (steps < 2) {
            helper.fail("1 回の右クリックですりつぶし終わった(長押しが要るはず): " + steps);
            return;
        }
        if (m.grindStep(level)) {
            helper.fail("粉をさらにすりつぶせてしまう");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void mixingNeedsTheExactCombination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MortarBlockEntity m = mortar(helper, new BlockPos(3, 2, 3));
        m.insert(new ItemStack(ModItems.MOONLIGHT_POWDER.get()));
        m.insert(new ItemStack(ModItems.CHARCOAL_POWDER.get()));
        if (!m.collect(level).isEmpty() || m.count() != 2) {
            helper.fail("足りない組み合わせで薬が取れた、または中身が消えた");
            return;
        }
        m.insert(new ItemStack(ModItems.MOONLIGHT_POWDER.get()));
        ItemStack out = m.collect(level);
        if (!out.is(ModItems.SPOREWARD_POWDER.get()) || m.count() != 0) {
            helper.fail("月光草の粉 2 + 炭の粉 1 で胞子除けの粉薬にならない: " + out + " / 残り " + m.count());
            return;
        }
        // 残りの 4 つの配合も確かめる。
        Object[][] recipes = {
                {ModItems.SPORE_STOPPING_ELIXIR.get(), List.of(ModItems.BITTER_ROOT_POWDER.get(), ModItems.BITTER_ROOT_POWDER.get(), ModItems.SAP_CRYSTAL_POWDER.get())},
                {ModItems.SMELLING_SALTS.get(), List.of(ModItems.PUNGENT_POWDER.get(), Items.BONE_MEAL, ModItems.PUNGENT_POWDER.get())},
                {ModItems.STASIS_PILL.get(), List.of(Items.HONEYCOMB, ModItems.MOONLIGHT_POWDER.get(), ModItems.BITTER_ROOT_POWDER.get())},
                {ModItems.WARMBLOOM_PILL.get(), List.of(ModItems.WARMBLOOM_POWDER.get(), ModItems.WARMBLOOM_POWDER.get(), Items.HONEYCOMB)}};
        for (Object[] r : recipes) {
            @SuppressWarnings("unchecked")
            List<Item> ings = (List<Item>) r[1];
            for (Item i : ings) {
                m.insert(new ItemStack(i));
            }
            ItemStack got = m.collect(level);
            if (!got.is((Item) r[0])) {
                helper.fail(ings + " で " + r[0] + " にならない: " + got);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void medicinesWork(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ((MedicineItem) ModItems.SPOREWARD_POWDER.get()).apply(player);
        if (MedicineEffects.dwellFactor(player) <= 1.0D) {
            helper.fail("胞子除けの粉薬でかかるまでの時間が延びない");
            return;
        }
        SporeInfectionEffects.setPlayerStage(player, 3);
        ((MedicineItem) ModItems.SPORE_STOPPING_ELIXIR.get()).apply(player);
        if (SporeInfectionEffects.stageOf(player) != 2) {
            helper.fail("胞子止めの霊薬で 1 段下がらない: " + SporeInfectionEffects.stageOf(player));
            return;
        }
        ((MedicineItem) ModItems.STASIS_PILL.get()).apply(player);
        SporeInfectionEffects.advance(player, 10_000_000);
        if (SporeInfectionEffects.stageOf(player) != 2) {
            helper.fail("進行止めの丸薬を飲んでも進む: " + SporeInfectionEffects.stageOf(player));
            return;
        }
        player.removeEffect(ModMobEffects.STASIS);
        ((MedicineItem) ModItems.WARMBLOOM_PILL.get()).apply(player);
        SporeInfectionEffects.raisePlayerStage(player);
        if (SporeInfectionEffects.stageOf(player) != 3 || player.hasEffect(ModMobEffects.STAGE_GUARD)
                || player.getData(ModAttachments.SPORE_HEALTH_RELIEF) != 1) {
            helper.fail("暖花の丸薬の守りが働かない: 段階 " + SporeInfectionEffects.stageOf(player) + " / 守り "
                    + player.hasEffect(ModMobEffects.STAGE_GUARD) + " / 免れた段 " + player.getData(ModAttachments.SPORE_HEALTH_RELIEF));
            return;
        }
        double before = SporeInfectionDetection.bonusFor(player);
        ((MedicineItem) ModItems.SMELLING_SALTS.get()).apply(player);
        if (!MedicineEffects.symptomsSuppressed(player) || SporeInfectionDetection.bonusFor(player) != 0.0D) {
            helper.fail("気付け薬で症状(見つかりやすさ " + before + ")が抑えられない");
            return;
        }
        SporeInfectionEffects.setPlayerStage(player, 0);
        if (player.getData(ModAttachments.SPORE_HEALTH_RELIEF) != 0) {
            helper.fail("治っても免れた段が残る");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void moonlightHerbOnlyAtNight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HerbCropBlock crop = (HerbCropBlock) ModBlocks.MOONLIGHT_HERB_CROP.get();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState mature = crop.defaultBlockState().setValue(HerbCropBlock.AGE, HerbCropBlock.MAX_AGE);
        boolean dayHerb = Block.getDrops(mature.setValue(HerbCropBlock.BLOOM, false), level, pos, null).stream()
                .anyMatch(s -> s.is(ModItems.MOONLIGHT_HERB.get()));
        boolean nightHerb = Block.getDrops(mature.setValue(HerbCropBlock.BLOOM, true), level, pos, null).stream()
                .anyMatch(s -> s.is(ModItems.MOONLIGHT_HERB.get()));
        if (dayHerb || !nightHerb) {
            helper.fail("月光草の採れ方がおかしい(昼に採れる " + dayHerb + " / 夜に採れる " + nightHerb + ")");
            return;
        }
        boolean warmHerb = Block.getDrops(ModBlocks.WARMBLOOM_CROP.get().defaultBlockState().setValue(HerbCropBlock.AGE, 3), level, pos, null)
                .stream().anyMatch(s -> s.is(ModItems.WARMBLOOM.get()));
        if (!warmHerb) {
            helper.fail("育ちきった暖花から暖花が採れない");
            return;
        }
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.GRASS_BLOCK);
        if (!crop.wildState().canSurvive(level, pos) || crop.defaultBlockState().canSurvive(level, pos)) {
            helper.fail("草の上で: 野生の株が枯れる、または種から植えた株が育つ(耕した土の上だけのはず)");
            return;
        }
        helper.succeed();
    }

    private MortarGameTests() {
    }
}
