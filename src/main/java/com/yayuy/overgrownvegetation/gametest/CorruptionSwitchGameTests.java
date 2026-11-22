package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /ov corruption true|false}(2026-10-10 ユーザー指定「false の場合はすべての汚染行為が止まるように」)。
 *
 * <p>値はワールドに 1 つなので、オフを書いてから確かめ終わるまでを<b>同じ tick の中</b>で済ませ、必ずオンに戻す
 * (戻し忘れると後のバッチの汚染のテストが全部落ちる)。バッチも分ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CorruptionSwitchGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CorruptionSwitchGameTests.class);
        }

        private Registration() {
        }
    }

    /** オフの間は、ブロック・シダ・バイオーム・爆発・侵食のどの入口も何もしない。オンに戻すと汚染できる。 */
    @GameTest(template = "gametest_empty7", batch = "corruption_switch")
    public static void offStopsEveryCorruptionEntryPoint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        helper.assertTrue(CorruptionSwitch.isOn() && CorruptionSwitch.enabled(server), "GameTest のサーバーで汚染がオンでない");

        BlockPos dirt = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos burstCenter = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(burstCenter.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockState dirtState = level.getBlockState(dirt);
        ChunkPos chunk = new ChunkPos(dirt);
        Holder<Biome> biomeBefore = level.getBiome(dirt);
        String failure = null;
        try {
            CorruptionSwitch.set(server, false);
            if (CorruptionSwitch.isOn() || CorruptionSwitch.enabled(server)) {
                failure = "オフを書いたのにオンのまま";
            } else if (CorruptionRegistry.canCorrupt(level, dirt, dirtState)) {
                failure = "オフなのに土を汚染できると答えた(共通の関門)";
            } else if (CorruptionRegistry.corrupt(level, dirt, dirtState, level.getRandom()) != dirtState) {
                failure = "オフなのに corrupt() が別のブロックを返した";
            } else if (CorruptionSpreader.corruptSingle(level, dirt) || !level.getBlockState(dirt).is(Blocks.DIRT)) {
                failure = "オフなのに 1 マスの汚染(胞子をまく Mob・苗床・寄生植物装備)が効いた";
            } else if (NoisyCorruptionBurst.sphere(level, Vec3.atCenterOf(burstCenter), 3, 0.0D) > 0
                    || !level.getBlockState(burstCenter.below()).is(Blocks.STONE)) {
                failure = "オフなのに球の汚染(アルボラの死・寄生クリーパー・ポータル)が効いた";
            } else if (BlightFernPlacement.spawn(level, burstCenter, MobSpawnType.MOB_SUMMONED) != null) {
                failure = "オフなのに汚染シダが生まれた";
            } else {
                CorruptionSpreader spreader = new CorruptionSpreader();
                spreader.seedGround(level, burstCenter);
                spreader.tick(level, burstCenter);
                OvergrownBiomeConverter.forceOvergrown(level, chunk);
                if (spreader.getCorruptedCount() > 0 || spreader.isFinished() || !level.getBlockState(burstCenter.below()).is(Blocks.STONE)) {
                    failure = "オフなのに汚染シダ・死体の侵食が進んだ(数 " + spreader.getCorruptedCount() + "・終わった " + spreader.isFinished() + ")";
                } else if (!level.getBiome(dirt).equals(biomeBefore)) {
                    failure = "オフなのにバイオームが書き換わった";
                }
            }
        } finally {
            CorruptionSwitch.set(server, true);
        }
        if (failure != null) {
            helper.fail(failure);
            return;
        }
        // オンに戻すと汚染できる
        if (!CorruptionSpreader.corruptSingle(level, dirt) || level.getBlockState(dirt).is(Blocks.DIRT)) {
            helper.fail("オンに戻したのに 1 マスの汚染が効かない: " + level.getBlockState(dirt));
            return;
        }
        helper.succeed();
    }

    private CorruptionSwitchGameTests() {
    }
}
