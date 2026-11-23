package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.ClimbingVine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 登る蔓(2026-11-03 試作)。ユーザー指定「蔦はどこかから生えているように見えること。片端は必ず地面(幹の根元の脇)か幹の根元」。
 * 幹 2 本(4 ブロック離す)の片方に置き、根元の端が地面の高さで幹のすぐ脇にあること・隣の幹へ渡っていることを見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ClimbingVineGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterClimbingVineGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(ClimbingVineGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos TRUNK_A = new BlockPos(1, 2, 3);
    private static final BlockPos TRUNK_B = new BlockPos(5, 2, 3);
    private static final int HEIGHT = 8;

    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void climbingVineGrowsFromTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                place(helper, new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
                for (int y = 2; y < 2 + HEIGHT + 2; y++) {
                    place(helper, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int y = 0; y < HEIGHT; y++) {
            place(helper, TRUNK_A.above(y), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
            place(helper, TRUNK_B.above(y), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
        }
        BlockPos base = helper.absolutePos(TRUNK_A);
        int placed = ClimbingVine.placeOnTree(level, base, 1, RandomSource.create(42L));
        if (placed != 1) {
            helper.fail("登る蔓が置けなかった: " + placed);
            return;
        }
        // 束(本体 + 細い蔓)と垂れる綱をすべて見る。
        int mains = 0;
        int bridged = 0;
        int ropes = 0;
        BlockPos b = helper.absolutePos(TRUNK_B);
        for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 0)),
                helper.absolutePos(new BlockPos(6, 2 + HEIGHT, 6)))) {
            if (!(level.getBlockEntity(p) instanceof CorruptedLianaBlockEntity vine) || !vine.hasClimb() || vine.getPrimaryPos() != null) {
                continue;
            }
            float[] path = vine.getClimbPath();
            int last = path.length - CorruptedLianaBlockEntity.CLIMB_STRIDE;
            BlockPos m = vine.getBlockPos();
            if (vine.isClimbHanging()) {
                // 垂れる綱: 先は地面に届かない(地面から 1 以上)
                ropes++;
                double tipY = m.getY() + (double) path[last + 1];
                if (tipY < base.getY() + 1.0D) {
                    helper.fail("垂れる綱が地面に届いている: 先 y=" + tipY);
                    return;
                }
                continue;
            }
            Vec3 root = ClimbingVine.rootPoint(vine);
            // 地面(根元の段の下端)から上へ 0.5 以内
            if (root.y > base.getY() + 0.5D || root.y < base.getY() - 0.5D) {
                helper.fail("根元の端が地面の高さに無い: y=" + root.y + " 地面=" + base.getY());
                return;
            }
            // 幹の面から水平 1.3 以内(幹の根元の脇)。添う細い蔓は本体から少しずれるので 1.7 まで
            double dx = Math.max(0.0D, Math.max(base.getX() - root.x, root.x - (base.getX() + 1)));
            double dz = Math.max(0.0D, Math.max(base.getZ() - root.z, root.z - (base.getZ() + 1)));
            boolean isMain = vine.isClimbMain();
            if (Math.hypot(dx, dz) > (isMain ? 1.3D : 1.7D)) {
                helper.fail("根元の端が幹から離れている: " + Math.hypot(dx, dz) + " root=" + root + " 印=" + m + " 幹=" + base);
                return;
            }
            if (!isMain) {
                continue;
            }
            mains++;
            if (vine.getClimbBridgeStart() >= 0) {
                bridged++;
                // 渡った先の端(最後の点)が隣の幹の面のそば
                double tx = m.getX() + (double) path[last];
                double tz = m.getZ() + (double) path[last + 2];
                double ex = Math.max(0.0D, Math.max(b.getX() - tx, tx - (b.getX() + 1)));
                double ez = Math.max(0.0D, Math.max(b.getZ() - tz, tz - (b.getZ() + 1)));
                if (Math.hypot(ex, ez) > 0.6D) {
                    helper.fail("先の端が隣の幹に付いていない: " + Math.hypot(ex, ez));
                    return;
                }
            }
        }
        if (mains != 1 || bridged != 1) {
            helper.fail("本体の蔓 " + mains + " 本・渡った本体 " + bridged + " 本(1 本ずつのはず)");
            return;
        }
        if (ropes < 1) {
            helper.fail("渡る区間から垂れる綱が無い");
            return;
        }
        helper.succeed();
    }

    /**
     * 林冠の細い蔓(2026-11-03 採用)。ユーザー指定: a(葉から真下)と b(葉から隣の木の葉へ浅い U)= 7 : 3、太さ 0.08〜0.12、
     * 仮決め: 両端・始まりは葉の塊の中。幹 2 本(6 ブロック離す)の天辺に葉の層を置き(高さを 1 段ずらす)、片方に付ける。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void canopyVinesSevenToThreeFromLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                place(helper, new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
                for (int y = 2; y < 14; y++) {
                    place(helper, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        net.minecraft.world.level.block.state.BlockState leaf = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        if (leaf.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) {
            leaf = leaf.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        }
        for (int y = 0; y < HEIGHT; y++) {
            place(helper, new BlockPos(0, 2 + y, 3), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
            place(helper, new BlockPos(6, 2 + y, 3), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
        }
        for (int z = 1; z <= 5; z++) {
            for (int x = 0; x <= 2; x++) {
                if (!(x == 0 && z == 3)) {
                    place(helper, new BlockPos(x, 2 + HEIGHT - 1, z), leaf);
                }
            }
            for (int x = 4; x <= 6; x++) {
                if (!(x == 6 && z == 3)) {
                    place(helper, new BlockPos(x, 2 + HEIGHT, z), leaf);
                }
            }
        }
        int[] placed = ClimbingVine.placeCanopyVines(level, helper.absolutePos(new BlockPos(0, 2, 3)), RandomSource.create(5L));
        int a = placed[0];
        int b = placed[1];
        if (b < 1 || b > 4 || a < 4 || a > 7) {
            helper.fail("a:b が 7:3 の幅に無い: a=" + a + " b=" + b);
            return;
        }
        int checked = 0;
        for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 0)),
                helper.absolutePos(new BlockPos(6, 2 + HEIGHT + 1, 6)))) {
            if (!(level.getBlockEntity(p) instanceof CorruptedLianaBlockEntity vine) || !vine.isClimbCanopy() || vine.getPrimaryPos() != null) {
                continue;
            }
            if (ClimbingVine.RADIUS_ROOT * vine.getClimbScale() * 2.0F > 0.12F + 1.0E-4F) {
                helper.fail("林冠の細い蔓が太い: " + ClimbingVine.RADIUS_ROOT * vine.getClimbScale() * 2.0F);
                return;
            }
            // 葉から真下(上の端が揺れない)と U の始まりは葉の中。U から垂れる蔓(上の端が U と一緒に揺れる)は U の上から
            if (vine.isClimbHanging() && vine.getClimbHangTop() > 0.0F) {
                continue;
            }
            Vec3 root = ClimbingVine.rootPoint(vine);
            if (!level.getBlockState(BlockPos.containing(root)).is(ModBlocks.OVERGROWN_LEAVES.get())) {
                helper.fail("始まりが葉の中に無い: " + root + " " + level.getBlockState(BlockPos.containing(root)));
                return;
            }
            checked++;
        }
        if (checked != a + b) {
            helper.fail("葉から出る細い蔓の数が合わない: 見た " + checked + " / a+b " + (a + b));
            return;
        }
        helper.succeed();
    }

    /**
     * 蔦を手やハサミで壊す(2026-11-08、ユーザー依頼「巻き付く蔦・間の蔦をしっかりハサミや手で壊せるように。手は少し時間がかかる」)。
     * 幹を登る区間の脇のマスにも狙いのマス(LianaSegmentBlock)があり、素手は vineHandBreakSeconds・ハサミは vineShearsBreakSeconds の速さ、
     * 幹の脇の 1 マスを素手で壊すと束(本体・添う細い蔓・垂れる綱)が全部消える。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void climbingVineBreaksByHandAndShears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                place(helper, new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
                for (int y = 2; y < 2 + HEIGHT + 2; y++) {
                    place(helper, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int y = 0; y < HEIGHT; y++) {
            place(helper, TRUNK_A.above(y), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
            place(helper, TRUNK_B.above(y), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
        }
        BlockPos base = helper.absolutePos(TRUNK_A);
        if (ClimbingVine.placeOnTree(level, base, 1, RandomSource.create(42L)) != 1) {
            helper.fail("登る蔓が置けなかった");
            return;
        }
        BlockPos lo = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos hi = helper.absolutePos(new BlockPos(6, 2 + HEIGHT + 1, 6));
        // 幹 A の脇(水平に隣り合う)・地面より上のマスにある狙いのマス
        BlockPos trunkSide = null;
        int besideTrunk = 0;
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (!(level.getBlockState(p).getBlock() instanceof com.yayuy.overgrownvegetation.block.LianaSegmentBlock)) {
                continue;
            }
            int ddx = Math.abs(p.getX() - base.getX());
            int ddz = Math.abs(p.getZ() - base.getZ());
            if (ddx + ddz == 1 && p.getY() >= base.getY() + 1 && p.getY() <= base.getY() + HEIGHT - 3) {
                besideTrunk++;
                if (trunkSide == null) {
                    trunkSide = p.immutable();
                }
            }
        }
        if (besideTrunk < 3 || trunkSide == null) {
            helper.fail("幹を登る区間の脇に狙いのマスが少ない: " + besideTrunk);
            return;
        }
        // 狙いの箱は幹の側の 1/3 か 2/3(マス全体ではない)
        net.minecraft.world.phys.AABB box = level.getBlockState(trunkSide).getShape(level, trunkSide).bounds();
        if (box.getXsize() > 0.7D && box.getZsize() > 0.7D) {
            helper.fail("幹の脇の狙いの箱が広すぎる: " + box);
            return;
        }
        net.minecraft.world.entity.player.Player player = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.SURVIVAL);
        player.setOnGround(true);
        net.minecraft.world.level.block.state.BlockState segment = level.getBlockState(trunkSide);
        float hand = segment.getDestroyProgress(player, level, trunkSide);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
        float shears = segment.getDestroyProgress(player, level, trunkSide);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        double handSeconds = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.vineHandBreakSeconds.get();
        double shearsSeconds = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.vineShearsBreakSeconds.get();
        int handTicks = (int) Math.ceil(1.0D / hand);
        int shearsTicks = (int) Math.ceil(1.0D / shears);
        if (Math.abs(handTicks - handSeconds * 20.0D) > 1.0D || Math.abs(shearsTicks - shearsSeconds * 20.0D) > 1.0D || shearsTicks >= handTicks) {
            helper.fail("壊す速さが設定と違う: 素手 " + handTicks + " tick(設定 " + handSeconds + " 秒)・ハサミ " + shearsTicks + " tick(設定 "
                    + shearsSeconds + " 秒)");
            return;
        }
        // 素手で 1 マス壊す(ServerPlayerGameMode#destroyBlock と同じ順: playerWillDestroy → removeBlock)
        segment.getBlock().playerWillDestroy(level, trunkSide, segment, player);
        level.removeBlock(trunkSide, false);
        int left = 0;
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (com.yayuy.overgrownvegetation.block.CorruptedLianaBlock.isLianaPart(level.getBlockState(p))) {
                left++;
            }
        }
        if (left != 0) {
            helper.fail("1 マス壊したのに蔦の束が残っている: " + left + " マス");
            return;
        }
        helper.succeed();
    }

    private static void place(GameTestHelper helper, BlockPos relative, net.minecraft.world.level.block.state.BlockState state) {
        helper.getLevel().setBlock(helper.absolutePos(relative), state, Block.UPDATE_CLIENTS);
    }
}
