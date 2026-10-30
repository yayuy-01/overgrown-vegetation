package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.defense.ClapperNetwork;
import com.yayuy.overgrownvegetation.defense.ClapperRope;
import com.yayuy.overgrownvegetation.defense.ClapperRopeBlock;
import com.yayuy.overgrownvegetation.defense.ClapperRopeBlockEntity;
import com.yayuy.overgrownvegetation.defense.ClapperRopeItem;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 鳴子(2026-10-15、サバイバル用アイテム ㊵。ユーザー指定)。
 * <ul>
 *     <li>柵の間に紐を張れ、端の柱を共有する紐が台帳でつながる({@link #ropesLinkThroughSharedPosts})。</li>
 *     <li>1 本を鳴らすとつながった紐が全部鳴り、紐を結んだ柱が少しの間レッドストーン信号 15 を出して止む
 *     ({@link #ringingSpreadsAndPowersPosts})。</li>
 *     <li>止まっている牛では鳴らない・動いている物(雪玉)が紐を横切ると鳴る({@link #movingMobRingsTheRope})。</li>
 *     <li>板を吊れる(すぐ隣には吊れない)・切ると紐と板が落ちる({@link #boardsHangAndDropWhenCut})。</li>
 *     <li>柱を壊すと紐が切れて落ちる({@link #removingAPostCutsTheRope})。</li>
 * </ul>
 * 揺れ(見た目)はクライアントなのでテストクライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ClapperGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ClapperGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "clappers";

    /** 柵を立てて紐を張り、目印のブロックの位置(絶対座標)を返す。 */
    private static BlockPos rope(GameTestHelper helper, BlockPos a, BlockPos b) {
        helper.setBlock(a, Blocks.OAK_FENCE);
        helper.setBlock(b, Blocks.OAK_FENCE);
        BlockPos marker = ClapperRopeItem.place(helper.getLevel(), helper.absolutePos(a), helper.absolutePos(b));
        if (marker == null) {
            throw new IllegalStateException("紐を張れない");
        }
        return marker;
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ropesLinkThroughSharedPosts(GameTestHelper helper) {
        BlockPos m1 = rope(helper, new BlockPos(1, 2, 1), new BlockPos(5, 2, 1));
        rope(helper, new BlockPos(5, 2, 1), new BlockPos(5, 2, 5));
        ServerLevel level = helper.getLevel();
        if (!(level.getBlockEntity(m1) instanceof ClapperRopeBlockEntity be) || !be.hasRope() || !ClapperRope.isPost(level.getBlockState(be.getSource()))) {
            helper.fail("紐の中身が無い");
            return;
        }
        int shared = ClapperNetwork.ropesAtPost(level, helper.absolutePos(new BlockPos(5, 2, 1)));
        if (shared != 2) {
            helper.fail("真ん中の柱に結ばれた紐が 2 本でない: " + shared);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void ringingSpreadsAndPowersPosts(GameTestHelper helper) {
        BlockPos m1 = rope(helper, new BlockPos(1, 2, 1), new BlockPos(5, 2, 1));
        rope(helper, new BlockPos(5, 2, 1), new BlockPos(5, 2, 5));
        ServerLevel level = helper.getLevel();
        BlockPos far = helper.absolutePos(new BlockPos(5, 2, 5));
        int rung = ClapperNetwork.ring(level, m1);
        if (rung != 2) {
            helper.fail("つながった紐が 2 本とも鳴らない: " + rung);
            return;
        }
        if (level.getSignal(far, Direction.UP) != 15) {
            helper.fail("鳴った紐の端の柱が信号 15 を出さない: " + level.getSignal(far, Direction.UP));
            return;
        }
        if (ClapperNetwork.ring(level, m1) != 0) {
            helper.fail("鳴ったばかりの紐がすぐまた鳴った(間を置くはず)");
            return;
        }
        helper.runAfterDelay(ClapperNetwork.POWER_TICKS + 5, () -> {
            if (level.getSignal(far, Direction.UP) != 0) {
                helper.fail("信号が止まない");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void movingMobRingsTheRope(GameTestHelper helper) {
        BlockPos m1 = rope(helper, new BlockPos(1, 2, 3), new BlockPos(5, 2, 3));
        ServerLevel level = helper.getLevel();
        ClapperRopeBlockEntity be = (ClapperRopeBlockEntity) level.getBlockEntity(m1);
        Vec3 mid = be.middle();
        // 止まっている牛が紐に触れていても鳴らない。
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
        cow.setPos(mid.x, mid.y - 0.6D, mid.z);
        helper.runAfterDelay(10, () -> {
            if (be.cooldownUntil() != 0L) {
                helper.fail("止まっている牛で鳴った");
                return;
            }
            cow.discard();
            // 実際に動く物(重さの無い雪玉)に紐を横切らせると鳴る。テストの中で瞬間移動させた物は、判定から見ると止まっている。
            net.minecraft.world.entity.projectile.Snowball ball = new net.minecraft.world.entity.projectile.Snowball(EntityType.SNOWBALL, level);
            ball.setPos(mid.x, mid.y, mid.z + 2.0D);
            ball.setNoGravity(true);
            ball.setDeltaMovement(0.0D, 0.0D, -0.25D);
            level.addFreshEntity(ball);
        });
        helper.succeedWhen(() -> {
            if (be.cooldownUntil() == 0L) {
                helper.fail("動いている物が紐を横切っても鳴らない");
            }
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void boardsHangAndDropWhenCut(GameTestHelper helper) {
        BlockPos m1 = rope(helper, new BlockPos(1, 2, 2), new BlockPos(5, 2, 2));
        ServerLevel level = helper.getLevel();
        ClapperRopeBlockEntity be = (ClapperRopeBlockEntity) level.getBlockEntity(m1);
        if (!be.addBoard(0.5F) || be.addBoard(0.52F) || !be.addBoard(0.2F)) {
            helper.fail("板の吊り方がおかしい(真ん中に吊れて、すぐ隣には吊れず、離れた所には吊れるはず)");
            return;
        }
        Vec3 mid = be.middle();
        ClapperRopeBlock.cut(level, m1, true);
        if (level.getBlockState(m1).is(com.yayuy.overgrownvegetation.registry.ModBlocks.CLAPPER_ROPE.get())) {
            helper.fail("切っても紐の目印が残る");
            return;
        }
        int rope = 0;
        int boards = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(mid, mid).inflate(2.0D))) {
            if (item.getItem().is(ModItems.CLAPPER_ROPE.get())) {
                rope += item.getItem().getCount();
            } else if (item.getItem().is(ModItems.CLAPPER_BOARD.get())) {
                boards += item.getItem().getCount();
            }
            item.discard();
        }
        if (rope != 1 || boards != 2) {
            helper.fail("切った時に落ちる物: 紐 " + rope + " / 板 " + boards + "(期待 1 / 2)");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void removingAPostCutsTheRope(GameTestHelper helper) {
        BlockPos m1 = rope(helper, new BlockPos(1, 2, 4), new BlockPos(5, 2, 4));
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(5, 2, 4), Blocks.AIR);
        helper.succeedWhen(() -> {
            if (level.getBlockState(m1).is(com.yayuy.overgrownvegetation.registry.ModBlocks.CLAPPER_ROPE.get())) {
                helper.fail("柱を壊しても紐が残る");
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(m1).inflate(6.0D))) {
                item.discard();
            }
        });
    }

    private ClapperGameTests() {
    }
}
