package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import com.yayuy.overgrownvegetation.corpse.RisingMob;
import com.yayuy.overgrownvegetation.entity.ParasitizedVillager;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 胞子感染で死んだ Mob が死体の姿勢へ崩れ落ち、死体ブロックになり、そこから起き上がる流れ(2026-10-09、G1 の作り直し)。
 * 見た目(バニラの描画の崩れ落ち・死体と起き上がりの継ぎ目)はクライアントで確かめる。ここではサーバー側の時刻・位置・向き。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CorpseCollapseGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CorpseCollapseGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "corpse_collapse";

    /**
     * 村人: 死んで 19 tick で死体ブロックが死んだマスに置かれ(向き・ずれを記録)、さらに 20 tick 後に Mob が消える。
     * 死体を孵すと、寄生された村人が死体のあった位置・向きで起き上がり(AI 停止)、起き上がりが終わると動ける。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void villagerCollapsesIntoCorpseAndRisesThere(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(3.3, 2.0, 3.6));
        villager.setNoAi(true);
        villager.setYRot(90.0F);
        villager.yBodyRot = 90.0F;
        villager.yBodyRotO = 90.0F;
        villager.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, 600, 0));
        Vec3 deathPos = villager.position();
        BlockPos cell = villager.blockPosition();
        villager.kill();

        helper.runAtTickTime(helper.getTick() + 10, () -> {
            helper.assertTrue(!villager.isRemoved(), "崩れ落ちの途中で Mob が消えた");
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(ModBlocks.OVERGROWN_VILLAGER_CORPSE.get()),
                    "崩れ落ちの途中なのに死体がもう置かれている");
        });
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 3, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(ModBlocks.OVERGROWN_VILLAGER_CORPSE.get()),
                    "崩れ落ちの終わりに死体が置かれていない: " + helper.getLevel().getBlockState(cell));
            CorruptedCorpseBlockEntity be = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(cell);
            helper.assertTrue(be != null && be.hasExactOffset(), "死体に位置のずれが記録されていない");
            helper.assertTrue(Math.abs(be.yaw() - 90.0F) < 0.5F, "死体の向きが死んだ Mob と違う: " + be.yaw());
            double x = cell.getX() + 0.5 + be.offsetX();
            double z = cell.getZ() + 0.5 + be.offsetZ();
            helper.assertTrue(Math.abs(x - deathPos.x) < 1.0E-3 && Math.abs(z - deathPos.z) < 1.0E-3,
                    "死体の位置が死んだ場所と違う: (" + x + ", " + z + ") / " + deathPos);
            // テスト場は狭く死体の侵食がすぐ終わって自然に孵ることがあるので、置かれたらすぐ孵す
            be.forceHatch(helper.getLevel());
        });
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + CorpseCollapse.HANDOFF_TICKS + 3, () -> {
            helper.assertTrue(villager.isRemoved(), "引き継ぎの後も崩れ落ちた Mob が残っている");
        });
        long hatchTick = helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 3;
        helper.runAtTickTime(hatchTick + 2, () -> {
            List<ParasitizedVillager> risen = helper.getLevel().getEntitiesOfClass(ParasitizedVillager.class,
                    new AABB(cell).inflate(2.0));
            helper.assertTrue(risen.size() == 1, "寄生された村人が 1 体孵っていない: " + risen.size());
            ParasitizedVillager pv = risen.get(0);
            helper.assertTrue(Math.abs(pv.getX() - deathPos.x) < 1.0E-3 && Math.abs(pv.getZ() - deathPos.z) < 1.0E-3,
                    "起き上がる位置が死体と違う: " + pv.position() + " / " + deathPos);
            helper.assertTrue(Math.abs(pv.getYRot() - 90.0F) < 0.5F && Math.abs(pv.yBodyRot - 90.0F) < 0.5F,
                    "起き上がる向きが死体と違う: " + pv.getYRot() + " / " + pv.yBodyRot);
            helper.assertTrue(pv.isRising() && pv.isNoAi(), "起き上がりの最中に AI が止まっていない");
        });
        helper.runAtTickTime(hatchTick + RisingMob.RISE_TICKS + 4, () -> {
            List<ParasitizedVillager> risen = helper.getLevel().getEntitiesOfClass(ParasitizedVillager.class,
                    new AABB(cell).inflate(3.0));
            helper.assertTrue(!risen.isEmpty() && !risen.get(0).isRising() && !risen.get(0).isNoAi(),
                    "起き上がりが終わっても動けない");
            risen.forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** 子どもの村人は死体を残さない(汚染シダ。2026-10-09 ユーザー指定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void babyVillagerLeavesNoCorpse(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(3.5, 2.0, 3.5));
        villager.setNoAi(true);
        villager.setBaby(true);
        villager.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, 600, 0));
        BlockPos cell = villager.blockPosition();
        villager.kill();
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 5, () -> {
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(ModBlocks.OVERGROWN_VILLAGER_CORPSE.get()),
                    "子どもの村人が死体になった");
            helper.succeed();
        });
    }

    // ---- 残りの 10 種(2026-10-09 の 11 種そろえ。ゾンビは即変身・スケルトンは骨粉をやめて死体にそろえた)
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void zombieCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.ZOMBIE, false, ModBlocks.OVERGROWN_ZOMBIE_CORPSE.get(), ModEntities.PARASITIZED_ZOMBIE.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void babyZombieCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.ZOMBIE, true, ModBlocks.OVERGROWN_BABY_ZOMBIE_CORPSE.get(), ModEntities.PARASITIZED_BABY_ZOMBIE.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void skeletonCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.SKELETON, false, ModBlocks.OVERGROWN_SKELETON_CORPSE.get(), ModEntities.PARASITIZED_SKELETON.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void endermanCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.ENDERMAN, false, ModBlocks.OVERGROWN_ENDERMAN_CORPSE.get(), ModEntities.PARASITIZED_ENDERMAN.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void cowCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.COW, false, ModBlocks.OVERGROWN_COW_CORPSE.get(), ModEntities.PARASITIZED_COW.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void pigCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.PIG, false, ModBlocks.OVERGROWN_PIG_CORPSE.get(), ModEntities.PARASITIZED_PIG.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void sheepCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.SHEEP, false, ModBlocks.OVERGROWN_SHEEP_CORPSE.get(), ModEntities.PARASITIZED_SHEEP.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void chickenCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.CHICKEN, false, ModBlocks.OVERGROWN_CHICKEN_CORPSE.get(), ModEntities.PARASITIZED_CHICKEN.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void creeperCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.CREEPER, false, ModBlocks.OVERGROWN_CREEPER_CORPSE.get(), ModEntities.PARASITIZED_CREEPER.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void spiderCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.SPIDER, false, ModBlocks.OVERGROWN_SPIDER_CORPSE.get(), ModEntities.PARASITIZED_SPIDER.get());
    }

    // ---- ネザーの媒介種の地上の 7 種(2026-10-09)。ガスト・ブレイズは死体を作らない(NetherVectorGameTests)
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void piglinCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.PIGLIN, false, ModBlocks.OVERGROWN_PIGLIN_CORPSE.get(), ModEntities.PARASITIZED_PIGLIN.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void piglinBruteCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.PIGLIN_BRUTE, false, ModBlocks.OVERGROWN_PIGLIN_BRUTE_CORPSE.get(), ModEntities.PARASITIZED_PIGLIN_BRUTE.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void zombifiedPiglinCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.ZOMBIFIED_PIGLIN, false, ModBlocks.OVERGROWN_ZOMBIFIED_PIGLIN_CORPSE.get(), ModEntities.PARASITIZED_ZOMBIFIED_PIGLIN.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void witherSkeletonCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.WITHER_SKELETON, false, ModBlocks.OVERGROWN_WITHER_SKELETON_CORPSE.get(), ModEntities.PARASITIZED_WITHER_SKELETON.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void hoglinCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.HOGLIN, false, ModBlocks.OVERGROWN_HOGLIN_CORPSE.get(), ModEntities.PARASITIZED_HOGLIN.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void zoglinCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.ZOGLIN, false, ModBlocks.OVERGROWN_ZOGLIN_CORPSE.get(), ModEntities.PARASITIZED_ZOGLIN.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void striderCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.STRIDER, false, ModBlocks.OVERGROWN_STRIDER_CORPSE.get(), ModEntities.PARASITIZED_STRIDER.get());
    }

    // 2026-10-10: アイアンゴーレム・ウォーデン(寄生体は自然には湧かず、この死体からだけ現れる)
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void ironGolemCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.IRON_GOLEM, false, ModBlocks.OVERGROWN_IRON_GOLEM_CORPSE.get(), ModEntities.PARASITIZED_IRON_GOLEM.get());
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void wardenCollapsesAndRises(GameTestHelper helper) {
        collapseAndRise(helper, EntityType.WARDEN, false, ModBlocks.OVERGROWN_WARDEN_CORPSE.get(), ModEntities.PARASITIZED_WARDEN.get());
    }

    /** 寄生されたアイアンゴーレム・ウォーデンは自然には湧かない(ユーザー指定 10-10): 汚染したスポナー・レイドなどが選ぶ G1 の一覧にも入らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void golemAndWardenNeverSpawnNaturally(GameTestHelper helper) {
        for (EntityType<?> type : List.of(ModEntities.PARASITIZED_IRON_GOLEM.get(), ModEntities.PARASITIZED_WARDEN.get())) {
            helper.assertTrue(type.is(com.yayuy.overgrownvegetation.ModTags.GROUP_1), type + " が G1 のタグに無い");
            helper.assertTrue(!com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(type), type + " が自然に湧く設定になっている");
            helper.assertTrue(!com.yayuy.overgrownvegetation.corruption.SpawnerCorruption.g1For(helper.getLevel()).contains(type),
                    type + " が汚染したスポナーの G1 の一覧に入っている");
        }
        helper.succeed();
    }

    /** ネザーの子ども(子どものピグリン)も死体を残さない(汚染シダ。子どもの決まり、2026-10-09)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void babyPiglinLeavesNoCorpse(GameTestHelper helper) {
        Mob piglin = helper.spawnWithNoFreeWill(EntityType.PIGLIN, new Vec3(3.5, 2.0, 3.5));
        piglin.setNoAi(true);
        piglin.setBaby(true);
        piglin.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, 600, 0));
        BlockPos cell = piglin.blockPosition();
        piglin.kill();
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 5, () -> {
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(ModBlocks.OVERGROWN_PIGLIN_CORPSE.get()),
                    "子どものピグリンが死体になった");
            helper.succeed();
        });
    }

    /**
     * 1 種ぶん: 胞子感染で死ぬ → 崩れ落ちの途中は Mob のまま(即変身しない・死体もまだ無い)→ 終わりに対応する死体が死んだマスに置かれる
     * → 孵すと対応する感染体が死んだ位置・向きで起き上がる(AI 停止)。
     */
    private static void collapseAndRise(GameTestHelper helper, EntityType<? extends Mob> type, boolean baby,
                                        Block corpse, EntityType<?> hatched) {
        Mob mob = helper.spawnWithNoFreeWill(type, new Vec3(3.5, 2.0, 3.5));
        mob.setNoAi(true);
        if (baby) {
            mob.setBaby(true);
        }
        mob.setYRot(90.0F);
        mob.yBodyRot = 90.0F;
        mob.yBodyRotO = 90.0F;
        mob.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, 600, 0));
        Vec3 deathPos = mob.position();
        BlockPos cell = mob.blockPosition();
        AABB around = new AABB(cell).inflate(2.0);
        mob.kill();

        helper.runAtTickTime(helper.getTick() + 10, () -> {
            helper.assertTrue(!mob.isRemoved(), "崩れ落ちの途中で Mob が消えた");
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(corpse), "崩れ落ちの途中なのに死体がもう置かれている");
            helper.assertTrue(helper.getLevel().getEntities(hatched, around, e -> true).isEmpty(), "死体を経由せずに感染体になった");
        });
        long hatchTick = helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 3;
        helper.runAtTickTime(hatchTick, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(corpse),
                    "崩れ落ちの終わりに " + corpse + " が置かれていない: " + helper.getLevel().getBlockState(cell));
            CorruptedCorpseBlockEntity be = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(cell);
            helper.assertTrue(be != null && be.hasExactOffset() && Math.abs(be.yaw() - 90.0F) < 0.5F, "死体に向き・ずれが記録されていない");
            be.forceHatch(helper.getLevel());
        });
        helper.runAtTickTime(hatchTick + 2, () -> {
            List<? extends Entity> risen = helper.getLevel().getEntities(hatched, around, e -> true);
            helper.assertTrue(risen.size() == 1, hatched + " が 1 体孵っていない: " + risen.size());
            Entity e = risen.get(0);
            helper.assertTrue(Math.abs(e.getX() - deathPos.x) < 1.0E-3 && Math.abs(e.getZ() - deathPos.z) < 1.0E-3,
                    "起き上がる位置が死体と違う: " + e.position() + " / " + deathPos);
            helper.assertTrue(Math.abs(e.getYRot() - 90.0F) < 0.5F, "起き上がる向きが死体と違う: " + e.getYRot());
            helper.assertTrue(e instanceof RisingMob r && r.isRising() && ((Mob) e).isNoAi(), "起き上がりの最中に AI が止まっていない");
            risen.forEach(Entity::discard);
            helper.succeed();
        });
    }

    private CorpseCollapseGameTests() {
    }
}
