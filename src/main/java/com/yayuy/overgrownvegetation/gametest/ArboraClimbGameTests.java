package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 群れに居ないアルボラが、<b>柵の向こう・小屋の中の相手</b>へ届くか(ユーザー報告 2026-10-02「アルボラがフェンスや塀を登って超えられない」
 * 「3×3×3 の立方体を柱 1 本で支えた構造の中に Mob がいると、集まって壊そうとしても壊せず、複数のアルボラが詰まる」)。
 * 同盟軍の基地を攻めるのは TD ではなくふつうの狩り(壁を壊す攻城が無い)なので、相手を直に追う張り付きの動きで越える・掘って入る。
 * <ul>
 *     <li>{@link #chasesTargetOverChainLinkFence} … 基地の外周と同じ金網 3 段 + 有刺鉄線の向こうの地面にいる相手。
 *     相手が低くても、面の向こうにいるなら上端まで登って跳び越える。</li>
 *     <li>{@link #chasesTargetOverLowWall} … 塀 2 段(当たり判定の高さ 1.5)の向こうの相手。塀の上の 0.5 を張り付く面に数える。</li>
 *     <li>{@link #digsIntoCabinOnPillar} … 柱 2 段の上の 3×3×4 の箱の中(1×2×1)にいる相手。柱を登り、箱の床下の天井の縁を回って
 *     横の面へ移り、相手の高さで面を掘って入る。</li>
 *     <li>{@link #digsIntoClosedRoom} … 中が 3×3×3 の閉じた部屋の真ん中にいる相手(面から 2 マス奥)。外壁を掘って入り、部屋の中を歩いて詰める。</li>
 * </ul>
 * どれも相手(NoAI のハスク)に最初の一撃が入れば成功。標的はテストが毎 tick 付け直す(隣のテスト場のハスクへ移らないように)。
 * 中身は {@code AbstractOvergrownInvaderEntity#tickSurfaceCrawl}・{@code SurfaceCrawl}。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ArboraClimbGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "arbora_climb";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ArboraClimbGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 900, batch = BATCH)
    public static void chasesTargetOverChainLinkFence(GameTestHelper helper) {
        List<BlockPos> built = new ArrayList<>();
        BlockState chain = com.yayuy.overgrownvegetation.base.BaseBlocks.CHAIN_LINK_FENCE.get().defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 4; y++) {
                put(helper, built, new BlockPos(3, y, z), chain);
            }
            helper.setBlock(new BlockPos(3, 5, z), ModBlocks.BARBED_WIRE.get().defaultBlockState());
        }
        connect(helper, built);
        chase(helper, "金網 3 段の向こう", new Vec3(1.5D, 2.0D, 3.5D), new Vec3(5.5D, 2.0D, 3.5D), 800L);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 900, batch = BATCH)
    public static void chasesTargetOverLowWall(GameTestHelper helper) {
        List<BlockPos> built = new ArrayList<>();
        BlockState wall = com.yayuy.overgrownvegetation.base.BaseBlocks.REINFORCED_CONCRETE_WALL.get().defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 3; y++) {
                put(helper, built, new BlockPos(3, y, z), wall);
            }
        }
        connect(helper, built);
        chase(helper, "塀 2 段の向こう", new Vec3(1.5D, 2.0D, 3.5D), new Vec3(5.5D, 2.0D, 3.5D), 800L);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 1500, batch = BATCH)
    public static void digsIntoCabinOnPillar(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        // 柱(3, 2〜3, 3)の上に、3×3×4 の箱(x, z = 2〜4、y = 4〜7)。中(3, 5〜6, 3)だけ空ける。天井のバリア(y = 8)の下に収まる。
        helper.setBlock(new BlockPos(3, 2, 3), stone);
        helper.setBlock(new BlockPos(3, 3, 3), stone);
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                for (int y = 4; y <= 7; y++) {
                    if (x == 3 && z == 3 && (y == 5 || y == 6)) {
                        continue;
                    }
                    helper.setBlock(new BlockPos(x, y, z), stone);
                }
            }
        }
        chase(helper, "柱の上の箱の中", new Vec3(0.5D, 2.0D, 0.5D), new Vec3(3.5D, 5.0D, 3.5D), 1400L);
    }

    /**
     * 中が 3×3×3 の閉じた部屋(外 5×5×4、x, z = 1〜5、y = 2〜5)の真ん中にいる相手。ユーザーの「3×3×3 の立方体」が中の広さの意味でも
     * 届くか(相手は面から 2 マス奥・屋根は頭の 2 マス上)。柱の上に載せると外壁がテスト場の外のバリアに接してしまうので、地面に置く。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 900, batch = BATCH)
    public static void digsIntoClosedRoom(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                for (int y = 2; y <= 5; y++) {
                    boolean inside = x >= 2 && x <= 4 && z >= 2 && z <= 4 && y <= 4;
                    if (!inside) {
                        helper.setBlock(new BlockPos(x, y, z), stone);
                    }
                }
            }
        }
        chase(helper, "閉じた部屋(中 3×3×3)の真ん中", new Vec3(0.5D, 2.0D, 0.5D), new Vec3(3.5D, 2.0D, 3.5D), 800L);
    }

    private static void put(GameTestHelper helper, List<BlockPos> built, BlockPos rel, BlockState state) {
        helper.setBlock(rel, state);
        built.add(rel);
    }

    /** 金網・塀の腕のつながりを整える(1 つずつ置くと、置いた側のブロックは隣を見ずに既定の形のまま)。 */
    private static void connect(GameTestHelper helper, List<BlockPos> built) {
        ServerLevel level = helper.getLevel();
        for (BlockPos rel : built) {
            BlockPos p = helper.absolutePos(rel);
            BlockState st = level.getBlockState(p);
            BlockState fixed = Block.updateFromNeighbourShapes(st, level, p);
            if (fixed != st) {
                level.setBlock(p, fixed, Block.UPDATE_ALL);
            }
        }
    }

    /**
     * {@code arboraAt} のアルボラに {@code huskAt} の NoAI のハスクを追わせ、{@code limit} tick のうちに一撃入れば成功。
     * 落ちたら最後の 180 tick のアルボラの様子(位置・張り付き・内部の数え)を添える。終わったら両方消す
     * (ハスクが噛まれて死ぬと汚染シダが湧いてテスト場を汚す。アルボラは隣のテスト場へ行かないように)。
     */
    private static void chase(GameTestHelper helper, String what, Vec3 arboraAt, Vec3 huskAt, long limit) {
        ServerLevel level = helper.getLevel();
        Husk husk = EntityType.HUSK.create(level);
        Mob arbora = ModEntities.ARBORA.get().create(level);
        if (husk == null || arbora == null) {
            helper.fail("ハスク・アルボラを作れない");
            return;
        }
        Vec3 h = helper.absoluteVec(huskAt);
        husk.moveTo(h.x, h.y, h.z, 0.0F, 0.0F);
        husk.setNoAi(true);
        // TD の侵攻の範囲に掛かっても片付けられないように(TdStage#mob と同じ印)。
        husk.addTag(com.yayuy.overgrownvegetation.td.TdHostiles.EXEMPT_TAG);
        level.addFreshEntity(husk);
        Vec3 a = helper.absoluteVec(arboraAt);
        arbora.moveTo(a.x, a.y, a.z, 0.0F, 0.0F);
        level.addFreshEntity(arbora);
        float max = husk.getMaxHealth();
        ArrayDeque<String> recent = new ArrayDeque<>();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            if (arbora.isAlive() && husk.isAlive()) {
                arbora.setTarget(husk);
            }
            if (helper.getTick() % 4L == 0L && arbora instanceof AbstractOvergrownInvaderEntity inv) {
                Vec3 p = helper.relativeVec(arbora.position());
                recent.addLast(String.format(Locale.ROOT, "t=%d (%.2f,%.2f,%.2f)%s%s {%s}", helper.getTick(), p.x, p.y, p.z,
                        inv.isClinging() ? " cling " + (inv.isOnCeiling() ? "up" : inv.getClimbFacing().getName()) : "",
                        arbora.onGround() ? " ground" : "", inv.debugClimbState()));
                while (recent.size() > 45) {
                    recent.removeFirst();
                }
            }
            if (!husk.isAlive() || husk.getHealth() < max - 0.01F) {
                done[0] = true;
                arbora.discard();
                husk.discard();
                helper.succeed();
                return;
            }
            if (helper.getTick() >= limit) {
                done[0] = true;
                String trace = String.join(" ## ", recent);
                arbora.discard();
                husk.discard();
                helper.fail(what + "の相手へ " + limit + " tick のうちに一撃も入らない / 直近: " + trace);
            }
        });
    }

    private ArboraClimbGameTests() {
    }
}
