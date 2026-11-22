package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * G2・G3 の Mob のサーバーの重さを測る(2026-11-16、コードの見直し 第 3 便)。<b>ふだんの GameTest には入らない</b>:
 * 環境変数 {@code OV_BENCH} があるときだけ登録する。
 *
 * <p>遠い決まった場所(チャンク x 1,700,000〜)に種ごとの 48 × 48 の平らな区画を並べ、各種を 25・50・100 体
 * (グレガリアは 100・200・400 体)ずつ湧かせる。狙う相手に動かないハスク(無敵)を区画あたり 体数 / 10(4 以上)置く。
 * 湧かせて 40 tick 後から 100 tick の間、その個体の tick にかかった時間だけを {@link EntityTickEvent} の前後で数え、
 * 「1 体 1 tick あたりの µs」をログに {@code OV_BENCH} の行で出す。GameTest のサーバーでは {@code MobThinkThrottle} が
 * 間引かないので、遊ぶときより重く出る(前後の比べに使う)。</p>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MobPerfBench {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            if (System.getenv("OV_BENCH") != null) event.register(MobPerfBench.class);
        }

        private Registration() {
        }
    }

    private MobPerfBench() {
    }

    static final String TAG = "ov_bench";
    static final String[] TYPES = {"pilimnes", "venamnes", "clipeimnes", "saltimnes", "falcimnes", "acumnes", "vesicaria", "strobila",
            "velophyll", "seminaria", "gregaria"};
    static final int[] COUNTS = {25, 50, 100};
    static final int CX0 = 1_700_000, CZ0 = 1_700_000, FLOOR = 99, AREA = 48;
    static final int WARM = 40, MEASURE = 100, PHASE = 160, START = 400;

    // ---- 数える(サーバーのスレッドだけ)
    private static boolean measuring;
    private static long startNs;
    private static final Map<EntityType<?>, long[]> ACC = new LinkedHashMap<>();

    @EventBusSubscriber(modid = OvergrownVegetation.MODID)
    public static final class Timing {
        private Timing() {
        }

        @SubscribeEvent
        public static void pre(EntityTickEvent.Pre e) {
            if (measuring && e.getEntity().getTags().contains(TAG)) startNs = System.nanoTime();
        }

        @SubscribeEvent
        public static void post(EntityTickEvent.Post e) {
            if (!measuring || !e.getEntity().getTags().contains(TAG)) return;
            long[] a = ACC.computeIfAbsent(e.getEntity().getType(), k -> new long[3]);
            long d = System.nanoTime() - startNs;
            a[0] += d;
            a[1]++;
            a[2] = Math.max(a[2], d);
        }
    }

    private static int countFor(String id, int phase) {
        return id.equals("gregaria") ? COUNTS[phase] * 4 : COUNTS[phase];
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = START + PHASE * 3 + 100)
    public static void measureMobServerCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> spawned = new ArrayList<>();
        helper.runAtTickTime(1, () -> {
            com.yayuy.overgrownvegetation.entity.Watched.benchFar = true;   // 見た目の計算は遊ぶときと同じく、近くのプレイヤーで決める(ここにはいない)
            for (int i = 0; i < TYPES.length; i++) {
                int cx = CX0 + i * 4;
                for (int dx = -1; dx <= 3; dx++) for (int dz = -1; dz <= 3; dz++) level.setChunkForced(cx + dx, CZ0 + dz, true);
            }
        });
        helper.runAtTickTime(20, () -> {
            BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
            for (int i = 0; i < TYPES.length; i++) {
                int x0 = (CX0 + i * 4) * 16, z0 = CZ0 * 16;
                StiltGameTests.fill(level, x0 - 4, FLOOR + 1, z0 - 4, x0 + AREA + 3, FLOOR + 24, z0 + AREA + 3, air);
                StiltGameTests.fill(level, x0 - 4, FLOOR, z0 - 4, x0 + AREA + 3, FLOOR, z0 + AREA + 3, stone);
            }
        });
        for (int p = 0; p < COUNTS.length; p++) {
            final int phase = p;
            int t0 = START + p * PHASE;
            helper.runAtTickTime(t0, () -> {
                Random rnd = new Random(1234 + phase);
                for (int i = 0; i < TYPES.length; i++) {
                    BlockPos c = new BlockPos((CX0 + i * 4) * 16 + AREA / 2, FLOOR + 1, CZ0 * 16 + AREA / 2);
                    if (!level.isPositionEntityTicking(c)) OvergrownVegetation.LOGGER.warn("OV_BENCH 区画 {} がまだ動いていない(数えた値は少なめに出る)", TYPES[i]);
                }
                for (int i = 0; i < TYPES.length; i++) {
                    EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, TYPES[i]));
                    int x0 = (CX0 + i * 4) * 16, z0 = CZ0 * 16;
                    int n = countFor(TYPES[i], phase);
                    for (int k = 0; k < n; k++) {
                        Entity e = type.create(level);
                        if (e == null) break;
                        e.moveTo(x0 + 2 + rnd.nextDouble() * (AREA - 4), FLOOR + 1, z0 + 2 + rnd.nextDouble() * (AREA - 4), rnd.nextFloat() * 360.0F, 0.0F);
                        e.addTag(TAG);
                        if (e instanceof Mob m) m.setPersistenceRequired();
                        level.addFreshEntity(e);
                        spawned.add(e);
                    }
                    for (int k = 0; k < Math.max(4, n / 10); k++) {
                        Husk h = EntityType.HUSK.create(level);
                        if (h == null) break;
                        h.moveTo(x0 + 4 + rnd.nextDouble() * (AREA - 8), FLOOR + 1, z0 + 4 + rnd.nextDouble() * (AREA - 8), 0.0F, 0.0F);
                        h.setNoAi(true);
                        h.setInvulnerable(true);
                        h.setPersistenceRequired();
                        level.addFreshEntity(h);
                        spawned.add(h);
                    }
                }
            });
            helper.runAtTickTime(t0 + WARM, () -> {
                ACC.clear();
                measuring = true;
                long alive = spawned.stream().filter(e -> e.isAlive() && e.getTags().contains(TAG)).count();
                OvergrownVegetation.LOGGER.info("OV_BENCH 段 {} を数え始める(tick {}・生きている {} 体・湧かせた {})", phase, helper.getTick(), alive, spawned.size());
            });
            helper.runAtTickTime(t0 + WARM + MEASURE, () -> {
                measuring = false;
                OvergrownVegetation.LOGGER.info("OV_BENCH 段 {} を数え終える(tick {}・種 {})", phase, helper.getTick(), ACC.size());
                for (Map.Entry<EntityType<?>, long[]> en : ACC.entrySet()) {
                    long[] a = en.getValue();
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(en.getKey()).getPath();
                    OvergrownVegetation.LOGGER.info(String.format(Locale.ROOT, "OV_BENCH %-11s 体数 %4d  1 体 1 tick %7.2f µs  合計 %7.3f ms/tick  最大 %6.2f ms",
                            id, countFor(id, phase), a[0] / 1e3 / Math.max(1, a[1]), a[0] / 1e6 / MEASURE, a[2] / 1e6));
                }
                for (Entity e : spawned) e.discard();
                spawned.clear();
            });
        }
        helper.runAtTickTime(START + PHASE * COUNTS.length + 10, () -> {
            com.yayuy.overgrownvegetation.entity.Watched.benchFar = false;
            for (int i = 0; i < TYPES.length; i++) {
                int cx = CX0 + i * 4;
                for (int dx = -1; dx <= 3; dx++) for (int dz = -1; dz <= 3; dz++) level.setChunkForced(cx + dx, CZ0 + dz, false);
            }
            helper.succeed();
        });
    }
}
