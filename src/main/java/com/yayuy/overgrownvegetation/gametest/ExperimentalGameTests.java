package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.network.ResearchSyncPayload;
import com.yayuy.overgrownvegetation.purify.BurntLand;
import com.yayuy.overgrownvegetation.purify.PurifyRegistry;
import com.yayuy.overgrownvegetation.purify.VanillaFire;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 実験的要素(2026-10-09、ユーザー指定「設定画面に『実験的要素』を用意し、オンのときだけ同盟軍を出す」)。
 * <ul>
 *     <li>項目の読み書き: オン・オフは 1 / 0、画面とチャットには「オン」「オフ」、既定はオフ({@link #toggleSettingReadsAndShows})。</li>
 *     <li>オフのワールド: 基地が生まれない(世界生成の写し)・同盟軍の研究ノードを送らない・成果物が同盟軍のアイテムのレシピは研究を開放していても封印・
 *     クリエイティブタブに出ない・火炎放射器は使えない。火による浄化(汚染された地面・覆われたブロックが変わる・焼けた荒地の候補)はオフでも起きる
 *     (10-10 にユーザー指定で隔離をやめた)。オンに戻すと全部戻る({@link #offWorldHidesAlliedArmy})。</li>
 *     <li>{@code /ov difficulty experimental} が値を書き、世界生成の写しも合わせる({@link #commandSwitchesExperimental})。</li>
 * </ul>
 * GameTest のサーバーは値が無ければオン(ほかのテストが同盟軍・浄化を前提にしている)なので、ここはオフを書いて<b>同じ tick の中で消す</b>
 * (ワールドに 1 つの値。戻し忘れると後のバッチの浄化・基地のテストが訳の分からない落ち方をする)。バッチも分ける。
 * 画面のボタンの見た目・クリエイティブのインベントリでの見え方はクライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ExperimentalGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ExperimentalGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "experimental";
    /** オフのワールドで隠れるはずの研究ノード(10-09 の同梱の研究ツリー)。 */
    private static final List<String> ALLIED_NODES = List.of("base_roads", "jamming_lamp", "base_walls", "base_supplies", "purification",
            "powered_armor_table", "factor_extraction", "armor_accessories"); // パワードアーマーまわりは 10-10 ユーザー指定で実験的要素へ
    /** オフでも残るはずの研究ノード(同盟軍より前からある防衛設備・胞子の対策)。 */
    private static final List<String> KEPT_NODES = List.of("defense_works", "anti_air", "spore_removal");

    /** オン・オフの項目の読み方・表示・丸め。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void toggleSettingReadsAndShows(GameTestHelper helper) {
        DifficultySetting s = DifficultySetting.EXPERIMENTAL;
        helper.assertTrue(s.kind() == DifficultySetting.Kind.TOGGLE, "実験的要素がオン・オフの項目でない");
        helper.assertTrue(s.defaultValue() == 0.0D, "既定がオフでない: " + s.defaultValue());
        helper.assertTrue(s.parse("on") == 1.0D && s.parse("true") == 1.0D && s.parse("1") == 1.0D
                && s.parse("off") == 0.0D && s.parse("false") == 0.0D && s.parse("0") == 0.0D, "on / off が読めない");
        helper.assertTrue(Double.isNaN(s.parse("2")) && Double.isNaN(s.parse("")), "オン・オフ以外を受け付けた");
        helper.assertTrue(s.clamp(0.7D) == 1.0D && s.clamp(0.2D) == 0.0D && s.clamp(5.0D) == 1.0D, "0 / 1 に丸まらない");
        helper.assertTrue(s.format(1.0D).equals("on") && s.format(0.0D).equals("off"), "文字列が on / off でない");
        helper.assertTrue(s.display(1.0D).equals(CommonComponents.OPTION_ON) && s.display(0.0D).equals(CommonComponents.OPTION_OFF),
                "画面・チャットの表示が「オン」「オフ」でない");
        helper.succeed();
    }

    /**
     * オフのワールドで同盟軍が隠れ、オンに戻すと全部戻る。値はワールドに 1 つなので、オフを書いてから確かめ終わるまでを同じ tick の中で済ませる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void offWorldHidesAlliedArmy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        WorldDifficultyData data = WorldDifficultyData.get(server);
        OptionalDouble saved = data.override(DifficultySetting.EXPERIMENTAL);
        helper.assertTrue(ExperimentalFeatures.enabled(server) && ExperimentalFeatures.worldgenEnabled(),
                "GameTest のサーバーで値が無いのにオンでない(ほかのテストが同盟軍を前提にしている)");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ResearchState research = player.getData(ModAttachments.RESEARCH);
        research.unlock("purification"); // 浄化を開放済みのプレイヤー(オンの間に開放した)
        player.setData(ModAttachments.RESEARCH, research);
        ResourceLocation flamethrowerRecipe = OvergrownVegetation.id("flamethrower");
        ResourceLocation repairRecipe = OvergrownVegetation.id("repair_material"); // 研究に載っていない同盟軍のレシピ

        BlockPos ground = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos flame = ground.above();
        BlockPos covered = helper.absolutePos(new BlockPos(5, 2, 3));
        Block overgrownDirt = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("overgrown_dirt"));
        Block coveredPlanks = CoveredBlocks.coveredOf(Blocks.OAK_PLANKS);
        RandomSource random = RandomSource.create(20261009L);
        ItemStack flamethrower = new ItemStack(PurifyRegistry.FLAMETHROWER.get());
        try {
            ExperimentalFeatures.set(server, false);
            check(helper, !ExperimentalFeatures.enabled(server) && !ExperimentalFeatures.worldgenEnabled(),
                    "オフを書いたのにオンのまま(世界生成の写し " + ExperimentalFeatures.worldgenEnabled() + ")");

            // 研究ツリー: 同盟軍のノードは送らない、ほかは送る
            ResearchTable table = DesignData.research();
            Set<String> sent = new HashSet<>();
            for (ResearchSyncPayload.NodeView view : ResearchSyncPayload.of(table, research,
                    node -> !ExperimentalFeatures.hidesNode(server, node)).nodes()) {
                sent.add(view.id());
            }
            for (String id : ALLIED_NODES) {
                check(helper, table.node(id) == null || !sent.contains(id), "オフなのに研究ノード " + id + " を送った");
            }
            for (String id : KEPT_NODES) {
                check(helper, table.node(id) == null || sent.contains(id), "同盟軍でない研究ノード " + id + " まで隠した");
            }

            // レシピ: 研究を開放していても封印、研究に載っていない同盟軍のレシピも封印、同盟軍でないレシピは関係ない
            check(helper, ResearchLogic.isRecipeLocked(player, flamethrowerRecipe), "オフなのに火炎放射器を作れる(浄化は開放済み)");
            check(helper, ResearchLogic.isRecipeLocked(player, repairRecipe), "オフなのに補修材を作れる");
            for (String recipe : List.of("powered_armor_table", "powered_armor_helmet", "factor_extractor", "cartridge", "night_vision_accessory", "mining_extension")) {
                check(helper, ExperimentalFeatures.locksRecipe(level, OvergrownVegetation.id(recipe)), "オフなのに " + recipe + " のレシピを封印しない(パワードアーマーまわり。研究とは別に)");
            }
            for (Item item : List.of(ModItems.POWERED_ARMOR_CHESTPLATE.get(), ModItems.FACTOR_TOXIN.get(), ModItems.SWIFT_ACCESSORY.get(), ModItems.ANCHOR_EXTENSION.get())) {
                check(helper, ExperimentalFeatures.isExperimentalItem(item), BuiltInRegistries.ITEM.getKey(item) + " が実験的要素のアイテムに入っていない");
            }
            check(helper, ResearchLogic.isRecipeLockedForAll(server, flamethrowerRecipe), "かまど側の判定がオフを見ていない");
            check(helper, !ExperimentalFeatures.locksRecipe(level, ResourceLocation.withDefaultNamespace("torch")),
                    "同盟軍でないレシピまで封印した");

            // クリエイティブタブ: 同盟軍のアイテムを出さない
            Set<Item> humanity = tabItems(level, ModCreativeTabs.HUMANITY.get());
            for (Item item : ExperimentalFeatures.experimentalItems()) {
                check(helper, !humanity.contains(item), "オフなのに " + BuiltInRegistries.ITEM.getKey(item) + " が人類のタブに出ている");
            }
            check(helper, humanity.contains(BuiltInRegistries.ITEM.get(OvergrownVegetation.id("gun_turret"))),
                    "同盟軍でない機関砲台までタブから消えた");

            // 火による浄化はオフでも起きる(10-10 にユーザー指定「炎の要素は隔離しない」で戻した): 汚染された土は上の火でも燃え尽きても変わり、
            // 覆われたブロックも変わり、焼けた荒地の候補を積む。燻る灰も実験的要素のアイテムではない。
            level.setBlock(ground, overgrownDirt.defaultBlockState(), Block.UPDATE_ALL);
            for (int step = 0; step < 300 && level.getBlockState(ground).is(overgrownDirt); step++) {
                if (!level.getBlockState(flame).is(Blocks.FIRE)) {
                    level.setBlock(flame, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
                }
                VanillaFire.step(level, flame, random);
            }
            check(helper, !level.getBlockState(ground).is(overgrownDirt), "オフだと上の火で汚染された土が変わらない(隔離をやめたはず)");
            level.setBlock(ground, overgrownDirt.defaultBlockState(), Block.UPDATE_ALL);
            VanillaFire.consume(level, ground, random, 0);
            check(helper, !level.getBlockState(ground).is(overgrownDirt), "オフだと汚染された土が燃え尽きても変わらない(隔離をやめたはず)");
            check(helper, BurntLand.isPending(ground), "オフだと焼けた荒地の候補を積まない(隔離をやめたはず)");
            level.setBlock(covered, coveredPlanks.defaultBlockState(), Block.UPDATE_ALL);
            VanillaFire.consume(level, covered, random, 0);
            check(helper, !level.getBlockState(covered).is(coveredPlanks), "オフだと覆われた板が燃え尽きても変わらない(隔離をやめたはず)");
            check(helper, !ExperimentalFeatures.experimentalItems().contains(PurifyRegistry.BURNT_SOIL_ITEM.get()),
                    "燻る灰が実験的要素のアイテムのまま(オフのワールドにもできるのにタブから消える)");

            // 火炎放射器は使えない
            player.setItemInHand(InteractionHand.MAIN_HAND, flamethrower.copy());
            player.getAbilities().instabuild = true; // 燃料の確かめを飛ばす(オンなら使える)
            InteractionResult off = PurifyRegistry.FLAMETHROWER.get().use(level, player, InteractionHand.MAIN_HAND).getResult();
            check(helper, off == InteractionResult.FAIL, "オフなのに火炎放射器を使えた: " + off);
            player.stopUsingItem();

            // オンに戻すと全部戻る
            ExperimentalFeatures.set(server, true);
            check(helper, ExperimentalFeatures.worldgenEnabled(), "オンに戻したのに世界生成の写しがオフのまま");
            check(helper, !ResearchLogic.isRecipeLocked(player, flamethrowerRecipe), "オンなのに浄化を開放したプレイヤーが火炎放射器を作れない");
            ResearchTable.Node purification = table.node("purification"); // run/config の古い写しには無いことがある
            check(helper, purification == null || !ExperimentalFeatures.hidesNode(server, purification), "オンなのに浄化のノードを隠した");
            check(helper, tabItems(level, ModCreativeTabs.HUMANITY.get()).contains(BaseBlocks.ASPHALT.get().asItem()),
                    "オンなのにアスファルトがタブに出ない");
            level.setBlock(ground, overgrownDirt.defaultBlockState(), Block.UPDATE_ALL);
            VanillaFire.consume(level, ground, random, 0);
            check(helper, !level.getBlockState(ground).is(overgrownDirt), "オンなのに汚染された土が燃え尽きても変わらない");
            InteractionResult on = PurifyRegistry.FLAMETHROWER.get().use(level, player, InteractionHand.MAIN_HAND).getResult();
            check(helper, on.consumesAction(), "オンなのに火炎放射器を使えない: " + on);
            player.stopUsingItem();
        } finally {
            if (saved.isPresent()) {
                data.set(DifficultySetting.EXPERIMENTAL, saved.getAsDouble());
            } else {
                data.reset(DifficultySetting.EXPERIMENTAL);
            }
            ExperimentalFeatures.refresh(server);
            level.setBlock(flame, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }


    // ------------------------------------------------------------------ 道具

    /** タブを組み直して中身のアイテムを返す(専用サーバーで組むので、外すかどうかはサーバーの値で決まる)。 */
    private static Set<Item> tabItems(ServerLevel level, CreativeModeTab tab) {
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess()));
        Set<Item> out = new HashSet<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            out.add(stack.getItem());
        }
        for (ItemStack stack : tab.getSearchTabDisplayItems()) {
            out.add(stack.getItem());
        }
        return out;
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message); // 投げるので、finally が値を戻してから落ちる
        }
    }

    private ExperimentalGameTests() {
    }
}
