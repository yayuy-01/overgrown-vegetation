package com.yayuy.overgrownvegetation.corruption.cliff;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 崖・急斜面の汚染(2026-11-20)の確かめ。テスト場に石の崖・急斜面を組み、{@link CliffShapes#decorateChunk} を直に呼ぶ
 * (汚染の段・巡回には頼らない)。
 * <ul>
 *   <li>急な崖に縄が垂れる(面の下のほうまで・崖の上を這う・先に垂れる根)。平地には出ない。</li>
 *   <li>急斜面には面に張り付いた根の網が出る(宙に浮いた根が無い)。</li>
 *   <li>植物側の Mob はふつうの Mob より速く登る(倍率の関数も)。</li>
 *   <li>崖の嚢を壊すと霧が出て、まわりの胞子濃度と暴露量が上がる。</li>
 *   <li>押し返す道具(灰と石灰の帯)の列には出ない。</li>
 *   <li>植物側の Mob が、崖の上の相手へ縄を伝って上る。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CliffGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CliffGameTests.class);
        }

        private Registration() {
        }
    }

    private CliffGameTests() {
    }

    static final String TEMPLATE = "gametest_empty24";
    /** 崖の高さ(床 y = 1 の上に 2〜TOP)。 */
    static final int TOP = 17;
    /** 崖の岩は x = 0〜CLIFF_X。面は x = CLIFF_X + 1(東向き)。 */
    static final int CLIFF_X = 8;

    /** 床(y = 1)と、その上を空ける。 */
    static void floor(GameTestHelper h) {
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= TOP + 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    /** 東向きの急な崖(x = 0〜8、高さ y = 2〜TOP)。 */
    static void cliff(GameTestHelper h) {
        floor(h);
        for (int x = 0; x <= CLIFF_X; x++) for (int z = 0; z < 24; z++) for (int y = 2; y <= TOP; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
    }

    /** テスト場にかかるチャンクに生やす。 */
    static CliffShapes.Result decorate(GameTestHelper h, boolean ropes, boolean sacs) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(23, 0, 23));
        CliffShapes.Result sum = new CliffShapes.Result();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                CliffShapes.Result r = CliffShapes.decorateChunk(level, new ChunkPos(cx, cz), ropes, sacs);
                sum.cliffEdges += r.cliffEdges;
                sum.slopeEdges += r.slopeEdges;
                sum.ropes += r.ropes;
                sum.thin += r.thin;
                sum.roots += r.roots;
                sum.sacs += r.sacs;
                sum.flesh += r.flesh;
                sum.cracks += r.cracks;
            }
        }
        return sum;
    }

    static int count(GameTestHelper h, int x0, int x1, int y0, int y1, int z0, int z1, BlockState want) {
        int n = 0;
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(want.getBlock())) n++;
        }
        return n;
    }

    static final BlockState ROOT = OrganBlocks.TANGLED_ROOT.get().defaultBlockState();

    /** 急な崖に縄が垂れ、崖の上を這い、先に垂れる根。離れた平地には出ない。 */
    @GameTest(template = TEMPLATE, batch = "cliff_a", timeoutTicks = 20)
    public static void ropesHangOnCliffNotOnFlat(GameTestHelper h) {
        cliff(h);
        CliffShapes.Result r = decorate(h, true, false);
        int face = count(h, CLIFF_X + 1, CLIFF_X + 2, 2, TOP, 2, 21, ROOT);
        int low = count(h, CLIFF_X + 1, CLIFF_X + 2, 2, TOP / 2, 2, 21, ROOT);
        int crawl = count(h, 0, CLIFF_X, TOP + 1, TOP + 1, 2, 21, ROOT);
        int flat = count(h, CLIFF_X + 6, 23, 2, TOP + 3, 0, 23, ROOT);
        int dangle = count(h, CLIFF_X + 1, CLIFF_X + 3, 1, TOP, 0, 23, ModBlocks.DANGLING_ROOTS.get().defaultBlockState());
        String info = r + " / 面 " + face + " / 下半分 " + low + " / 崖の上 " + crawl + " / 平地 " + flat + " / 垂れる根の先 " + dangle;
        OvergrownVegetation.LOGGER.info("[cliff test] {}", info);
        h.assertTrue(face >= 30, "崖の面の縄が少ない: " + info);
        h.assertTrue(low >= 6, "縄が崖の下のほうまで届かない: " + info);
        h.assertTrue(crawl >= 4, "崖の上を這う根が無い: " + info);
        h.assertTrue(flat == 0, "平地に根が出た: " + info);
        h.assertTrue(dangle >= 1, "先の垂れる根が無い: " + info);
        h.succeed();
    }

    /** 急斜面(2 段 / 1 マス)には、面に張り付いた根の網が出る。宙に浮いた根は無い。 */
    @GameTest(template = TEMPLATE, batch = "cliff_b", timeoutTicks = 20)
    public static void slopeGetsCrawlingNet(GameTestHelper h) {
        floor(h);
        for (int x = 0; x <= 9; x++) {
            int top = 2 + (9 - x) * 2;
            for (int z = 0; z < 24; z++) for (int y = 2; y <= top; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        CliffShapes.Result r = decorate(h, true, false);
        int onSlope = 0, floating = 0;
        for (int x = 0; x <= 12; x++) for (int y = 2; y <= 24; y++) for (int z = 4; z <= 19; z++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!h.getBlockState(p).is(ROOT.getBlock())) continue;
            onSlope++;
            boolean held = false;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockState n = h.getBlockState(p.relative(d));
                if (n.is(Blocks.STONE) || n.is(ROOT.getBlock())) held = true;
            }
            if (!held) floating++;
        }
        String info = r + " / 斜面の根 " + onSlope + " / 浮いた根 " + floating;
        OvergrownVegetation.LOGGER.info("[cliff test] {}", info);
        h.assertTrue(r.slopeEdges > 0, "急斜面と見分けない: " + info);
        h.assertTrue(onSlope >= 15, "急斜面の網が少ない: " + info);
        h.assertTrue(floating == 0, "宙に浮いた根がある: " + info);
        h.succeed();
    }

    /** 植物側の Mob(寄生されたゾンビ)はふつうのゾンビより速く登る。プレイヤーの倍率は植物側より小さい。 */
    @GameTest(template = TEMPLATE, batch = "cliff_c", timeoutTicks = 80)
    public static void plantSideClimbsFaster(GameTestHelper h) {
        floor(h);
        for (int y = 2; y <= 20; y++) {
            h.setBlock(new BlockPos(5, y, 5), ROOT);
            h.setBlock(new BlockPos(5, y, 15), ROOT);
        }
        // AI なし(setNoAi)だと体の動き(travel)自体が回らないので、Goal だけ外して湧かせ、毎 tick 根へ押す
        Mob plant = h.spawnWithNoFreeWill(ModG1.PARASITIZED_ZOMBIE.get(), new Vec3(6.5D, 2.0D, 5.5D));
        Mob other = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(6.5D, 2.0D, 15.5D));
        plant.setInvulnerable(true);
        other.setInvulnerable(true);
        double y0p = plant.getY(), y0o = other.getY();
        h.onEachTick(() -> {
            for (Mob m : new Mob[]{plant, other}) m.setDeltaMovement(-0.12D, m.getDeltaMovement().y, 0.0D);
        });
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.nameUUIDFromBytes("ov_cliff_climb".getBytes()), "ov_cliff_climb"));
        double pr = RootClimb.riseFor(plant), or = RootClimb.riseFor(other), plr = RootClimb.riseFor(fp);
        h.runAtTickTime(50, () -> {
            double gp = plant.getY() - y0p, go = other.getY() - y0o;
            String info = String.format("植物側 %.2f / ふつう %.2f ブロック(50 tick)。上りの縦: 植物側 %.3f / ふつう %.3f / プレイヤー %.3f", gp, go, pr, or, plr);
            OvergrownVegetation.LOGGER.info("[cliff test] {}", info);
            h.assertTrue(go > 2.0D, "ふつうの Mob が根を登らない: " + info);
            h.assertTrue(gp > go * 1.4D, "植物側が速く登らない: " + info);
            h.assertTrue(plr < pr, "プレイヤーが植物側より速い: " + info);
            h.succeed();
        });
    }

    /** 崖の嚢は壊すと霧を出し、まわりの胞子濃度と暴露量が上がる。 */
    @GameTest(template = TEMPLATE, batch = "cliff_d", timeoutTicks = 120)
    public static void cliffSacBurstRaisesSporesAndExposure(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        floor(h);
        BlockPos rel = new BlockPos(8, 2, 8);
        h.setBlock(rel, OrganBlocks.CLIFF_SAC.get().defaultBlockState());
        Cow cow = h.spawn(EntityType.COW, new Vec3(10.5D, 2.0D, 8.5D));
        cow.setNoAi(true); // 牛は動かなくてよい(暴露を数えるだけ)
        BlockPos abs = h.absolutePos(rel);
        int cx = abs.getX() >> 4, cz = abs.getZ() >> 4;
        SporeFieldServer.watchForTests(cx, cz);
        SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
        SporeFieldServer.Cell c0 = SporeFieldServer.cell(cx, cz);
        double before = c0 == null ? 0.0D : c0.conc();
        double dose0 = SporeExposureTracker.dose(cow);
        // 触れても割れない(ふつうのブロック)。壊すと割れる
        FakePlayer fp = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_cliff_sac".getBytes()), "ov_cliff_sac"));
        fp.gameMode.destroyBlock(abs);
        h.assertTrue(level.getBlockState(abs).isAir(), "嚢が壊れない");
        h.assertTrue(CliffSacFog.active(level) >= 1, "霧が出ない");
        h.runAtTickTime(60, () -> {
            SporeFieldServer.catchUpForTests(level, DesignData.sporeField().updateTicks());
            SporeFieldServer.Cell c1 = SporeFieldServer.cell(cx, cz);
            double after = c1 == null ? 0.0D : c1.conc();
            double dose1 = SporeExposureTracker.dose(cow);
            SporeFieldServer.withFieldForTests(f -> f.extractAround(cx, cz, 2.0, Double.POSITIVE_INFINITY)); // 片付け
            String info = String.format("升の浮遊 %.4f → %.4f / 牛の暴露量 %.2f → %.2f", before, after, dose0, dose1);
            OvergrownVegetation.LOGGER.info("[cliff test] {}", info);
            h.assertTrue(after > before, "胞子濃度が上がらない: " + info);
            h.assertTrue(dose1 > dose0, "暴露量が上がらない: " + info);
            h.succeed();
        });
    }

    /** 灰と石灰の帯の列(崖の面の前の z = 0〜11)には縄が出ない。帯の無い側(z = 12〜23)には出る。 */
    @GameTest(template = TEMPLATE, batch = "cliff_e", timeoutTicks = 20)
    public static void noRopesWherePushedBack(GameTestHelper h) {
        cliff(h);
        for (int z = 0; z <= 11; z++) {
            h.setBlock(new BlockPos(CLIFF_X + 1, 2, z), ModPushBack.ASH_LIME_BAND.get().defaultBlockState());
            h.setBlock(new BlockPos(CLIFF_X + 2, 2, z), ModPushBack.ASH_LIME_BAND.get().defaultBlockState());
        }
        CliffShapes.Result r = decorate(h, true, true);
        int guarded = count(h, CLIFF_X + 1, CLIFF_X + 2, 3, TOP + 1, 0, 11, ROOT) + count(h, CLIFF_X + 1, CLIFF_X + 2, 3, TOP + 1, 0, 11,
                OrganBlocks.CLIFF_SAC.get().defaultBlockState());
        int open = count(h, CLIFF_X + 1, CLIFF_X + 2, 3, TOP + 1, 12, 23, ROOT);
        String info = r + " / 帯の列 " + guarded + " / 帯の無い側 " + open;
        OvergrownVegetation.LOGGER.info("[cliff test] {}", info);
        h.assertTrue(guarded == 0, "押し返す帯の列に生えた: " + info);
        h.assertTrue(open > 0, "帯の無い側にも生えない(テストの前提が崩れた): " + info);
        h.succeed();
    }

    /** 植物側の Mob が、崖の上の相手へ縄を伝って上る(経路が崖の下で止まっても)。 */
    @GameTest(template = TEMPLATE, batch = "cliff_f", timeoutTicks = 900) // 上りきるまで 133〜493 tick(縄の形で揺れる)
    public static void plantMobClimbsRopeToTarget(GameTestHelper h) {
        cliff(h);
        climbToTop(h);
    }

    /**
     * でこぼこで岩棚と二股のある崖でも、植物側の Mob が縄を経路に入れて上りきる(11-20 ユーザー決定「二股のすき間で止まらず上りきること」)。
     * 面は 4 マスに 1 つ 1 マス出っ張り、高さ 9 まで 2 マス出た岩棚(下は岩)。縄は二股・ゆらぎのある本物の形。
     */
    @GameTest(template = TEMPLATE, batch = "cliff_g", timeoutTicks = 1200)
    public static void plantMobClimbsUnevenCliff(GameTestHelper h) {
        cliff(h);
        for (int y = 2; y <= TOP - 1; y++) {
            for (int z = 0; z < 24; z++) {
                if (CliffShapes.hash(y, z, 77) % 4 == 0) h.setBlock(new BlockPos(CLIFF_X + 1, y, z), Blocks.STONE);
            }
        }
        // 岩棚 = 下に岩のある段(高さ 9 まで 2 マス出た台)。下が空いた張り出しは、縄が真下へ垂れるだけで登りは確かめていない(報告)
        for (int z = 4; z <= 13; z++) for (int x = CLIFF_X + 1; x <= CLIFF_X + 2; x++) for (int y = 2; y <= 9; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        climbToTop(h);
    }

    /** 縄を生やし、崖の上の村人を相手にした寄生ゾンビが、崖の上(高さ TOP + 1)まで上るのを待つ。 */
    static void climbToTop(GameTestHelper h) {
        // 縄の位置は世界の座標のハッシュと割合(既定 0.8)で決まるので、テスト場の置かれた所しだいで Mob の近くに縄が出ない回があった
        // (11-20 夜の全件で、経路が縄に一度も触れず崖の下で止まった)。上りを確かめるテストなので、ここだけ割合を 1 にする(同じ tick の中で戻す)
        CliffTable.Rope rope = DesignData.cliff().rope;
        double was = rope.chance;
        rope.chance = 1.0D;
        CliffShapes.Result placed;
        try {
            placed = decorate(h, true, false);
        } finally {
            rope.chance = was;
        }
        Villager prey = h.spawn(EntityType.VILLAGER, new Vec3(3.5D, TOP + 1.0D, 12.5D));
        prey.setNoAi(true);
        // 無敵にしない(無敵の相手はバニラの canBeSeenAsEnemy で攻撃の相手から外れ、G1 の追う Goal が動かない)
        Mob z = h.spawn(ModG1.PARASITIZED_ZOMBIE.get(), new Vec3(16.5D, 2.0D, 12.5D));
        z.setPersistenceRequired();
        // G1 の相手選びは、動かない村人を毎 tick 外す(テストの都合)。相手選びを外して村人だけを追わせる(ゲーム内ではプレイヤーを追って上りきるのを確かめた)
        z.targetSelector.removeAllGoals(g -> true);
        z.setTarget(prey);
        int topY = h.absolutePos(new BlockPos(0, TOP + 1, 0)).getY();
        double[] best = {z.getY()};
        java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>(); // 揺れの調べ(11-23): 落ちたときに直前の経過を出す
        h.onEachTick(() -> {
            if (z.getTarget() != prey) z.setTarget(prey);
            best[0] = Math.max(best[0], z.getY());
            if (h.getTick() % 10 == 0) {
                net.minecraft.world.level.pathfinder.Path pa = z.getNavigation().getPath();
                Vec3 rp = h.relativeVec(z.position());
                String next = pa == null ? "なし" : pa.isDone() ? "終わり" : (pa.getNextNodeIndex() + "/" + pa.getNodeCount() + "→" + h.relativeVec(Vec3.atLowerCornerOf(pa.getNextNodePos())));
                trace.addLast(String.format("t%d(%.1f,%.1f,%.1f)%s%s %s%s", h.getTick(), rp.x, rp.y, rp.z, z.onGround() ? "地" : "宙", RootClimb.touching(z) ? "縄" : "",
                        next, pa != null && pa.canReach() ? "届" : ""));
                if (trace.size() > 6) trace.removeFirst();
                if (h.getTick() == 1180 && z.getY() < topY - 0.01D) {
                    // 落ちる直前: まわりのブロックと、今いる所からの試しの経路
                    StringBuilder env = new StringBuilder(" まわり:");
                    BlockPos bp = z.blockPosition();
                    for (int dy = -1; dy <= 2; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = bp.offset(dx, dy, dz);
                        net.minecraft.world.level.block.state.BlockState s = h.getLevel().getBlockState(q);
                        if (s.isAir()) continue;
                        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                        env.append(' ').append(dx).append(',').append(dy).append(',').append(dz).append('=').append(id.replace("tangled_root", "縄").replace("stone", "石"));
                    }
                    long a0 = RootPath.addedCount, t0 = RootPath.touchCount;
                    net.minecraft.world.level.pathfinder.Path probe = z.getNavigation().createPath(prey, 0);
                    env.append(" 試し:").append(probe == null ? "null" : (probe.canReach() ? "届く" : "届かない") + probe.getNodeCount() + "節");
                    if (probe != null) for (int i = 0; i < probe.getNodeCount(); i++) {
                        Vec3 n = h.relativeVec(Vec3.atLowerCornerOf(probe.getNode(i).asBlockPos()));
                        env.append(String.format(" (%.0f,%.0f,%.0f)", n.x, n.y, n.z));
                    }
                    env.append(" 縄の節 +").append(RootPath.addedCount - a0).append(" 取り付き +").append(RootPath.touchCount - t0)
                            .append(" 延長 ").append(RootPath.climbAllowance(z, java.util.Set.of(prey.blockPosition())));
                    trace.addLast(env.toString());
                }
            }
            if (h.getTick() % 40 == 0) {
                if (h.getTick() == 40) {
                    net.minecraft.world.level.pathfinder.Path probe = z.getNavigation().createPath(prey, 0);
                    StringBuilder sb = new StringBuilder();
                    if (probe != null) for (int i = 0; i < probe.getNodeCount(); i++) sb.append(h.relativePos(probe.getNode(i).asBlockPos())).append(" ");
                    OvergrownVegetation.LOGGER.info("[cliff test] 追う距離 {} / 調べる節の倍率の元 {}", z.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE), RootPath.climbAllowance(z, java.util.Set.of(prey.blockPosition())));
                    OvergrownVegetation.LOGGER.info("[cliff test] 試しの経路 届く {} 節 {} / 縄の節 足した {} 取り付き {}", probe != null && probe.canReach(), sb, RootPath.addedCount, RootPath.touchCount);
                }
                net.minecraft.world.level.pathfinder.Path pa = z.getNavigation().getPath();
                OvergrownVegetation.LOGGER.info("[cliff test] 登り {} tick: 高さ {} 地面 {} 経路 {} 届く {} 次の節 {} 触れる {}", h.getTick(), String.format("%.1f", z.getY() - (topY - TOP - 1)),
                        z.onGround(), pa == null ? "なし" : (pa.isDone() ? "終わり" : pa.getNodeCount() + " 節"), pa != null && pa.canReach(),
                        pa == null || pa.isDone() ? "-" : h.relativePos(pa.getNextNodePos()), RootClimb.touching(z));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(z.getY() >= topY - 0.01D && z.onGround(), String.format("まだ崖の上にいない: 高さ %.1f(いちばん高く %.1f)・縄 %d・崖の縁 %d・経過 %s", z.getY() - (topY - TOP - 1), best[0] - (topY - TOP - 1), placed.ropes, placed.cliffEdges, String.join(" ", trace)));
            OvergrownVegetation.LOGGER.info("[cliff test] 上った tick {}", h.getTick());
        });
    }
}
