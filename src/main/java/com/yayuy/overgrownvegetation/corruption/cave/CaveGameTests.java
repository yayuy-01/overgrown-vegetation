package com.yayuy.overgrownvegetation.corruption.cave;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.cliff.CliffSacFog;
import com.yayuy.overgrownvegetation.corruption.fissure.FissureLook;
import com.yayuy.overgrownvegetation.corruption.fissure.FissureWorld;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 汚染された洞窟の飾り(2026-11-20)の確かめ。遠くの専用チャンクに石の塊を組み、中に高い部屋・張り出し・壁沿いの空気を作る。
 * <ul>
 *   <li>汚染された洞窟に柱・水ぶくれ・縄が出る。汚染されていない洞窟・地上には出ない。</li>
 *   <li>柱(編んだ蔦)は登れない。宙に垂れた縄(絡む根)は登れる。</li>
 *   <li>水ぶくれを壊すと霧が 1 回出る(塊を続けて割っても霧を重ねない)。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CaveGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CaveGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int TOP = 79, Y0 = 50, Y1 = 61;

    /** 石の塊(上は空気)に、x 3〜13・z 3〜13・y 50〜61 の部屋と、x 9〜13 の y 56〜57 の張り出しを掘る。 */
    static void build(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(cx + dx, cz + dz);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = Y0 - 6; y <= TOP + 12; y++) {
            boolean room = x >= 3 && x <= 13 && z >= 3 && z <= 13 && y >= Y0 && y <= Y1 && !(x >= 9 && y >= 56 && y <= 57);
            BlockState want = y > TOP || room ? air : stone;
            m.set((cx << 4) + x, y, (cz << 4) + z);
            if (level.getBlockState(m) != want) level.setBlock(m, want, flags);
        }
    }

    /** 部屋の固い面を全部汚染する。 */
    static void corrupt(ServerLevel level, int cx, int cz) {
        RandomSource r = RandomSource.create(5);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = Y0 - 1; y <= Y1 + 1; y++) {
            m.set((cx << 4) + x, y, (cz << 4) + z);
            BlockState s = level.getBlockState(m);
            if (s.isAir()) continue;
            boolean faces = false;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (level.getBlockState(m.relative(d)).isAir()) faces = true;
            if (faces) FissureWorld.corruptOne(level, m.immutable(), s, r, 0.0D);
        }
    }

    static int count(ServerLevel level, int cx, int cz, Block b, int y0, int y1) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -2; x < 18; x++) for (int z = -2; z < 18; z++) for (int y = y0; y <= y1; y++)
            if (level.getBlockState(m.set((cx << 4) + x, y, (cz << 4) + z)).is(b)) n++;
        return n;
    }

    @GameTest(template = "gametest_empty7", batch = "cave_a", timeoutTicks = 40)
    public static void corruptedCaveGetsPillarBlisterAndRopes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int cx = 760_000, cz = 760_000;
        build(level, cx, cz);
        corrupt(level, cx, cz);
        LevelChunk chunk = level.getChunk(cx, cz);
        CaveDecor.Result r = CaveDecor.decorateChunk(level, chunk, RandomSource.create(1), new CaveDecor.Options(1.0D, 1.0D, 99));
        long d0 = System.nanoTime();
        FissureLook.drainAll();
        OvergrownVegetation.LOGGER.info("[cave test] 飾りを決める {} ms・置く {} ms・{} マス", r.nanos / 1e6, (System.nanoTime() - d0) / 1e6, r.cells);
        int vine = count(level, cx, cz, OrganBlocks.WOVEN_VINE.get(), Y0, Y1);
        int blister = count(level, cx, cz, OrganBlocks.CAVE_BLISTER.get(), Y0 - 1, Y1 + 1);
        int rope = count(level, cx, cz, OrganBlocks.TANGLED_ROOT.get(), Y0, Y1);
        String info = "柱 " + r.pillars + "・水ぶくれ " + r.blisters + "・縄 " + r.ropes + "(編んだ蔦 " + vine + "・水ぶくれ " + blister + "・絡む根 " + rope + ")";
        OvergrownVegetation.LOGGER.info("[cave test] {}", info);
        h.assertTrue(r.pillars == 1 && vine >= 20, "柱が出ない: " + info);
        h.assertTrue(r.blisters == 1 && blister >= 10, "水ぶくれが出ない: " + info);
        h.assertTrue(r.ropes >= 2 && rope >= 10, "縄が出ない: " + info);
        int above = 0;
        for (Block b : new Block[]{OrganBlocks.WOVEN_VINE.get(), OrganBlocks.CAVE_BLISTER.get(), OrganBlocks.TANGLED_ROOT.get()}) above += count(level, cx, cz, b, TOP - 4, TOP + 6);
        h.assertTrue(above == 0, "地表のそばに出た: " + above);
        h.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "cave_b", timeoutTicks = 40)
    public static void cleanCaveGetsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int cx = 762_000, cz = 762_000;
        build(level, cx, cz);
        LevelChunk chunk = level.getChunk(cx, cz);
        CaveDecor.Result r = CaveDecor.decorateChunk(level, chunk, RandomSource.create(1), new CaveDecor.Options(1.0D, 1.0D, 99));
        FissureLook.drainAll();
        h.assertTrue(r.cells == 0, "汚染されていない洞窟に飾りが出た: " + r.cells);
        h.succeed();
    }

    /** 柱(編んだ蔦)は登れない。宙に垂れた縄(壁なしの絡む根)は登れる。崖の上り比べと同じく 50 tick で比べる。 */
    @GameTest(template = "gametest_empty24", batch = "cave_c", timeoutTicks = 80)
    public static void pillarNotClimbableRopeClimbable(GameTestHelper h) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        for (int y = 2; y <= 20; y++) {
            h.setBlock(new BlockPos(5, y, 5), OrganBlocks.TANGLED_ROOT.get());
            h.setBlock(new BlockPos(5, y, 15), OrganBlocks.WOVEN_VINE.get());
        }
        Mob rope = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(6.5D, 2.0D, 5.5D));
        Mob vine = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(6.5D, 2.0D, 15.5D));
        rope.setInvulnerable(true);
        vine.setInvulnerable(true);
        double y0r = rope.getY(), y0v = vine.getY();
        h.onEachTick(() -> {
            for (Mob m : new Mob[]{rope, vine}) m.setDeltaMovement(-0.12D, m.getDeltaMovement().y, 0.0D);
        });
        h.runAtTickTime(50, () -> {
            double gr = rope.getY() - y0r, gv = vine.getY() - y0v;
            String info = String.format("縄 %.2f / 柱 %.2f ブロック(50 tick)", gr, gv);
            OvergrownVegetation.LOGGER.info("[cave test] {}", info);
            h.assertTrue(gr > 2.0D, "宙に垂れた縄を登れない: " + info);
            h.assertTrue(gv < 0.6D, "柱を登れた: " + info);
            h.succeed();
        });
    }

    /** 水ぶくれを壊すと霧が出る。塊の隣を続けて割っても霧は重ねない。 */
    @GameTest(template = "gametest_empty7", batch = "cave_d", timeoutTicks = 20)
    public static void blisterBurstsOnceIntoFog(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(2, 2, 2)), b = h.absolutePos(new BlockPos(3, 2, 2));
        level.setBlock(a, OrganBlocks.CAVE_BLISTER.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(b, OrganBlocks.CAVE_BLISTER.get().defaultBlockState(), Block.UPDATE_ALL);
        FakePlayer fp = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_cave_blister".getBytes()), "ov_cave_blister"));
        int before = CliffSacFog.active(level);
        fp.gameMode.destroyBlock(a);
        int one = CliffSacFog.active(level);
        fp.gameMode.destroyBlock(b);
        int two = CliffSacFog.active(level);
        h.assertTrue(level.getBlockState(a).isAir() && level.getBlockState(b).isAir(), "水ぶくれが壊れない");
        h.assertTrue(one == before + 1, "壊しても霧が出ない: " + before + " → " + one);
        h.assertTrue(two == one, "隣を割って霧を重ねた: " + one + " → " + two);
        h.succeed();
    }
}
