package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.block.ParasiteNurseryBlockEntity;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.effect.SporeInfectionTracker;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.ParasitizedCow;
import com.yayuy.overgrownvegetation.g1.G1Evolutions;
import com.yayuy.overgrownvegetation.g1.G1Life;
import com.yayuy.overgrownvegetation.g1.G1Lifecycle;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * G1 の作り直し 第 1 段(2026-10-22): Mob の感染の閾値(空の下の浮遊 × 秒)・死体の放出と時計・司令塔の分岐と 3 ルート・進化の時計。
 * 胞子の場はほかのテストと共有の 1 つなので、場を回すテストは別のバッチに 1 本ずつ置く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class G1RemakeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(G1RemakeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "g1_remake";

    private G1RemakeGameTests() {
    }

    // ---------------------------------------------------------------- 感染

    /**
     * 空の下の牛は、その升の浮遊 P% で 50% × 30 秒 ÷ P 秒ぶん積んで感染する(早すぎず遅すぎず)。屋根の下の牛は積まない
     * (テスト場のバイオームが繁茂・過繁茂でなければ)。
     */
    @GameTest(template = TEMPLATE, batch = "g1_remake_field", timeoutTicks = 200)
    public static void mobInfectionNeedsSkyAndAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Cow open = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(1.5, 2.0, 1.5));
        Cow roofed = helper.spawnWithNoFreeWill(EntityType.COW, new Vec3(5.5, 2.0, 5.5));
        helper.setBlock(new BlockPos(5, 5, 5), Blocks.STONE);
        for (Cow cow : List.of(open, roofed)) {
            int cx = cow.getBlockX() >> 4, cz = cow.getBlockZ() >> 4;
            SporeFieldServer.watchForTests(cx, cz);
            SporeFieldServer.withFieldForTests(f -> {
                f.emitPoint(cx, cz, 1.0D);
                return null;
            });
        }
        SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
        waitForAir(helper, level, open, roofed, 0);
    }

    /** 場の写しに浮遊が出るまで待ってから(裏のスレッドの計算)、1 度だけ確かめる。 */
    private static void waitForAir(GameTestHelper helper, ServerLevel level, Cow open, Cow roofed, int tries) {
        double air = SporeFieldServer.airAt(open.getBlockX() >> 4, open.getBlockZ() >> 4);
        if (Double.isNaN(air) || air <= 0.1D) {
            helper.assertTrue(tries < 30, "場の写しに浮遊が出ない: " + air);
            helper.runAfterDelay(5, () -> waitForAir(helper, level, open, roofed, tries + 1));
            return;
        }
        {
            double p = SporeInfectionTracker.mobAirPercent(level, open);
            G1Table g = DesignData.g1();
            helper.assertTrue(p >= g.infectPercent, "空の下の浮遊が少なすぎる: " + p);
            if (!com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(roofed.blockPosition()))) {
                helper.assertTrue(!SporeInfectionTracker.underSky(level, roofed), "屋根の下なのに空の下と判定された");
                helper.assertTrue(SporeInfectionTracker.mobAirPercent(level, roofed) == 0.0D, "屋根の下で浮遊を浴びている");
            }
            // 1 回 = 1 秒ぶん。閾値 = 50 × 30 %秒。届く 1 回前までは感染しない
            int needed = (int) Math.ceil(g.infectPercent * g.infectSeconds / p);
            for (int i = 0; i < needed - 1; i++) {
                SporeInfectionTracker.tickMob(level, open, 20);
                SporeInfectionTracker.tickMob(level, roofed, 20);
            }
            helper.assertTrue(!open.hasEffect(ModMobEffects.SPORE_INFECTION), "閾値より前に感染した(" + needed + " 秒の手前)");
            SporeInfectionTracker.tickMob(level, open, 20);
            SporeInfectionTracker.tickMob(level, roofed, 20);
            helper.assertTrue(open.hasEffect(ModMobEffects.SPORE_INFECTION), "閾値に届いても感染しない(" + needed + " 秒)");
            if (!com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(roofed.blockPosition()))) {
                helper.assertTrue(!roofed.hasEffect(ModMobEffects.SPORE_INFECTION), "屋根の下の牛が感染した");
            }
            open.discard();
            roofed.discard();
            helper.succeed();
        }
    }

    // ---------------------------------------------------------------- 死体

    /** 死体は emitTicks の間は起き上がらず、時計が来たら消えて起き上がる(外れは萌芽体)。その間その升へ胞子を放つ。 */
    @GameTest(template = TEMPLATE, batch = "g1_remake_corpse", timeoutTicks = 300)
    public static void corpseEmitsThenRises(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SporeFieldTable before = DesignData.sporeField();
        // 放出を見分けやすく 1 回(100 tick)で 1.0 にする
        SporeFieldTable.Json j = new SporeFieldTable.Json();
        j.sources = new SporeFieldTable.Sources();
        j.sources.corpsePerDay = 240.0D;
        DesignData.setSporeFieldForTests(SporeFieldTable.fromJson(j));
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.OVERGROWN_COW_CORPSE.get());
        BlockPos abs = helper.absolutePos(rel);
        int cx = abs.getX() >> 4, cz = abs.getZ() >> 4;
        double air0 = SporeFieldServer.withFieldForTests(f -> f.air(cx, cz));
        int emitTicks = DesignData.g1().corpseEmitTicks;
        CorruptedCorpseBlockEntity be = (CorruptedCorpseBlockEntity) level.getBlockEntity(abs);
        helper.assertTrue(be != null, "死体の中身が無い");
        be.setAgeForTests(emitTicks - 150); // 次の 100 の倍数で 1 回放つ
        helper.runAfterDelay(60, () -> {
            DesignData.setSporeFieldForTests(before); // 放出は 50 tick 目に済んでいる。ここで落ちても後のバッチへ持ち越さない
            helper.assertTrue(level.getBlockState(abs).is(ModBlocks.OVERGROWN_COW_CORPSE.get()), "時計の前に死体が消えた");
            SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
        });
        helper.runAfterDelay(200, () -> {
            DesignData.setSporeFieldForTests(before);
            double air1 = SporeFieldServer.withFieldForTests(f -> f.air(cx, cz));
            helper.assertTrue(air1 > air0 + 0.3D, "死体がその升へ胞子を放っていない: " + air0 + " → " + air1);
            helper.assertTrue(!level.getBlockState(abs).is(ModBlocks.OVERGROWN_COW_CORPSE.get()), "時計が来ても死体が起き上がらない");
            AABB box = new AABB(abs).inflate(2.0D);
            boolean risen = !level.getEntitiesOfClass(ParasitizedCow.class, box).isEmpty()
                    || !level.getEntitiesOfClass(BlightFernEntity.class, box).isEmpty();
            helper.assertTrue(risen, "死体の後に寄生された牛も萌芽体もいない");
            level.getEntitiesOfClass(Mob.class, box).forEach(Mob::discard);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- 司令塔の分岐

    /** 1 日たつと司令塔が道を決める。進化体の無い鶏は進化を選べず、湿った暗い所が無ければ苗床も選べない(= 根付き)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void directorDecidesAfterOneDay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob chicken = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_CHICKEN.get(), new Vec3(3.5, 2.0, 3.5));
        G1Life life = chicken.getData(ModAttachments.G1_LIFE);
        long day = (long) (DesignData.g1().branchAfterDays * 24000.0D);
        G1Lifecycle.step(level, chicken, life, day - 40);
        helper.assertTrue(life.route == G1Life.Route.NONE, "1 日より前に道が決まった");
        G1Lifecycle.step(level, chicken, life, 40);
        helper.assertTrue(life.route == G1Life.Route.ROOTED, "鶏が根付き以外を選んだ: " + life.route + " / " + life.note);
        chicken.discard();
        helper.succeed();
    }

    /** ここで倒された G1 が多いほど、進化の点が上がる(揺らぎ ±20% を越えて)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void killsRaiseEvolveScore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob zombie = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(3.5, 2.0, 3.5));
        ForestDirector director = ForestDirector.get(level.getServer());
        ForestDirector.Decision quiet = director.decide(level, zombie);
        ChunkPos chunk = new ChunkPos(zombie.blockPosition());
        for (int i = 0; i < 10; i++) director.recordKill(level, chunk);
        ForestDirector.Decision bloody = director.decide(level, zombie);
        helper.assertTrue(bloody.kills() >= 9.0D, "倒された数を覚えていない: " + bloody.kills());
        helper.assertTrue(bloody.evolve() > quiet.evolve(), "倒された G1 が多いのに進化の点が上がらない: "
                + quiet.evolve() + " → " + bloody.evolve());
        zombie.discard();
        helper.succeed();
    }

    // ---------------------------------------------------------------- 3 ルート

    /** 根付き: 汚染し終える(または時間切れ)と、その場に「根付いた寄生体」を置いて消える。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void rootedBecomesBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob zombie = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(3.5, 2.0, 3.5));
        G1Life life = zombie.getData(ModAttachments.G1_LIFE);
        G1Lifecycle.force(level, zombie, life, G1Life.Route.ROOTED);
        BlockPos at = zombie.blockPosition();
        G1Lifecycle.step(level, zombie, life, DesignData.g1().rootedMaxTicks);
        helper.assertTrue(zombie.isRemoved(), "根付き終わっても Mob が残っている");
        helper.assertTrue(level.getBlockState(at).is(ModBlocks.ROOTED_PARASITE.get()), "根付いた寄生体が置かれていない: " + level.getBlockState(at));
        helper.succeed();
    }

    /** 苗床: 行き先に着く(または時間切れ)と「苗床」になり、苗床は萌芽体を生む。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void nurseryBecomesBlockAndBreedsSprouts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob cow = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_COW.get(), new Vec3(3.5, 2.0, 3.5));
        G1Life life = cow.getData(ModAttachments.G1_LIFE);
        life.nurseryTarget = cow.blockPosition();
        G1Lifecycle.force(level, cow, life, G1Life.Route.NURSERY);
        BlockPos at = cow.blockPosition();
        G1Lifecycle.step(level, cow, life, 20);
        helper.assertTrue(cow.isRemoved(), "苗床に着いても Mob が残っている");
        helper.assertTrue(level.getBlockState(at).is(ModBlocks.PARASITE_NURSERY.get()), "苗床が置かれていない: " + level.getBlockState(at));
        ParasiteNurseryBlockEntity be = (ParasiteNurseryBlockEntity) level.getBlockEntity(at);
        helper.assertTrue(be != null && be.produce(level, at, DesignData.g1()), "苗床が萌芽体を生まない");
        List<BlightFernEntity> sprouts = level.getEntitiesOfClass(BlightFernEntity.class, new AABB(at).inflate(2.0D));
        helper.assertTrue(!sprouts.isEmpty(), "生んだ萌芽体がいない");
        sprouts.forEach(Mob::discard);
        helper.succeed();
    }

    /** 進化: 1 段目まで evolve1Days、2 段目までさらに evolve2Days。時計は個体に保存され(NBT の往復)、入れ替わっても続く。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void evolutionClockTwoStages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        G1Table g = DesignData.g1();
        Mob zombie = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(3.5, 2.0, 3.5));
        G1Life life = zombie.getData(ModAttachments.G1_LIFE);
        G1Lifecycle.force(level, zombie, life, G1Life.Route.EVOLVE);
        long first = (long) (g.evolve1Days * 24000.0D);
        G1Lifecycle.step(level, zombie, life, first - 20);
        helper.assertTrue(!zombie.isRemoved(), "2 日より前に進化した");
        // 時計は保存される
        CompoundTag saved = life.serializeNBT(level.registryAccess());
        G1Life loaded = new G1Life();
        loaded.deserializeNBT(level.registryAccess(), saved);
        helper.assertTrue(loaded.route == G1Life.Route.EVOLVE && loaded.routeAge == first - 20, "進化の時計が保存されない");
        G1Lifecycle.step(level, zombie, life, 20);
        helper.assertTrue(zombie.isRemoved(), "2 日たっても進化しない");
        List<Mob> stage1 = level.getEntitiesOfClass(Mob.class, new AABB(zombie.blockPosition()).inflate(2.0D),
                m -> m.getType() == ModEntities.VINEARM_ZOMBIE.get());
        helper.assertTrue(stage1.size() == 1, "蔦腕が 1 体いない: " + stage1.size());
        Mob vinearm = stage1.get(0);
        G1Life l1 = vinearm.getData(ModAttachments.G1_LIFE);
        helper.assertTrue(l1.route == G1Life.Route.EVOLVE && l1.routeAge == 0, "1 段目の一生が進化の途中になっていない");
        helper.assertTrue(G1Evolutions.stageOf(vinearm.getType()) == 1, "蔦腕が 1 段目でない");
        long second = (long) (g.evolve2Days * 24000.0D);
        G1Lifecycle.step(level, vinearm, l1, second - 20);
        helper.assertTrue(!vinearm.isRemoved(), "5 日より前に 2 段目になった");
        G1Lifecycle.step(level, vinearm, l1, 20);
        List<Mob> stage2 = level.getEntitiesOfClass(Mob.class, new AABB(vinearm.blockPosition()).inflate(2.0D),
                m -> m.getType() == ModEntities.VINE_EXECUTIONER.get());
        helper.assertTrue(stage2.size() == 1, "蔦の処刑者が 1 体いない: " + stage2.size());
        helper.assertTrue(stage2.get(0).getType().is(com.yayuy.overgrownvegetation.ModTags.GROUP_5), "蔦の処刑者が G5 でない");
        stage2.forEach(Mob::discard);
        helper.succeed();
    }

    /** 4 種の進化の行き先と分類(タグ)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void evolutionTableAndGroups(GameTestHelper helper) {
        record Line(EntityType<?> base, EntityType<?> first, EntityType<?> second, com.yayuy.overgrownvegetation.phase.MobGroup group) {
        }
        List<Line> lines = List.of(
                new Line(ModEntities.PARASITIZED_ZOMBIE.get(), ModEntities.VINEARM_ZOMBIE.get(), ModEntities.VINE_EXECUTIONER.get(), com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_5),
                new Line(ModEntities.PARASITIZED_VILLAGER.get(), ModEntities.HOLLOW_VILLAGER.get(), ModEntities.ROOT_STRIKER.get(), com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_5),
                new Line(ModEntities.PARASITIZED_COW.get(), ModEntities.SPORESAC_COW.get(), ModEntities.WALKING_SPORANGIUM.get(), com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_4),
                new Line(ModEntities.PARASITIZED_CREEPER.get(), ModEntities.PODDED_CREEPER.get(), ModEntities.BURST_POD.get(), com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_4));
        for (Line l : lines) {
            helper.assertTrue(G1Evolutions.next(l.base()) == l.first() && G1Evolutions.next(l.first()) == l.second()
                    && G1Evolutions.next(l.second()) == null, "進化の行き先が違う: " + l.base());
            helper.assertTrue(com.yayuy.overgrownvegetation.phase.MobGroup.of(l.first()) == com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_1,
                    "1 段目が G1 でない: " + l.first());
            helper.assertTrue(com.yayuy.overgrownvegetation.phase.MobGroup.of(l.second()) == l.group(), "2 段目の分類が違う: " + l.second());
            helper.assertTrue(!com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(l.first())
                    && !com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(l.second()), "進化体が自然・レイドに出る設定: " + l.first());
        }
        helper.assertTrue(G1Evolutions.next(ModEntities.PARASITIZED_CHICKEN.get()) == null, "鶏に進化体がある");
        helper.succeed();
    }

    /** G1 は自然には湧かない(どのフェーズ・どの場所の湧きの表にもいない)。レイドには残る(naturalSpawn は true)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void g1HasNoNaturalSpawns(GameTestHelper helper) {
        for (EntityType<?> type : List.of(ModEntities.PARASITIZED_COW.get(), ModEntities.PARASITIZED_ZOMBIE.get(),
                ModEntities.PARASITIZED_PIGLIN.get(), ModEntities.PARASITIZED_GHAST.get(), ModEntities.PARASITIZED_ENDERMAN.get())) {
            for (int phase = 0; phase <= 10; phase++) {
                for (com.yayuy.overgrownvegetation.data.MobStat.SpawnPlace place : com.yayuy.overgrownvegetation.data.MobStat.SpawnPlace.values()) {
                    helper.assertTrue(!DesignData.mobs().pool(phase, place).contains(type), type + " がフェーズ " + phase + " の " + place + " に湧く");
                }
            }
            helper.assertTrue(com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(type), type + " がレイド・汚染したスポナーからも外れている");
        }
        helper.succeed();
    }
}
