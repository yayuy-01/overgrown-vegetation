package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.GestatrixEntity;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.SpawnTable;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * ゲスタトリクス(G6・歩く苗床。2026-11-17)。期待値は design/spawns.json の {@code gestatrix} と director.json の苗床の値から。
 * 産む数・上限・前触れの取りやめ・根を下ろす・根付いた後も続けて孵す、を見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GestatrixGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GestatrixGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final Vec3 CENTER = new Vec3(3.5, 2.0, 3.5);

    private GestatrixGameTests() {
    }

    private static GestatrixEntity spawn(GameTestHelper helper) {
        GestatrixEntity g = helper.spawn(ModCrawlers.GESTATRIX.get(), CENTER);
        g.setNoAi(false);
        return g;
    }

    private static List<GregariaEntity> brood(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(GregariaEntity.class, helper.getBounds().inflate(8.0D));
    }

    /** 前触れの後に broodMin〜broodMax 匹を産み、嚢が 1 つ減る。産むのを重ねても、生きている手先は broodCap を超えない。 */
    @GameTest(template = TEMPLATE, batch = "gestatrix_birth", timeoutTicks = 400)
    public static void birthsBroodWithinCap(GameTestHelper helper) {
        SpawnTable.Gestatrix t = DesignData.spawns().gestatrix;
        ServerLevel level = helper.getLevel();
        GestatrixEntity g = spawn(helper);
        helper.runAfterDelay(2, () -> g.forceStateForTests(GestatrixEntity.WINDUP, 1));
        int firstAt = 2 + 1 + GestatrixEntity.BIRTH_AT + 3;
        helper.runAfterDelay(firstAt, () -> {
            int n = brood(helper).size();
            helper.assertTrue(n >= t.broodMin && n <= t.broodMax, "産んだ数が違う: " + n + "(" + t.broodMin + "〜" + t.broodMax + ")");
            helper.assertTrue(g.births() == 1 && g.sacs() == t.birthsToRoot - 1, "嚢が減らない: 産んだ回 " + g.births() + "・嚢 " + g.sacs());
            helper.assertTrue(g.broodAlive(level) == n, "手先を数えていない: " + g.broodAlive(level) + " / " + n);
            g.setBirthsForTests(-10); // 根を下ろさないように
        });
        // 上限まで重ねて産ませる
        int step = 1 + GestatrixEntity.BIRTH_T + 2;
        for (int i = 0; i < 5; i++) {
            helper.runAfterDelay(firstAt + 2 + i * step, () -> g.forceStateForTests(GestatrixEntity.WINDUP, 1));
        }
        helper.runAfterDelay(firstAt + 2 + 5 * step + 4, () -> {
            int n = brood(helper).size();
            helper.assertTrue(n <= t.broodCap && g.broodAlive(level) <= t.broodCap, "手先が上限を超えた: " + n + "(上限 " + t.broodCap + ")");
            helper.assertTrue(n == t.broodCap, "上限まで産んでいない: " + n);
            helper.assertTrue(g.broodRoom(level) == 0, "上限なのに余りがある: " + g.broodRoom(level));
            brood(helper).forEach(net.minecraft.world.entity.Entity::discard);
            helper.succeed();
        });
    }

    /** 前触れの間に背の嚢(高い所)へ矢が当たると、その回は産めない。低い所(脚)に当たっても止まらない。 */
    @GameTest(template = TEMPLATE, batch = "gestatrix_cancel", timeoutTicks = 200)
    public static void sacHitCancelsBirth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GestatrixEntity g = spawn(helper);
        helper.runAfterDelay(2, () -> {
            g.forceStateForTests(GestatrixEntity.WINDUP, GestatrixEntity.WINDUP_T);
            Arrow low = new Arrow(EntityType.ARROW, level);
            low.setPos(g.getX(), g.getY() + 0.05D, g.getZ());
            DamageSource lowHit = level.damageSources().arrow(low, null);
            helper.assertFalse(g.hitsSac(lowHit), "脚に当たった矢を嚢に数えた");
            g.hurt(lowHit, 1.0F);
            helper.assertTrue(g.state() == GestatrixEntity.WINDUP, "脚に当たっただけで取りやめた");
        });
        helper.runAfterDelay(14, () -> {
            Arrow high = new Arrow(EntityType.ARROW, level);
            high.setPos(g.getX(), g.getY() + g.getBbHeight() * 0.9D, g.getZ());
            DamageSource hit = level.damageSources().arrow(high, null);
            helper.assertTrue(g.hitsSac(hit), "背に当たった矢を嚢に数えない");
            g.hurt(hit, 1.0F);
            helper.assertTrue(g.state() == GestatrixEntity.CANCEL && g.cancels() == 1, "嚢に当たっても取りやめない: 状態 " + g.state());
        });
        helper.runAfterDelay(14 + GestatrixEntity.CANCEL_T + 4, () -> {
            helper.assertTrue(g.births() == 0 && brood(helper).isEmpty(), "取りやめたのに産んだ: " + g.births());
            helper.assertTrue(g.state() == GestatrixEntity.REST, "取りやめの後に休まない: " + g.state());
            helper.succeed();
        });
    }

    /**
     * birthsToRoot 回産むと根を下ろして苗床になる(縁の印が外れる)。根付いた後は、司令塔が払うたびに続けて孵し、
     * nurseryLifeHatches 回孵したら枯れる。ここでは G4 以降の代わりに豚を出す。
     */
    @GameTest(template = TEMPLATE, batch = "gestatrix_root", timeoutTicks = 600)
    public static void rootsThenKeepsHatching(GameTestHelper helper) {
        SpawnTable.Gestatrix t = DesignData.spawns().gestatrix;
        DirectorTable dt = DesignData.director();
        ServerLevel level = helper.getLevel();
        ForestDirector director = ForestDirector.get(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        double budgetBefore = director.available(level);
        DirectorGates.setPhaseForTests(5);
        DirectorGates.setStageForTests(PhaseStage.PEAK);
        GestatrixEntity g = spawn(helper);
        g.addTag(EnemySpawns.EDGE_TAG);
        List<Supplier<? extends EntityType<? extends Mob>>> pool = List.of(() -> EntityType.PIG);
        g.setSpawnPoolForTests(pool);
        helper.runAfterDelay(2, () -> {
            g.setBirthsForTests(t.birthsToRoot - 1);
            g.forceStateForTests(GestatrixEntity.WINDUP, 1);
        });
        int rootedAt = 2 + 1 + GestatrixEntity.BIRTH_T + GestatrixEntity.ROOT_T + 4;
        helper.runAfterDelay(rootedAt, () -> {
            helper.assertTrue(g.rooted() && g.births() == t.birthsToRoot && g.sacs() == 0, "決めた回数産んでも根を下ろさない: 状態 " + g.state() + "・産んだ回 " + g.births());
            helper.assertFalse(g.getTags().contains(EnemySpawns.EDGE_TAG), "根付いたのに縁の印が残っている(縁の上限に数え、遠くで片付けてしまう)");
            brood(helper).forEach(net.minecraft.world.entity.Entity::discard);
            director.setBudget(level, ForestDirector.cap(level));
            g.setRootedTimersForTests(99999, 1);
        });
        int hatchedAt = rootedAt + GestatrixEntity.HATCH_T + 4;
        helper.runAfterDelay(hatchedAt, () -> {
            List<Pig> pigs = level.getEntitiesOfClass(Pig.class, helper.getBounds().inflate(4.0D));
            helper.assertTrue(!pigs.isEmpty() && g.hatches() == 1 && g.isAlive(), "根付いた後に孵さない・1 回で枯れた: 豚 " + pigs.size() + "・孵した回 " + g.hatches());
            pigs.forEach(net.minecraft.world.entity.Entity::discard);
        });
        // 残りの回数を孵させる
        for (int i = 1; i < dt.nurseryLifeHatches; i++) {
            int at = hatchedAt + 2 + (i - 1) * (GestatrixEntity.HATCH_T + 4);
            helper.runAfterDelay(at, () -> {
                director.setBudget(level, ForestDirector.cap(level));
                g.setRootedTimersForTests(99999, 1);
            });
        }
        helper.runAfterDelay(hatchedAt + 2 + (dt.nurseryLifeHatches - 1) * (GestatrixEntity.HATCH_T + 4) + 6, () -> {
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            director.setBudget(level, budgetBefore);
            level.getEntitiesOfClass(Pig.class, helper.getBounds().inflate(4.0D)).forEach(net.minecraft.world.entity.Entity::discard);
            helper.assertTrue(!g.isAlive() && g.spent(), "決めた回数孵しても枯れない: " + g.hatches() + " / " + dt.nurseryLifeHatches);
            helper.succeed();
        });
    }

    /** 縁の湧き: G6 の送り込みのゲートは F6 でも、ゲスタトリクスは種の open で F4 から湧き、上限は edge.caps の 6 × 段の強さ。 */
    @GameTest(template = TEMPLATE, batch = "gestatrix_open", timeoutTicks = 20)
    public static void opensAtF4OnTheEdge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        try {
            DirectorGates.setPhaseForTests(3);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            helper.assertTrue(EnemySpawns.edgeCap(level, 6) == 0, "F3 で G6 の枠が開いている: " + EnemySpawns.edgeCap(level, 6));
            DirectorGates.setPhaseForTests(4);
            int cap = EnemySpawns.edgeCap(level, 6);
            helper.assertTrue(cap >= 1 && cap <= DesignData.spawns().cap(6), "F4 で G6 の枠が開かない: " + cap);
            int[] counts = new int[11];
            boolean picked = false;
            net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(9L);
            for (int i = 0; i < 400 && !picked; i++) {
                SpawnTable.Species sp = EnemySpawns.pick(level, counts, rnd, false);
                if (sp != null && sp.type() == ModCrawlers.GESTATRIX.get()) picked = true;
            }
            helper.assertTrue(picked, "F4 でゲスタトリクスが選ばれない");
            counts[6] = cap;
            for (int i = 0; i < 400; i++) {
                SpawnTable.Species sp = EnemySpawns.pick(level, counts, rnd, false);
                helper.assertTrue(sp == null || sp.type() != ModCrawlers.GESTATRIX.get(), "上限なのにゲスタトリクスを選んだ");
            }
            helper.succeed();
        } finally {
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
        }
    }
}
