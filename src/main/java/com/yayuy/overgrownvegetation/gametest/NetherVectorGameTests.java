package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedFlyer;
import com.yayuy.overgrownvegetation.entity.SporeBombAim;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * ネザーの媒介種(2026-10-09)のサーバー側の決まり。
 * <ul>
 *     <li>胞子感染で死んだガスト・ブレイズは死体を作らず、その場で寄生体に入れ替わり、15 秒漂ってから目覚める(ユーザー指定)。</li>
 *     <li>寄生されたガスト・ブレイズの胞子爆弾は毒を乗せる(燃える代わり、ユーザー指定)。</li>
 *     <li>ネザーの媒介種はネザーにだけ、オーバーワールド産の媒介種はネザーの外にだけ湧く。</li>
 *     <li>撃ち出しの向き({@link SporeBombAim})は狙った所へ届く。</li>
 * </ul>
 * 見た目(入れ替わりの瞬間・眠り・目覚め・撃つアニメ)はクライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherVectorGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(NetherVectorGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "nether_vectors";

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void infectedGhastBecomesDormantParasiteAndWakes(GameTestHelper helper) {
        reanimates(helper, EntityType.GHAST, ModEntities.PARASITIZED_GHAST.get(), true);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void infectedBlazeBecomesDormantParasite(GameTestHelper helper) {
        reanimates(helper, EntityType.BLAZE, ModEntities.PARASITIZED_BLAZE.get(), false);
    }

    /**
     * 胞子感染で死ぬ → 次の tick には元の Mob が消え、寄生体が同じ位置・向きで眠った状態(AI 停止)でいる。死体ブロックは置かれない。
     * {@code untilAwake} なら、15 秒 + 目覚めの後に AI が戻ることまで見る。
     */
    private static void reanimates(GameTestHelper helper, EntityType<? extends Mob> type, EntityType<?> parasite, boolean untilAwake) {
        Mob mob = helper.spawnWithNoFreeWill(type, new Vec3(3.5, 3.0, 3.5));
        mob.setNoAi(true);
        mob.setYRot(90.0F);
        mob.yBodyRot = 90.0F;
        mob.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, 600, 0));
        Vec3 deathPos = mob.position();
        AABB around = new AABB(mob.blockPosition()).inflate(4.0);
        mob.kill();

        helper.runAtTickTime(helper.getTick() + 2, () -> {
            helper.assertTrue(mob.isRemoved(), "入れ替わった元の Mob が残っている");
            List<? extends net.minecraft.world.entity.Entity> found = helper.getLevel().getEntities(parasite, around, e -> true);
            helper.assertTrue(found.size() == 1, parasite + " が 1 体になっていない: " + found.size());
            AbstractParasitizedFlyer flyer = (AbstractParasitizedFlyer) found.get(0);
            helper.assertTrue(flyer.position().distanceTo(deathPos) < 0.2, "入れ替わった位置が違う: " + flyer.position() + " / " + deathPos);
            helper.assertTrue(Math.abs(flyer.getYRot() - 90.0F) < 0.5F, "入れ替わった向きが違う: " + flyer.getYRot());
            helper.assertTrue(flyer.isDormant() && flyer.isNoAi(), "入れ替わった直後に眠っていない");
            if (!untilAwake) {
                flyer.discard();
                helper.succeed();
            }
        });
        if (untilAwake) {
            helper.runAtTickTime(helper.getTick() + 2 + AbstractParasitizedFlyer.DORMANT_TICKS + AbstractParasitizedFlyer.WAKE_TICKS + 4, () -> {
                List<? extends net.minecraft.world.entity.Entity> found = helper.getLevel().getEntities(parasite, around.inflate(4.0), e -> true);
                helper.assertTrue(found.size() == 1, parasite + " が見つからない");
                AbstractParasitizedFlyer flyer = (AbstractParasitizedFlyer) found.get(0);
                helper.assertTrue(!flyer.isDormant() && !flyer.isNoAi(), "15 秒 + 目覚めの後も眠ったまま");
                helper.assertTrue(flyer.position().distanceTo(deathPos) < 3.0, "眠っている間に流れすぎた: " + flyer.position());
                flyer.discard();
                helper.succeed();
            });
        }
    }

    /** 寄生されたブレイズの爆弾(表 parasitized_blaze.bomb)は当たった相手に毒を乗せ、ダメージを与える。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void blazeSporeBombPoisons(GameTestHelper helper) {
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new Vec3(3.5, 2.0, 3.5));
        pig.setNoAi(true);
        float before = pig.getHealth();
        Vec3 at = pig.position().add(0.0, 2.5, 0.0);
        VelophyllBombEntity bomb = new VelophyllBombEntity(helper.getLevel(), at, new Vec3(0.0, -0.4, 0.0), null, 0.25F)
                .withStats("parasitized_blaze");
        helper.getLevel().addFreshEntity(bomb);
        helper.runAtTickTime(helper.getTick() + 20, () -> {
            helper.assertTrue(bomb.isRemoved(), "爆弾が炸裂していない");
            helper.assertTrue(pig.hasEffect(MobEffects.POISON), "毒が乗っていない");
            helper.assertTrue(pig.getHealth() < before, "ダメージが入っていない: " + pig.getHealth());
            pig.discard();
            helper.succeed();
        });
    }

    /** ネザーの媒介種はネザーにだけ、オーバーワールド産の媒介種(G1)はネザーの外にだけ。ほかのグループは両方。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void netherVectorsSpawnOnlyInTheNether(GameTestHelper helper) {
        helper.assertTrue(OvergrownSpawnProfile.netherRuleAllows(true, ModEntities.PARASITIZED_GHAST.get()), "ネザーの媒介種がネザーに湧かない");
        helper.assertTrue(OvergrownSpawnProfile.netherRuleAllows(true, ModEntities.PARASITIZED_PIGLIN.get()), "ネザーの媒介種がネザーに湧かない");
        helper.assertTrue(!OvergrownSpawnProfile.netherRuleAllows(false, ModEntities.PARASITIZED_HOGLIN.get()), "ネザーの媒介種がネザーの外に湧く");
        helper.assertTrue(!OvergrownSpawnProfile.netherRuleAllows(true, ModEntities.PARASITIZED_COW.get()), "オーバーワールド産の媒介種がネザーに湧く");
        helper.assertTrue(OvergrownSpawnProfile.netherRuleAllows(false, ModEntities.PARASITIZED_COW.get()), "オーバーワールド産の媒介種が外に湧かない");
        helper.assertTrue(OvergrownSpawnProfile.netherRuleAllows(true, ModEntities.VELOPHYLL.get()), "G1 以外がネザーで止められた");
        helper.succeed();
    }

    /** 撃ち出しの向き: 決まった初速で、上から・同じ高さ・下から、それぞれ狙った所の 1 ブロック以内へ届く。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporeBombAimReachesTarget(GameTestHelper helper) {
        Vec3[] deltas = {new Vec3(20, -8, 5), new Vec3(30, 0, -10), new Vec3(8, 4, 8), new Vec3(-45, -20, 12)};
        for (Vec3 d : deltas) {
            Vec3 v = SporeBombAim.solve(d, 1.5);
            // 狙った所の水平距離に来たときの高さ
            double vx = v.x;
            double vy = v.y;
            double vz = v.z;
            double x = 0;
            double y = 0;
            double z = 0;
            double target = d.horizontalDistance();
            boolean reached = false;
            for (int t = 0; t < 400; t++) {
                vy = (vy - VelophyllBombEntity.GRAVITY) * VelophyllBombEntity.DRAG;
                vx *= VelophyllBombEntity.DRAG;
                vz *= VelophyllBombEntity.DRAG;
                x += vx;
                y += vy;
                z += vz;
                if (Math.sqrt(x * x + z * z) >= target) {
                    helper.assertTrue(Math.abs(y - d.y) < 1.0, "高さが外れた: " + d + " → " + y);
                    helper.assertTrue(Math.abs(Math.atan2(z, x) - Math.atan2(d.z, d.x)) < 0.05, "向きが外れた: " + d);
                    reached = true;
                    break;
                }
            }
            helper.assertTrue(reached, "届かない: " + d);
        }
        helper.succeed();
    }

    private NetherVectorGameTests() {
    }
}
