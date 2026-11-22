package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.spawn.FogLurkers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 怖い現象 E の「たまに本物」(FogLurkers、2026-11-16): 濃霧の間・G3 のフェーズから・昼と朝だけ / 汚染地帯の外では出ない /
 * 近づかれる(攻撃される)まで動かない / 霧が晴れたら去る。フェーズと霧の差し替えは世界に 1 つなので自分だけのバッチで 1 本にまとめる。
 * (見通しの縁の木を探す所は、テスト場の高さの地図が天井を答えるので、Claude のクライアントで確かめる。)
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FogLurkerGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FogLurkerGameTests.class);
        }

        private Registration() {
        }
    }

    private FogLurkerGameTests() {
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "gametest_empty7", batch = "fog_lurker", timeoutTicks = 20)
    public static void onlyInFogFromG3AndStandsUntilWokenThenLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        PhaseData data = PhaseData.get(level);
        net.minecraft.world.level.GameRules.BooleanValue spawning = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
        boolean spawningBefore = spawning.get();
        spawning.set(true, level.getServer()); // GameTest のサーバーは Mob の湧きが切ってある
        try {
            // 霧とフェーズ
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            FogLurkers.fogOverride = 0.8D;
            helper.assertTrue(FogLurkers.conditions(level), "濃霧・F5 なのに出ない");
            FogLurkers.fogOverride = 0.1D;
            helper.assertTrue(!FogLurkers.conditions(level), "霧が薄いのに出る");
            FogLurkers.fogOverride = 0.8D;
            DirectorGates.setPhaseForTests(4);
            helper.assertTrue(!FogLurkers.conditions(level), "G3 のフェーズより前に出る");
            DirectorGates.setPhaseForTests(5);
            // 昼と朝だけ
            helper.assertTrue(FogLurkers.dayOrMorning(1000L) && FogLurkers.dayOrMorning(23500L) && !FogLurkers.dayOrMorning(15000L), "昼と朝の判定が違う");
            // 汚染地帯の外では出ない(遠くの台帳に載っていないチャンク)
            ChunkPos far = new ChunkPos(700_000, 700_000);
            data.forgetOvergrownChunk(far);
            helper.assertTrue(FogLurkers.trySpawn(level, far.getMiddleBlockX(), far.getMiddleBlockZ(), level.getRandom()) == null, "汚染地帯の外で出た");

            // 立ち止まった本物(テスト場の中に置く): 攻撃されるまで動かない・霧が晴れたら去る
            EntityType<? extends Mob> type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "clipeimnes"));
            Mob a = helper.spawn(type, new Vec3(1.5D, 2.0D, 1.5D));
            a.setNoAi(true);
            a.addTag(FogLurkers.TAG);
            FogLurkers.onJoinForTests(a);
            Mob b = helper.spawn(type, new Vec3(5.5D, 2.0D, 5.5D));
            b.setNoAi(true);
            b.addTag(FogLurkers.TAG);
            FogLurkers.onJoinForTests(b);
            FogLurkers.tickLurkers(level, true);
            helper.assertTrue(a.isNoAi() && b.isNoAi() && !a.isRemoved(), "霧の間に、近づかれてもいないのに動いた・消えた");
            b.hurt(level.damageSources().generic(), 1.0F);
            helper.assertTrue(!b.isNoAi() && !b.getTags().contains(FogLurkers.TAG) && b.getTags().contains(FogLurkers.WOKE_TAG), "攻撃されても動き出さない");
            FogLurkers.tickLurkers(level, false); // 霧が晴れた
            helper.assertTrue(a.isRemoved(), "霧が晴れても、立ち止まっていた本物が去らない");
            helper.assertTrue(!b.isRemoved(), "動き出した本物まで消えた(ふつうの敵になったはず)");
            b.discard();
            helper.succeed();
        } finally {
            spawning.set(spawningBefore, level.getServer());
            FogLurkers.fogOverride = null;
            FogLurkers.clearForTests();
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
        }
    }
}
