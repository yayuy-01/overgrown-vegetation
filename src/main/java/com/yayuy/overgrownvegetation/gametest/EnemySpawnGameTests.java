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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
     * 世界を通す確かめ(1 本にまとめる):
     * 縁の見分け(内側 1・外側 1・その外は縁でない)・地下のそば・フェーズで開く・段で上限が変わる・上限で止まる・
     * 置いた個体に印・司令塔の口(苗床・送り込み・大きな襲撃の数え方)。
     */
    @GameTest(template = "gametest_empty7", batch = "enemy_spawns_world", timeoutTicks = 40)
    public static void edgeUndergroundGatesCapsAndDirectorHooks(GameTestHelper helper) {
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
        List<Mob> made = new ArrayList<>();
        try {
            // ---- 縁: 3 × 3 の真ん中は縁でない・その周り(内側)は縁・1 つ外(外側)は縁・2 つ外は縁でない
            helper.assertTrue(!EnemySpawns.isEdgeChunk(data, cx, cz), "過繁茂の真ん中が縁になっている");
            helper.assertTrue(EnemySpawns.isEdgeChunk(data, cx + 1, cz), "内側 1 が縁でない");
            helper.assertTrue(EnemySpawns.isEdgeChunk(data, cx + 2, cz) && EnemySpawns.isEdgeChunk(data, cx + 2, cz + 2), "外側 1 が縁でない");
            helper.assertTrue(!EnemySpawns.isEdgeChunk(data, cx + 3, cz) && !EnemySpawns.isEdgeChunk(data, cx - 3, cz + 3), "外側 2 が縁になっている");
            // 縁でないチャンクでは湧かない
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            helper.assertTrue(EnemySpawns.tryEdge(level, cx + 4, cz, new int[11], RandomSource.create(1L)) == EnemySpawns.Miss.NOT_EDGE, "縁の外で湧こうとした");
            helper.assertTrue(EnemySpawns.tryEdge(level, cx, cz, new int[11], RandomSource.create(1L)) == EnemySpawns.Miss.NOT_EDGE, "過繁茂の真ん中で湧こうとした");

            // ---- 地下: 過繁茂の真下とそこから nearChunks まで
            int near = DesignData.spawns().underNearChunks;
            helper.assertTrue(EnemySpawns.nearLedger(data, cx, cz, near) && EnemySpawns.nearLedger(data, cx + 1 + near, cz, near), "過繁茂の下・そばが地下の湧きにならない");
            helper.assertTrue(!EnemySpawns.nearLedger(data, cx + 2 + near, cz, near), "過繁茂から離れた所が地下の湧きになる");
            helper.assertTrue(EnemySpawns.tryUnder(level, cx + 2 + near, cz, new int[11], RandomSource.create(1L)) == EnemySpawns.Miss.NOT_NEAR, "離れた所で地下に湧こうとした");
            // 地下に選ばれるのは洞窟に住む G1 だけ
            RandomSource r = RandomSource.create(5L);
            for (int i = 0; i < 200; i++) {
                SpawnTable.Species sp = EnemySpawns.pick(level, new int[11], r, true);
                helper.assertTrue(sp != null && sp.group() == 1 && sp.habitats().contains(SpawnTable.Habitat.CAVE), "地下に洞窟の G1 でない種: " + (sp == null ? "null" : sp.label()));
            }

            // ---- フェーズで開く・段で上限が変わる(期待値は設計値から)
            SpawnTable t = DesignData.spawns();
            PhaseTable pt = DesignData.phases();
            // 11-18 夜 ユーザー決定「出るフェーズは種ごと」: G2 の箱はグレガリア・アルボラ・セミナリア・ペディキュラが F3 から開く(ストロビラ・ヴェシカリアは F4)
            DirectorGates.setPhaseForTests(2);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            helper.assertTrue(EnemySpawns.edgeCap(level, 2) == 0 && EnemySpawns.edgeCap(level, 3) == 0, "F2 で G2・G3 の縁が開いている");
            DirectorGates.setPhaseForTests(3);
            DirectorGates.setStageForTests(PhaseStage.INTRO);
            int g2Intro = EnemySpawns.edgeCap(level, 2);
            helper.assertTrue(g2Intro == (int) Math.round(t.cap(2) * pt.stageMultiplier(3, PhaseStage.INTRO)), "F3 導入の G2 の上限が違う: " + g2Intro);
            helper.assertTrue(EnemySpawns.edgeCap(level, 3) == 0, "F3 で G3 の縁が開いている");
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            helper.assertTrue(EnemySpawns.edgeCap(level, 2) == t.cap(2) && g2Intro < t.cap(2), "F3 最盛の G2 の上限が違う");
            helper.assertTrue(EnemySpawns.edgeCap(level, 1) == t.cap(1), "F3 で G1 の縁が最盛でない");
            r = RandomSource.create(9L);
            for (int i = 0; i < 300; i++) {
                SpawnTable.Species sp = EnemySpawns.pick(level, new int[11], r, false);
                String id = sp == null || sp.id() == null ? "" : sp.id();
                helper.assertTrue(!id.endsWith(":strobila") && !id.endsWith(":vesicaria") && !id.endsWith(":velophyll") && !id.endsWith(":gestatrix"),
                        "F3 の縁に F4 の種が選ばれた: " + id);
            }
            DirectorGates.setPhaseForTests(4);
            DirectorGates.setStageForTests(PhaseStage.INTRO);
            helper.assertTrue(EnemySpawns.edgeCap(level, 3) == 0, "F4 で G3 の縁が開いている");
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.INTRO);
            helper.assertTrue(EnemySpawns.edgeCap(level, 2) == t.cap(2), "F5 導入で G2 が導入に戻った");
            helper.assertTrue(EnemySpawns.edgeCap(level, 3) == (int) Math.round(t.cap(3) * pt.stageMultiplier(5, PhaseStage.INTRO)), "F5 導入の G3 の上限が違う");
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            int total = EnemySpawns.edgeCap(level, 1) + EnemySpawns.edgeCap(level, 2) + EnemySpawns.edgeCap(level, 3);
            helper.assertTrue(total == t.cap(1) + t.cap(2) + t.cap(3), "最盛の合計が表と違う: " + total);
            helper.assertTrue(EnemySpawns.underCap(level) == t.underCap, "地下の上限が表と違う");

            // ---- 開いたグループだけ選ぶ・上限に達したグループは選ばない
            DirectorGates.setPhaseForTests(4);
            r = RandomSource.create(7L);
            for (int i = 0; i < 300; i++) {
                SpawnTable.Species sp = EnemySpawns.pick(level, new int[11], r, false);
                helper.assertTrue(sp != null && sp.group() != 3, "F4 の縁に G3 が選ばれた");
            }
            int[] full = new int[11];
            full[1] = EnemySpawns.edgeCap(level, 1);
            full[2] = EnemySpawns.edgeCap(level, 2);
            full[4] = EnemySpawns.edgeCap(level, 4); // 11-17: G4 のゲスタトリクスも F4 から(種の open)
            helper.assertTrue(EnemySpawns.pick(level, full, r, false) == null, "上限なのに選んだ");
            helper.assertTrue(EnemySpawns.tryEdge(level, cx + 1, cz, full, r) == EnemySpawns.Miss.FULL, "上限なのに縁で湧こうとした");
            full[2] = 0;
            for (int i = 0; i < 100; i++) {
                SpawnTable.Species sp = EnemySpawns.pick(level, full, r, false);
                helper.assertTrue(sp != null && sp.group() == 2, "G1 が上限なのに G1 を選んだ");
            }

            // ---- 置く: 印が付き、数えられる
            BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
            helper.assertTrue(EnemySpawns.place(level, type("arbora"), at, SpawnTable.Habitat.GROUND, EnemySpawns.EDGE_TAG, 2) == EnemySpawns.Miss.SPAWNED, "縁の個体を置けない");
            Mob arbora = level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(at).inflate(2.0D), EnemySpawns::spawnedHere).stream().findFirst().orElse(null);
            helper.assertTrue(arbora != null, "置いた個体が見つからない");
            made.add(arbora);
            int[] c = EnemySpawns.count(level, at.getX(), at.getY(), at.getZ(), 16.0D);
            helper.assertTrue(c[2] >= 1, "置いた G2 が数えられない");

            // ---- 司令塔の口: 苗床は F4 で G3 を孵さず、F5 で孵す
            List<Supplier<? extends EntityType<? extends Mob>>> g3Pool = List.of(() -> (EntityType<? extends Mob>) type("falcimnes"));
            DirectorGates.setPhaseForTests(4);
            director.setBudget(level, 100000.0D);
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), g3Pool) == null, "F4 で苗床が G3 を孵した");
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            director.setBudget(level, 100000.0D);
            helper.assertTrue(director.buySpawn(level, RandomSource.create(1L), g3Pool) == type("falcimnes"), "F5 で苗床が G3 を孵さない");
            // 出す種: F4 は G2 の地上の種だけ・F5 から G3
            DirectorGates.setPhaseForTests(4);
            helper.assertTrue(DirectorHooks.pickPlant(level, r, 3) == null, "F4 で送り込みに G3 を選んだ");
            for (int i = 0; i < 100; i++) {
                EntityType<?> pt4 = DirectorHooks.pickPlant(level, r, 0);
                helper.assertTrue(pt4 != null && DirectorHooks.groupOf(pt4) == 2 && DirectorHooks.marchable(pt4), "F4 の出す種が違う: " + pt4);
            }
            DirectorGates.setPhaseForTests(5);
            helper.assertTrue(DirectorHooks.pickPlant(level, r, 3) != null, "F5 で送り込みに G3 を選べない");

            // ---- 送り込み: 拠点のまわりにいる G2 を歩かせる(縁の印は外れる)
            BaseMemory mem = BaseMemory.createForTests();
            ChunkPos baseChunk = new ChunkPos((arbora.blockPosition().getX() >> 4) + 3, arbora.blockPosition().getZ() >> 4);
            mem.addPresence(baseChunk.toLong(), DesignData.director().base.rememberScore * 2.0D, 0.0D);
            mem.update(0.0D);
            BaseMemory.Base b = mem.bases().get(0);
            double dist = Math.hypot(arbora.getX() - b.centerX(), arbora.getZ() - b.centerZ());
            helper.assertTrue(dist > DesignData.director().base.raidFromMinBlocks && dist < DesignData.director().base.raidSearchBlocks, "拠点までの距離の前提が違う: " + dist);
            director.setBudget(level, 0.0D); // 湧かせる分は払えない = いる個体だけ
            int sent = DirectorHooks.send(level, b, 1, RandomSource.create(3L), 2);
            helper.assertTrue(sent >= 1 && PlantMarch.active(arbora) && PlantMarch.baseId(arbora) == b.id(), "まわりの G2 を送り込めない(" + sent + ")");
            helper.assertTrue(!EnemySpawns.spawnedHere(arbora), "送った個体に縁の印が残っている");
            helper.assertTrue(MobGroup.of(arbora) != null && MobGroup.of(arbora).number() == 2, "アルボラが G2 でない");

            // ---- 送り込み: まわりにいなければ、森の方角(テストでは決め打ちの所)に湧かせて歩かせ、予算から払う
            director.setBudget(level, 100000.0D);
            DirectorHooks.setSpotForTests(() -> helper.absolutePos(new BlockPos(5, 2, 5)));
            long spawnedBefore = DirectorHooks.sentSpawned;
            double budget0 = director.available(level);
            int sentG3 = DirectorHooks.send(level, b, 1, RandomSource.create(4L), 3);
            List<Mob> g3s = level.getEntitiesOfClass(Mob.class, GtCompat.bounds(helper).inflate(4.0D),
                    m -> PlantMarch.active(m) && DirectorHooks.groupOf(m.getType()) == 3);
            made.addAll(g3s);
            helper.assertTrue(sentG3 == 1 && DirectorHooks.sentSpawned == spawnedBefore + 1 && g3s.size() == 1,
                    "足りない G3 を湧かせて送れない: 送った " + sentG3 + "・湧かせた " + (DirectorHooks.sentSpawned - spawnedBefore) + "・見つけた " + g3s.size());
            double paid = budget0 - director.available(level);
            double price = ForestDirector.stagePrice(ForestDirector.spawnCost(g3s.get(0).getType()), DirectorGates.strength(level, DirectorGates.Gate.WAVE_G3));
            helper.assertTrue(Math.abs(paid - price) < 1.0E-6D, "湧かせた分の払いが違う: " + paid + "(値段 " + price + ")");
            helper.assertTrue(!EnemySpawns.spawnedHere(g3s.get(0)), "送り込みで湧かせた個体に縁の印が付いている");

            // ---- 大きな襲撃: 印の付いた G2 も数える
            int aliveBefore = LargeRaids.alive(level, b);
            arbora.addTag(LargeRaids.ATTACKER_TAG);
            helper.assertTrue(LargeRaids.alive(level, b) == aliveBefore + 1, "大きな襲撃が G2 の攻め手を数えない");
            helper.succeed();
        } finally {
            DirectorHooks.setSpotForTests(null);
            for (Mob m : made) m.discard();
            for (ChunkPos p : added) data.forgetOvergrownChunk(p);
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            director.setBudget(level, budgetBefore);
        }
    }
}
