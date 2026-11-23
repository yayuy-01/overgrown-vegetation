package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import com.yayuy.overgrownvegetation.spawn.SpawnTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 敵の湧き方(縁・地下・司令塔の口。2026-11-15)。フェーズの差し替えと台帳は世界に 1 つなので、世界を使う確かめは 1 本にまとめ、
 * 自分だけのバッチに入れる。台帳は遠くのチャンク(座標 9_000_000 / 16 あたり)に足し、最後に外す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class EnemySpawnGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(EnemySpawnGameTests.class);
        }

        private Registration() {
        }
    }

    private EnemySpawnGameTests() {
    }

    private static EntityType<?> type(String path) {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path));
    }

    /** 表: 名指しで、萌芽体は入らない。空の種は司令塔の口に入らない。G1 の家畜・村人は洞窟に住まない。 */
    @GameTest(template = "gametest_empty7", batch = "enemy_spawns_pure", timeoutTicks = 20)
    public static void tableHasHabitatsAndNoBlightFern(GameTestHelper helper) {
        SpawnTable t = DesignData.spawns();
        EntityType<?> fern = type("blight_fern");
        for (SpawnTable.Species sp : t.species) {
            helper.assertTrue(sp.type() != fern, "萌芽体が湧きの表に入っている");
            if (sp.g1() == G1Species.COW || sp.g1() == G1Species.VILLAGER) {
                helper.assertTrue(!sp.habitats().contains(SpawnTable.Habitat.CAVE), sp.label() + " が洞窟に住む");
            }
        }
        for (String sky : List.of("velophyll", "seminaria", "pedicula", "acumnes")) {
            helper.assertTrue(!DirectorHooks.marchable(type(sky)), sky + " が司令塔の口に入っている");
        }
        for (String ground : List.of("arbora", "gregaria", "strobila", "vesicaria", "falcimnes", "venamnes", "clipeimnes", "pilimnes", "saltimnes")) {
            helper.assertTrue(DirectorHooks.marchable(type(ground)), ground + " が司令塔の口に入っていない");
        }
        // 苗床に登録されたのは地上の G2・G3 だけ
        List<EntityType<?>> nursery = new ArrayList<>();
        for (Supplier<? extends EntityType<? extends Mob>> s : ForestDirector.nurserySpawns()) nursery.add(s.get());
        helper.assertTrue(nursery.contains(type("arbora")) && nursery.contains(type("falcimnes")), "苗床に G2・G3 が登録されていない: " + nursery);
        helper.assertTrue(!nursery.contains(type("velophyll")) && !nursery.contains(fern), "苗床に空の種か萌芽体が入っている");
        helper.succeed();
    }

    /**
     * 縁の判定(巣の置き場・森の中の判定が使う)と、司令塔の口(苗床が孵す種・前線が出す種のフェーズ)。
     * 2026-11-23 片付け E: 縁・地下の湧き・送り込み・大きな襲撃の数えの確かめは、その仕組みごと消した。
     */
    @GameTest(template = "gametest_empty7", batch = "enemy_spawns_world", timeoutTicks = 40)
    public static void edgeChunksAndDirectorPicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        ForestDirector director = ForestDirector.get(level);
        double budgetBefore = director.available(level);
        // 遠くの 3 × 3 の過繁茂(真ん中 cx, cz)
        int cx = 560_000, cz = 560_000;
        List<ChunkPos> added = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos p = new ChunkPos(cx + dx, cz + dz);
                if (data.adoptOvergrownChunk(level, p)) added.add(p);
            }
        }
        try {
            // ---- 縁: 3 × 3 の真ん中は縁でない・その周り(内側)は縁・1 つ外(外側)は縁・2 つ外は縁でない
            helper.assertTrue(!EnemySpawns.isEdgeChunk(data, cx, cz), "過繁茂の真ん中が縁になっている");
            helper.assertTrue(EnemySpawns.isEdgeChunk(data, cx + 1, cz), "内側 1 が縁でない");
            helper.assertTrue(EnemySpawns.isEdgeChunk(data, cx + 2, cz) && EnemySpawns.isEdgeChunk(data, cx + 2, cz + 2), "外側 1 が縁でない");
            helper.assertTrue(!EnemySpawns.isEdgeChunk(data, cx + 3, cz) && !EnemySpawns.isEdgeChunk(data, cx - 3, cz + 3), "外側 2 が縁になっている");

            // ---- 司令塔の口: 苗床は F4 で G3 を孵さず、F5 で孵す
            List<Supplier<? extends EntityType<? extends Mob>>> g3Pool = List.of(() -> (EntityType<? extends Mob>) type("falcimnes"));
            DirectorGates.setPhaseForTests(4);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            director.setBudget(level, 100000.0D);
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), g3Pool) == null, "F4 で苗床が G3 を孵した");
            DirectorGates.setPhaseForTests(5);
            director.setBudget(level, 100000.0D);
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), g3Pool) == type("falcimnes"), "F5 で苗床が G3 を孵さない");
            // 出す種: F4 は G2 の地上の種だけ・F5 から G3
            RandomSource r = RandomSource.create(9L);
            DirectorGates.setPhaseForTests(4);
            helper.assertTrue(DirectorHooks.pickPlant(level, r, 3) == null, "F4 で G3 を選んだ");
            for (int i = 0; i < 100; i++) {
                EntityType<?> pt4 = DirectorHooks.pickPlant(level, r, 0);
                helper.assertTrue(pt4 != null && DirectorHooks.groupOf(pt4) == 2 && DirectorHooks.marchable(pt4), "F4 の出す種が違う: " + pt4);
            }
            DirectorGates.setPhaseForTests(5);
            helper.assertTrue(DirectorHooks.pickPlant(level, r, 3) != null, "F5 で G3 を選べない");
            helper.succeed();
        } finally {
            for (ChunkPos p : added) data.forgetOvergrownChunk(p);
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            director.setBudget(level, budgetBefore);
        }
    }
}
