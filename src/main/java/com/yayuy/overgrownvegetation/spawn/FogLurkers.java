package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 怖い現象 E「霧の中のシルエット」の「たまに本物」(2026-11-16 ユーザー了承。正本は Vault「怖い現象 A・D・E(実装の記録)」)。
 * 見た目だけの偽の影はクライアント(eerie-look)。こちらはサーバーが本物の G5 を出す。
 * <ul>
 *   <li>出る時: 濃霧(度合い {@code minFog} 以上)・昼と朝・G5 のフェーズ(司令塔の gates の waveG5)から・汚染地帯の中(プレイヤーのいるチャンクが過繁茂の台帳)・
 *   ピースフルでない・{@code doMobSpawning}。</li>
 *   <li>頻度: その状態の間、プレイヤーごとに平均 {@code meanSeconds} 秒に 1 回(偽の影の平均 90 秒の 10 倍 = 偽 10 回に本物 1 回)。同時に 1 体まで。</li>
 *   <li>場所: 見通しの縁({@code minBlocks}〜{@code maxBlocks})の、木の幹のそばの地面。プレイヤーの方を向いて立つ。</li>
 *   <li>止まり方: 種のクラスに手を入れず NoAI で立ち止まらせる。プレイヤーが {@code wakeBlocks} まで近づくか、攻撃されると NoAI を外し、ふつうの敵対に戻る。</li>
 *   <li>濃霧が晴れたら(または昼と朝を過ぎたら)、まだ動いていない本物は消える(去る)。</li>
 * </ul>
 * 数は {@code spawns.json} の {@code fogLurker}(どれも【仮】)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FogLurkers {

    /** 立ち止まっている本物の印。 */
    public static final String TAG = "ov_fog_lurker";
    /** 確かめ用の命令({@code /ov perf spawns lurker})で出した印。霧が晴れても去らない(近づけば動き出す)。 */
    public static final String MANUAL_TAG = "ov_fog_lurker_manual";
    /** 動き出した後の印(片付けの対象から外れる。ふつうの敵)。 */
    public static final String WOKE_TAG = "ov_fog_lurker_woke";

    private static final int INTERVAL = 20;
    private static final Set<Mob> LURKERS = Collections.newSetFromMap(new WeakHashMap<>());

    /** テスト用: 濃霧の度合いと時刻の判定を差し替える(null で戻す)。 */
    @Nullable
    public static Double fogOverride;
    public static boolean tickInGameTests;

    /** 確かめ用: これまでに出した数・動き出した数・去った数。 */
    public static long spawned, woke, left;

    private FogLurkers() {
    }

    /** 今の濃霧の度合い(0〜1)。 */
    public static double fog(ServerLevel level) {
        if (fogOverride != null) return fogOverride;
        MinecraftServer server = level.getServer();
        WindWeatherData wx = WindWeatherData.get(server);
        return PrevailingWind.fogLevel(WindWeatherData.windSeed(server), level.getDayTime(), wx.weather(), null);
    }

    /** 昼と朝か(dayTime 23000〜24000・0〜12000。偽の影と同じ)。 */
    public static boolean dayOrMorning(long dayTime) {
        long tod = Math.floorMod(dayTime, 24000L);
        return tod >= 23000L || tod < 12000L;
    }

    /** 本物を出してよい状態か(プレイヤーの場所を問わない部分)。 */
    public static boolean conditions(ServerLevel level) {
        SpawnTable.FogLurker f = DesignData.spawns().fogLurker;
        if (!f.enabled || level.dimension() != Level.OVERWORLD || level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return false;
        if (!DirectorGates.open(level, DirectorGates.Gate.WAVE_G5)) return false;
        if (fogOverride == null && !dayOrMorning(level.getDayTime())) return false;
        return fog(level) >= f.minFog;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tickInGameTests && level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        if (level.getGameTime() % INTERVAL != 0) return;
        boolean ok = conditions(level);
        tickLurkers(level, ok);
        if (!ok) return;
        SpawnTable.FogLurker f = DesignData.spawns().fogLurker;
        double p = INTERVAL / (f.meanSeconds * 20.0D);
        RandomSource random = level.getRandom();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || hasLurkerNear(player, f.maxBlocks * 2.0D)) continue;
            if (random.nextDouble() >= p) continue;
            trySpawn(level, player, random);
        }
    }

    /** 立ち止まっている本物の見回り: 近づかれたら動き出す。霧が晴れたら(conditions が false)去る。 */
    public static void tickLurkers(ServerLevel level, boolean conditions) {
        double wake = DesignData.spawns().fogLurker.wakeBlocks;
        for (Mob m : List.copyOf(LURKERS)) {
            if (m.isRemoved() || !m.getTags().contains(TAG)) {
                LURKERS.remove(m);
                continue;
            }
            if (m.level() != level) continue;
            if (!conditions && !m.getTags().contains(MANUAL_TAG)) { // 確かめ用の命令で出したものは、条件を見ずに残す(見比べやすいように。11-17 eerie-look の勧め)
                m.discard();
                LURKERS.remove(m);
                left++;
                continue;
            }
            for (ServerPlayer p : level.players()) {
                if (!p.isSpectator() && !p.isCreative() && p.distanceToSqr(m) <= wake * wake) {
                    wakeUp(m);
                    break;
                }
            }
        }
    }

    /** 動き出す(NoAI を外し、ふつうの敵対へ)。 */
    public static void wakeUp(Mob m) {
        if (!m.getTags().contains(TAG)) return;
        m.removeTag(TAG);
        m.addTag(WOKE_TAG);
        m.setNoAi(false);
        LURKERS.remove(m);
        woke++;
    }

    /** 保存から戻った、立ち止まっている本物も見回りに入れる。 */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob m && m.getTags().contains(TAG)) LURKERS.add(m);
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Mob m && m.getTags().contains(TAG)) wakeUp(m);
    }

    private static boolean hasLurkerNear(ServerPlayer player, double r) {
        for (Mob m : LURKERS) {
            if (!m.isRemoved() && m.level() == player.level() && m.distanceToSqr(player) <= r * r) return true;
        }
        return false;
    }

    /** そのプレイヤーの見通しの縁に 1 体出す(汚染地帯の中だけ)。出せたらその Mob。 */
    @Nullable
    public static Mob trySpawn(ServerLevel level, ServerPlayer player, RandomSource random) {
        // 地下では出さない(偽の影と同じ。11-16 eerie-look とそろえた)
        if (!level.canSeeSky(player.blockPosition()) && player.getY() < level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ()) - 8) return null;
        Mob m = trySpawn(level, player.getX(), player.getZ(), random, player.getEyePosition());
        return m;
    }

    /** (x, z) から見た見通しの縁に 1 体出す(GameTest からも呼ぶ)。 */
    @Nullable
    public static Mob trySpawn(ServerLevel level, double x, double z, RandomSource random) {
        return trySpawn(level, x, z, random, null);
    }

    /** eye が null でなければ、その目から見通せる所だけ(偽の影と同じ)。 */
    @Nullable
    public static Mob trySpawn(ServerLevel level, double x, double z, RandomSource random, @Nullable net.minecraft.world.phys.Vec3 eye) {
        SpawnTable.FogLurker f = DesignData.spawns().fogLurker;
        PhaseData data = PhaseData.get(level);
        if (!data.isOvergrownKey(ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4))) return null; // 汚染地帯の中だけ
        EntityType<?> type = pickType(random);
        if (type == null) return null;
        for (int i = 0; i < f.tries; i++) {
            double ang = random.nextDouble() * Math.PI * 2.0D;
            double d = f.minBlocks + random.nextDouble() * (f.maxBlocks - f.minBlocks);
            BlockPos feet = besideTrunk(level, Mth.floor(x + Math.cos(ang) * d), Mth.floor(z + Math.sin(ang) * d));
            if (feet == null || !data.isOvergrownKey(ChunkPos.asLong(feet.getX() >> 4, feet.getZ() >> 4))) continue;
            if (eye != null && level.clip(new net.minecraft.world.level.ClipContext(eye, new net.minecraft.world.phys.Vec3(feet.getX() + 0.5D, feet.getY() + 1.5D, feet.getZ() + 0.5D),
                    net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType()
                    != net.minecraft.world.phys.HitResult.Type.MISS) continue; // 目から見通せる所だけ
            Mob m = EnemySpawns.create(level, type, feet, MobSpawnType.EVENT, random);
            if (m == null) continue;
            if (!com.yayuy.overgrownvegetation.director.ForestEconomy.payNatural(level, m)) { // 11-20: 霧の中の本物も森の予算を払う
                m.discard();
                return null;
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(z - (feet.getZ() + 0.5D), x - (feet.getX() + 0.5D))) - 90.0D);
            m.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
            m.setYHeadRot(yaw);
            m.setYBodyRot(yaw);
            m.setNoAi(true);
            m.addTag(TAG);
            level.addFreshEntity(m);
            LURKERS.add(m);
            spawned++;
            return m;
        }
        return null;
    }

    /** (x, z) のまわり 3 ブロックで、原木の幹に接した地面(立てる所)。無ければ null。 */
    @Nullable
    static BlockPos besideTrunk(ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos feet = TreeCanopyGroundFinder.findSpawnSurface(level, x + dx, z + dz);
                if (feet == null || !level.getFluidState(feet).isEmpty()) continue;
                if (cramped(level, feet)) continue; // まわりの升が塞がっていると、体の幅のある種がはまる
                // 幹は 1〜2 ブロック先(体の幅が 1 ブロックを超える種は、幹に接した升だとはまるので 2 ブロック先も見る)
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    for (int k = 1; k <= 2; k++) {
                        BlockPos side = feet.relative(d, k);
                        if (level.getBlockState(side).is(BlockTags.LOGS) || level.getBlockState(side.above()).is(BlockTags.LOGS)) return feet;
                    }
                }
            }
        }
        return null;
    }

    /** 足元の升の、まわり 4 方向(足と頭の高さ)に動きを止めるブロックがあるか。 */
    private static boolean cramped(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = feet.relative(d);
            if (level.getBlockState(s).blocksMotion() || level.getBlockState(s.above()).blocksMotion()) return true;
        }
        return false;
    }

    @Nullable
    private static EntityType<?> pickType(RandomSource random) {
        SpawnTable.FogLurker f = DesignData.spawns().fogLurker;
        double total = 0.0D;
        for (double w : f.weights.values()) total += w;
        double r = random.nextDouble() * total;
        for (var e : f.weights.entrySet()) {
            r -= e.getValue();
            if (r <= 0.0D) {
                net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(e.getKey());
                return rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
            }
        }
        return null;
    }

    /** テスト用: 置いた個体を見回りに入れる(湧かせた後に印を付けたとき)。 */
    public static void onJoinForTests(Mob m) {
        if (m.getTags().contains(TAG)) LURKERS.add(m);
    }

    /** テスト用: 立ち止まっている本物を全部忘れる(個体は残す)。 */
    public static void clearForTests() {
        LURKERS.clear();
    }
}
