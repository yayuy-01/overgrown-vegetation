package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import com.yayuy.overgrownvegetation.entity.leap.AmbushSpots;
import com.yayuy.overgrownvegetation.entity.leap.LeapMotion;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlan;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlanner;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModLeapers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * サルティムネスの段 1(2026-11-14): 「跳ぶ型」の跳び(着地点・弧・柵越え・落下ダメージ無し)と伏せ場所選び。
 * 体長 7.2 ブロック・跳び 3〜10 ブロックは 7 × 7 の場に入らないので、24 × 24 の場 {@code gametest_empty24}(床は相対 y = 1、外はバリア)を使う。
 * 向きは Minecraft の yRot(0 = +z)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SaltimnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SaltimnesGameTests.class);
        }

        private Registration() {
        }
    }

    private SaltimnesGameTests() {
    }

    static final String ARENA = "gametest_empty24";

    /** 床の上 (x, y, z)(相対)に向き yaw で置く。名前を付けると伏せ場所を探さない({@code nostroll})。 */
    private static SaltimnesEntity placeAt(GameTestHelper helper, double x, double y, double z, float yaw, String name) {
        SaltimnesEntity s = helper.spawn(ModLeapers.SALTIMNES.get(), new BlockPos((int) x, (int) y, (int) z));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        s.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        s.snapFacing(yaw);
        if (name != null) s.setCustomName(Component.literal(name));
        return s;
    }

    /** 柱・壁を積む(相対 x0〜x1・z0〜z1、y = 2 から高さ h)。 */
    private static void fill(GameTestHelper helper, int x0, int x1, int z0, int z1, int y0, int h, net.minecraft.world.level.block.Block b) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y < y0 + h; y++) helper.setBlock(new BlockPos(x, y, z), b);
            }
        }
    }

    /** {@code delay} tick 後に 1 回だけ流す(runAfterDelay はその後の tick にも流れることがあった: 11-15 に 2 回目の呼び出しで「跳び始めない」)。 */
    static void once(GameTestHelper helper, long delay, Runnable r) {
        boolean[] ran = {false};
        helper.runAfterDelay(delay, () -> {
            if (ran[0]) return;
            ran[0] = true;
            r.run();
        });
    }

    /** 失敗のときに添える今の様子(相対位置・接地・速度・段・跳んだ回数)。 */
    static String state(GameTestHelper helper, SaltimnesEntity s) {
        Vec3 r = helper.relativeVec(s.position());
        Vec3 v = s.getDeltaMovement();
        return String.format("位置 %.2f %.2f %.2f 接地 %s 速度 %.3f %.3f %.3f 段 %d 跳んだ %d 生きている %s NoAI %s", r.x, r.y, r.z, s.onGround(), v.x, v.y, v.z,
                s.leapMotion().phase(), s.leapMotion().leaps(), s.isAlive(), s.isNoAi());
    }

    /** G5 に入り、自然には湧かず、体の芯は 1.8 × 1.0。 */
    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void registeredAsG5WithoutNaturalSpawn(GameTestHelper helper) {
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 12.5D, 0.0F, "nostroll");
        if (MobGroup.of(s.getType()) != MobGroup.GROUP_5) {
            helper.fail("G5 に入っていない: " + MobGroup.of(s.getType()));
            return;
        }
        if (DesignData.mobs().forId(SaltimnesEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(s.getBbWidth() - 1.8F) > 1e-3 || Math.abs(s.getBbHeight() - 1.0F) > 1e-3) {
            helper.fail("芯の大きさが 1.8 × 1.0 でない: " + s.getBbWidth() + " × " + s.getBbHeight());
            return;
        }
        helper.succeed();
    }

    /**
     * 平らな所で 6 ブロック先へ: 弧は一番低い上げ(頂点 = 2.5 ブロック)で、宙は 14〜18 tick。実際に跳ぶと
     * 身を沈める → 宙 → 着地 → 終わり の順に進み、着く所から 0.6 ブロック以内に下り、体力は減らない。
     */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void flatLeapFollowsTheArc(GameTestHelper helper) {
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 5.5D, 0.0F, "nostroll");
        once(helper, 2L, () -> {
            LeapPlan p = LeapPlanner.plan(s, helper.absoluteVec(new Vec3(12.5D, 2.0D, 11.5D)), SaltimnesEntity.LEAP);
            if (p == null) {
                helper.fail("平らな所で 6 ブロック先へ跳ぶ弧が無い");
                return;
            }
            double peak = p.peakY() - p.from().y;
            if (Math.abs(peak - 2.5D) > 0.05D || p.ticks() < 14 || p.ticks() > 18) {
                helper.fail(String.format("弧が違う: 頂点 %.2f・宙 %d tick", peak, p.ticks()));
                return;
            }
            if (!s.leapToward(helper.absoluteVec(new Vec3(12.5D, 2.0D, 11.5D)))) {
                helper.fail("跳び始めない: " + state(helper, s));
                return;
            }
            Vec3 to = s.leapMotion().plan().to();
            float hp = s.getHealth();
            int[] seen = new int[5];
            boolean[] done = {false};
            helper.onEachTick(() -> {
                if (done[0]) return;
                int ph = s.leapMotion().phase();
                seen[ph]++;
                if (ph == LeapMotion.NONE && seen[LeapMotion.LAND] > 0) {
                    done[0] = true;
                    double off = Math.sqrt(Math.pow(s.getX() - to.x, 2) + Math.pow(s.getZ() - to.z, 2));
                    if (seen[LeapMotion.CROUCH] == 0 || seen[LeapMotion.AIR] == 0 || seen[LeapMotion.FALL] > 0) {
                        helper.fail("段の順が違う: 沈む " + seen[1] + "・宙 " + seen[2] + "・落ち " + seen[3]);
                    } else if (off > 0.6D || Math.abs(s.getY() - to.y) > 0.1D) {
                        helper.fail(String.format("着く所からずれた: 横 %.2f・縦 %.2f", off, s.getY() - to.y));
                    } else if (s.getHealth() < hp) {
                        helper.fail("自分の跳びで体力が減った");
                    } else {
                        helper.succeed();
                    }
                }
            });
        });
    }

    /** 5 ブロックの壁は越えられない(上へ 3 ブロックまで・頂点の上げ 5.5 まで): 跳ぶ弧が無い。 */
    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void cannotLeapOverHighWall(GameTestHelper helper) {
        fill(helper, 0, 23, 9, 9, 2, 7, Blocks.STONE);
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 4.5D, 0.0F, "nostroll");
        once(helper, 2L, () -> {
            LeapPlan p = LeapPlanner.plan(s, helper.absoluteVec(new Vec3(12.5D, 2.0D, 14.5D)), SaltimnesEntity.LEAP);
            if (p != null) helper.fail("高い壁を越える弧を作った: 着く所 " + p.to());
            else helper.succeed();
        });
    }

    /** 水たまりの真ん中を狙っても、水には下りない(近くの乾いた所へ)。 */
    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void doesNotLandInWater(GameTestHelper helper) {
        for (int x = 11; x <= 13; x++) {
            for (int z = 11; z <= 13; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
        }
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 4.5D, 0.0F, "nostroll");
        once(helper, 2L, () -> {
            LeapPlan p = LeapPlanner.plan(s, helper.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), SaltimnesEntity.LEAP);
            if (p == null) {
                helper.fail("乾いた所へも跳ばない: " + state(helper, s));
                return;
            }
            BlockPos below = BlockPos.containing(p.to().x, p.to().y - 0.5D, p.to().z);
            if (!helper.getLevel().getFluidState(below).isEmpty() || !helper.getLevel().getFluidState(below.above()).isEmpty()) {
                helper.fail("水の上に下りる: " + helper.relativePos(below));
            } else {
                helper.succeed();
            }
        });
    }

    /** 4 ブロックの台から下へ跳び下りても、落下ダメージを受けない(場の天井が y 10 なので台は低め)。 */
    @GameTest(template = ARENA, timeoutTicks = 160)
    public static void leapsDownWithoutFallDamage(GameTestHelper helper) {
        fill(helper, 7, 17, 0, 7, 2, 4, Blocks.STONE);   // 台の上面は相対 y = 6、z 0〜7(着いた体の腹の先が台に掛からない)
        SaltimnesEntity s = placeAt(helper, 12.5D, 6.0D, 4.0D, 0.0F, "nostroll");
        once(helper, 4L, () -> {
            float hp = s.getHealth();
            if (!s.leapToward(helper.absoluteVec(new Vec3(12.5D, 2.0D, 13.5D)))) {
                helper.fail("台から下へ跳ばない: " + state(helper, s));
                return;
            }
            helper.onEachTick(() -> {
                if (s.leapMotion().active() || s.getY() > helper.absoluteVec(new Vec3(0, 2.5D, 0)).y) return;
                if (s.getHealth() < hp) helper.fail("落下ダメージを受けた: " + hp + " → " + s.getHealth());
                else helper.succeed();
            });
        });
    }

    /**
     * 柵を越える: 場を横切る柵(z = 9)の手前に置き、柵の向こうを伏せ場所にすると、経路が無いので跳んで越える。
     * 体長があるので、腹まで柵を越えた所(芯の中心が z 13 より先)に下りる。
     */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void leapsOverFenceToReachAmbushSpot(GameTestHelper helper) {
        fill(helper, 0, 23, 9, 9, 2, 1, Blocks.OAK_FENCE);
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 5.0D, 0.0F, null);
        once(helper, 2L, () -> s.setAmbushSpotForTests(helper.absoluteVec(new Vec3(12.5D, 2.0D, 17.5D)), 0.0F));
        float hp = s.getHealth();
        helper.onEachTick(() -> {
            double z = helper.relativeVec(s.position()).z;
            if (s.leapMotion().leaps() > 0 && !s.leapMotion().active() && s.onGround()) {
                if (z < 13.0D) helper.fail(String.format("柵の向こうへ下りていない: z %.2f ", z) + state(helper, s) + " 最後の着地はぶつかった " + s.leapMotion().lastAborted());
                else if (s.getHealth() < hp) helper.fail("跳んで体力が減った");
                else helper.succeed();
            }
        });
    }

    /** 伏せ場所の向き: 幅 4 の通路(石の壁 x = 10・15)では、体が入るのは通路に沿う向きだけで、開けた先(+z)へ顔を向ける。 */
    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void ambushSpotFacesTheOpenLane(GameTestHelper helper) {
        fill(helper, 10, 10, 0, 23, 2, 2, Blocks.STONE);
        fill(helper, 15, 15, 0, 23, 2, 2, Blocks.STONE);
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 12.5D, 0.0F, "nostroll");
        once(helper, 2L, () -> {
            Vec3 at = helper.absoluteVec(new Vec3(12.5D, 2.0D, 5.5D));
            AmbushSpots.Spot spot = AmbushSpots.score(s, SaltimnesEntity.LEAP, at.x, at.z);
            if (spot == null) {
                helper.fail("通路の中に伏せられない");
            } else if (Math.abs(net.minecraft.util.Mth.wrapDegrees(spot.yaw())) > 1.0F) {
                helper.fail("開けた先へ顔を向けていない: " + spot.yaw());
            } else {
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 段 3: 技(11-15)

    /** 動かない的(ハスク。日に焼けない)を (x, z) に置き、サルティムネスの標的にする。 */
    private static net.minecraft.world.entity.monster.Husk prey(GameTestHelper helper, SaltimnesEntity s, double x, double z) {
        net.minecraft.world.entity.monster.Husk h = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos((int) x, 2, (int) z));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        h.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        h.setNoAi(true);
        s.setTarget(h);
        return h;
    }

    /**
     * 跳び抱え: 前 6 ブロックほど(鼻先から約 3)の相手へ、溜め → 跳ぶ → 首の下に押さえ込む(相手が乗客になる)→ 噛む(体力が減る)→ 放す。
     * 溜めの間は光る(描画の値はクライアントだけなので、ここでは技の段の並びを見る)。
     */
    @GameTest(template = ARENA, timeoutTicks = 260)
    public static void pouncePinsAndBitesPrey(GameTestHelper helper) {
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 4.5D, 0.0F, "nostroll");
        net.minecraft.world.entity.monster.Husk h = prey(helper, s, 12.5D, 11.5D);
        float hp0 = h.getHealth();
        boolean[] pinned = {false};
        helper.onEachTick(() -> {
            if (s.act() == SaltimnesEntity.ACT_PIN && s.pinned() == h) pinned[0] = true;
            if (pinned[0] && s.act() == SaltimnesEntity.ACT_NONE) {
                if (h.isPassenger()) helper.fail("技が終わっても放していない");
                else if (h.getHealth() >= hp0) helper.fail("押さえ込んだのに噛んでいない(体力 " + h.getHealth() + ")");
                else helper.succeed();
            }
        });
        helper.runAtTickTime(250L, () -> {
            if (!pinned[0]) helper.fail("押さえ込まない: 技 " + s.act() + " " + state(helper, s) + " 的 " + helper.relativeVec(h.position()));
        });
    }

    /** 後ろ足の蹴り: 体の後ろ 5 ブロックの相手を蹴って傷つけ、後ろへ弾き飛ばす。前・横には出さない(ここでは後ろだけ置く)。 */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void kicksPreyBehind(GameTestHelper helper) {
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 12.5D, 0.0F, "nostroll");
        net.minecraft.world.entity.monster.Husk h = prey(helper, s, 12.5D, 7.5D);
        h.setNoAi(false);   // 弾かれて動けるように(標的を探す Goal は外れている)
        float hp0 = h.getHealth();
        double z0 = helper.absoluteVec(new Vec3(0, 0, 7.5D)).z;
        boolean[] kicked = {false};
        helper.onEachTick(() -> {
            if (s.act() == SaltimnesEntity.ACT_KICK) kicked[0] = true;
            if (kicked[0] && h.getHealth() < hp0) {
                if (h.getZ() < z0 - 0.3D || h.getDeltaMovement().z < -0.1D) helper.succeed();
            }
        });
        helper.runAtTickTime(110L, () -> helper.fail("蹴らない・弾かない: 蹴った " + kicked[0] + " 体力 " + h.getHealth() + " 的の z の差 "
                + String.format("%.2f", h.getZ() - z0) + " " + state(helper, s)));
    }

    /** 化けて待つ: 伏せている間は、鼻先から 6 ブロックより遠い標的には動かない。近づくと跳び抱える。 */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void hiddenWaitsUntilPreyIsClose(GameTestHelper helper) {
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 3.5D, 0.0F, "nostroll");
        s.hideHereForTests(0.0F);
        net.minecraft.world.entity.monster.Husk h = prey(helper, s, 12.5D, 18.5D);   // 鼻先から約 11.7
        Vec3 p0 = s.position();
        once(helper, 60L, () -> {
            if (s.position().distanceTo(p0) > 0.2D || s.act() != SaltimnesEntity.ACT_NONE || s.leapMotion().active()) {
                helper.fail("遠い標的に化けを解いた: 技 " + s.act() + " " + state(helper, s));
                return;
            }
            Vec3 near = helper.absoluteVec(new Vec3(12.5D, 2.0D, 10.5D));   // 鼻先から約 3.7
            h.moveTo(near.x, near.y, near.z, 0.0F, 0.0F);
        });
        helper.onEachTick(() -> {
            if (helper.getTick() > 60L && (s.act() == SaltimnesEntity.ACT_POUNCE || s.act() == SaltimnesEntity.ACT_PIN)) helper.succeed();
        });
    }

    /** 連続跳躍: 横へ 2 回跳んでから跳び抱えに行く(押さえ込むか、外して隙)。 */
    @GameTest(template = ARENA, timeoutTicks = 360)
    public static void zigzagLeapsTwiceThenPounces(GameTestHelper helper) {
        SaltimnesEntity s = placeAt(helper, 12.5D, 2.0D, 3.5D, 0.0F, "nostroll");
        prey(helper, s, 12.5D, 15.5D);
        s.forceZigForTests();
        int[] zigs = {0};
        boolean[] inZig = {false}, done = {false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            int a = s.act();
            if (a == SaltimnesEntity.ACT_ZIG_AIR && !inZig[0]) zigs[0]++;
            inZig[0] = a == SaltimnesEntity.ACT_ZIG_AIR;
            if (a == SaltimnesEntity.ACT_PIN || a == SaltimnesEntity.ACT_POUNCE_RECOVER) {
                done[0] = true;
                if (zigs[0] < 2) helper.fail("横跳びが 2 回でない: " + zigs[0]);
                else helper.succeed();
            }
        });
        helper.runAtTickTime(350L, () -> helper.fail("連続跳躍が終わらない: 横跳び " + zigs[0] + " 技 " + s.act() + " " + state(helper, s)));
    }
}
