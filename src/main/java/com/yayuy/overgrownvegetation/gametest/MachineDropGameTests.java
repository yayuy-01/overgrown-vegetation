package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.registry.CreativeTabOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 人類側の機械の壊し方(2026-10-10 ユーザー指定「人類側の機械が壊しにくい問題とドロップしない問題。適正ツールはつるはしで、
 * 石以上であれば壊して回収することができるようにしてほしい」)。
 *
 * <p>原因: 胞子除去装置・胞子拡散装置・除染装置は鉄ブロックの性質を写していて「適正ツールでないと落ちない」のに、
 * どの道具の {@code mineable} タグにも入っていなかった(= 何で掘っても素手の速さで、何も落ちない)。
 * 研究台・作成テーブル・弾薬箱は斧が適正で素手でも落ちた(ユーザーが「機械として揃える」を選んだ)。
 *
 * <p>機械 = クリエイティブタブの区分が「機械」の置けるもの({@link CreativeTabOrder})。<b>基地の管理端末だけは除く</b>
 * (ユーザー指定: 基地の設備なので落ちないまま)。新しい機械はタブの機械の一覧に足せば、ここでも自動で調べられる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MachineDropGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MachineDropGameTests.class);
        }

        private Registration() {
        }
    }

    /** どの機械も: つるはしが適正・木のつるはしでは落ちず・石のつるはしで自分が落ちる。 */
    @GameTest(template = "gametest_empty7")
    public static void machinesNeedStonePickaxeAndDropThemselves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ItemStack wooden = new ItemStack(Items.WOODEN_PICKAXE);
        ItemStack stone = new ItemStack(Items.STONE_PICKAXE);
        List<String> checked = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem blockItem) || CreativeTabOrder.sectionOf(item) != CreativeTabOrder.Section.MACHINE) {
                continue;
            }
            Block block = blockItem.getBlock();
            if (block == BaseBlocks.BASE_TERMINAL.get()) {
                continue;
            }
            String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            BlockState state = block.defaultBlockState();
            if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                helper.fail(name + " が #minecraft:mineable/pickaxe に無い(何で掘っても素手の速さになる)");
                return;
            }
            if (state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(BlockTags.MINEABLE_WITH_SHOVEL) || state.is(BlockTags.MINEABLE_WITH_HOE)) {
                helper.fail(name + " がつるはし以外の mineable にも入っている");
                return;
            }
            if (!state.requiresCorrectToolForDrops()) {
                helper.fail(name + " が適正ツール無しでも落ちる(requiresCorrectToolForDrops が無い)");
                return;
            }
            if (wooden.isCorrectToolForDrops(state)) {
                helper.fail(name + " が木のつるはしで回収できる(#minecraft:needs_stone_tool に無い)");
                return;
            }
            if (!stone.isCorrectToolForDrops(state)) {
                helper.fail(name + " が石のつるはしで回収できない(needs_iron_tool 以上に入っている?)");
                return;
            }
            List<ItemStack> drops = Block.getDrops(state, level, pos, null, null, stone);
            if (drops.stream().noneMatch(stack -> stack.is(item))) {
                helper.fail(name + " を石のつるはしで壊しても自分が落ちない(ルートテーブル): " + drops);
                return;
            }
            checked.add(name);
        }
        if (checked.size() < 17) {
            helper.fail("機械が " + checked.size() + " 個しか見つからない(17 個のはず): " + checked);
            return;
        }
        helper.succeed();
    }

    private MachineDropGameTests() {
    }
}
