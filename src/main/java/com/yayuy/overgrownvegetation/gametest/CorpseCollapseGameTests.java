package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.effect.G1HostilityInjector;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * G1 段階 0(2026-11-08 の組み込み): 感染 → 崩れ落ち → 死体 → 起き上がり → 3 段の 1 撃と、周りの Mob の反応。
 * 見た目(崩れ落ち・突き破り・起き上がりのアニメ)はクライアントで確かめる。ここではサーバー側の時刻・位置・向き・同期の段階。
 * 種ごとのテストは {@link #perSpecies} が {@link G1Species} の全種ぶん作る(種を足せば増える)。
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
    private static final String BATCH = "g1_stage0";

    @GameTestGenerator
    public static Collection<TestFunction> perSpecies() {
        List<TestFunction> out = new ArrayList<>();
        for (G1Species s : G1Species.values()) {
            if (s.infectable()) {
                out.add(new TestFunction(BATCH, "g1_" + s.key() + "_spore_death_collapses_and_rises", OvergrownVegetation.MODID + ":" + TEMPLATE, 200, 0, true,
                        helper -> sporeDeathCollapsesIntoCorpseAndRises(helper, s)));
            } else {
                // 感染の道を作らない種(ウォーデン): 元の Mob は死体にならず、置いた死体からは起き上がる
                out.add(new TestFunction(BATCH, "g1_" + s.key() + "_not_infectable_but_rises", OvergrownVegetation.MODID + ":" + TEMPLATE, 120, 0, true,
                        helper -> notInfectableButRisesFromCorpse(helper, s)));
            }
        }
        // 1 段目(2026-11-12): 死ぬと death_burst の長さで破裂して消え、死体は残らない
        for (com.yayuy.overgrownvegetation.entity.g1.G1Developed d : com.yayuy.overgrownvegetation.entity.g1.G1Developed.values()) {
            out.add(new TestFunction(BATCH, "g1_developed_" + d.key() + "_bursts_and_vanishes", OvergrownVegetation.MODID + ":" + TEMPLATE, 80, 0, true,
                    helper -> developedBurstsAndVanishes(helper, d)));
        }
        return out;
    }

    /** 1 段目: 死んでから death_burst の長さ(+ 少し)で消え、その場に死体ブロックは無い。生きている間は殴りに行ける(3 段の 1 撃を持つ)。 */
    static void developedBurstsAndVanishes(GameTestHelper helper, com.yayuy.overgrownvegetation.entity.g1.G1Developed d) {
        com.yayuy.overgrownvegetation.entity.DevelopedParasitized mob = (com.yayuy.overgrownvegetation.entity.DevelopedParasitized)
                helper.spawn(com.yayuy.overgrownvegetation.registry.ModG1.developed(d).get(), new Vec3(3.5, 2.0, 3.5));
        helper.assertTrue(mob.attacks() && mob.getMaxHealth() == (float) d.health, d.key() + ": 体力か攻撃の有無が表と違う: " + mob.getMaxHealth());
        BlockPos cell = mob.blockPosition();
        mob.hurt(helper.getLevel().damageSources().generic(), 10000.0F);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + d.burst - 2, () -> helper.assertTrue(!mob.isRemoved(), d.key() + ": 破裂の前に消えた"));
        helper.runAtTickTime(t0 + d.burst + 3, () -> {
            helper.assertTrue(mob.isRemoved(), d.key() + ": death_burst の長さを過ぎても消えない");
            helper.assertTrue(!(helper.getLevel().getBlockEntity(cell) instanceof CorruptedCorpseBlockEntity), d.key() + ": 死体が残った");
            helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(cell).inflate(4.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** 元の Mob を湧かせる(子どもの種は子どもに)。 */
    private static LivingEntity spawnVictim(GameTestHelper helper, G1Species s, Vec3 at) {
        Mob victim = (Mob) helper.spawnWithNoFreeWill(vanillaType(s), at);
        victim.setNoAi(true);
        if (victim instanceof net.minecraft.world.entity.monster.Zombie z) {
            z.setBaby(s.key().startsWith("baby"));
        }
        return victim;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob> vanillaType(G1Species s) {
        return switch (s) {
            case ZOMBIE, BABY_ZOMBIE -> EntityType.ZOMBIE;
            case VILLAGER -> EntityType.VILLAGER;
            case COW -> EntityType.COW;
            case PIG -> EntityType.PIG;
            case SHEEP -> EntityType.SHEEP;
            case CHICKEN -> EntityType.CHICKEN;
            case CREEPER -> EntityType.CREEPER;
            case SKELETON -> EntityType.SKELETON;
            case SPIDER -> EntityType.SPIDER;
            case ENDERMAN -> EntityType.ENDERMAN;
            case IRON_GOLEM -> EntityType.IRON_GOLEM;
            case WARDEN -> EntityType.WARDEN;
        };
    }

    /**
     * 胞子感染(ダメージの種類 spore_infection)で死ぬと、19 tick で死んだマスに死体ブロック(向き・ずれを記録)、さらに 20 tick 後に Mob が消える。
     * 死体を起き上がらせると、死体のあった位置・向きで G1 が起き上がり(AI 停止)、起き上がりの長さが過ぎると動ける。
     */
    static void sporeDeathCollapsesIntoCorpseAndRises(GameTestHelper helper, G1Species s) {
        LivingEntity victim = spawnVictim(helper, s, new Vec3(3.3, 2.0, 3.6));
        victim.setYRot(90.0F);
        victim.yBodyRot = 90.0F;
        victim.yBodyRotO = 90.0F;
        Vec3 deathPos = victim.position();
        BlockPos cell = victim.blockPosition();
        victim.hurt(ModDamageTypes.sporeInfection(helper.getLevel()), 1000.0F);
        helper.assertTrue(victim.isDeadOrDying(), "胞子感染のダメージで死んでいない");
        long t0 = helper.getTick();

        helper.runAtTickTime(t0 + 10, () -> {
            helper.assertTrue(!victim.isRemoved(), "崩れ落ちの途中で Mob が消えた");
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(s.corpseBlock()), "崩れ落ちの途中なのに死体がもう置かれている");
        });
        helper.runAtTickTime(t0 + CorpseCollapse.COLLAPSE_TICKS + 3, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(s.corpseBlock()),
                    "崩れ落ちの終わりに死体が置かれていない: " + helper.getLevel().getBlockState(cell));
            CorruptedCorpseBlockEntity be = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(cell);
            helper.assertTrue(be != null && be.hasExactOffset(), "死体に位置のずれが記録されていない");
            helper.assertTrue(Math.abs(be.yaw() - 90.0F) < 0.5F, "死体の向きが死んだ Mob と違う: " + be.yaw());
            helper.assertTrue(be.placedGameTime() != Long.MIN_VALUE, "置かれた時刻(突き破りを流すかの判断)が無い");
            double x = cell.getX() + 0.5 + be.offsetX();
            double z = cell.getZ() + 0.5 + be.offsetZ();
            helper.assertTrue(Math.abs(x - deathPos.x) < 1.0E-3 && Math.abs(z - deathPos.z) < 1.0E-3,
                    "死体の位置が死んだ場所と違う: (" + x + ", " + z + ") / " + deathPos);
            be.rise(helper.getLevel(), cell);
        });
        long riseTick = t0 + CorpseCollapse.COLLAPSE_TICKS + 3;
        helper.runAtTickTime(t0 + CorpseCollapse.COLLAPSE_TICKS + CorpseCollapse.HANDOFF_TICKS + 3,
                () -> helper.assertTrue(victim.isRemoved(), "引き継ぎの後も崩れ落ちた Mob が残っている"));
        helper.runAtTickTime(riseTick + 2, () -> {
            List<AbstractParasitizedMonster> risen = risen(helper, cell, s);
            helper.assertTrue(risen.size() == 1, "G1 が 1 体起き上がっていない: " + risen.size());
            AbstractParasitizedMonster g1 = risen.get(0);
            helper.assertTrue(Math.abs(g1.getX() - deathPos.x) < 1.0E-3 && Math.abs(g1.getZ() - deathPos.z) < 1.0E-3,
                    "起き上がる位置が死体と違う: " + g1.position() + " / " + deathPos);
            helper.assertTrue(Math.abs(g1.getYRot() - 90.0F) < 0.5F && Math.abs(g1.yBodyRot - 90.0F) < 0.5F,
                    "起き上がる向きが死体と違う: " + g1.getYRot() + " / " + g1.yBodyRot);
            helper.assertTrue(g1.isRising() && g1.isNoAi(), "起き上がりの最中に AI が止まっていない");
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(s.corpseBlock()), "起き上がった後も死体が残っている");
        });
        helper.runAtTickTime(riseTick + s.riseTicks() + 4, () -> {
            List<AbstractParasitizedMonster> risen = risen(helper, cell, s);
            helper.assertTrue(!risen.isEmpty() && !risen.get(0).isRising() && !risen.get(0).isNoAi(), "起き上がりが終わっても動けない");
            risen.forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** 感染の道を作らない種: 胞子感染のダメージで死んでも死体にならない。置いた死体ブロックからは起き上がる。 */
    static void notInfectableButRisesFromCorpse(GameTestHelper helper, G1Species s) {
        LivingEntity victim = spawnVictim(helper, s, new Vec3(1.5, 2.0, 1.5));
        BlockPos vcell = victim.blockPosition();
        victim.hurt(ModDamageTypes.sporeInfection(helper.getLevel()), 10000.0F);
        BlockPos cell = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.getLevel().setBlock(cell, s.corpseBlock().defaultBlockState(), 3);
        ((CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(cell)).rise(helper.getLevel(), cell);
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 5, () -> {
            helper.assertTrue(!(helper.getLevel().getBlockEntity(vcell) instanceof CorruptedCorpseBlockEntity), s.key() + " が死体になった(感染の道は作らない)");
            List<AbstractParasitizedMonster> risen = risen(helper, cell, s);
            helper.assertTrue(risen.size() == 1, "置いた死体から " + s.key() + " が起き上がらない: " + risen.size());
            risen.forEach(e -> e.discard());
            helper.succeed();
        });
    }

    private static List<AbstractParasitizedMonster> risen(GameTestHelper helper, BlockPos cell, G1Species s) {
        return helper.getLevel().getEntitiesOfClass(AbstractParasitizedMonster.class, new AABB(cell).inflate(3.0),
                e -> e.species() == s);
    }

    /** ふつうの死(殴られて・落ちて)では死体にならない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void ordinaryDeathLeavesNoCorpse(GameTestHelper helper) {
        LivingEntity victim = spawnVictim(helper, G1Species.ZOMBIE, new Vec3(3.5, 2.0, 3.5));
        BlockPos cell = victim.blockPosition();
        victim.kill();
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 5, () -> {
            helper.assertTrue(!(helper.getLevel().getBlockEntity(cell) instanceof CorruptedCorpseBlockEntity), "ふつうの死なのに死体が置かれた");
            helper.succeed();
        });
    }

    /** 死体は時計({@link CorruptedCorpseBlockEntity#RISE_AFTER_TICKS})が来たら、ひとりでに起き上がる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void corpseRisesWhenClockRunsOut(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        BlockPos cell = helper.absolutePos(rel);
        helper.getLevel().setBlock(cell, G1Species.ZOMBIE.corpseBlock().defaultBlockState(), 3);
        CorruptedCorpseBlockEntity be = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(cell);
        helper.assertTrue(be != null, "死体のブロックエンティティが無い");
        be.setAgeForTests(CorruptedCorpseBlockEntity.RISE_AFTER_TICKS - 3);
        helper.runAtTickTime(helper.getTick() + 10, () -> {
            helper.assertTrue(!helper.getLevel().getBlockState(cell).is(G1Species.ZOMBIE.corpseBlock()), "時計が来ても死体のまま");
            List<AbstractParasitizedMonster> risen = risen(helper, cell, G1Species.ZOMBIE);
            helper.assertTrue(risen.size() == 1, "時計で G1 が起き上がっていない: " + risen.size());
            risen.forEach(e -> e.discard());
            helper.succeed();
        });
    }

    // 1 撃の段(溜め無し・当たる tick・間隔)は G1NoWindupGameTests(2026-11-19 に 3 段の 1 撃から作り直した)

    /** G1 は日に焼けない(段階 0 の共通: ゾンビ・スケルトンも燃えない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void g1DoesNotBurnInDaylight(GameTestHelper helper) {
        helper.getLevel().setDayTime(6000);
        AbstractParasitizedMonster g1 = (AbstractParasitizedMonster) helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
        g1.setNoAi(true);
        helper.runAtTickTime(helper.getTick() + 40, () -> {
            helper.assertTrue(!g1.isOnFire(), "日の下で燃えている");
            g1.discard();
            helper.succeed();
        });
    }

    /** 攻撃の力を持つ Mob には G1〜G8・環境生物を狙う Goal が足され、持たない Mob には足されない。鉄のゴーレムは G1 を狙う。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void otherMobsTurnOnG1(GameTestHelper helper) {
        Skeleton skeleton = helper.spawn(EntityType.SKELETON, new Vec3(1.5, 2.0, 1.5));
        Mob cow = helper.spawn(EntityType.COW, new Vec3(5.5, 2.0, 1.5));
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new Vec3(1.5, 2.0, 5.5));
        AbstractParasitizedMonster g1 = (AbstractParasitizedMonster) helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(4.5, 2.0, 4.5));
        g1.setNoAi(true);
        helper.assertTrue(G1HostilityInjector.hasInjected(skeleton), "スケルトンに G1 を狙う Goal が無い");
        helper.assertTrue(!G1HostilityInjector.hasInjected(cow), "牛(攻撃を持たない)に G1 を狙う Goal が足された");
        helper.succeedWhen(() -> {
            helper.assertTrue(golem.getTarget() == g1, "鉄のゴーレムが G1 を狙わない: " + golem.getTarget());
            skeleton.discard();
            cow.discard();
            golem.discard();
            g1.discard();
        });
    }

    /** 生きている村人は G1 を「近くの敵」に数える(逃げる)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void villagerSeesG1AsHostile(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new Vec3(1.5, 2.0, 3.5));
        AbstractParasitizedMonster g1 = (AbstractParasitizedMonster) helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(5.5, 2.0, 3.5));
        g1.setNoAi(true);
        helper.succeedWhen(() -> {
            helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE).filter(e -> e == g1).isPresent(),
                    "村人が G1 を近くの敵に数えない");
            villager.discard();
            g1.discard();
        });
    }

    /** 死体になる前の持ち物・色を、起き上がった G1 が引き継ぐ(スケルトンの弓・羊の毛の色)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void skeletonKeepsBowAndSheepKeepsColor(GameTestHelper helper) {
        Skeleton skeleton = helper.spawnWithNoFreeWill(EntityType.SKELETON, new Vec3(1.5, 2.0, 1.5));
        skeleton.setNoAi(true);
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
        net.minecraft.world.entity.animal.Sheep sheep = helper.spawnWithNoFreeWill(EntityType.SHEEP, new Vec3(5.5, 2.0, 5.5));
        sheep.setNoAi(true);
        sheep.setColor(net.minecraft.world.item.DyeColor.RED);
        BlockPos sc = skeleton.blockPosition();
        BlockPos hc = sheep.blockPosition();
        skeleton.hurt(ModDamageTypes.sporeInfection(helper.getLevel()), 1000.0F);
        sheep.hurt(ModDamageTypes.sporeInfection(helper.getLevel()), 1000.0F);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + CorpseCollapse.COLLAPSE_TICKS + 3, () -> {
            CorruptedCorpseBlockEntity sbe = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(sc);
            CorruptedCorpseBlockEntity hbe = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(hc);
            helper.assertTrue(sbe != null && sbe.heldItem().is(net.minecraft.world.item.Items.BOW), "スケルトンの死体が弓を覚えていない");
            helper.assertTrue(hbe != null && hbe.woolColor() == net.minecraft.world.item.DyeColor.RED.getId(), "羊の死体が毛の色を覚えていない");
            sbe.rise(helper.getLevel(), sc);
            hbe.rise(helper.getLevel(), hc);
        });
        helper.runAtTickTime(t0 + CorpseCollapse.COLLAPSE_TICKS + 6, () -> {
            List<AbstractParasitizedMonster> sk = risen(helper, sc, G1Species.SKELETON);
            List<AbstractParasitizedMonster> sh = risen(helper, hc, G1Species.SHEEP);
            helper.assertTrue(sk.size() == 1 && sk.get(0).getMainHandItem().is(net.minecraft.world.item.Items.BOW), "起き上がったスケルトンが弓を持っていない");
            helper.assertTrue(sh.size() == 1 && ((com.yayuy.overgrownvegetation.entity.ParasitizedSheep) sh.get(0)).getColor()
                    == net.minecraft.world.item.DyeColor.RED, "起き上がった羊の毛の色が違う");
            sk.forEach(e -> e.discard());
            sh.forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** 死体から起き上がった G1(牛)は、起き上がりが終わったら近くの生き物を襲う。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void risenG1HuntsNearbyMob(GameTestHelper helper) {
        BlockPos cell = helper.absolutePos(new BlockPos(2, 2, 3));
        helper.getLevel().setBlock(cell, G1Species.COW.corpseBlock().defaultBlockState(), 3);
        CorruptedCorpseBlockEntity be = (CorruptedCorpseBlockEntity) helper.getLevel().getBlockEntity(cell);
        be.rise(helper.getLevel(), cell);
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(5.5, 2.0, 3.5));
        pig.setNoAi(true);
        float hp0 = pig.getHealth();
        helper.succeedWhen(() -> {
            List<AbstractParasitizedMonster> cows = risen(helper, cell, G1Species.COW);
            helper.assertTrue(!cows.isEmpty(), "牛の G1 がいない");
            helper.assertTrue(pig.getHealth() < hp0, "起き上がった牛が豚を襲わない(標的 " + cows.get(0).getTarget() + "・段階 "
                    + cows.get(0).action() + "・起き上がり中 " + cows.get(0).isRising() + ")");
            cows.forEach(e -> e.discard());
            pig.discard();
        });
    }

    /**
     * 死体になる Mob は元のドロップを出さない。死体を壊すと G1 のドロップ(旧の共通素材の植物繊維は必ず 1 つ以上)が出て、起き上がらない(11-08 ユーザー決定)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void corpseDropsOnlyWhenBroken(GameTestHelper helper) {
        LivingEntity victim = spawnVictim(helper, G1Species.ZOMBIE, new Vec3(3.5, 2.0, 3.5));
        BlockPos cell = victim.blockPosition();
        victim.hurt(ModDamageTypes.sporeInfection(helper.getLevel()), 1000.0F);
        long t0 = helper.getTick();
        helper.runAtTickTime(t0 + CorpseCollapse.COLLAPSE_TICKS + 3, () -> {
            List<net.minecraft.world.entity.item.ItemEntity> items = helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, new AABB(cell).inflate(3.0));
            helper.assertTrue(items.isEmpty(), "死体になったゾンビが元のドロップを出した: " + items.size());
            net.minecraft.world.level.block.state.BlockState state = helper.getLevel().getBlockState(cell);
            net.minecraft.world.level.block.entity.BlockEntity be = helper.getLevel().getBlockEntity(cell);
            net.minecraft.world.entity.player.Player player = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.SURVIVAL);
            helper.getLevel().removeBlock(cell, false);
            state.getBlock().playerDestroy(helper.getLevel(), player, cell, state, be, net.minecraft.world.item.ItemStack.EMPTY);
            boolean fiber = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(cell).inflate(3.0))
                    .stream().anyMatch(i -> i.getItem().is(com.yayuy.overgrownvegetation.registry.ModItems.PLANT_FIBER.get()));
            helper.assertTrue(fiber, "死体を壊しても G1 のドロップ(植物繊維)が出ない");
        });
        helper.runAtTickTime(t0 + CorpseCollapse.COLLAPSE_TICKS + 20, () -> {
            helper.assertTrue(risen(helper, cell, G1Species.ZOMBIE).isEmpty(), "壊した死体から G1 が起き上がった");
            helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(cell).inflate(3.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** G1 は萌芽体(この MOD の Mob)を襲わない(共通の仕様「G1・萌芽体どうしは襲わない」)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void g1IgnoresBlightFern(GameTestHelper helper) {
        AbstractParasitizedMonster g1 = (AbstractParasitizedMonster) helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(2.5, 2.0, 3.5));
        Mob fern = helper.spawn(com.yayuy.overgrownvegetation.registry.ModEntities.BLIGHT_FERN.get(), new Vec3(4.5, 2.0, 3.5));
        fern.setNoAi(true);
        helper.runAtTickTime(helper.getTick() + 60, () -> {
            helper.assertTrue(g1.getTarget() != fern, "G1 が萌芽体を狙った");
            helper.assertTrue(!com.yayuy.overgrownvegetation.entity.g1.G1TargetGoal.isPrey(g1, fern), "萌芽体が獲物に数えられている");
            g1.discard();
            fern.discard();
            helper.succeed();
        });
    }

    /** 子どもの村人は死体を作らない(汚染シダになる。11-05 共通の決定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void babyVillagerLeavesNoCorpse(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(3.5, 2.0, 3.5));
        villager.setNoAi(true);
        villager.setBaby(true);
        BlockPos cell = villager.blockPosition();
        villager.hurt(ModDamageTypes.sporeInfection(helper.getLevel()), 1000.0F);
        helper.runAtTickTime(helper.getTick() + CorpseCollapse.COLLAPSE_TICKS + 5, () -> {
            helper.assertTrue(!(helper.getLevel().getBlockEntity(cell) instanceof CorruptedCorpseBlockEntity), "子どもの村人が死体になった");
            helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(cell).inflate(3.0)).forEach(e -> e.discard());
            helper.succeed();
        });
    }

    /** クリーパーは殴らない(振りに入らない)。数秒おきに袋が縮んで吹く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void creeperNeverStrikesButPuffs(GameTestHelper helper) {
        AbstractParasitizedMonster creeper = (AbstractParasitizedMonster) helper.spawn(G1Species.CREEPER.entityType(), new Vec3(2.5, 2.0, 3.5));
        Mob pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(3.6, 2.0, 3.5));
        pig.setNoAi(true);
        boolean[] puffed = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(creeper.action() != AbstractParasitizedMonster.ACTION_STRIKE, "クリーパーが殴った");
            if (creeper.action() == com.yayuy.overgrownvegetation.entity.ParasitizedCreeper.ACTION_PUFF) {
                puffed[0] = true;
            }
        });
        helper.runAtTickTime(helper.getTick() + 200, () -> {
            helper.assertTrue(puffed[0], "200 tick の間に一度も吹かない");
            helper.assertTrue(pig.getHealth() >= pig.getMaxHealth(), "クリーパーが豚を傷つけた");
            creeper.discard();
            pig.discard();
            helper.succeed();
        });
    }

    /** クモは毒が効かない。鶏は落ちても傷まない(元の性質のうち仕様で残す・外すもの)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void spiderIgnoresPoisonAndChickenIgnoresFalls(GameTestHelper helper) {
        AbstractParasitizedMonster spider = (AbstractParasitizedMonster) helper.spawn(G1Species.SPIDER.entityType(), new Vec3(1.5, 2.0, 1.5));
        spider.setNoAi(true);
        helper.assertTrue(!spider.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 200)),
                "クモに毒が効いた");
        AbstractParasitizedMonster chicken = (AbstractParasitizedMonster) helper.spawn(G1Species.CHICKEN.entityType(), new Vec3(5.5, 6.0, 5.5));
        chicken.setNoAi(true);
        helper.runAtTickTime(helper.getTick() + 40, () -> {
            helper.assertTrue(chicken.getHealth() >= chicken.getMaxHealth(), "鶏が落ちて傷ついた: " + chicken.getHealth());
            spider.discard();
            chicken.discard();
            helper.succeed();
        });
    }

    private CorpseCollapseGameTests() {
    }
}
