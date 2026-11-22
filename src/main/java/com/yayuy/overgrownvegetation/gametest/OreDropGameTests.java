package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 鉱石の落とし物(2026-10-09、ユーザー報告「クロロフィライト鉱石・樹液結晶・チラコイトなどを壊しても原石が出ない」)。
 *
 * <p>原因: 6 つの鉱石のルートテーブルのシルクタッチの判定が 1.20 の書き方({@code "predicate": {"enchantments": [...]}})だった。
 * 1.21 のアイテムの判定はこの欄を知らないので黙って捨て、空の判定(= どの道具でも当たり)になる → 普通のツルハシで掘っても
 * 「シルクタッチ」の枝が選ばれ、鉱石ブロックそのものが落ちていた。1.21 の書き方は {@code "predicate": {"predicates": {"minecraft:enchantments": [...]}}}。
 * 読み込みのエラーは出ないので、ログでは気づけない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OreDropGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OreDropGameTests.class);
        }

        private Registration() {
        }
    }


    /** 過繁茂しているバニラの鉱石 16 種(汚染が置き換える先。元のバニラの鉱石は名前から {@code overgrown_} を外したもの)。 */
    private static final String[] OVERGROWN_VANILLA_ORES = {
            "coal_ore", "copper_ore", "iron_ore", "gold_ore", "lapis_ore", "redstone_ore", "diamond_ore", "emerald_ore",
            "deepslate_coal_ore", "deepslate_copper_ore", "deepslate_iron_ore", "deepslate_gold_ore",
            "deepslate_lapis_ore", "deepslate_redstone_ore", "deepslate_diamond_ore", "deepslate_emerald_ore",
    };

    /**
     * 過繁茂しているバニラの鉱石は、元のバニラの鉱石と同じに振る舞う(2026-10-10 ユーザー指定「覆われた鉱石類を壊したときに原石が出ない問題を直して」。
     * それまでは自分自身だけを落とし、経験値も無く、木のツルハシでも掘れた)。落ちる物の種類・シルクタッチで自分・掘れるツルハシの段階・経験値の幅を元と比べる。
     */
    @GameTest(template = "gametest_empty7")
    public static void overgrownVanillaOresBehaveLikeVanillaOres(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        Holder<Enchantment> silkTouch = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        silk.enchant(silkTouch, 1);
        ItemStack[] tiers = {new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.IRON_PICKAXE)};
        for (String name : OVERGROWN_VANILLA_ORES) {
            Block ore = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("overgrown_" + name));
            Block vanilla = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(name));
            if (ore.defaultBlockState().isAir() || vanilla.defaultBlockState().isAir()) {
                helper.fail(name + " の過繁茂版か元が見つからない");
                return;
            }
            BlockState state = ore.defaultBlockState();
            Set<Item> got = new HashSet<>();
            Block.getDrops(state, level, pos, null, null, pickaxe).forEach(stack -> got.add(stack.getItem()));
            Set<Item> want = new HashSet<>();
            Block.getDrops(vanilla.defaultBlockState(), level, pos, null, null, pickaxe).forEach(stack -> want.add(stack.getItem()));
            if (want.isEmpty() || !got.equals(want)) {
                helper.fail("overgrown_" + name + " をツルハシで掘ると " + want + " が落ちるはずが " + got);
                return;
            }
            List<ItemStack> silked = Block.getDrops(state, level, pos, null, null, silk);
            if (silked.size() != 1 || !silked.get(0).is(ore.asItem())) {
                helper.fail("overgrown_" + name + " をシルクタッチで掘ると鉱石そのものが落ちるはずが " + silked);
                return;
            }
            if (!state.requiresCorrectToolForDrops()) {
                helper.fail("overgrown_" + name + " が適正ツール無しでも落ちる");
                return;
            }
            for (ItemStack tier : tiers) {
                if (tier.isCorrectToolForDrops(state) != tier.isCorrectToolForDrops(vanilla.defaultBlockState())) {
                    helper.fail("overgrown_" + name + " を " + tier.getItem() + " で掘れるかが元の鉱石と違う(needs_*_tool のタグ)");
                    return;
                }
            }
            int[] mine = xpRange(state, level, pos, pickaxe);
            int[] theirs = xpRange(vanilla.defaultBlockState(), level, pos, pickaxe);
            if (mine[0] != theirs[0] || mine[1] != theirs[1]) {
                helper.fail("overgrown_" + name + " の経験値 " + mine[0] + "〜" + mine[1] + " が元の " + theirs[0] + "〜" + theirs[1] + " と違う");
                return;
            }
        }
        helper.succeed();
    }

    /** 経験値を 400 回引いた最小と最大(幅は最大 5 通りなので、どれかを引き損ねることはまず無い)。 */
    private static int[] xpRange(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 400; i++) {
            int xp = state.getExpDrop(level, pos, null, null, tool);
            min = Math.min(min, xp);
            max = Math.max(max, xp);
        }
        return new int[]{min, max};
    }

    /** 1.20 の書き方のアイテムの判定(黙って空の判定になる)がこの mod のルートテーブルに残っていない。 */
    private static final Pattern OLD_ITEM_PREDICATE = Pattern.compile("\"predicate\":\\{\"(enchantments|stored_enchantments|item|tag|nbt|durability|potion)\"");

    @GameTest(template = "gametest_empty7")
    public static void lootTablesUseCurrentItemPredicateFormat(GameTestHelper helper) {
        Map<ResourceLocation, Resource> found = helper.getLevel().getServer().getResourceManager().listResources("loot_table",
                rl -> rl.getNamespace().equals(OvergrownVegetation.MODID) && rl.getPath().endsWith(".json"));
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                StringBuilder text = new StringBuilder();
                char[] buf = new char[4096];
                for (int n; (n = reader.read(buf)) > 0; ) {
                    text.append(buf, 0, n);
                }
                if (OLD_ITEM_PREDICATE.matcher(text.toString().replaceAll("\\s", "")).find()) {
                    offenders.add(entry.getKey().getPath());
                }
            } catch (Exception e) {
                offenders.add(entry.getKey().getPath() + " (読めない: " + e + ")");
            }
        }
        if (found.isEmpty()) {
            helper.fail("この mod のルートテーブルが 1 つも見つからない(探し方の誤り)");
        } else if (!offenders.isEmpty()) {
            helper.fail("1.20 の書き方のアイテムの判定が残っている(1.21 は \"predicates\": {\"minecraft:enchantments\": ...}): " + offenders);
        } else {
            helper.succeed();
        }
    }

    private OreDropGameTests() {
    }
}
