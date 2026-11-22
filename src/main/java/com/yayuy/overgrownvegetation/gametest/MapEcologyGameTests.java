package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.Gson;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadTestHooks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.ecosystem.EcoTable;
import com.yayuy.overgrownvegetation.ecosystem.map.MapCapacity;
import com.yayuy.overgrownvegetation.ecosystem.map.MapEcology;
import com.yayuy.overgrownvegetation.ecosystem.map.MapLedger;
import com.yayuy.overgrownvegetation.ecosystem.map.MapSync;
import com.yayuy.overgrownvegetation.ecosystem.map.MapTable;
import com.yayuy.overgrownvegetation.infect.GenericInfection;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.field.GermMul;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 地図の上の生態系(2026-11-21 {@code ecosystem.map})のテスト。
 * <ul>
 *   <li>升の満員(草原・砂漠・村)と生き物の少ない升</li>
 *   <li>感染が進むと発芽のしきい値が下がる(倍率と、胞子の場の発芽)</li>
 *   <li>全部感染した升が過繁茂の印になり、読み込まれたら地形が変わる・知らせが来る</li>
 *   <li>生き物の少ない升はこの道を使わない</li>
 *   <li>読み込んだときに実物が台帳に合う(照らし合わせで消える)・実物の死が台帳へ返る</li>
 *   <li>保存と復元・升が数万あるときの重さ</li>
 * </ul>
 * 世界(台帳・過繁茂)を触るテストは自分だけのバッチ。座標は実行ごとにずらす(GameTest のワールドに台帳が残っても重ならない)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MapEcologyGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MapEcologyGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String WORLD_BATCH = "map_ecology_world";
    private static final String REAL_BATCH = "map_ecology_real";
    private static final int RUN_SALT = new java.util.Random().nextInt(1 << 16);
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();

    /** まだ誰も触っていない遠い升。 */
    private static long freshCell() {
        int base = 200_000 + (RUN_SALT % 4096) * 64 + NEXT.getAndIncrement() * 8; // 世界の境界(約 187 万チャンク)の内側
        return ChunkPos.asLong(base, -base);
    }

    private static Holder<Biome> biome(ServerLevel level, net.minecraft.resources.ResourceKey<Biome> key) {
        return level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
    }

    // ---------------------------------------------------------------- 満員

    /** 同梱の ecosystem.json の map 節が Java の既定値と同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void bundledJsonMatchesDefault(GameTestHelper h) {
        try (InputStream in = MapEcologyGameTests.class.getResourceAsStream("/overgrown_vegetation/design/ecosystem.json")) {
            h.assertTrue(in != null, "design/ecosystem.json が同梱されていない");
            EcoTable.Json j = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), EcoTable.Json.class);
            String a = MapTable.fromJson(j.map).describe(), b = MapTable.DEFAULT.describe();
            h.assertTrue(a.equals(b), "map 節が既定値と違う\njson: " + a + "\njava: " + b);
        } catch (java.io.IOException e) {
            h.fail(e.toString());
        }
        h.succeed();
    }

    /** 草原・砂漠・村の升の満員(設計値から引く)。海は 0。砂漠は生き物の少ない升。 */
    @GameTest(template = "gametest_empty7")
    public static void capacityByBiome(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MapTable t = DesignData.ecosystem().map;
        MapCapacity.Info plains = MapCapacity.infoFor(biome(level, Biomes.PLAINS), false, t);
        MapCapacity.Info desert = MapCapacity.infoFor(biome(level, Biomes.DESERT), false, t);
        MapCapacity.Info village = MapCapacity.infoFor(biome(level, Biomes.PLAINS), true, t);
        MapCapacity.Info ocean = MapCapacity.infoFor(biome(level, Biomes.DEEP_OCEAN), false, t);
        MapCapacity.Info forest = MapCapacity.infoFor(biome(level, Biomes.FOREST), false, t);
        int plainsWant = t.biomeFull.getOrDefault("minecraft:plains", t.defaultFull);
        int desertWant = t.biomeFull.getOrDefault("minecraft:desert", t.defaultFull);
        h.assertTrue(plains.full() == plainsWant && plains.villagers() == 0, "草原の満員 " + plains + " のはずが " + plainsWant);
        h.assertTrue(desert.full() == desertWant, "砂漠の満員 " + desert + " のはずが " + desertWant);
        h.assertTrue(village.full() == plainsWant + t.villageFull && village.villagers() == t.villageFull, "村の升 " + village);
        h.assertTrue(ocean.full() == 0, "海の満員が 0 でない: " + ocean);
        h.assertTrue(forest.full() == t.defaultFull, "森(表に無い・動物の湧きあり)が既定の満員でない: " + forest);
        h.assertTrue(desert.full() < t.barrenBelow && plains.full() >= t.barrenBelow, "生き物の少ない升の境(" + t.barrenBelow + ")が草原・砂漠を分けない");
        h.succeed();
    }

    // ---------------------------------------------------------------- 発芽のしきい値

    /** 感染の割合で倍率が下がり、胞子の場はその升だけしきい値未満の降り積もりで芽吹く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void infectionLowersGermThreshold(GameTestHelper h) {
        MapTable t = DesignData.ecosystem().map;
        h.assertTrue(MapEcology.germMul(0, t) == 1f, "割合 0 で倍率が 1 でない");
        float half = MapEcology.germMul(0.5, t), full = MapEcology.germMul(1.0, t);
        h.assertTrue(half < 1f && full < half && Math.abs(full - t.germMinMul) < 1e-6, "倍率が割合で下がらない: " + half + " / " + full);

        SporeField.Params p = SporeFieldTable.gameDefaults();
        int[] a = sproutDayWithMul(p, full, 3, 3), b = sproutDayWithMul(p, 1f, 3, 3);
        String info = "全部感染: " + a[0] + " 回目 / 感染なし: " + b[0] + " 回目(しきい値の " + (int) (100 * (full + 1) / 2) + "% の降り積もり)";
        OvergrownVegetation.LOGGER.info("[map ecology test] {}", info);
        h.assertTrue(a[0] > 0, "感染した升がしきい値の倍率で芽吹かない: " + info);
        h.assertTrue(b[0] < 0, "感染していない升がしきい値未満で芽吹いた: " + info);
        h.succeed();
    }

    /** 降り積もりを (倍率 + 1) / 2 × しきい値に保って雨の 2 日回す。芽吹いた回(芽吹かなければ −1)。 */
    private static int[] sproutDayWithMul(SporeField.Params p, float mul, int cx, int cz) {
        SporeField f = new SporeField(11L, p);
        Long2FloatOpenHashMap m = new Long2FloatOpenHashMap();
        m.put(ChunkPos.asLong(cx >> 2, cz >> 2), mul);
        f.germMul = new GermMul(m, 2);
        double keep = p.germThreshold * (mul + 1f) / 2f;
        PrevailingWind.Sample calm = new PrevailingWind.Sample();
        int steps = (int) (2 * PrevailingWind.DAY / p.stepTicks);
        for (int k = 1; k <= steps; k++) {
            double d = f.deposited(cx, cz);
            if (d < keep) f.depositPoint(cx, cz, keep - d);
            f.step(new SporeField.Conditions((long) k * p.stepTicks, true, false, false), calm);
            if (f.isCorrupted(cx, cz)) return new int[]{k};
        }
        return new int[]{-1};
    }

    // ---------------------------------------------------------------- 過繁茂

    /** 全部感染した升が過繁茂の印になり(16 チャンクとも台帳・書き換え待ち)、知らせが 1 回だけ来て、読み込まれたら地形が変わる。 */
    @GameTest(template = "gametest_empty7", batch = WORLD_BATCH, timeoutTicks = 400)
    public static void fullyInfectedCellBecomesOvergrown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        if (!MapEcology.active(level)) {
            h.fail("地図の上の生態系が止まっている(ピースフルか MOD の設定「動物や村人の感染」がオフ)");
            return;
        }
        long cell = freshCell();
        MapLedger ledger = MapLedger.get(level.getServer());
        ledger.putForTests(cell, 12, 0, 0, 0, 0, 0, MapLedger.NONE);
        List<Long> heard = new ArrayList<>();
        MapEcology.OvergrownListener l = (lv, k) -> heard.add(k);
        MapEcology.addOvergrownListener(l);
        try {
            MapEcology.InfectResult half = MapEcology.infect(level, cell, 10, 20L * 18); // 10 体 × 18 秒 = 6 頭
            MapEcology.CellView v = MapEcology.view(level, cell);
            h.assertTrue(half.infected() == 6 && !half.becameOvergrown() && !v.overgrown(), "半分で過繁茂になった・数が違う: " + half + " " + v);
            h.assertTrue(half.income() > 0 && sum(half.byType()) == 6, "稼ぎ・種類の数: " + half);
            h.assertTrue(Math.abs(v.ratio() - 0.5) < 1e-6 && v.germMul() < 1f, "割合・倍率: " + v);
            MapEcology.InfectResult rest = MapEcology.infect(level, cell, 50, 20L * 3600);
            v = MapEcology.view(level, cell);
            h.assertTrue(rest.infected() == 6 && rest.becameOvergrown() && v.overgrown() && v.healthy() < 1e-6, "全部感染で過繁茂にならない: " + rest + " " + v);
            h.assertTrue(heard.size() == 1 && heard.get(0) == cell, "知らせ: " + heard);
            MapEcology.InfectResult more = MapEcology.infect(level, cell, 50, 20L * 60);
            h.assertTrue(more.infected() == 0 && heard.size() == 1, "食べ終えた升でまだ感染する・知らせが二度来た");
            PhaseData phase = PhaseData.get(level);
            int s = DesignData.ecosystem().cellShift, n = 1 << s;
            int bx = ChunkPos.getX(cell) << s, bz = ChunkPos.getZ(cell) << s;
            for (int dx = 0; dx < n; dx++)
                for (int dz = 0; dz < n; dz++) {
                    ChunkPos cp = new ChunkPos(bx + dx, bz + dz);
                    h.assertTrue(phase.isOvergrownKey(cp.toLong()), "台帳に載っていない: " + cp);
                    h.assertTrue(LedgerSpreadTestHooks.isPending(level, cp), "書き換え待ちに無い: " + cp);
                }
            // 読み込まれたら地形が変わる(1 チャンクだけ、ゲームと同じ書き換えで)
            ChunkPos one = new ChunkPos(bx + 1, bz + 1);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(one.x + dx, one.z + dz);
            h.assertTrue(LedgerSpreadTestHooks.convertNow(level, one), "書き換えずに外した");
            BlockPos mid = one.getMiddleBlockPosition(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, one.getMiddleBlockX(), one.getMiddleBlockZ()));
            h.assertTrue(ModBiomes.isFullyOvergrown(level.getBiome(mid)), "読み込まれてもバイオームが過繁茂にならない");
        } finally {
            MapEcology.removeOvergrownListener(l);
            com.yayuy.overgrownvegetation.spore.field.SporeSprouts.clearForTests(level);
        }
        h.succeed();
    }

    private static int sum(Map<EntityType<?>, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 生き物の少ない升は感染が進まず、過繁茂の印も付かず、倍率も 1。 */
    @GameTest(template = "gametest_empty7", batch = WORLD_BATCH)
    public static void barrenCellIsNotUsed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        long cell = freshCell();
        MapTable t = DesignData.ecosystem().map;
        MapLedger.get(level.getServer()).putForTests(cell, Math.max(0, t.barrenBelow - 1), 0, 0, 0, 0, 0, MapLedger.NONE);
        MapEcology.InfectResult r = MapEcology.infect(level, cell, 50, 20L * 3600);
        MapEcology.CellView v = MapEcology.view(level, cell);
        h.assertTrue(r.infected() == 0 && !r.becameOvergrown(), "生き物の少ない升で感染が進んだ: " + r);
        h.assertTrue(v.barren() && !v.overgrown() && v.germMul() == 1f, "生き物の少ない升の様子: " + v);
        h.succeed();
    }

    // ---------------------------------------------------------------- 実物

    /** 台帳で半分感染した升に入ると、実物の牛の半分が消える(部隊に連れて行かれた。死なない・感染体にならない)。感染でない死は欠けになる。 */
    @GameTest(template = "gametest_empty7", batch = REAL_BATCH, timeoutTicks = 200)
    public static void realAnimalsFollowLedger(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        if (!MapEcology.active(level)) {
            h.fail("地図の上の生態系が止まっている(ピースフルか MOD の設定「動物や村人の感染」がオフ)");
            return;
        }
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        long cell = MapEcology.cellOf(at.getX() >> 4, at.getZ() >> 4);
        MapLedger ledger = MapLedger.get(level.getServer());
        List<Cow> cows = new ArrayList<>();
        for (int i = 0; i < 8; i++) cows.add(h.spawn(EntityType.COW, new BlockPos(1 + (i % 4), 2, 1 + (i / 4) * 2)));
        int realInCell = MapSyncTestView.count(level, cell);
        // 台帳: 満員 16・感染 8(まだ実物に反映していない)= 健康 8 → 8 ÷ (8 + 8) = 半分
        ledger.putForTests(cell, 16, 0, 8, 0, 0, 0, MapLedger.NONE);
        int done = MapSync.reconcile(level, cell, 1000);
        int want = (int) Math.round(realInCell * 0.5);
        // 消える(部隊に連れて行かれた): 死なない・感染体にならない・死体ブロックも無い
        long removed = cows.stream().filter(c -> c.isRemoved() && c.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED).count();
        long turned = cows.stream().filter(c -> GenericInfection.isTurned(c) || c.isDeadOrDying()).count();
        String info = "升の実物 " + realInCell + "・消した " + done + "・消えた牛 " + removed + "・死んだ/感染した牛 " + turned + "・のはず " + want;
        h.assertTrue(want >= 1 && done == want && removed <= done && removed >= 1 && turned == 0, info);
        h.assertTrue(MapSyncTestView.count(level, cell) == realInCell - done, "升の実物が消した分だけ減っていない(" + info + ")");
        MapLedger.Cell c = ledger.get(cell);
        h.assertTrue(c.synced == 8 && Math.abs(c.infected - 8) < 1e-6, "照らし合わせで台帳の感染が増えた・基準が進まない: 感染 " + c.infected + "・基準 " + c.synced + "(" + info + ")");
        // 2 回目は何もしない
        h.assertTrue(MapSync.reconcile(level, cell, 1000) == 0, "2 回目でまた消した");
        // 感染でない死 → 欠け +1(健康が 1 減る)
        double healthyBefore = MapEcology.view(level, cell).healthy();
        Cow alive = cows.stream().filter(LivingEntity::isAlive).filter(x -> !GenericInfection.isTurned(x))
                .filter(x -> MapEcology.cellOf(x.getBlockX() >> 4, x.getBlockZ() >> 4) == cell).findFirst().orElse(null);
        h.assertTrue(alive != null, "生きている牛が残っていない(" + info + ")");
        alive.hurt(level.damageSources().fellOutOfWorld(), 1000f);
        double healthyAfter = MapEcology.view(level, cell).healthy();
        h.assertTrue(Math.abs(healthyBefore - healthyAfter - 1) < 1e-6, "感染でない死で健康が 1 減らない: " + healthyBefore + " → " + healthyAfter);
        ledger.putForTests(cell, 16, 0, 0, 0, 0, 0, MapLedger.NONE); // 後片付け(この升はテストのたびに使う)
        h.succeed();
    }

    /** 升の中の数える実物の数(テストから)。 */
    private static final class MapSyncTestView {
        static int count(ServerLevel level, long cell) {
            int s = DesignData.ecosystem().cellShift, size = 16 << s;
            int x0 = ChunkPos.getX(cell) << (s + 4), z0 = ChunkPos.getZ(cell) << (s + 4);
            return level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(x0, level.getMinBuildHeight(), z0, x0 + size, level.getMaxBuildHeight(), z0 + size),
                    e -> e.isAlive() && MapSync.tracked(e) && !GenericInfection.isTurned(e)).size();
        }
    }

    // ---------------------------------------------------------------- 保存と重さ

    /** 書いて読むと同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void saveAndRestore(GameTestHelper h) {
        MapLedger a = MapLedger.createDetachedForTests();
        a.putForTests(ChunkPos.asLong(1, 2), 24, 0, 3.25, 1.5, 100L, 3, MapLedger.NONE);
        a.putForTests(ChunkPos.asLong(-5, 7), 16, 20, 36, 0, 0L, 30, MapLedger.DONE);
        a.putForTests(ChunkPos.asLong(9, -9), 16, 0, 16, 0, 0L, 16, MapLedger.PARTIAL);
        MapLedger b = MapLedger.load(a.write(new CompoundTag()));
        h.assertTrue(b.size() == 3, "数が違う: " + b.size());
        for (long k : new long[]{ChunkPos.asLong(1, 2), ChunkPos.asLong(-5, 7), ChunkPos.asLong(9, -9)}) {
            MapLedger.Cell x = a.get(k), y = b.get(k);
            h.assertTrue(y != null && x.animals == y.animals && x.villagers == y.villagers && x.infected == y.infected && x.synced == y.synced && x.state == y.state
                            && MapLedger.lackNow(x, 200L, 24000.0 * 10) == MapLedger.lackNow(y, 200L, 24000.0 * 10),
                    "升 " + new ChunkPos(k) + " が戻らない");
        }
        h.succeed();
    }

    /** 升が 5 万あるときの保存・読み込み・胞子の場への倍率づくり・1 升の読み書きの時間(ログに出す。上限はゆるく)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void manyCellsAreLight(GameTestHelper h) {
        MapLedger a = MapLedger.createDetachedForTests();
        java.util.Random r = new java.util.Random(5);
        int n = 50_000;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = ChunkPos.asLong(r.nextInt(4000) - 2000, r.nextInt(4000) - 2000);
            a.putForTests(keys[i], 16, r.nextInt(4) == 0 ? 20 : 0, r.nextInt(16), r.nextInt(4), r.nextInt(100000), r.nextInt(8), MapLedger.NONE);
        }
        long t0 = System.nanoTime();
        CompoundTag tag = a.write(new CompoundTag());
        long t1 = System.nanoTime();
        MapLedger b = MapLedger.load(tag);
        long t2 = System.nanoTime();
        MapTable t = DesignData.ecosystem().map;
        Long2FloatOpenHashMap m = new Long2FloatOpenHashMap();
        for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<MapLedger.Cell> e : b.entries()) {
            MapLedger.Cell c = e.getValue();
            if (c.infected > 0) m.put(e.getLongKey(), MapEcology.germMul(MapLedger.ratio(c), t));
        }
        GermMul g = new GermMul(m, 2);
        long t3 = System.nanoTime();
        double acc = 0;
        for (int i = 0; i < 100_000; i++) {
            MapLedger.Cell c = b.get(keys[i % n]);
            acc += MapLedger.healthy(c, 50_000L, 240000.0) + g.at(ChunkPos.getX(keys[i % n]) << 2, ChunkPos.getZ(keys[i % n]) << 2);
        }
        long t4 = System.nanoTime();
        String info = String.format(java.util.Locale.ROOT, "升 %d: 書く %.1f ms・読む %.1f ms・倍率づくり %.1f ms(%d 升)・10 万回の読み %.1f ms(%.0f)",
                b.size(), (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, g.size(), (t4 - t3) / 1e6, acc);
        OvergrownVegetation.LOGGER.info("[map ecology perf] {}", info);
        h.assertTrue(b.size() == a.size(), "読み込みで升が減った: " + info);
        h.assertTrue((t1 - t0) / 1e6 < 2000 && (t2 - t1) / 1e6 < 2000 && (t3 - t2) / 1e6 < 2000, "重すぎる: " + info);
        h.succeed();
    }
}
