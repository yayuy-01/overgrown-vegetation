package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.spawn.BandRoster;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 森からの距離の帯と縁だけを選ぶくじ・群れのまとめ湧き(2026-11-17 ユーザー決定。正本は Vault「敵の湧き方」の【決定 11-17】)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BandPackGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BandPackGameTests.class);
        }

        private Registration() {
        }
    }

    private BandPackGameTests() {
    }

    private static EntityType<?> type(String path) {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path));
    }

    /** 帯の距離の数え方(外 +・内 −)と、名簿の重み(G1 は森に近いほど重い・G4/G5 は内外 4)。遠くの 7 × 7 の過繁茂で。 */
    @GameTest(template = "gametest_empty7", batch = "band_pure", timeoutTicks = 20)
    public static void bandDistancesAndRosterWeights(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        int cx = 730_000, cz = 730_000;
        List<ChunkPos> added = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ChunkPos p = new ChunkPos(cx + dx, cz + dz);
                if (data.adoptOvergrownChunk(level, p)) added.add(p);
            }
        }
        try {
            // プレイヤーは森の真ん中から東へ 8 チャンク(外 5)
            double px = (cx + 8) * 16 + 8, pz = cz * 16 + 8;
            BandRoster r = BandRoster.build(data, px, pz, 0L, 16, 4, 0.0D, 100_000.0D);
            helper.assertTrue(r.distanceAt(cx + 4, cz) == 1 && r.distanceAt(cx + 5, cz) == 2 && r.distanceAt(cx + 12, cz) == 9,
                    "外の距離が違う: " + r.distanceAt(cx + 4, cz) + "・" + r.distanceAt(cx + 5, cz) + "・" + r.distanceAt(cx + 12, cz));
            helper.assertTrue(r.distanceAt(cx + 3, cz) == -1 && r.distanceAt(cx + 2, cz) == -2 && r.distanceAt(cx, cz) == -4,
                    "内の距離が違う: " + r.distanceAt(cx + 3, cz) + "・" + r.distanceAt(cx + 2, cz) + "・" + r.distanceAt(cx, cz));
            helper.assertTrue(BandRoster.g1Weight(1, 16) == 1.0D && BandRoster.g1Weight(16, 16) < 0.01D && BandRoster.g1Weight(17, 16) == 0.0D
                    && BandRoster.g1Weight(2, 16) > BandRoster.g1Weight(8, 16), "G1 の重みが近いほど重くない");
            // G4/G5 の名簿は内外 4 だけ: 7 × 7 の全部(内 −1〜−4)+ 外 1〜4 の輪(15 × 15 − 7 × 7 = 176)
            helper.assertTrue(r.g45Size() == 7 * 7 + (15 * 15 - 7 * 7), "G4/G5 の名簿の数が違う: " + r.g45Size());
            // G1 の名簿から引くと、1 チャンクあたりでは森に近いほど多く出る(遠い帯はチャンクの数が多いので、数の合計ではなく 1 チャンクあたりで比べる)
            RandomSource rnd = RandomSource.create(3L);
            int near = 0, far = 0, n = 4000;
            for (int i = 0; i < n; i++) {
                long k = r.draw(1, rnd);
                int d = r.distanceAt(ChunkPos.getX(k), ChunkPos.getZ(k));
                helper.assertTrue(d >= 1 && d <= 16, "G1 が帯の外から引かれた: " + d);
                if (d <= 2) near++;
                if (d >= 7 && d <= 8) far++;
            }
            int nearCells = 0, farCells = 0;
            for (int x = cx - 12; x <= cx + 28; x++) {
                for (int z = cz - 20; z <= cz + 20; z++) {
                    int d = r.distanceAt(x, z);
                    if (d >= 1 && d <= 2) nearCells++;
                    if (d >= 7 && d <= 8) farCells++;
                }
            }
            helper.assertTrue(near / (double) nearCells > 1.8D * far / farCells, "G1 が 1 チャンクあたりで森の近くに寄らない: 近く " + near + "/" + nearCells + "・遠く " + far + "/" + farCells);
            for (int i = 0; i < 500; i++) {
                long k = r.draw(4, rnd);
                int d = r.distanceAt(ChunkPos.getX(k), ChunkPos.getZ(k));
                helper.assertTrue(d != 0 && Math.abs(d) <= 4, "G4/G5 が内外 4 の外から引かれた: " + d);
            }
            helper.succeed();
        } finally {
            for (ChunkPos p : added) data.forgetOvergrownChunk(p);
        }
    }

    /** 群れのまとめ湧き: アルボラ 3〜5 + ストロビラ 1〜2 を 1 か所に置き、アルボラが先導役の群れになる。上限は 1 体ずつ数え、余りの分だけ置く。 */
    @GameTest(template = "gametest_empty7", batch = "band_pack", timeoutTicks = 20)
    public static void packSpawnsTogetherWithArboraLeading(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        try {
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            SpawnTable.Species arbora = null;
            for (SpawnTable.Species sp : DesignData.spawns().species) if (sp.type() == type("arbora")) arbora = sp;
            helper.assertTrue(arbora != null && arbora.packs() && arbora.packMin() >= 3, "アルボラが群れで湧く種になっていない");
            BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
            int[] counts = new int[11];
            EnemySpawns.Miss m = EnemySpawns.placePack(level, arbora, arbora.type(), at, counts, RandomSource.create(5L));
            List<Mob> made = level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(8.0D), EnemySpawns::spawnedHere);
            int arb = 0, str = 0;
            for (Mob x : made) {
                if (x.getType() == type("arbora")) arb++;
                if (x.getType() == type("strobila")) str++;
            }
            helper.assertTrue(m == EnemySpawns.Miss.SPAWNED && arb >= arbora.packMin() && arb <= arbora.packMax() && str >= 1,
                    "群れがそろって湧かない: アルボラ " + arb + "・ストロビラ " + str + "(" + m + ")");
            helper.assertTrue(counts[4] == made.size(), "上限を 1 体ずつ数えていない: " + counts[4] + " / " + made.size());
            Mob lead = null;
            CrawlerPack pack = null;
            for (Mob x : made) {
                CrawlerPack p = CrawlerPack.externalOf(x);
                helper.assertTrue(p != null, "群れに入っていない個体がいる: " + x.getType());
                pack = p;
            }
            lead = pack.leader();
            helper.assertTrue(lead.getType() == type("arbora") && pack.size() == made.size(), "先導役がアルボラでない・群れの数が違う: " + lead.getType());
            for (Mob x : made) x.discard();
            // 上限の余りが群れの最小(3)より少なければ置かない(切れ端を散らさない)
            int[] full = new int[11];
            full[4] = EnemySpawns.edgeCap(level, 4) - 2;
            EnemySpawns.Miss m2 = EnemySpawns.placePack(level, arbora, arbora.type(), at, full, RandomSource.create(6L));
            List<Mob> made2 = level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(8.0D), EnemySpawns::spawnedHere);
            helper.assertTrue(made2.isEmpty() && m2 == EnemySpawns.Miss.FULL, "余りが群れの最小より少ないのに置いた: " + made2.size() + "(" + m2 + ")");
            // 余りが 4 なら 4 体だけ。頭の種を先に減らすので、混ぜる種(ストロビラ)は残る
            int[] room4 = new int[11];
            room4[4] = EnemySpawns.edgeCap(level, 4) - 4;
            EnemySpawns.placePack(level, arbora, arbora.type(), at, room4, RandomSource.create(7L));
            List<Mob> made3 = level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(8.0D), EnemySpawns::spawnedHere);
            int str3 = 0;
            for (Mob x : made3) if (x.getType() == type("strobila")) str3++;
            helper.assertTrue(made3.size() == 4 && room4[4] == EnemySpawns.edgeCap(level, 4) && str3 >= 1,
                    "上限の余りで切った群れが違う: " + made3.size() + " 体・ストロビラ " + str3);
            for (Mob x : made3) x.discard();
            helper.succeed();
        } finally {
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
        }
    }
}
