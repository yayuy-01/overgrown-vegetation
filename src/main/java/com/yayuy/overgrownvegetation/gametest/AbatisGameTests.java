package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.AbatisBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 逆茂木(2026-10-16、サバイバル用アイテム ㊶。ユーザー指定)。
 * <ul>
 *     <li>刺さると傷つき、1 回ぶん傷む / スニーク中のプレイヤーは刺さらない({@link #pokeHurtsAndSneakingIsSafe})。</li>
 *     <li>1 段の回数で次の段へ・4 段目を刺し切ると壊れて棒 1 本({@link #wearAdvancesStagesAndBreaks})。</li>
 *     <li>棒で右クリックすると 1 段ぶん戻り、棒を 1 本使う・無傷なら棒を使わない({@link #stickRepairsOneStage})。</li>
 *     <li>壊すと、無傷なら逆茂木・傷んでいたら棒 2 本({@link #dropsDependOnWear})。</li>
 * </ul>
 * 1 段の回数は {@code defense.json} から引く(決め打ちしない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AbatisGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AbatisGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "abatis";

    private static BlockState abatis(int stage, int hits) {
        return ModBlocks.ABATIS.get().defaultBlockState().setValue(AbatisBlock.STAGE, stage).setValue(AbatisBlock.HITS, hits);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void pokeHurtsAndSneakingIsSafe(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, abatis(0, 0));
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(rel);
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, rel);
        float before = cow.getHealth();
        if (!AbatisBlock.poke(level, pos, level.getBlockState(pos), cow) || cow.getHealth() >= before) {
            helper.fail("牛が刺さらない(体力 " + before + " → " + cow.getHealth() + ")");
            return;
        }
        if (AbatisBlock.totalHits(level.getBlockState(pos)) != 1) {
            helper.fail("刺さったのに傷まない: " + level.getBlockState(pos));
            return;
        }
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(true);
        if (AbatisBlock.poke(level, pos, level.getBlockState(pos), player)) {
            helper.fail("スニーク中のプレイヤーが刺さった");
            return;
        }
        if (AbatisBlock.totalHits(level.getBlockState(pos)) != 1) {
            helper.fail("スニークで通っただけで傷んだ");
            return;
        }
        cow.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void wearAdvancesStagesAndBreaks(GameTestHelper helper) {
        int per = AbatisBlock.hitsPerStage();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, abatis(0, per - 1));
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(rel);
        AbatisBlock.wear(level, pos, level.getBlockState(pos), 1);
        BlockState s = level.getBlockState(pos);
        if (!s.is(ModBlocks.ABATIS.get()) || s.getValue(AbatisBlock.STAGE) != 1 || s.getValue(AbatisBlock.HITS) != 0) {
            helper.fail(per + " 回目で次の段へ進まない: " + s);
            return;
        }
        helper.setBlock(rel, abatis(AbatisBlock.STAGES - 1, per - 1));
        AbatisBlock.wear(level, pos, level.getBlockState(pos), 1);
        if (level.getBlockState(pos).is(ModBlocks.ABATIS.get())) {
            helper.fail("4 段目を刺し切っても壊れない");
            return;
        }
        int sticks = 0;
        int other = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0D))) {
            if (item.getItem().is(Items.STICK)) {
                sticks += item.getItem().getCount();
            } else {
                other++;
            }
            item.discard();
        }
        if (sticks != 1 || other != 0) {
            helper.fail("壊れたときの落とし物: 棒 " + sticks + " / ほか " + other + "(期待 1 / 0)");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void stickRepairsOneStage(GameTestHelper helper) {
        int per = AbatisBlock.hitsPerStage();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, abatis(2, 5));
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(rel);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK, 3));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        level.getBlockState(pos).useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
        int total = AbatisBlock.totalHits(level.getBlockState(pos));
        if (total != 2 * per + 5 - per) {
            helper.fail("棒 1 本で 1 段ぶん戻らない: 刺した回数 " + total + "(期待 " + (per + 5) + ")");
            return;
        }
        if (player.getMainHandItem().getCount() != 2) {
            helper.fail("修理で棒を 1 本使わない: 残り " + player.getMainHandItem().getCount());
            return;
        }
        helper.setBlock(rel, abatis(0, 0));
        level.getBlockState(pos).useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
        if (player.getMainHandItem().getCount() != 2) {
            helper.fail("無傷なのに棒を使った");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dropsDependOnWear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos fresh = new BlockPos(2, 2, 2);
        BlockPos worn = new BlockPos(5, 2, 5);
        helper.setBlock(fresh, abatis(0, 0));
        helper.setBlock(worn, abatis(0, 1));
        level.destroyBlock(helper.absolutePos(fresh), true);
        level.destroyBlock(helper.absolutePos(worn), true);
        int[] freshDrops = count(level, helper.absolutePos(fresh));
        int[] wornDrops = count(level, helper.absolutePos(worn));
        if (freshDrops[0] != 1 || freshDrops[1] != 0) {
            helper.fail("無傷のを壊した落とし物: 逆茂木 " + freshDrops[0] + " / 棒 " + freshDrops[1] + "(期待 1 / 0)");
            return;
        }
        if (wornDrops[0] != 0 || wornDrops[1] != 2) {
            helper.fail("傷んだのを壊した落とし物: 逆茂木 " + wornDrops[0] + " / 棒 " + wornDrops[1] + "(期待 0 / 2)");
            return;
        }
        helper.succeed();
    }

    /** {逆茂木, 棒} の数(数えた物は消す)。 */
    private static int[] count(ServerLevel level, BlockPos pos) {
        int[] n = new int[2];
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.0D))) {
            if (item.getItem().is(ModItems.ABATIS_ITEM.get())) {
                n[0] += item.getItem().getCount();
            } else if (item.getItem().is(Items.STICK)) {
                n[1] += item.getItem().getCount();
            }
            item.discard();
        }
        return n;
    }

    private AbatisGameTests() {
    }
}
