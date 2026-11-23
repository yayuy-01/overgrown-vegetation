package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.FilmTrails;
import com.yayuy.overgrownvegetation.entity.FollowFilmTrailGoal;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 水面の膜を通った跡(2026-11-13、{@link FilmTrails})と、筋をたどる Goal({@link FollowFilmTrailGoal})。
 * テスト場の {@value #SKY} 段上の空中に作って試し、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FilmTrailGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterFilmTrailGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(FilmTrailGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;

    /** 膜の下を泳ぐ魚が膜を通ると、跡の点が残る(膜の無い所では残らない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void swimmerUnderFilmLeavesTrail(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        int depth = 3;
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= depth; y++) {
                    level.setBlock(origin.offset(x, y, z), wall ? Blocks.GLASS.defaultBlockState() : Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                if (!wall && z <= 3) {
                    level.setBlock(origin.offset(x, depth + 1, z), ModBlocks.ALGAE_FILM.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        Cod cod = EntityType.COD.create(level);
        if (cod == null) {
            helper.fail("タラを作れなかった");
            return;
        }
        cod.setNoAi(true);
        double y = origin.getY() + depth + 0.3;
        cod.moveTo(origin.getX() + 3.5, y, origin.getZ() + 1.5);
        level.addFreshEntity(cod);
        int[] step = {0};
        helper.onEachTick(() -> {
            if (step[0] < 40 && cod.isAlive()) {
                step[0]++;
                cod.setPos(cod.getX(), y, origin.getZ() + 1.5 + step[0] * 0.1);
            }
        });
        helper.runAfterDelay(60, () -> {
            Vec3 center = Vec3.atCenterOf(origin.offset(3, depth, 3));
            int id = cod.getId();
            long underFilm = FilmTrails.near(level, center, 6.0, FilmTrails.LIFETIME, p -> p.owner() == id && p.z() < origin.getZ() + 4.0).size();
            long openWater = FilmTrails.near(level, center, 6.0, FilmTrails.LIFETIME, p -> p.owner() == id && p.z() > origin.getZ() + 4.8).size();
            cod.discard();
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    for (int yy = 0; yy <= depth + 1; yy++) {
                        level.setBlock(origin.offset(x, yy, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            if (underFilm < 3) {
                helper.fail("膜の下を泳いだのに跡の点が少ない: " + underFilm);
            } else if (openWater > 0) {
                helper.fail("膜の無い所にも跡が残った: " + openWater);
            } else {
                helper.succeed();
            }
        });
    }

    /** 筋をたどる Goal は、跡の新しい方へ進む。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void followGoalWalksTowardNewerPoints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 4; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        long now = level.getGameTime();
        // 古い点(x = 1)から新しい点(x = 11)へ、1 ブロックおきの筋
        for (int i = 0; i <= 10; i++) {
            FilmTrails.record(level, new FilmTrails.Point(origin.getX() + 1.5 + i, origin.getY() + 2.0, origin.getZ() + 2.5,
                    now - 200 + i * 10, 0.4F, 1.0F, -7, false));
        }
        Pig pig = EntityType.PIG.create(level);
        if (pig == null) {
            helper.fail("豚を作れなかった");
            return;
        }
        pig.moveTo(origin.getX() + 1.5, origin.getY() + 1.0, origin.getZ() + 2.5);
        pig.goalSelector.removeAllGoals(g -> true);
        pig.targetSelector.removeAllGoals(g -> true);
        pig.goalSelector.addGoal(1, new FollowFilmTrailGoal(pig, 1.2, 6.0, p -> true));
        level.addFreshEntity(pig);
        double startX = pig.getX();
        FollowFilmTrailGoal[] goal = {null};
        pig.goalSelector.getAvailableGoals().forEach(w -> {
            if (w.getGoal() instanceof FollowFilmTrailGoal f) {
                goal[0] = f;
            }
        });
        String[] seen = {""};
        helper.runAfterDelay(5, () -> seen[0] = "5 tick 目: 走っている " + pig.goalSelector.getAvailableGoals().stream().anyMatch(w -> w.isRunning())
                + " 目標 " + (goal[0] != null && goal[0].goal() != null) + " 経路 " + pig.getNavigation().getPath()
                + " 近くの点 " + FilmTrails.near(level, pig.position(), 6.0, FilmTrails.LIFETIME, p -> true).size()
                + " 位置 " + pig.position() + " 生きている " + pig.isAlive() + " AI " + !pig.isNoAi());
        helper.runAfterDelay(160, () -> {
            double moved = pig.getX() - startX;
            pig.discard();
            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 4; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (moved < 6.0) {
                helper.fail("筋の新しい方へ進まない: " + String.format("%.1f", moved) + " ブロック・" + seen[0]);
            } else {
                helper.succeed();
            }
        });
    }
}
