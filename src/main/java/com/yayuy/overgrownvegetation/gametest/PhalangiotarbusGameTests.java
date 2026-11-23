package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity;
import com.yayuy.overgrownvegetation.entity.leap.LeapMotion;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModLeapers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ファランギオタルブスの段 1(2026-11-16): 小さな地這いの虫の型で歩き、跳ぶ型で跳ぶ(移動の跳びだけ)。場は 24 × 24 の {@code gametest_empty24}。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhalangiotarbusGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterPhalangiotarbusGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(PhalangiotarbusGameTests.class);
        }

        private Registration() {
        }
    }

    private PhalangiotarbusGameTests() {
    }

    static final String ARENA = "gametest_empty24";

    private static PhalangiotarbusEntity placeAt(GameTestHelper helper, double x, double y, double z, float yaw, String name) {
        PhalangiotarbusEntity s = helper.spawn(ModLeapers.PHALANGIOTARBUS.get(), new BlockPos((int) x, (int) y, (int) z));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        s.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        s.snapFacing(yaw);
        if (name != null) s.setCustomName(Component.literal(name));
        return s;
    }

    /** 狩る側の環境生物に入り、体の芯は 1.0 × 0.8(自然湧きは湧きの表の持ち場。11-16 に MobStats から外れた)。 */
    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void registeredAsHunter(GameTestHelper helper) {
        PhalangiotarbusEntity s = placeAt(helper, 12.5D, 2.0D, 12.5D, 0.0F, "nostroll");
        if (MobGroup.of(s.getType()) != MobGroup.CRITTER_HUNTER) {
            helper.fail("狩る側の環境生物に入っていない: " + MobGroup.of(s.getType()));
        } else if (Math.abs(s.getBbWidth() - 1.0F) > 1e-3 || Math.abs(s.getBbHeight() - 0.8F) > 1e-3) {
            helper.fail("芯の大きさが 1.0 × 0.8 でない: " + s.getBbWidth() + " × " + s.getBbHeight());
        } else {
            helper.succeed();
        }
    }

    /** 平らな所で 4 ブロック先へ跳ぶ: 沈む → 宙 → 着地の順に進み、着く所から 0.6 以内に下り、体力は減らない。 */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void flatLeapLands(GameTestHelper helper) {
        PhalangiotarbusEntity s = placeAt(helper, 12.5D, 2.0D, 6.5D, 0.0F, "nostroll");
        boolean[] started = {false}, done = {false};
        int[] seen = new int[5];
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (!started[0]) {
                if (helper.getTick() < 3L) return;
                started[0] = true;
                if (!s.leapToward(helper.absoluteVec(new Vec3(12.5D, 2.0D, 10.5D)))) helper.fail("跳び始めない");
                return;
            }
            int ph = s.leapMotion().phase();
            seen[ph]++;
            if (ph == LeapMotion.NONE && seen[LeapMotion.LAND] > 0) {
                done[0] = true;
                Vec3 to = s.leapMotion().plan().to();
                double off = Math.sqrt(Math.pow(s.getX() - to.x, 2) + Math.pow(s.getZ() - to.z, 2));
                if (seen[LeapMotion.AIR] == 0 || seen[LeapMotion.FALL] > 0) helper.fail("段の順が違う: 宙 " + seen[2] + "・落ち " + seen[3]);
                else if (off > 0.6D) helper.fail(String.format("着く所からずれた: %.2f", off));
                else if (s.getHealth() < s.getMaxHealth()) helper.fail("自分の跳びで体力が減った");
                else helper.succeed();
            }
        });
    }

    /** 段差 2 の台の上へ歩いて行くよう言うと、経路が届かないので跳び上がる(這い上がれるのは 1 段まで)。 */
    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void leapsUpLedgeItCannotClimb(GameTestHelper helper) {
        for (int x = 0; x <= 23; x++) {
            for (int z = 12; z <= 23; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
            }
        }
        PhalangiotarbusEntity s = placeAt(helper, 12.5D, 2.0D, 6.5D, 0.0F, null);
        boolean[] asked = {false};
        helper.onEachTick(() -> {
            if (!asked[0] && helper.getTick() >= 3L) {
                asked[0] = true;
                BlockPos goal = helper.absolutePos(new BlockPos(12, 4, 16));
                s.getNavigation().moveTo(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D, 1.0D);
            }
            if (s.leapMotion().leaps() > 0 && !s.leapMotion().active() && s.onGround()) {
                if (helper.relativeVec(s.position()).y >= 3.9D) helper.succeed();
                else helper.fail("台の上へ上がっていない: " + helper.relativeVec(s.position()));
            }
        });
    }
}
