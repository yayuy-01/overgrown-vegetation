package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.ecosystem.EcoLedger;
import com.yayuy.overgrownvegetation.ecosystem.EcoSpawns;
import com.yayuy.overgrownvegetation.ecosystem.EcoTable;
import com.yayuy.overgrownvegetation.ecosystem.Ecosystem;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生態系の台帳(2026-11-16)。期待値は設計値({@code ecosystem.json})から出す。
 * 世界に 1 つの台帳・多様性を触る確かめは 1 本にまとめて自分だけのバッチに入れ、始めと終わりに満員へ戻す(旧版の罠: 同じバッチのテストが同時に動かす)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class EcosystemGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterEcosystemGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(EcosystemGameTests.class);
        }

        private Registration() {
        }
    }

    private EcosystemGameTests() {
    }

    private static final long DAY = 24000L;

    /** 升の頭数が倒すと減り、5 日で満員に戻る。満員に戻った升は消える。深く減った升も 5 日で戻る。多様性は固定の物差しで下がる。 */
    @GameTest(template = "gametest_empty7", batch = "eco_pure", timeoutTicks = 20)
    public static void cellsDropRecoverAndHealth(GameTestHelper helper) {
        EcoTable t = DesignData.ecosystem();
        EcoLedger l = EcoLedger.createDetachedForTests();
        long t0 = 1_000_000L;
        helper.assertTrue(l.heads(Ecosystem.PREY, 0, 0, t0) == t.fullPrey && l.health(t0) == 1.0D, "空の台帳が満員でない");
        l.kill(Ecosystem.PREY, 1, 1, t0);
        l.kill(Ecosystem.HUNTER, 2, 3, t0); // 同じ升(4×4 チャンクなら 0,0)
        helper.assertTrue(Math.abs(l.heads(Ecosystem.PREY, 0, 0, t0) - (t.fullPrey - 1)) < 1e-9 && Math.abs(l.heads(Ecosystem.HUNTER, 0, 0, t0) - (t.fullHunter - 1)) < 1e-9, "倒しても升が減らない");
        helper.assertTrue(l.heads(Ecosystem.PREY, 1 << t.cellShift, 0, t0) == t.fullPrey, "隣の升まで減った");
        double expect = 1.0D - (t.weightPrey + t.weightHunter) / t.scale;
        helper.assertTrue(Math.abs(l.health(t0) - expect) < 1e-9, "多様性が物差しどおりでない: " + l.health(t0) + " / " + expect);
        // 半分の日数で半分戻る(欠け 1 < 満員 → 満員 ÷ 日数 の速さ)
        long half = (long) (t.recoveryDays * DAY / 2);
        double h2 = l.heads(Ecosystem.PREY, 0, 0, t0 + half);
        helper.assertTrue(h2 == t.fullPrey, "欠け 1 は半日もかからず戻るはず(満員 ÷ 日数の速さ): " + h2);
        // 頭数 0 の升でさらに倒しても多様性は下がる。深く減った升も recoveryDays で満員へ
        for (int i = 0; i < t.fullPrey * 3; i++) l.kill(Ecosystem.PREY, 40, 40, t0);
        helper.assertTrue(l.heads(Ecosystem.PREY, 40, 40, t0) == 0.0D, "頭数が 0 より下か、0 にならない");
        double deep = l.health(t0);
        helper.assertTrue(deep < expect, "頭数 0 の升で倒しても多様性が下がらない");
        long back = (long) Math.ceil(t.recoveryDays * DAY);
        helper.assertTrue(l.heads(Ecosystem.PREY, 40, 40, t0 + back - DAY) < t.fullPrey, "深い升が早く戻りすぎる");
        helper.assertTrue(l.heads(Ecosystem.PREY, 40, 40, t0 + back) == t.fullPrey, "深い升が recoveryDays で戻らない");
        l.recount(t0 + back);
        helper.assertTrue(l.damagedCells() == 0 && l.health(t0 + back) == 1.0D, "戻った升が消えない・多様性が戻らない");
        // 予算の倍率: 健康 100% で 1、0% で incomeFloor
        helper.assertTrue(EcoLedger.incomeFactor(1.0D, t.incomeFloor) == 1.0D && EcoLedger.incomeFactor(0.0D, t.incomeFloor) == t.incomeFloor, "予算の倍率が違う");
        // 守りの割合: しきい値以上で 0
        helper.assertTrue(EcoSpawns.defenseFactor(t.innerThreshold, t.innerThreshold) == 0.0D && EcoSpawns.defenseFactor(0.0D, t.innerThreshold) == 1.0D, "守りの割合が違う");
        helper.succeed();
    }

    /** 保存して読み戻すと同じ。 */
    @GameTest(template = "gametest_empty7", batch = "eco_pure", timeoutTicks = 20)
    public static void saveAndLoad(GameTestHelper helper) {
        EcoLedger l = EcoLedger.createDetachedForTests();
        long t0 = 500_000L;
        l.kill(Ecosystem.PREY, 3, 3, t0);
        l.kill(Ecosystem.PREY, 3, 3, t0 + 100);
        l.kill(Ecosystem.HUNTER, -20, 7, t0 + 200);
        CompoundTag tag = l.write(new CompoundTag());
        EcoLedger r = EcoLedger.load(tag);
        long at = t0 + 5000;
        helper.assertTrue(r.damagedCells() == l.damagedCells() && r.kills(Ecosystem.PREY) == 2 && r.kills(Ecosystem.HUNTER) == 1, "升の数・殺しの数が戻らない");
        helper.assertTrue(r.heads(Ecosystem.PREY, 3, 3, at) == l.heads(Ecosystem.PREY, 3, 3, at) && r.heads(Ecosystem.HUNTER, -20, 7, at) == l.heads(Ecosystem.HUNTER, -20, 7, at)
                && r.health(at) == l.health(at), "読み戻した頭数・多様性が違う");
        helper.succeed();
    }

    /** まわりの升の賑わいの送る値(11-17 小さな生き物): 倒すとその升の値だけ下がり、隣は満員のまま。通信で壊れない。 */
    @GameTest(template = "gametest_empty7", batch = "eco_pure", timeoutTicks = 20)
    public static void cellsPayloadFollowsHeads(GameTestHelper helper) {
        EcoTable t = DesignData.ecosystem();
        EcoLedger l = EcoLedger.createDetachedForTests();
        long t0 = 700_000L;
        int chunkX = -3, chunkZ = 5; // 負の座標も
        int cx = chunkX >> t.cellShift, cz = chunkZ >> t.cellShift;
        int r = com.yayuy.overgrownvegetation.ecosystem.EcoCellsSender.RADIUS;
        var before = com.yayuy.overgrownvegetation.ecosystem.EcoCellsSender.build(l, cx, cz, r, t0);
        int mid = before.index(cx, cz), side = before.index(cx + 1, cz);
        helper.assertTrue(mid >= 0 && side >= 0 && before.index(cx + r + 1, cz) == -1, "添字が違う");
        helper.assertTrue(com.yayuy.overgrownvegetation.network.EcoCellsPayload.unpack(before.prey()[mid]) == 1.0F, "満員の升が 1 でない");
        int n = Math.max(1, t.fullPrey / 2);
        for (int i = 0; i < n; i++) l.kill(Ecosystem.PREY, chunkX, chunkZ, t0);
        l.kill(Ecosystem.HUNTER, chunkX, chunkZ, t0);
        var after = com.yayuy.overgrownvegetation.ecosystem.EcoCellsSender.build(l, cx, cz, r, t0);
        float prey = com.yayuy.overgrownvegetation.network.EcoCellsPayload.unpack(after.prey()[mid]);
        float hunter = com.yayuy.overgrownvegetation.network.EcoCellsPayload.unpack(after.hunter()[mid]);
        float expect2 = (float) (t.fullPrey - n) / t.fullPrey, expect3 = (float) (t.fullHunter - 1) / t.fullHunter;
        helper.assertTrue(Math.abs(prey - expect2) < 0.005F && Math.abs(hunter - expect3) < 0.005F,
                "倒した升の値が頭数どおりに下がらない: 獲物の環境生物 " + prey + " / " + expect2 + "・狩る側の環境生物 " + hunter + " / " + expect3);
        helper.assertTrue(com.yayuy.overgrownvegetation.network.EcoCellsPayload.unpack(after.prey()[side]) == 1.0F, "隣の升まで下がった");
        helper.assertTrue(!after.sameAs(before), "値が変わったのに同じと見なした(送られない)");
        // 通信で壊れない
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        com.yayuy.overgrownvegetation.network.EcoCellsPayload.STREAM_CODEC.encode(buf, after);
        var back = com.yayuy.overgrownvegetation.network.EcoCellsPayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(back.sameAs(after), "通信で中身が変わった");
        helper.succeed();
    }

    /** 重さ: 減った升 1 万で、倒す 1 回・数え直し 1 回・保存 1 回の時間を測る(数字はログへ)。 */
    @GameTest(template = "gametest_empty7", batch = "eco_pure", timeoutTicks = 200)
    public static void weight(GameTestHelper helper) {
        EcoLedger l = EcoLedger.createDetachedForTests();
        long t0 = 2_000_000L;
        RandomSource r = RandomSource.create(1L);
        for (int i = 0; i < 10_000; i++) l.putCellForTests(ChunkPos.asLong(r.nextInt(4000) - 2000, r.nextInt(4000) - 2000), 3.0D, t0, 1.0D, t0);
        int n = l.damagedCells();
        // 温め
        for (int i = 0; i < 20; i++) l.recount(t0 + i);
        long a = System.nanoTime();
        for (int i = 0; i < 20; i++) l.recount(t0 + 100 + i);
        double recountUs = (System.nanoTime() - a) / 20.0D / 1000.0D;
        a = System.nanoTime();
        for (int i = 0; i < 100_000; i++) l.kill(2 + (i & 1), r.nextInt(16000) - 8000, r.nextInt(16000) - 8000, t0 + 200);
        double killNs = (System.nanoTime() - a) / 100_000.0D;
        l.health(t0 + 200); // 倒した直後の数え直しを先に済ませる(読むだけの時間を測る)
        a = System.nanoTime();
        for (int i = 0; i < 100_000; i++) l.health(t0 + 200);
        double readNs = (System.nanoTime() - a) / 100_000.0D;
        int cells = l.damagedCells();
        a = System.nanoTime();
        CompoundTag tag = l.write(new CompoundTag());
        double saveMs = (System.nanoTime() - a) / 1e6;
        String msg = String.format(Locale.ROOT, "生態系の台帳の重さ: 減った升 %d で数え直し %.0f µs / 倒す 1 回 %.0f ns / 健康を読む %.1f ns / 升 %d の保存の写し %.1f ms",
                n, recountUs, killNs, readNs, cells, saveMs);
        OvergrownVegetation.LOGGER.info(msg);
        // ゆるい天井(遅い機械でも落ちない幅。中身はログで見る)
        helper.assertTrue(recountUs < 20_000.0D && killNs < 20_000.0D && readNs < 2_000.0D && saveMs < 2_000.0D && tag.contains("Keys"), msg);
        helper.succeed();
    }

    /**
     * 世界を通す確かめ(1 本にまとめる): 数える殺し手(プレイヤーは数え、落下・Mob は数えない)・死の口で升が減る・
     * 予算の倍率(健康 100% で 1・上限は変わらない)・内側の守り(健康で 0 体・殺しすぎると減った升に G2・内側でない所には湧かない)・仮の種の印。
     */
    @GameTest(template = "gametest_empty7", batch = "eco_world", timeoutTicks = 60)
    public static void killsHealthBudgetAndInnerDefense(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EcoLedger ledger = EcoLedger.get(level);
        PhaseData data = PhaseData.get(level);
        Integer phaseBefore = DirectorGates.phaseOverride();
        PhaseStage stageBefore = DirectorGates.stageOverride();
        EcoTable t = DesignData.ecosystem();
        ledger.reset();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 2, 3));
        int ocx = origin.getX() >> 4, ocz = origin.getZ() >> 4;
        List<ChunkPos> added = new ArrayList<>();
        List<Mob> made = new ArrayList<>();
        try {
            long now = EcoLedger.now(level);
            helper.assertTrue(Ecosystem.health(level) == 1.0D && Ecosystem.incomeFactor(level) == 1.0D, "始めの健康が 100% でない");

            // ---- 数える殺し手
            Player player = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
            Mob frog = EntityType.FROG.create(level);
            frog.moveTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
            frog.addTag(Ecosystem.STUB_TAG_PREY);
            level.addFreshEntity(frog);
            made.add(frog);
            helper.assertTrue(Ecosystem.critterKind(frog) == Ecosystem.PREY, "印の付いたカエルが獲物の環境生物にならない");
            Mob plainFrog = EntityType.FROG.create(level);
            helper.assertTrue(Ecosystem.critterKind(plainFrog) == Ecosystem.NONE, "印の無いカエルまで獲物の環境生物になる");
            plainFrog.discard();
            helper.assertTrue(Ecosystem.counted(level.damageSources().playerAttack(player)), "プレイヤーの殺しを数えない");
            helper.assertTrue(!Ecosystem.counted(level.damageSources().fall()) && !Ecosystem.counted(level.damageSources().mobAttack(frog)), "落下・Mob の殺しを数える");
            frog.hurt(level.damageSources().playerAttack(player), 1000.0F);
            helper.assertTrue(!frog.isAlive(), "カエルが倒れない");
            helper.assertTrue(Math.abs(ledger.heads(Ecosystem.PREY, ocx, ocz, now) - (t.fullPrey - 1)) < 1e-6 && ledger.kills(Ecosystem.PREY) >= 1, "倒しても升が減らない");
            helper.assertTrue(Ecosystem.health(level) < 1.0D, "倒しても多様性が下がらない");
            // 落下で死んだ獲物の環境生物は数えない
            long killsBefore = ledger.kills(Ecosystem.PREY);
            Mob frog2 = EntityType.FROG.create(level);
            frog2.moveTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
            frog2.addTag(Ecosystem.STUB_TAG_PREY);
            level.addFreshEntity(frog2);
            made.add(frog2);
            frog2.hurt(level.damageSources().fall(), 1000.0F);
            helper.assertTrue(ledger.kills(Ecosystem.PREY) == killsBefore, "落下で死んだ獲物の環境生物を数えた");

            // ---- 内側の守り: 5×5 の過繁茂の真ん中は内側、縁は内側でない
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    ChunkPos p = new ChunkPos(ocx + dx, ocz + dz);
                    if (data.adoptOvergrownChunk(level, p)) added.add(p);
                }
            }
            helper.assertTrue(EcoSpawns.inside(data, ocx, ocz) && !EcoSpawns.inside(data, ocx + 2, ocz) && !EcoSpawns.inside(data, ocx + 5, ocz),
                    "内側の見分けが違う(真ん中は内側・縁と外は内側でない)");
            DirectorGates.setPhaseForTests(5);
            DirectorGates.setStageForTests(PhaseStage.PEAK);
            ledger.reset();
            double capBefore = ForestDirector.cap(level); // 面積(過繁茂のチャンク)を足した後に測る。ここから変わるのは健康だけ
            helper.assertTrue(EcoSpawns.innerCap(level, 2) == 0 && EcoSpawns.innerCap(level, 3) == 0, "健康 100% で内側の守りが開いている");
            helper.assertTrue(EcoSpawns.tryInner(level, origin.getX(), origin.getZ(), RandomSource.create(1L)) == EcoSpawns.Miss.HEALTHY, "健康なのに守りを試した");
            // 殺しすぎる: 多様性をしきい値の半分まで下げる(この升で獲物の環境生物を倒す)
            int need = (int) Math.ceil((1.0D - t.innerThreshold / 2.0D) * t.scale / t.weightPrey);
            for (int i = 0; i < need; i++) ledger.kill(Ecosystem.PREY, ocx, ocz, EcoLedger.now(level));
            double h = Ecosystem.health(level);
            helper.assertTrue(h <= t.innerThreshold / 2.0D + 1e-6, "多様性が下がりきらない: " + h);
            int capG2 = EcoSpawns.innerCap(level, 2), capG3 = EcoSpawns.innerCap(level, 3);
            double f = EcoSpawns.defenseFactor(h, t.innerThreshold);
            helper.assertTrue(capG2 == (int) Math.round(t.innerCapG2 * f) && capG3 == (int) Math.round(t.innerCapG3 * f) && capG2 > 0,
                    "内側の上限が設計値と違う: G2 " + capG2 + "・G3 " + capG3);
            // 予算: 倍率は健康どおり・上限は変わらない
            helper.assertTrue(Math.abs(Ecosystem.incomeFactor(level) - EcoLedger.incomeFactor(h, t.incomeFloor)) < 1e-9, "予算の倍率が健康どおりでない");
            helper.assertTrue(Math.abs(ForestDirector.cap(level) - capBefore) < 1e-9, "健康で上限が変わった");
            // 減った升を優先して選ぶ(この升はプレイヤーの位置から見て 24 ブロックより遠い所にあるとして、遠くから見る)
            long chunk = EcoSpawns.pickChunk(level, origin.getX() + 200, origin.getZ(), 24, 300, RandomSource.create(3L));
            long cell = EcoLedger.cellOf(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), t.cellShift);
            helper.assertTrue(cell == EcoLedger.cellOf(ocx, ocz, t.cellShift), "減った升を選ばない");
            // 内側でないチャンクには湧かない
            helper.assertTrue(EcoSpawns.tryInnerAt(level, ocx + 2, ocz, new int[4], capG2, capG3, RandomSource.create(1L)) == EcoSpawns.Miss.NOT_INSIDE, "縁に内側の守りが湧こうとした");
            helper.assertTrue(EcoSpawns.tryInnerAt(level, ocx, ocz, new int[] {0, 0, capG2, capG3}, capG2, capG3, RandomSource.create(1L)) == EcoSpawns.Miss.FULL, "上限なのに湧こうとした");
            // 真ん中に湧く(置き場は乱数なので何回か)
            EcoSpawns.Miss last = null;
            RandomSource rr = RandomSource.create(9L);
            for (int i = 0; i < 40 && last != EcoSpawns.Miss.SPAWNED; i++) last = EcoSpawns.tryInnerAt(level, ocx, ocz, new int[4], capG2, capG3, rr);
            helper.assertTrue(last == EcoSpawns.Miss.SPAWNED, "殺しすぎても内側に守りが湧かない: " + last);
            // プレイヤーのまわりから選ぶ道(tryInner: 減った升を選ぶ → 置く)も通す
            last = null;
            for (int i = 0; i < 60 && last != EcoSpawns.Miss.SPAWNED; i++) last = EcoSpawns.tryInner(level, origin.getX() + 40, origin.getZ(), rr);
            helper.assertTrue(last == EcoSpawns.Miss.SPAWNED, "tryInner で内側に守りが湧かない: " + last);
            List<Mob> inner = level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(origin).inflate(160.0D, 256.0D, 160.0D),
                    m -> m.getTags().contains(EcoSpawns.INNER_TAG));
            made.addAll(inner);
            helper.assertTrue(!inner.isEmpty(), "湧いた守りが見つからない");
            int[] c = EcoSpawns.count(level, origin.getX(), origin.getZ(), 64.0D);
            helper.assertTrue(c[2] + c[3] >= 1, "湧いた守りが数えられない");
            // 仮の種: GameTest では湧かせてよい。倒した升(頭数 0)には湧かない
            helper.assertTrue(EcoSpawns.stubAllowed(level), "GameTest で仮の種が許されていない");
            for (int i = 0; i < t.fullHunter * 2; i++) ledger.kill(Ecosystem.HUNTER, ocx, ocz, EcoLedger.now(level));
            helper.assertTrue(ledger.heads(Ecosystem.PREY, ocx, ocz, EcoLedger.now(level)) == 0.0D && ledger.heads(Ecosystem.HUNTER, ocx, ocz, EcoLedger.now(level)) == 0.0D, "頭数が 0 にならない");
            EcoSpawns.Miss s = EcoSpawns.tryStubAt(level, ocx, ocz, new int[4], RandomSource.create(2L));
            helper.assertTrue(s == EcoSpawns.Miss.THIN || s == EcoSpawns.Miss.FULL, "頭数 0 の升に仮の種が湧こうとした: " + s);
            helper.succeed();
        } finally {
            for (Mob m : made) m.discard();
            for (ChunkPos p : added) data.forgetOvergrownChunk(p);
            DirectorGates.setPhaseForTests(phaseBefore);
            DirectorGates.setStageForTests(stageBefore);
            ledger.reset();
        }
    }
}
