package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.purify.Burning;
import com.yayuy.overgrownvegetation.purify.BurntLand;
import com.yayuy.overgrownvegetation.purify.FlamethrowerItem;
import com.yayuy.overgrownvegetation.purify.PurifyRegistry;
import com.yayuy.overgrownvegetation.purify.TreeBurn;
import com.yayuy.overgrownvegetation.purify.VanillaFire;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 浄化 —— 火炎放射器と焼けた荒地(順 2 の着手順 9、2026-10-08)。10-09 朝に、火炎放射器はふつうの火をまく道具になり、この MOD の木・植物・
 * 覆われたブロックは燃え尽きにくく燃え移りにくい形へ作り直した(木は幹が焼け切れて地面から離れた部分だけが丸ごと燃え落ちる)。
 * <ul>
 *     <li>燃え方の分け方と変わり方: ユーザー指定の 5 つは焼けた土、そのほかの汚染は汚染される前のブロック、地脈・結晶・汚染されていないものは燃えない
 *     ({@link #kindsAndConversion})。燃え尽き方: 覆われたブロックは元のブロック、植物と木は消える、地面に埋まった根は焼けた土。ふつうの火は燃えない壁の前の宙に付かない
 *     ({@link #consumeByKind})。</li>
 *     <li>炎の光線は下草をその場で火にして通り抜け、当たったブロックのまわり(裏・横も)にふつうの火を付ける。覆われたブロックはその場では変わらない
 *     ({@link #flameRayLightsVanillaFire}・{@link #flameWrapsAroundTrunk})。</li>
 *     <li>ふつうの火の表: 燃え移りやすさ 0・燃え尽きにくい値。汚染された土の上の火は消える前に土を変えることが多いが、変えずに消えることもある。
 *     覆われた床は上の火では変わらない。汚染のそばの火は消えやすい({@link #fireOnCorruptedGround})。</li>
 *     <li>木: 幹が焼け切れると、地面から離れた部分(混ざったバニラの原木・覆われた原木・葉も)が丸ごと燃え落ち、切り株は残る。汚染されていない木も同じ
 *     ({@link #severedTreeBurnsWhole})。支えのある木の葉が燃えても、建物の原木・巨大樹が焼け切れても丸ごとは燃えない。原木の無い宙の葉の塊は燃え落ちる
 *     ({@link #supportedTreesStay})。炎を出さずに丸ごと消す口({@link #removeQuietlyClearsWholeTree})。</li>
 *     <li>焼けた荒地になったチャンクは、バイオームが焼けた荒地・汚染の台帳から外れる・汚染が入らない。3 日たつと世界生成のときのバイオームへ戻り、
 *     台帳(焼けた荒地)から消える({@link #burntChunkIsFirebreakAndReverts}。テスト場のチャンクを使うので 1 本 1 バッチ)。火で汚染が変わったチャンクは
 *     まとめて見たときに焼けた荒地になる({@link #fireFlipsLedgerChunk})。</li>
 *     <li>燻る灰ブロック(焼けた土。10-09 に改名)は砂と同じく落ちる({@link #ashFallsLikeSand})。</li>
 *     <li>燃料は石炭・木炭を 1 個ずつ使い、1 個で 200 tick 吹ける({@link #fuelUsesCoalAndCharcoal})。</li>
 *     <li>レシピがあり、同梱の研究ツリーの「浄化」(前提は対空。10-10 にユーザーが胞子の除去から移した)が封印する。焼けた荒地のバイオームがある。焼けた土は汚染されない。
 *     専用の炎のブロックは無い。言語ファイルに名前がある({@link #recipeResearchBiomeAndLang})。</li>
 * </ul>
 * 右クリック長押しで吹く手触り・火の広がり方・木が燃え落ちる見た目・焼けた荒地の見た目はクライアントで確かめる
 * (GameTest にプレイヤーがいない。GameTest のサーバーは火の広がりが切られているので、火の更新の中身を直接呼ぶ)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PurifyGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    /**
     * 偽のプレイヤー(ワールドに 1 人で、ほかのテストも使う)の位置・持ち物を変えるテストのバッチ。ほかのクラスの偽のプレイヤーを使うテストと同時に走らせない。
     */
    private static final String FAKE_PLAYER_BATCH = "purify_fake_player";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PurifyGameTests.class);
        }

        private Registration() {
        }
    }

    private static Block mod(String path) {
        return BuiltInRegistries.BLOCK.get(OvergrownVegetation.id(path));
    }

    private static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /** テスト場の中(床より上)を空気に戻す。 */
    private static void clearArena(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 7; y++) {
                for (int z = 0; z <= 6; z++) {
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** 燃え尽きて消えた(空気か、そのマスに火が残った)。 */
    private static boolean gone(BlockState state) {
        return state.isAir() || state.is(BlockTags.FIRE);
    }

    /** 燃え方の分け方と、焼けたときの変わり方。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void kindsAndConversion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Object[][] kinds = {
                {mod("ley_vein"), Burning.Kind.NONE}, {mod("energy_crystal"), Burning.Kind.NONE},
                {mod("chlorophyllite_ore"), Burning.Kind.NONE}, {PurifyRegistry.BURNT_SOIL.get(), Burning.Kind.NONE},
                {Blocks.GRASS_BLOCK, Burning.Kind.NONE}, {Blocks.OAK_LOG, Burning.Kind.NONE},
                {mod("giant_overgrown_log"), Burning.Kind.TREE}, {mod("hardened_giant_trunk"), Burning.Kind.TREE},
                {mod("overgrown_leaves"), Burning.Kind.TREE}, {mod("overgrown_log"), Burning.Kind.TREE},
                {mod("overgrown_growth"), Burning.Kind.PLANT},
                {mod("overgrown_cow_corpse"), Burning.Kind.PLANT},
                {mod("overgrown_dirt"), Burning.Kind.GROUND}, {CoveredBlocks.coveredOf(Blocks.COBBLESTONE), Burning.Kind.GROUND},
        };
        for (Object[] k : kinds) {
            Block block = (Block) k[0];
            helper.assertTrue(block != null && block != Blocks.AIR, "テストのブロックが無い: " + k[1]);
            helper.assertTrue(Burning.kind(block.defaultBlockState()) == k[1],
                    name(block) + " の燃え方が " + Burning.kind(block.defaultBlockState()) + "(" + k[1] + " のはず)");
        }
        // この MOD が燃え尽き方を決めるのは汚染と木(汚染されていない木も —— 宙に残った部分を見る)。木材・草ブロック・地脈はバニラのまま。
        helper.assertTrue(VanillaFire.handles(Blocks.OAK_LOG.defaultBlockState()) && VanillaFire.handles(mod("overgrown_growth").defaultBlockState())
                && VanillaFire.handles(CoveredBlocks.coveredOf(Blocks.COBBLESTONE).defaultBlockState()), "汚染・木を扱わない");
        helper.assertTrue(!VanillaFire.handles(Blocks.OAK_PLANKS.defaultBlockState()) && !VanillaFire.handles(Blocks.GRASS_BLOCK.defaultBlockState())
                && !VanillaFire.handles(mod("ley_vein").defaultBlockState()), "汚染でも木でもないものを扱う");

        Block burnt = PurifyRegistry.BURNT_SOIL.get();
        Object[][] cases = {
                {mod("overgrown_grass_block"), burnt}, {mod("overgrown_dirt"), burnt}, {mod("overgrown_gravel"), burnt},
                {mod("overgrown_mud"), burnt}, {mod("overgrown_block"), burnt},
                {mod("overgrown_stone"), Blocks.STONE}, {mod("overgrown_sand"), Blocks.SAND},
                {mod("overgrown_iron_ore"), Blocks.IRON_ORE}, {mod("overgrown_netherrack"), Blocks.NETHERRACK},
                {CoveredBlocks.coveredOf(Blocks.COBBLESTONE), Blocks.COBBLESTONE}, {mod("overgrown_wall"), Blocks.COBBLESTONE_WALL},
                {mod("overgrown_log"), mod("overgrown_log")}, {Blocks.GRASS_BLOCK, Blocks.GRASS_BLOCK},
        };
        for (int i = 0; i < cases.length; i++) {
            Block before = (Block) cases[i][0];
            Block expected = (Block) cases[i][1];
            BlockPos pos = helper.absolutePos(new BlockPos(1 + (i % 5), 2 + (i / 5) * 2, 1 + (i % 2) * 3));
            level.setBlock(pos, before.defaultBlockState(), Block.UPDATE_ALL);
            Burning.convert(level, pos, true);
            Block after = level.getBlockState(pos).getBlock();
            helper.assertTrue(after == expected, name(before) + " が焼けて " + name(after) + " になった(" + name(expected) + " のはず)");
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        clearArena(helper);
        helper.succeed();
    }

    /**
     * 燃え尽き方({@link VanillaFire#consume} を直接呼ぶ): 覆われたブロック・覆われた原木・消えた松明は元に戻り、汚染の草は消え(火が残ることもある)、
     * 床に埋まった根は焼けた土、立っている原木は消える。木材はバニラの決まりのまま。ふつうの火は、燃えない壁の前の宙には付かず、覆われたブロックの前なら付く。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void consumeByKind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RandomSource random = RandomSource.create(20261009L);
        Block coveredCobble = CoveredBlocks.coveredOf(Blocks.COBBLESTONE);
        Block coveredOak = CoveredBlocks.coveredOf(Blocks.OAK_LOG);
        helper.assertTrue(coveredOak != null && coveredOak != Blocks.AIR, "覆われたオークの原木が無い");

        Object[][] reverts = {
                {new BlockPos(1, 2, 1), coveredCobble, Blocks.COBBLESTONE}, {new BlockPos(2, 2, 1), coveredOak, Blocks.OAK_LOG},
                {new BlockPos(3, 2, 1), CoveredBlocks.EXTINGUISHED_TORCH.get(), Blocks.TORCH},
                {new BlockPos(5, 2, 1), mod("overgrown_block"), PurifyRegistry.BURNT_SOIL.get()},
        };
        for (Object[] r : reverts) {
            BlockPos p = helper.absolutePos((BlockPos) r[0]);
            level.setBlock(p, ((Block) r[1]).defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(level.getBlockState(p).is((Block) r[1]), name((Block) r[1]) + " が置けない");
            VanillaFire.consume(level, p, random, 0);
            helper.assertTrue(level.getBlockState(p).is((Block) r[2]), name((Block) r[1]) + " が燃え尽きて " + level.getBlockState(p)
                    + " になった(" + name((Block) r[2]) + " のはず)");
        }

        BlockPos soil = helper.absolutePos(new BlockPos(1, 1, 3));
        BlockPos growth = soil.above();
        level.setBlock(soil, mod("overgrown_dirt").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(growth, mod("overgrown_growth").defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(level.getBlockState(growth).is(mod("overgrown_growth")), "汚染の草が置けない");
        VanillaFire.consume(level, growth, random, 0);
        helper.assertTrue(gone(level.getBlockState(growth)), "燃え尽きた汚染の草が残る: " + level.getBlockState(growth));

        BlockPos root = helper.absolutePos(new BlockPos(3, 1, 3)); // 横が床の石 = 地面に埋まった根
        level.setBlock(root, mod("overgrown_wood").defaultBlockState(), Block.UPDATE_ALL);
        VanillaFire.consume(level, root, random, 0);
        helper.assertTrue(level.getBlockState(root).is(PurifyRegistry.BURNT_SOIL.get()), "床に埋まった根が焼けた土にならない(穴が開く): "
                + level.getBlockState(root));

        BlockPos standing = helper.absolutePos(new BlockPos(5, 2, 3));
        level.setBlock(standing, mod("overgrown_log").defaultBlockState(), Block.UPDATE_ALL);
        VanillaFire.consume(level, standing, random, 15);
        helper.assertTrue(gone(level.getBlockState(standing)), "燃え尽きた原木が残る: " + level.getBlockState(standing));

        BlockPos planks = helper.absolutePos(new BlockPos(5, 2, 5));
        level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        // 先に走ったテストが残したハイヴノードの記録が水平 512 以内にあると、ハイヴノードの守り(HiveFireGuard)で「横取り」に見える
        // (2026-10-16、テストを足して格子の位置がずれたら落ちるようになった)。木材の決まりだけを見るため、近くの記録をこの間だけ外す。
        PhaseData phaseData = PhaseData.get(level);
        java.util.List<BlockPos> nearNodes = new java.util.ArrayList<>();
        for (long packed : phaseData.hiveNodeSnapshot()) {
            BlockPos n = BlockPos.of(packed);
            if (Math.hypot(n.getX() - planks.getX(), n.getZ() - planks.getZ()) <= com.yayuy.overgrownvegetation.purify.HiveFireGuard.RADIUS + 1.0D) {
                nearNodes.add(n);
            }
        }
        nearNodes.forEach(phaseData::removeHiveNode);
        try {
            helper.assertTrue(!VanillaFire.onBurnOut(level, planks, 300, random, 0, Direction.WEST), "木材の燃え方をこの MOD が横取りした");
        } finally {
            nearNodes.forEach(phaseData::recordHiveNode);
        }

        // ふつうの火は、燃えない壁の前の宙には付かず、燃える物(覆われたブロック)の前なら付く。
        BlockPos wall = helper.absolutePos(new BlockPos(3, 4, 5));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!Burning.ignite(level, wall.west()), "燃えない壁の前の宙に火が付いた");
        level.setBlock(wall, coveredCobble.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(Burning.ignite(level, wall.west()), "覆われたブロックの前に火が付かない");
        helper.assertTrue(level.getBlockState(wall.west()).is(Blocks.FIRE), "付いたのがふつうの火でない: " + level.getBlockState(wall.west()));
        // 過繁茂しているブロックの壁の前の宙にも火が付く(2026-10-09 ユーザー指摘「過繁茂しているブロックに火がつかない」)。
        level.setBlock(wall.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wall, mod("overgrown_block").defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(Burning.ignite(level, wall.west()), "過繁茂しているブロックの壁の前に火が付かない");

        level.setBlock(soil, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(root, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        clearArena(helper);
        helper.succeed();
    }

    /**
     * 偽のプレイヤー(テスト場の西の端、東向き)から炎を吹く: 目の高さの汚染の草はその場でふつうの火になり(炎は通り抜ける)、その先の覆われた丸石の壁で止まる。
     * 壁の前の 3 × 3 にふつうの火が付く。壁はその場では変わらない。厚さ 1 の壁なので裏にも付く(2026-10-09「ブロックの裏や側面にも」)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = FAKE_PLAYER_BATCH)
    public static void flameRayLightsVanillaFire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 3));
        player.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, -90.0F, 0.0F); // 東(+X)向き・水平
        int eyeY = (int) Math.floor(player.getEyeY());
        BlockPos fernGround = new BlockPos(feet.getX() + 3, eyeY - 1, feet.getZ());
        BlockPos fern = fernGround.above();
        Block coveredCobble = CoveredBlocks.coveredOf(Blocks.COBBLESTONE);
        level.setBlock(fernGround, mod("overgrown_grass_block").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(fern, mod("overgrown_growth").defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(level.getBlockState(fern).is(mod("overgrown_growth")), "汚染の草が置けない(支えが無い?)");
        List<BlockPos> wall = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = new BlockPos(feet.getX() + 4, eyeY + dy, feet.getZ() + dz);
                level.setBlock(p, coveredCobble.defaultBlockState(), Block.UPDATE_ALL);
                wall.add(p);
            }
        }
        BlockPos behind = new BlockPos(feet.getX() + 5, eyeY, feet.getZ());

        Burning.spray(level, player, FlamethrowerItem.RANGE, false);

        helper.assertTrue(level.getBlockState(fern).is(Blocks.FIRE), "汚染の草がふつうの火にならない: " + level.getBlockState(fern));
        int fires = 0;
        for (BlockPos p : wall) {
            helper.assertTrue(level.getBlockState(p).is(coveredCobble), "炎が当たっただけで壁が変わった: " + level.getBlockState(p));
            if (level.getBlockState(p.west()).is(Blocks.FIRE)) {
                fires++;
            }
        }
        helper.assertTrue(fires >= 7, "壁の前の 3 × 3 に火が付かない: " + fires + " / 9(草の台の 1 マスは付かない)");
        helper.assertTrue(level.getBlockState(behind).is(Blocks.FIRE), "厚さ 1 の壁の裏に火が付かない: " + level.getBlockState(behind)
                + " / 壁 " + level.getBlockState(behind.west()) + " 燃える " + level.getBlockState(behind.west()).isFlammable(level, behind.west(), Direction.EAST)
                + " / 裏の下 " + level.getBlockState(behind.below()) + " / 裏の上 " + level.getBlockState(behind.above())
                + " / 草の所 " + level.getBlockState(fern) + " / 草の下 " + level.getBlockState(fernGround));
        level.setBlock(fernGround, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        clearArena(helper);
        helper.succeed();
    }

    /**
     * 過繁茂した茂み(当たり判定の無い、葉の塊のような植物)の塊に炎を当てると、炎はいちばん手前の茂みで止まり、茂みごとまわりに火が付く
     * (2026-10-09 ユーザー指摘「過繁茂した茂みに火がつけれない」—— 前は炎がすり抜け、通った列にしか付かなかった)。止まった所から 2 マス奥の茂みは燃えない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = FAKE_PLAYER_BATCH)
    public static void flameStopsAtBushes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 3));
        player.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, -90.0F, 0.0F); // 東(+X)向き・水平
        int eyeY = (int) Math.floor(player.getEyeY());
        Block bush = mod("overgrown_bush");
        List<BlockPos> near = new ArrayList<>();
        List<BlockPos> far = new ArrayList<>();
        for (int dx = 3; dx <= 5; dx++) {
            for (int y = feet.getY(); y <= eyeY + 1; y++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(feet.getX() + dx, y, feet.getZ() + dz);
                    level.setBlock(p, bush.defaultBlockState(), Block.UPDATE_ALL);
                    (dx == 5 ? far : near).add(p);
                }
            }
        }
        BlockPos hit = new BlockPos(feet.getX() + 3, eyeY, feet.getZ());

        Burning.spray(level, player, FlamethrowerItem.RANGE, false);

        // 茂みは火になる(支えの茂みも火になって宙に浮いた火は、その場で燃え落ちて空気になる)。
        helper.assertTrue(gone(level.getBlockState(hit)), "炎が当たった茂みが燃えない: " + level.getBlockState(hit));
        int burnt = 0;
        int fires = 0;
        for (BlockPos p : near) {
            BlockState s = level.getBlockState(p);
            if (gone(s)) {
                burnt++;
            }
            if (s.is(Blocks.FIRE)) {
                fires++;
            }
        }
        helper.assertTrue(burnt >= near.size() / 2 && fires > 0, "当たった茂みのまわりの茂みが燃えない: 燃えた " + burnt + "(火 " + fires + ")/ "
                + near.size());
        for (BlockPos p : far) {
            helper.assertTrue(level.getBlockState(p).is(bush), "炎がすり抜けて奥の茂みまで燃えた: " + helper.relativePos(p) + " " + level.getBlockState(p));
        }
        clearArena(helper);
        helper.succeed();
    }

    /** 1 本だけ立った幹に炎を当てると、手前だけでなく横と裏にも火が付く(2026-10-09 ユーザー指定「ブロックの裏や側面にも火をつけれるように」)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = FAKE_PLAYER_BATCH)
    public static void flameWrapsAroundTrunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 3));
        player.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, -90.0F, 0.0F); // 東(+X)向き・水平
        int eyeY = (int) Math.floor(player.getEyeY());
        for (int dy = -1; dy <= 1; dy++) {
            level.setBlock(new BlockPos(feet.getX() + 3, eyeY + dy, feet.getZ()), mod("overgrown_log").defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos hit = new BlockPos(feet.getX() + 3, eyeY, feet.getZ());

        Burning.spray(level, player, FlamethrowerItem.RANGE, false);

        helper.assertTrue(level.getBlockState(hit.west()).is(Blocks.FIRE), "幹の手前に火が付かない: " + level.getBlockState(hit.west()));
        helper.assertTrue(level.getBlockState(hit.north()).is(Blocks.FIRE) && level.getBlockState(hit.south()).is(Blocks.FIRE),
                "幹の横に火が付かない: 北 " + level.getBlockState(hit.north()) + " / 南 " + level.getBlockState(hit.south()));
        helper.assertTrue(level.getBlockState(hit.east()).is(Blocks.FIRE), "幹の裏に火が付かない: " + level.getBlockState(hit.east()));
        helper.assertTrue(level.getBlockState(hit).is(mod("overgrown_log")), "炎が当たっただけで幹が燃えた: " + level.getBlockState(hit));
        clearArena(helper);
        helper.succeed();
    }

    /**
     * ふつうの火の表: この MOD の植物・葉・木・覆われたブロック・汚染された地面は燃え移りやすさ 0 で、燃え尽きにくい値(覆われた木材も木材の 20 ではなく同じ値)。
     * 燻る灰ブロック・地脈は燃えない。汚染された土の上の火を消えるまで回すと、土を燻る灰ブロックにすることがほとんどだが、変えずに消えることもある
     * (1 本の乱数を回し続けて数える)。覆われた床は上の火では変わらない(燃え尽きにくい)。燻る灰の上の火はすぐには消えずにくすぶる。
     * 汚染のそばの火は消え、汚染から離れた火は消えない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void fireOnCorruptedGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FireBlock vanilla = (FireBlock) Blocks.FIRE;
        Object[][] table = {
                {mod("overgrown_growth"), VanillaFire.PLANT_BURN}, {mod("overgrown_leaves"), VanillaFire.LEAF_BURN},
                {mod("overgrown_log"), VanillaFire.WOOD_BURN}, {mod("giant_overgrown_log"), VanillaFire.WOOD_BURN},
                {CoveredBlocks.coveredOf(Blocks.COBBLESTONE), VanillaFire.COVERED_BURN},
                {CoveredBlocks.coveredOf(Blocks.OAK_PLANKS), VanillaFire.COVERED_BURN},
                {CoveredBlocks.EXTINGUISHED_TORCH.get(), VanillaFire.COVERED_BURN}, {ModBlocks.OVERGROWN_HUSK.get(), VanillaFire.COVERED_BURN},
                {mod("overgrown_dirt"), VanillaFire.GROUND_BURN}, {mod("overgrown_block"), VanillaFire.GROUND_BURN},
                {mod("overgrown_stone"), VanillaFire.GROUND_BURN}, {PurifyRegistry.BURNT_SOIL.get(), 0}, {mod("ley_vein"), 0},
                {mod("overgrown_netherrack"), 0}, // 元が消えない火の台は燃える物にしない(溶岩のそばで消えない火が増える)
        };
        for (Object[] row : table) {
            BlockState s = ((Block) row[0]).defaultBlockState();
            helper.assertTrue(vanilla.getBurnOdds(s) == (int) row[1] && vanilla.getIgniteOdds(s) == 0,
                    name((Block) row[0]) + " の燃えやすさが違う: 燃え尽き " + vanilla.getBurnOdds(s) + "(" + row[1] + " のはず)/ 燃え移り "
                            + vanilla.getIgniteOdds(s));
        }

        BlockPos ground = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos flame = ground.above();
        RandomSource random = RandomSource.create(20261008L);
        int trials = 300;
        int converted = 0;
        for (int i = 0; i < trials; i++) {
            level.setBlock(ground, mod("overgrown_dirt").defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(flame, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
            // 地面が燃える物なので、火は年齢では消えない(消えるのは汚染のそばで消える分だけ)。
            for (int step = 0; step < 400 && level.getBlockState(flame).is(Blocks.FIRE); step++) {
                VanillaFire.step(level, flame, random);
                if (level.getBlockState(ground).is(PurifyRegistry.BURNT_SOIL.get())) {
                    converted++;
                    break;
                }
            }
            level.setBlock(flame, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.assertTrue(converted > trials * 70 / 100 && converted < trials * 97 / 100,
                "火が消える前に燻る灰ブロックになった割合が 8 割ほどから遠い: " + converted + " / " + trials);

        // 覆われた床は上の火では変わらない(燃える物なので、バニラの火の決まりで燃え尽きる)。
        Block coveredCobble = CoveredBlocks.coveredOf(Blocks.COBBLESTONE);
        level.setBlock(ground, coveredCobble.defaultBlockState(), Block.UPDATE_ALL);
        for (int step = 0; step < 100; step++) {
            if (!level.getBlockState(flame).is(Blocks.FIRE)) {
                level.setBlock(flame, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
            }
            VanillaFire.step(level, flame, random);
        }
        helper.assertTrue(level.getBlockState(ground).is(coveredCobble), "覆われた床が上の火ですぐ変わった: " + level.getBlockState(ground));

        // 燻る灰の上の火(隣に燃える物が無い)はすぐには消えず、平均 1 / ASH_FIRE_OUT 回の火の更新でくすぶって消える(バニラなら年齢で 12 回ほどで消える)。
        level.setBlock(ground, PurifyRegistry.BURNT_SOIL.get().defaultBlockState(), Block.UPDATE_ALL);
        int runs = 200;
        int total = 0;
        for (int i = 0; i < runs; i++) {
            level.setBlock(flame, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
            int life = 0;
            while (level.getBlockState(flame).is(Blocks.FIRE) && life < 1000) {
                helper.assertTrue(VanillaFire.step(level, flame, random), "燻る灰の上の火をバニラに任せた(年齢で消える)");
                life++;
            }
            total += life;
        }
        double mean = total / (double) runs;
        double expected = 1.0D / VanillaFire.ASH_FIRE_OUT;
        helper.assertTrue(mean > expected * 0.6D && mean < expected * 1.5D,
                "燻る灰の上の火がくすぶる長さが違う: 平均 " + mean + " 回(" + expected + " 回ほどのはず)");

        // 汚染の草のそばの火(下はきれいな石)は消える。
        level.setBlock(ground, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(ground.east(), mod("overgrown_dirt").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(flame.east(), mod("overgrown_growth").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(flame, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
        boolean fizzled = false;
        for (int step = 0; step < 400 && !fizzled; step++) {
            fizzled = VanillaFire.step(level, flame, random);
        }
        helper.assertTrue(fizzled && level.getBlockState(flame).isAir(), "汚染の草のそばの火が消えない");

        // 汚染から離れた火は消さない(バニラの決まりのまま)。
        BlockPos far = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(far, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
        for (int step = 0; step < 400; step++) {
            helper.assertTrue(!VanillaFire.step(level, far, random), "汚染から離れた火を消した");
        }
        level.setBlock(ground.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        clearArena(helper);
        helper.succeed();
    }

    /**
     * 幹が焼け切れると、地面から離れた部分が丸ごと燃え落ち、切り株は残る(2026-10-09 朝のユーザー指定)。過繁茂の原木・覆われた原木・バニラの原木・
     * 過繁茂の葉・バニラの葉が混ざった木で、切るのはバニラの原木。汚染されていない木も同じ。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void severedTreeBurnsWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RandomSource random = RandomSource.create(7L);
        Block coveredOak = CoveredBlocks.coveredOf(Blocks.OAK_LOG);
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlock(base, mod("overgrown_dirt").defaultBlockState(), Block.UPDATE_ALL);
        Object[][] stumpParts = {{new BlockPos(1, 2, 1), mod("overgrown_log")}, {new BlockPos(1, 3, 1), coveredOak}};
        Object[][] upperParts = {
                {new BlockPos(1, 5, 1), mod("overgrown_log")}, {new BlockPos(2, 5, 2), mod("overgrown_wood")}, // 斜めにつながる枝
                {new BlockPos(1, 6, 1), mod("overgrown_leaves")}, {new BlockPos(0, 5, 1), Blocks.OAK_LEAVES},
                {new BlockPos(2, 6, 2), Blocks.OAK_LEAVES}, {new BlockPos(1, 6, 0), mod("overgrown_leaves")},
        };
        List<BlockPos> stump = place(helper, stumpParts);
        List<BlockPos> upper = place(helper, upperParts);
        BlockPos cut = helper.absolutePos(new BlockPos(1, 4, 1));
        level.setBlock(cut, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        // 汚染されていない木(幹 3 段 + 葉)。
        BlockPos cleanStump = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos cleanCut = cleanStump.above();
        BlockPos cleanTop = cleanCut.above();
        BlockPos cleanLeaf = cleanTop.above();
        for (BlockPos p : List.of(cleanStump, cleanCut, cleanTop)) {
            level.setBlock(p, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(cleanLeaf, Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);

        int jobs = TreeBurn.jobCount();
        VanillaFire.consume(level, cut, random, 0);
        VanillaFire.consume(level, cleanCut, random, 0);
        helper.assertTrue(gone(level.getBlockState(cut)) && gone(level.getBlockState(cleanCut)), "切った所が燃え尽きない");
        for (BlockPos p : upper) {
            helper.assertTrue(TreeBurn.isClaimed(level, p), "地面から離れた " + helper.relativePos(p) + " が燃え落ち始めない");
        }
        helper.assertTrue(TreeBurn.isClaimed(level, cleanTop) && TreeBurn.isClaimed(level, cleanLeaf), "汚染されていない木の上の部分が燃え落ち始めない");
        for (BlockPos p : stump) {
            helper.assertTrue(!TreeBurn.isClaimed(level, p), "切り株 " + helper.relativePos(p) + " まで燃え落ち始めた");
        }
        helper.assertTrue(!TreeBurn.isClaimed(level, cleanStump), "汚染されていない木の切り株まで燃え落ち始めた");
        helper.assertTrue(TreeBurn.jobCount() == jobs + 2, "燃え落ちる塊の数が " + (TreeBurn.jobCount() - jobs) + "(2 のはず)");

        TreeBurn.runAll(Integer.MAX_VALUE, true);
        for (BlockPos p : upper) {
            helper.assertTrue(gone(level.getBlockState(p)), "地面から離れた " + helper.relativePos(p) + " が燃え残った: " + level.getBlockState(p));
        }
        helper.assertTrue(gone(level.getBlockState(cleanTop)) && gone(level.getBlockState(cleanLeaf)), "汚染されていない木の上の部分が燃え残った");
        helper.assertTrue(level.getBlockState(stump.get(0)).is(mod("overgrown_log")) && level.getBlockState(stump.get(1)).is(coveredOak),
                "切り株が燃えた・変わった");
        helper.assertTrue(level.getBlockState(cleanStump).is(Blocks.OAK_LOG), "汚染されていない木の切り株が燃えた");
        helper.assertTrue(!TreeBurn.isClaimed(level, upper.get(0)), "燃え落ちた後も印が残る");
        level.setBlock(base, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        clearArena(helper);
        helper.succeed();
    }

    /**
     * 丸ごと燃えないもの: 支えのある木の葉が燃えても、木材に接した原木(建物)・巨大樹が焼け切れても、上の部分は燃え落ち始めない。
     * 原木の無い宙の葉の塊は燃え落ちる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void supportedTreesStay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RandomSource random = RandomSource.create(11L);
        // 支えのある木: 葉が 1 つ燃えても何も始まらない。
        place(helper, new Object[][]{
                {new BlockPos(1, 2, 1), mod("overgrown_log")}, {new BlockPos(1, 3, 1), mod("overgrown_log")},
                {new BlockPos(1, 4, 1), mod("overgrown_log")}, {new BlockPos(1, 5, 1), mod("overgrown_leaves")},
                {new BlockPos(0, 4, 1), mod("overgrown_leaves")}, {new BlockPos(2, 4, 1), mod("overgrown_leaves")},
        });
        int jobs = TreeBurn.jobCount();
        VanillaFire.consume(level, helper.absolutePos(new BlockPos(0, 4, 1)), random, 0);
        helper.assertTrue(TreeBurn.jobCount() == jobs && !TreeBurn.isClaimed(level, helper.absolutePos(new BlockPos(1, 5, 1))),
                "支えのある木の葉が燃えただけで木が燃え落ち始めた");

        // 建物: 上の原木が木材に接している。
        place(helper, new Object[][]{
                {new BlockPos(5, 2, 1), Blocks.OAK_LOG}, {new BlockPos(5, 3, 1), Blocks.OAK_LOG}, {new BlockPos(5, 4, 1), Blocks.OAK_LOG},
                {new BlockPos(6, 4, 1), Blocks.OAK_PLANKS},
        });
        VanillaFire.consume(level, helper.absolutePos(new BlockPos(5, 3, 1)), random, 0);
        helper.assertTrue(!TreeBurn.isClaimed(level, helper.absolutePos(new BlockPos(5, 4, 1))), "建物の原木を丸ごと燃やし始めた");

        // 巨大樹: 焼け切れても丸ごとは燃えない(大きすぎて確かめられない。1 マスずつ燃えるだけ)。
        place(helper, new Object[][]{
                {new BlockPos(1, 2, 5), mod("giant_overgrown_log")}, {new BlockPos(1, 3, 5), mod("giant_overgrown_cambium")},
                {new BlockPos(1, 4, 5), mod("giant_overgrown_log")}, {new BlockPos(1, 5, 5), mod("hardened_giant_trunk")},
        });
        VanillaFire.consume(level, helper.absolutePos(new BlockPos(1, 3, 5)), random, 0);
        helper.assertTrue(!TreeBurn.isClaimed(level, helper.absolutePos(new BlockPos(1, 4, 5))), "巨大樹を丸ごと燃やし始めた");
        helper.assertTrue(TreeBurn.jobCount() == jobs, "支えのある木・建物・巨大樹で燃え落ちる塊ができた: " + (TreeBurn.jobCount() - jobs));

        // 原木の無い宙の葉の塊は燃え落ちる。
        BlockPos leafA = helper.absolutePos(new BlockPos(3, 5, 4));
        BlockPos leafB = helper.absolutePos(new BlockPos(4, 5, 4));
        level.setBlock(leafA, mod("overgrown_leaves").defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(leafB, mod("overgrown_leaves").defaultBlockState(), Block.UPDATE_ALL);
        VanillaFire.consume(level, leafA, random, 0);
        helper.assertTrue(TreeBurn.isClaimed(level, leafB), "宙に残った葉の塊が燃え落ち始めない");
        TreeBurn.runAll(Integer.MAX_VALUE, true);
        helper.assertTrue(gone(level.getBlockState(leafB)), "宙に残った葉が燃え残った: " + level.getBlockState(leafB));
        clearArena(helper);
        helper.succeed();
    }

    /** 部品を置いて、置いた位置(絶対座標)を返す。 */
    private static List<BlockPos> place(GameTestHelper helper, Object[][] parts) {
        List<BlockPos> placed = new ArrayList<>();
        for (Object[] part : parts) {
            BlockPos p = helper.absolutePos((BlockPos) part[0]);
            helper.getLevel().setBlock(p, ((Block) part[1]).defaultBlockState(), Block.UPDATE_ALL);
            placed.add(p);
        }
        return placed;
    }

    /** 焼けた荒地が戻るときの後始末の口: 汚染された木を炎を出さずに丸ごと消す。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void removeQuietlyClearsWholeTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> tree = new ArrayList<>();
        for (BlockPos rel : new BlockPos[]{new BlockPos(3, 2, 3), new BlockPos(3, 3, 3), new BlockPos(3, 4, 3)}) {
            BlockPos p = helper.absolutePos(rel);
            level.setBlock(p, mod("overgrown_log").defaultBlockState(), Block.UPDATE_ALL);
            tree.add(p);
        }
        for (BlockPos rel : new BlockPos[]{new BlockPos(3, 5, 3), new BlockPos(2, 4, 3), new BlockPos(4, 4, 3)}) {
            BlockPos p = helper.absolutePos(rel);
            level.setBlock(p, mod("overgrown_leaves").defaultBlockState(), Block.UPDATE_ALL);
            tree.add(p);
        }
        int removed = TreeBurn.removeQuietly(level, helper.absolutePos(new BlockPos(3, 5, 3)));
        helper.assertTrue(removed == tree.size(), "消した数が " + removed + "(" + tree.size() + " のはず)");
        for (BlockPos p : tree) {
            helper.assertTrue(level.getBlockState(p).isAir(), "木の " + helper.relativePos(p) + " が残った: " + level.getBlockState(p));
        }
        helper.succeed();
    }

    /** 燻る灰ブロック(id は焼けた土の {@code burnt_soil})は砂と同じく落ちる(2026-10-09 ユーザー指定)。下が空いた所に置くと、落ちて床の上に止まる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void ashFallsLikeSand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Block ash = PurifyRegistry.BURNT_SOIL.get();
        helper.assertTrue(ash instanceof FallingBlock, "燻る灰ブロックが落ちるブロックでない");
        BlockPos high = helper.absolutePos(new BlockPos(3, 5, 3));
        BlockPos landed = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(high, ash.defaultBlockState(), Block.UPDATE_ALL);
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(high).isAir(), "置いた所に残っている: " + level.getBlockState(high));
            helper.assertTrue(level.getBlockState(landed).is(ash), "床の上に落ちていない: " + level.getBlockState(landed));
            level.setBlock(landed, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
    }

    /**
     * テスト場のチャンクを焼けた荒地にする → バイオームが焼けた荒地・台帳から外れる・汚染が入らない → 3 日ずらして見回り・戻す →
     * 世界生成のときのバイオーム(世界の生成器に聞いた値)に戻り、焼けた荒地の台帳から消える。途中で落ちても元へ戻す。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = "purify_chunk")
    public static void burntChunkIsFirebreakAndReverts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos inside = helper.absolutePos(new BlockPos(3, 2, 3));
        ChunkPos pos = new ChunkPos(inside);
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        PhaseData phase = PhaseData.get(level);
        boolean wasLedger = phase.isOvergrownChunk(pos);
        int qx = QuartPos.fromBlock(pos.getMiddleBlockX());
        int qy = QuartPos.fromBlock(level.getSeaLevel());
        int qz = QuartPos.fromBlock(pos.getMiddleBlockZ());
        ResourceKey<Biome> original = level.getUncachedNoiseBiome(qx, qy, qz).unwrapKey().orElseThrow();
        try {
            helper.assertTrue(!phase.isClimaxChunk(pos) && !BurntLand.isBurntChunk(level, pos),
                    "テスト場のチャンクが極相か、もう焼けている(前のテストの残り?)");
            phase.adoptOvergrownChunk(level, pos);
            helper.assertTrue(BurntLand.flip(level, chunk), "焼けた荒地にできない");
            helper.assertTrue(biomeAt(level, pos).is(ModBiomes.BURNT_WASTELAND), "バイオームが焼けた荒地にならない: " + key(biomeAt(level, pos)));
            helper.assertTrue(!phase.isOvergrownChunk(pos), "焼けた荒地が汚染の台帳に残っている");
            helper.assertTrue(BurntLand.isBurntChunk(level, pos) && BurntLand.isBurnt(level, inside), "焼けた荒地の台帳に載らない");
            // 胞子除去装置の判定は乱数を引くので外す(ほかのテストの装置が残っていると揺れる)。焼けた荒地の判定は装置の前にある。
            helper.assertTrue(!CorruptionRegistry.canCorrupt(level, inside, Blocks.DIRT.defaultBlockState(), false),
                    "焼けた荒地の土が汚染できる(焼けている間は入らないはず)");
            helper.assertTrue(!BurntLand.flip(level, chunk), "焼けた荒地をもう一度焼けた荒地にできた");

            BurntLand.age(level.getServer(), BurntLand.REVERT_TICKS - 100L);
            BurntLand.scheduleDue(level.getServer());
            BurntLand.drain(level, 64);
            helper.assertTrue(BurntLand.isBurntChunk(level, pos), "3 日より前に戻った");

            BurntLand.age(level.getServer(), 200L);
            BurntLand.scheduleDue(level.getServer());
            BurntLand.drain(level, 64);
            helper.assertTrue(!BurntLand.isBurntChunk(level, pos), "3 日たっても焼けた荒地の台帳から消えない");
            Holder<Biome> now = level.getBiome(new BlockPos(pos.getMiddleBlockX(), level.getSeaLevel(), pos.getMiddleBlockZ()));
            helper.assertTrue(now.is(original), "元のバイオームに戻らない: " + key(now) + "(" + original.location() + " のはず)");
            helper.assertTrue(CorruptionRegistry.canCorrupt(level, inside, Blocks.DIRT.defaultBlockState(), false),
                    "元に戻った後も土が汚染できない");
        } finally {
            if (BurntLand.isBurntChunk(level, pos)) {
                BurntLand.revert(level, chunk);
            }
            if (wasLedger) {
                phase.adoptOvergrownChunk(level, pos);
            } else {
                phase.forgetOvergrownChunk(pos);
            }
        }
        helper.succeed();
    }

    /**
     * 火で汚染が変わったチャンクは焼けた荒地の候補に積まれ、まとめて見たときに焼けた荒地になる(火 → {@code noteBurnt} → {@code considerPending}
     * → {@code consider} → {@code flip})。変わった瞬間にはならない。テスト場のチャンクを汚染の台帳に載せて試す
     * (テスト場の上はバリアなので、地面の汚染を数えると少ない = 焼けた荒地になれる)。途中で落ちても元へ戻す。1 本 1 バッチ。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = "purify_chunk_fire")
    public static void fireFlipsLedgerChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos flame = ground.above();
        ChunkPos pos = new ChunkPos(ground);
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        PhaseData phase = PhaseData.get(level);
        boolean wasLedger = phase.isOvergrownChunk(pos);
        try {
            helper.assertTrue(!phase.isClimaxChunk(pos) && !BurntLand.isBurntChunk(level, pos),
                    "テスト場のチャンクが極相か、もう焼けている(前のテストの残り?)");
            phase.adoptOvergrownChunk(level, pos);
            level.setBlock(ground, mod("overgrown_dirt").defaultBlockState(), Block.UPDATE_ALL);
            RandomSource random = RandomSource.create(3L);
            for (int step = 0; step < 400 && !level.getBlockState(ground).is(PurifyRegistry.BURNT_SOIL.get()); step++) {
                if (!level.getBlockState(flame).is(Blocks.FIRE)) {
                    level.setBlock(flame, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
                }
                VanillaFire.step(level, flame, random);
            }
            helper.assertTrue(level.getBlockState(ground).is(PurifyRegistry.BURNT_SOIL.get()), "火の下の汚染された土が焼けた土にならない");
            helper.assertTrue(!BurntLand.isBurntChunk(level, pos), "変わった瞬間に焼けた荒地になった(まとめて見るはず)");
            BurntLand.considerPending(level);
            helper.assertTrue(BurntLand.isBurntChunk(level, pos), "火で汚染が変わったチャンクが、まとめて見ても焼けた荒地にならない");
        } finally {
            if (BurntLand.isBurntChunk(level, pos)) {
                BurntLand.revert(level, chunk);
            }
            if (wasLedger) {
                phase.adoptOvergrownChunk(level, pos);
            } else {
                phase.forgetOvergrownChunk(pos);
            }
            level.setBlock(flame, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(ground, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    /** 炎の扇の中の Mob に火が付いて傷を負う。村人(NPC)には付かない。扇の外の Mob にも付かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = FAKE_PLAYER_BATCH)
    public static void flamesIgniteMobsButNotVillagers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 3));
        player.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, -90.0F, 0.0F); // 東(+X)向き・水平
        net.minecraft.world.entity.animal.Pig pig = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, new BlockPos(4, 2, 3));
        net.minecraft.world.entity.npc.Villager villager =
                helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(5, 2, 3));
        net.minecraft.world.entity.animal.Pig aside = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, new BlockPos(1, 2, 6));
        float before = pig.getHealth();
        Burning.spray(level, player, FlamethrowerItem.RANGE, true);
        helper.assertTrue(pig.isOnFire() && pig.getHealth() < before, "扇の中の豚に火が付かない・傷を負わない: 火 " + pig.isOnFire()
                + " / 体力 " + pig.getHealth());
        helper.assertTrue(!villager.isOnFire(), "村人に火が付いた");
        helper.assertTrue(!aside.isOnFire(), "扇の外の豚に火が付いた");
        pig.discard();
        villager.discard();
        aside.discard();
        clearArena(helper); // 奥のバリアの前の床に付いたふつうの火を消す
        helper.succeed();
    }

    /** 燃料: 石炭 1 個 → 200 tick、尽きたら木炭 1 個 → 200 tick、尽きたら使えない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = FAKE_PLAYER_BATCH)
    public static void fuelUsesCoalAndCharcoal(GameTestHelper helper) {
        FakePlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.getInventory().clearContent();
        try {
            player.getInventory().setItem(0, new ItemStack(Items.COAL));
            player.getInventory().setItem(1, new ItemStack(Items.CHARCOAL));
            ItemStack thrower = new ItemStack(PurifyRegistry.FLAMETHROWER.get());
            int step = FlamethrowerItem.FUEL_STEP;
            int perItem = FlamethrowerItem.FUEL_PER_ITEM / step;
            for (int i = 0; i < perItem; i++) {
                helper.assertTrue(FlamethrowerItem.drawFuel(player, thrower, step), "石炭のぶんで " + i + " 回目に燃料が尽きた");
            }
            helper.assertTrue(player.getInventory().getItem(0).isEmpty() && player.getInventory().getItem(1).is(Items.CHARCOAL),
                    "石炭を 1 個使っていない・木炭まで使った");
            helper.assertTrue(FlamethrowerItem.fuel(thrower) == 0, "石炭 1 個ぶん使ったのに燃料が残る: " + FlamethrowerItem.fuel(thrower));
            for (int i = 0; i < perItem; i++) {
                helper.assertTrue(FlamethrowerItem.drawFuel(player, thrower, step), "木炭のぶんで " + i + " 回目に燃料が尽きた");
            }
            helper.assertTrue(player.getInventory().getItem(1).isEmpty(), "木炭を使っていない");
            helper.assertTrue(!FlamethrowerItem.drawFuel(player, thrower, step), "燃料が無いのに吹けた");
        } finally {
            player.getInventory().clearContent();
        }
        helper.succeed();
    }

    /** レシピ・研究ノード・バイオーム・タグ・言語。専用の炎のブロックは無い(2026-10-09 朝に外した)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void recipeResearchBiomeAndLang(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(level.getServer().getRecipeManager().byKey(OvergrownVegetation.id("flamethrower")).isPresent(),
                "火炎放射器のレシピが無い");
        JsonObject node = read("/overgrown_vegetation/design/research.json").getAsJsonObject("nodes").getAsJsonObject("purification");
        helper.assertTrue(node != null, "研究ツリーに「浄化」が無い");
        helper.assertTrue(contains(node.getAsJsonArray("unlocks"), "flamethrower"), "「浄化」が火炎放射器のレシピを封印していない");
        // 前提は 10-08 に「胞子の除去」で作り、その後ユーザーが mod マネージャーで「対空」(費用 3000)へ移した(2026-10-10 に確認)。
        helper.assertTrue(contains(node.getAsJsonArray("parents"), "anti_air"), "「浄化」の前提が対空でない");
        helper.assertTrue(level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(ModBiomes.BURNT_WASTELAND),
                "焼けた荒地のバイオームが無い");
        helper.assertTrue(!ModBiomes.isOvergrownLike(level.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(ModBiomes.BURNT_WASTELAND)), "焼けた荒地が繁茂系に数えられている");
        helper.assertTrue(PurifyRegistry.BURNT_SOIL.get().defaultBlockState().is(ModTags.CORRUPTION_IMMUNE), "焼けた土が汚染される");
        helper.assertTrue(!BuiltInRegistries.BLOCK.containsKey(OvergrownVegetation.id("flamethrower_fire")), "外したはずの専用の炎のブロックが残っている");
        for (String lang : new String[]{"ja_jp", "en_us"}) {
            JsonObject json = read("/assets/overgrown_vegetation/lang/" + lang + ".json");
            for (String key : new String[]{"item.overgrown_vegetation.flamethrower", "item.overgrown_vegetation.flamethrower.desc",
                    "item.overgrown_vegetation.flamethrower.fuel", "message.overgrown_vegetation.flamethrower.no_fuel",
                    "block.overgrown_vegetation.burnt_soil", "biome.overgrown_vegetation.burnt_wasteland"}) {
                helper.assertTrue(json.has(key), lang + " に無い: " + key);
            }
        }
        helper.succeed();
    }

    private static Holder<Biome> biomeAt(ServerLevel level, ChunkPos pos) {
        return level.getBiome(pos.getMiddleBlockPosition(level.getSeaLevel()));
    }

    private static String key(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("?");
    }

    private static boolean contains(JsonArray array, String value) {
        if (array == null) {
            return false;
        }
        for (JsonElement e : array) {
            if (value.equals(e.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject read(String path) {
        try (InputStream in = PurifyGameTests.class.getResourceAsStream(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(path + " が読めない: " + e);
        }
    }

    private PurifyGameTests() {
    }
}
