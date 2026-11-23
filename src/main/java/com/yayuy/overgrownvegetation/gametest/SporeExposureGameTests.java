package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 胞子の暴露量(2026-11-04 感染システムの第 1 段)。場の値に頼らず、濃度を渡して積む口({@link SporeExposureTracker#tickWith}・
 * {@link SporeExposureTracker#accumulate})を直に叩く。期待値は設計値(spore_field.json の exposure)から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeExposureGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterSporeExposureGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(SporeExposureGameTests.class);
        }

        private Registration() {
        }
    }

    private SporeExposureGameTests() {
    }

    private static final String BATCH = "spore_exposure";

    private static Cow cow(GameTestHelper helper, int x, int z) {
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(x, 2, z));
        c.setInvulnerable(true);
        return c;
    }

    /** 濃度 × 秒が積まれる(50% に 1 秒 = 50 %秒)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void exposureAccumulates(GameTestHelper helper) {
        Cow c = cow(helper, 3, 3);
        long now = helper.getLevel().getGameTime();
        SporeExposureTracker.accumulate(c, 50.0D, 1.0D, 20, now);
        SporeExposureTracker.accumulate(c, 50.0D, 1.0D, 20, now);
        double d = SporeExposureTracker.dose(c);
        if (Math.abs(d - 100.0D) > 1e-6) {
            helper.fail("50% に 2 秒で 100 %秒のはず: " + d);
            return;
        }
        helper.succeed();
    }

    /** 下限(5%)未満は積まない。添付も作らない。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void belowMinPercentDoesNotAccumulate(GameTestHelper helper) {
        SporeFieldTable t = DesignData.sporeField();
        Cow c = cow(helper, 3, 3);
        SporeExposureTracker.accumulate(c, t.exposureMinPercent() - 0.1D, 1.0D, 200, helper.getLevel().getGameTime());
        if (OvAttach.hasData(c, ModAttachments.SPORE_EXPOSURE) || SporeExposureTracker.dose(c) != 0.0D) {
            helper.fail("下限未満で積まれた: " + SporeExposureTracker.dose(c));
            return;
        }
        SporeExposureTracker.accumulate(c, t.exposureMinPercent(), 1.0D, 20, helper.getLevel().getGameTime());
        if (Math.abs(SporeExposureTracker.dose(c) - t.exposureMinPercent()) > 1e-6) {
            helper.fail("下限ちょうどで積まれない: " + SporeExposureTracker.dose(c));
            return;
        }
        helper.succeed();
    }

    /** 濃い所を出ると半減期(1 分)で半分。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void exposureHalvesInOneMinuteOutside(GameTestHelper helper) {
        SporeFieldTable t = DesignData.sporeField();
        Cow c = cow(helper, 3, 3);
        long now = helper.getLevel().getGameTime();
        SporeExposureTracker.setDose(c, 1000.0D, now);
        int ticks = (int) Math.round(t.exposureHalfLifeSeconds() * 20.0D);
        // 1 分を判定の間隔(Mob 40 tick)で刻んでも 1 回でも同じになる
        for (int i = 0; i < ticks / 40; i++) {
            SporeExposureTracker.accumulate(c, 0.0D, 1.0D, 40, now);
        }
        double d = SporeExposureTracker.dose(c);
        if (Math.abs(d - 500.0D) > 1.0D) {
            helper.fail("1 分で半分(500)のはず: " + d);
            return;
        }
        helper.succeed();
    }

    /** 屋根の下の Mob は積まない(繁茂・過繁茂のバイオームでなければ)。空の下なら積む。プレイヤーは屋根の判定を使わない(仮決め)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void roofedMobDoesNotAccumulate(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos roofed = helper.absolutePos(new BlockPos(1, 2, 1));
        if (ModBiomes.isOvergrownLike(level.getBiome(roofed))) {
            helper.fail("テスト場が繁茂・過繁茂のバイオーム(前提が崩れた)");
            return;
        }
        helper.setBlock(new BlockPos(1, 5, 1), Blocks.STONE);
        Cow under = cow(helper, 1, 1);
        Cow open = cow(helper, 5, 5);
        if (SporeExposureTracker.underSky(level, under) || !SporeExposureTracker.underSky(level, open)) {
            helper.fail("屋根の判定が逆: 屋根の下 " + SporeExposureTracker.underSky(level, under) + "・空の下 " + SporeExposureTracker.underSky(level, open));
            return;
        }
        SporeExposureTracker.tickWith(level, under, 20, 100.0D);
        SporeExposureTracker.tickWith(level, open, 20, 100.0D);
        if (OvAttach.hasData(under, ModAttachments.SPORE_EXPOSURE) || SporeExposureTracker.dose(under) != 0.0D) {
            helper.fail("屋根の下の Mob が積んだ: " + SporeExposureTracker.dose(under));
            return;
        }
        if (Math.abs(SporeExposureTracker.dose(open) - 100.0D) > 1e-6) {
            helper.fail("空の下の Mob が積まない: " + SporeExposureTracker.dose(open));
            return;
        }
        helper.setBlock(new BlockPos(3, 5, 3), Blocks.STONE);
        Player p = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        p.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5D, 2.0D, 3.5D)));
        SporeExposureTracker.tickWith(level, p, 20, 100.0D);
        if (Math.abs(SporeExposureTracker.dose(p) - 100.0D) > 1e-6) {
            helper.fail("プレイヤーは屋根の下でも渡した濃度で積むはず: " + SporeExposureTracker.dose(p));
            return;
        }
        helper.succeed();
    }

    /** Mob の 1 回ぶん(100% に 感染の量/100 + 5 秒 = 届いて余る)。 */
    private static int overDoseTicks() {
        return (int) Math.ceil((DesignData.sporeField().infectDose() / 100.0D + 5.0D) * 20.0D);
    }

    /**
     * Mob が 1500 %秒に届くと、防御(防具・耐性の効果)を無視して 20 ダメージ → 暴露量は 0 に戻り、また積む(11-04 ユーザー決定)。
     * 体力 21 以上は生き残る。アイアンゴーレム(100)に鉄の防具と耐性 V を付けて確かめる。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 60)
    public static void mobAtDoseTakesTwentyAndResets(GameTestHelper helper) {
        SporeFieldTable t = DesignData.sporeField();
        var level = helper.getLevel();
        IronGolem g = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(3, 2, 3));
        g.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        g.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 2000, 4));
        float before = g.getHealth();
        SporeExposureTracker.tickWith(level, g, overDoseTicks(), 100.0D);
        SporeExposure ex = OvAttach.getData(g, ModAttachments.SPORE_EXPOSURE);
        if (Math.abs(g.getHealth() - (before - (float) t.mobExposureDamage())) > 1.0E-3F) {
            helper.fail("防御を無視した " + t.mobExposureDamage() + " ダメージにならない: " + before + " → " + g.getHealth());
            return;
        }
        if (ex.dose != 0.0D || ex.reachedCount != 1 || ex.lastReachedGameTime < 0) {
            helper.fail("20 ダメージの後の暴露量 " + ex.dose + "・回数 " + ex.reachedCount + "(期待 0・1)");
            return;
        }
        // 胞子の中に居続ければまた 20(無敵時間も貫く)
        SporeExposureTracker.tickWith(level, g, overDoseTicks(), 100.0D);
        if (Math.abs(g.getHealth() - (before - 2.0F * (float) t.mobExposureDamage())) > 1.0E-3F || ex.reachedCount != 2) {
            helper.fail("2 回目: 体力 " + g.getHealth() + "・回数 " + ex.reachedCount);
            return;
        }
        // 11-09: 1 回ごとに最大体力も 20 下がる(100 → 60)。保存して読み直しても下がったまま
        double expectMax = 100.0D - 2.0D * t.mobMaxHealthLossPerHit();
        if (Math.abs(g.getMaxHealth() - expectMax) > 1.0E-3D) {
            helper.fail("2 回で最大体力が " + expectMax + " にならない: " + g.getMaxHealth());
            return;
        }
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        g.saveWithoutId(saved);
        IronGolem copy = EntityType.IRON_GOLEM.create(level);
        copy.load(saved);
        if (Math.abs(copy.getMaxHealth() - expectMax) > 1.0E-3D) {
            helper.fail("読み直すと最大体力が戻った: " + copy.getMaxHealth());
            return;
        }
        helper.succeed();
    }

    /** 体力 21 の Mob は 1 回では死なず 1 残る。体力 20 以下(牛 10)は 1 回で死ぬ(想定内、11-04 ユーザー)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 60)
    public static void toughMobSurvivesWeakMobDies(GameTestHelper helper) {
        var level = helper.getLevel();
        IronGolem g = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(2, 2, 2));
        g.getAttribute(Attributes.MAX_HEALTH).setBaseValue(21.0D);
        g.setHealth(21.0F);
        Cow c = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 5));
        SporeExposureTracker.tickWith(level, g, overDoseTicks(), 100.0D);
        SporeExposureTracker.tickWith(level, c, overDoseTicks(), 100.0D);
        if (!g.isAlive() || Math.abs(g.getHealth() - 1.0F) > 1.0E-3F) {
            helper.fail("体力 21 の Mob が生き残らない: 生存 " + g.isAlive() + "・体力 " + g.getHealth());
            return;
        }
        if (c.isAlive() && c.getHealth() > 0.0F) {
            helper.fail("体力 10 の牛が 1 回で死なない: 体力 " + c.getHealth());
            return;
        }
        if (EntityType.ENDER_DRAGON.is(com.yayuy.overgrownvegetation.ModTags.SPORE_EXPOSURE_IMMUNE)
                || EntityType.WITHER.is(com.yayuy.overgrownvegetation.ModTags.SPORE_EXPOSURE_IMMUNE)) {
            helper.fail("ボス(ドラゴン・ウィザー)が測らない Mob のタグに入っている(11-09 ユーザー決定でボスも測る)");
            return;
        }
        helper.succeed();
    }

    /** 無敵の Mob はダメージが通らず、暴露量は頭打ちのまま(次の判定でまた試す)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 60)
    public static void invulnerableMobKeepsDose(GameTestHelper helper) {
        SporeFieldTable t = DesignData.sporeField();
        Spider z = helper.spawnWithNoFreeWill(EntityType.SPIDER, new BlockPos(3, 2, 3));
        z.setInvulnerable(true);
        float health = z.getHealth();
        SporeExposureTracker.tickWith(helper.getLevel(), z, overDoseTicks(), 100.0D);
        SporeExposure ex = OvAttach.getData(z, ModAttachments.SPORE_EXPOSURE);
        if (ex.dose != t.infectDose() || ex.reachedCount != 0 || z.getHealth() != health || z.getMaxHealth() != z.getAttribute(Attributes.MAX_HEALTH).getBaseValue()) {
            helper.fail("無敵の Mob: 暴露 " + ex.dose + "・回数 " + ex.reachedCount + "・体力 " + z.getHealth());
            return;
        }
        helper.succeed();
    }

    /**
     * 測る Mob は植物側以外のすべて(11-09 ユーザー決定)。バニラ・ボスは測り、植物側(G1 の寄生体・萌芽体・アルボラ・ファルキムネス・ハイヴノード)は
     * 測らない。他 mod の Mob はテスト環境に無いが、{@link SporeExposureTracker#isMeasuredMob} は id の名前空間を見ない
     * (Mob であること・植物側でないこと・データパック用のタグだけ)。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH)
    public static void measuresEveryoneButPlantSide(GameTestHelper helper) {
        var level = helper.getLevel();
        EntityType<?>[] measured = {EntityType.WOLF, EntityType.HORSE, EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.PILLAGER};
        for (EntityType<?> t : measured) {
            if (!(t.create(level) instanceof net.minecraft.world.entity.LivingEntity e) || !SporeExposureTracker.isMeasuredMob(e)) {
                helper.fail("測るはずの Mob を測らない: " + EntityType.getKey(t));
                return;
            }
        }
        java.util.List<EntityType<?>> plant = new java.util.ArrayList<>(java.util.List.of(
                com.yayuy.overgrownvegetation.registry.ModEntities.BLIGHT_FERN.get(),
                com.yayuy.overgrownvegetation.registry.ModEntities.HIVE_NODE.get(),
                com.yayuy.overgrownvegetation.registry.ModEntities.ARBORA.get(),
                com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get()));
        for (com.yayuy.overgrownvegetation.corpse.G1Species s : com.yayuy.overgrownvegetation.corpse.G1Species.values()) {
            plant.add(s.entityType());
        }
        for (EntityType<?> t : plant) {
            if (!(t.create(level) instanceof net.minecraft.world.entity.LivingEntity e)) {
                helper.fail("植物側の Mob を作れない: " + EntityType.getKey(t));
                return;
            }
            if (SporeExposureTracker.isMeasuredMob(e) || !SporeExposureTracker.isPlantSide(e)) {
                helper.fail("植物側の Mob を測っている: " + EntityType.getKey(t));
                return;
            }
        }
        helper.succeed();
    }

    /**
     * G1 の表に無い種(オオカミ・ウマ)は、感染の死で死なずに汎用の感染体に変わり、萌芽体は生えない
     * (2026-11-15 ユーザー決定「萌芽体が湧くのをなくして感染体になる」。11-09 の「萌芽体が生える」を置き換えた)。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_sprout_wolf", timeoutTicks = 40)
    public static void unlistedWolfTurnsOnInfectionDeath(GameTestHelper helper) {
        turnsOnInfectionDeath(helper, EntityType.WOLF);
    }

    @GameTest(template = "gametest_empty7", batch = "spore_sprout_horse", timeoutTicks = 40)
    public static void unlistedHorseTurnsOnInfectionDeath(GameTestHelper helper) {
        turnsOnInfectionDeath(helper, EntityType.HORSE);
    }

    private static void turnsOnInfectionDeath(GameTestHelper helper, EntityType<? extends net.minecraft.world.entity.Mob> type) {
        var level = helper.getLevel();
        net.minecraft.world.entity.Mob m = helper.spawnWithNoFreeWill(type, new BlockPos(3, 2, 3));
        net.minecraft.world.phys.AABB around = m.getBoundingBox().inflate(40.0D);
        level.getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.BlightFernEntity.class, around).forEach(e -> e.discard());
        m.hurt(com.yayuy.overgrownvegetation.registry.ModDamageTypes.sporeInfection(level), 1000.0F);
        helper.runAfterDelay(5, () -> {
            var ferns = level.getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.BlightFernEntity.class, m.getBoundingBox().inflate(4.0D));
            ferns.forEach(e -> e.discard());
            if (!m.isAlive() || !com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(m)) {
                helper.fail(EntityType.getKey(type) + " が感染の死で汎用の感染体に変わらない(生きている " + m.isAlive() + ")");
                return;
            }
            if (!ferns.isEmpty()) {
                helper.fail(EntityType.getKey(type) + " の感染の死で萌芽体が生えた");
                return;
            }
            helper.succeed();
        });
    }
}
