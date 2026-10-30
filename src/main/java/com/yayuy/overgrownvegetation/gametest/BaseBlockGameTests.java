package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.AmmoCrateBlockEntity;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.List;

/**
 * 同盟軍の基地の建材(2026-10-01 第 1 弾、{@link BaseBlocks})のテスト。
 *
 * <p>守りたいのは「置けて、壊すと戻り、作り方が研究に載っている」こと。素材(json)の書き忘れは
 * runClient でしか紫黒の絵として見えないので、サーバーで確かめられるもの(ルートテーブル・レシピ・タグ・
 * 研究の封印)はここで拾う。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseBlockGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseBlockGameTests.class);
        }

        private Registration() {
        }
    }

    private static final TagKey<Block> ROAD_BLOCKS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "road_blocks"));

    /** 石切台のレシピ(研究には載せない。元のブロックが封印されているので先回りできない)。 */
    private static final List<String> STONECUTTING = List.of(
            "asphalt_slab_from_asphalt_stonecutting",
            "asphalt_stairs_from_asphalt_stonecutting",
            "reinforced_concrete_slab_from_reinforced_concrete_stonecutting",
            "reinforced_concrete_stairs_from_reinforced_concrete_stonecutting",
            "reinforced_concrete_wall_from_reinforced_concrete_stonecutting");

    /** どの建材も置ける(ブロック状態が残る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void everyBlockPlaces(GameTestHelper helper) {
        List<DeferredBlock<Block>> all = BaseBlocks.all();
        for (int i = 0; i < all.size(); i++) {
            BlockPos pos = new BlockPos(i % 6, 2 + i / 6, 0);
            Block block = all.get(i).get();
            helper.setBlock(pos, block);
            if (!helper.getBlockState(pos).is(block)) {
                helper.fail(all.get(i).getId() + " が置けない");
                return;
            }
        }
        helper.succeed();
    }

    /** ルートテーブル・作業台のレシピ・研究の封印がそろっている。石切台のレシピも読めている。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void lootRecipesAndResearch(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (DeferredBlock<Block> holder : BaseBlocks.all()) {
            Block block = holder.get();
            ResourceLocation id = holder.getId();
            LootTable loot = server.reloadableRegistries().getLootTable(block.getLootTable());
            if (loot == LootTable.EMPTY) {
                helper.fail(id + " のルートテーブルが無い");
                return;
            }
            if (server.getRecipeManager().byKey(id).isEmpty()) {
                helper.fail(id + " のレシピが読み込まれていない(result が文字列だと黙って消える)");
                return;
            }
            if (DesignData.research().nodeForRecipe(id) == null) {
                helper.fail(id + " のレシピが研究に載っていない(最初から作れてしまう)");
                return;
            }
        }
        for (String path : STONECUTTING) {
            if (server.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path)).isEmpty()) {
                helper.fail(path + " が読み込まれていない");
                return;
            }
        }
        helper.succeed();
    }

    /** 道のタグと、塀どうしのつながり(#minecraft:walls に入っていないとつながらない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void roadTagAndWallsConnect(GameTestHelper helper) {
        for (Block road : List.of(BaseBlocks.ASPHALT.get(), BaseBlocks.ASPHALT_SLAB.get(), BaseBlocks.ASPHALT_STAIRS.get())) {
            if (!road.defaultBlockState().is(ROAD_BLOCKS)) {
                helper.fail(road + " が road_blocks に無い");
                return;
            }
        }
        if (!BaseBlocks.REINFORCED_CONCRETE_WALL.get().defaultBlockState().is(BlockTags.WALLS)) {
            helper.fail("鉄筋コンクリートの塀が #minecraft:walls に無い");
            return;
        }
        BlockPos west = new BlockPos(2, 2, 2);
        BlockPos east = west.east();
        helper.setBlock(west, BaseBlocks.REINFORCED_CONCRETE_WALL.get());
        helper.setBlock(east, BaseBlocks.REINFORCED_CONCRETE_WALL.get());
        helper.runAfterDelay(2, () -> {
            if (helper.getBlockState(west).getValue(WallBlock.EAST_WALL) == WallSide.NONE) {
                helper.fail("隣り合う塀がつながらない");
                return;
            }
            helper.succeed();
        });
    }

    /** 弾薬箱は中身を持ち、壊すと箱と中身の両方が出る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void ammoCrateKeepsAndDropsContents(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 2, 3);
        helper.setBlock(pos, BaseBlocks.AMMO_CRATE.get());
        if (!(helper.getBlockEntity(pos) instanceof AmmoCrateBlockEntity crate)) {
            helper.fail("弾薬箱の中身(ブロックエンティティ)が作られていない");
            return;
        }
        crate.setItem(0, new ItemStack(Items.ARROW, 7));
        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityPresent(Items.ARROW, pos, 2.0D);
            helper.assertItemEntityPresent(BaseBlocks.AMMO_CRATE.get().asItem(), pos, 2.0D);
            helper.succeed();
        });
    }

    private BaseBlockGameTests() {
    }
}
