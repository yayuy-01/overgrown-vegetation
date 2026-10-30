package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.network.EarthquakeShakePayload;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.quake.EarthquakeData;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * F1 の前の<b>前兆</b>(2026-10-24、地脈技術の 4 段目。Vault「序盤の遊び方」やること 9)。
 * プレイヤーに「侵攻が来る」とは知らせず、地脈の様子がおかしいことにだけ気付けるようにする。
 *
 * <p>前兆の間 = フェーズ 0・凍結していない・ワールドの経過(F1 の時刻の条件と同じ {@code getGameTime})が
 * 「地割れの発生日時 − {@code omen.leadTicks}」(既定 3 日 − 0.5 日 = 2.5 日)以上。F1 に入るまで続く
 * (実績がそろわず F1 が延びればそのまま続く)。開発用に {@code /ov phase omen true|false|auto} で強制できる。
 * 保存するのは強制の向き・始まった時刻・次の地鳴りの時刻だけで、前兆かどうかは毎 tick 求め直す。
 *
 * <p>中身(数値は phases.json の {@code global.omen}、全部仮):
 * <ul>
 *     <li>汲み上げ器の効率が {@link #pumpJitter} で 5 秒ごとに ±40% 揺れる・表示に警告({@link LeyPumpBlockEntity})。
 *     始まった瞬間と以後 10 分ごとに、動いている汲み上げ器の 32 ブロック以内のプレイヤーへアクションバーで知らせる。</li>
 *     <li>地脈の花がランダム tick でしおれ、前兆が終わると戻る({@link com.yayuy.overgrownvegetation.block.LeyFlowerBlock})。</li>
 *     <li>家畜が線から離れる({@link LeyOmenAvoidVeinGoal})。</li>
 *     <li>10〜20 分おきに、各プレイヤーの近くの線の上で軽い地鳴り(地割れの揺れの通信を小さく借りる。地形は壊さない)。</li>
 * </ul>
 * 前兆かどうかは {@link #isActive()}(オーバーワールドの tick で 1 回求めた値。ブロックや Goal から毎回読んでも軽い)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyOmen {

    /** 強制なし(経過とフェーズで決める)・強制オン・強制オフ。 */
    public enum Mode { AUTO, ON, OFF }

    private static volatile boolean active;
    private static volatile boolean samplesOpen;

    private LeyOmen() {
    }

    /** 今が前兆の間か(サーバー。オーバーワールドの tick ごとに求め直す)。 */
    public static boolean isActive() {
        return active;
    }

    /** 汲み上げ器の雫に未知の試料が混じる時期か(F1 の後も続く。強制オンでも開く)。 */
    public static boolean samplesOpen() {
        return samplesOpen;
    }

    /** F1 の時刻の条件(地割れの発生日時)から引いた、前兆の始まり。 */
    public static long startTicks(MinecraftServer server) {
        return Math.max(0L, WorldDifficultyData.phase1StartTicks(server) - DesignData.phases().omen().leadTicks());
    }

    /** 未知の試料が混じり始める時刻。 */
    public static long sampleStartTicks(MinecraftServer server) {
        return Math.max(0L, WorldDifficultyData.phase1StartTicks(server) - DesignData.phases().omen().sampleLeadTicks());
    }

    /** その時刻・データで前兆の間かを求める(保存した強制の向きを含む)。 */
    public static boolean computeActive(ServerLevel overworld) {
        Data data = Data.get(overworld);
        if (data.override == Mode.ON) {
            return true;
        }
        if (data.override == Mode.OFF) {
            return false;
        }
        PhaseData phase = PhaseData.get(overworld);
        return phase.getPhase() == 0 && !phase.isFrozen() && overworld.getGameTime() >= startTicks(overworld.getServer());
    }

    /** 強制の向きを変えて、すぐに求め直す(コマンド・GameTest)。 */
    public static void setOverride(ServerLevel overworld, Mode value) {
        Data data = Data.get(overworld);
        data.override = value;
        data.setDirty();
        refresh(overworld);
    }

    public static Mode override(ServerLevel overworld) {
        return Data.get(overworld).override;
    }

    /** 前兆が始まったゲーム内時刻(前兆でなければ −1)。 */
    public static long startedAt(ServerLevel overworld) {
        return Data.get(overworld).startedAt;
    }

    private static void refresh(ServerLevel overworld) {
        boolean now = computeActive(overworld);
        active = now;
        samplesOpen = now || overworld.getGameTime() >= sampleStartTicks(overworld.getServer());
    }

    /**
     * 汲み上げ器の効率に掛ける揺れ(1 ± {@code pumpJitter})。{@code pumpJitterTicks}(5 秒)ごとに、場所と時刻の窓から決まる値に引き直す
     * (保存しない。同じ窓なら同じ値)。
     */
    public static double pumpJitter(BlockPos pos, long gameTime) {
        PhaseTable.Omen o = DesignData.phases().omen();
        long window = Math.floorDiv(gameTime, o.pumpJitterTicks());
        long h = pos.asLong() * 0x9E3779B97F4A7C15L + window * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        double u = ((h >>> 11) * 0x1.0p-53) * 2.0D - 1.0D; // −1〜1
        return 1.0D + u * o.pumpJitter();
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        refresh(level);
        Data data = Data.get(level);
        long now = level.getGameTime();
        if (!active) {
            if (data.startedAt >= 0L) {
                data.startedAt = -1L;
                data.nextQuake = -1L;
                data.noticePeriod = -1L;
                data.setDirty();
            }
            return;
        }
        long start = TickBudget.begin();
        int done = 0;
        try {
            PhaseTable.Omen o = DesignData.phases().omen();
            if (data.startedAt < 0L) {
                data.startedAt = now;
                data.nextQuake = now + nextQuakeDelay(level.getRandom(), o);
                data.setDirty();
            }
            long period = (now - data.startedAt) / o.noticeTicks();
            if (period != data.noticePeriod) {
                data.noticePeriod = period;
                data.setDirty();
                done += noticeNearPumps(level, o.noticeRadius());
            }
            if (now >= data.nextQuake) {
                data.nextQuake = now + nextQuakeDelay(level.getRandom(), o);
                data.setDirty();
                done += lightQuake(level, o);
            }
        } finally {
            TickBudget.end(PerfJob.LEY_OMEN, start, done);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        active = false;
        samplesOpen = false;
    }

    private static long nextQuakeDelay(RandomSource random, PhaseTable.Omen o) {
        int min = Math.min(o.quakeMinTicks(), o.quakeMaxTicks());
        int max = Math.max(o.quakeMinTicks(), o.quakeMaxTicks());
        return Mth.randomBetweenInclusive(random, min, max);
    }

    /**
     * 動いている汲み上げ器から {@code radius} 以内のプレイヤーへ「地脈の流れが乱れている…」(アクションバー)。
     * 汲み上げ器はプレイヤーのまわりの読み込まれたチャンクのブロックエンティティから探す。知らせた人数を返す。
     */
    public static int noticeNearPumps(ServerLevel level, int radius) {
        int told = 0;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !hasRunningPumpNear(level, player.blockPosition(), radius)) {
                continue;
            }
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.ley_omen.notice")
                    .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC), true);
            told++;
        }
        return told;
    }

    static boolean hasRunningPumpNear(ServerLevel level, BlockPos center, int radius) {
        int r2 = radius * radius;
        int cr = (radius >> 4) + 1;
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dz = -cr; dz <= cr; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof LeyPumpBlockEntity pump && pump.isRunning()
                            && be.getBlockPos().distSqr(center) <= r2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 軽い地鳴り: 各プレイヤーの近く({@code quakeMinDistance}〜{@code quakeMaxDistance})の線の上を震源に、小さな揺れと低い音。
     * 地割れの揺れの通信を小さく借りる(地形は壊さない)。地割れの最中は送らない(クライアントの揺れは 1 つしか持てない)。
     * 近いプレイヤーの震源は 1 つにまとめる。起こした数を返す。
     */
    public static int lightQuake(ServerLevel level, PhaseTable.Omen o) {
        if (EarthquakeData.get(level).hasActive()) {
            return 0;
        }
        java.util.List<BlockPos> epicenters = new java.util.ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            boolean covered = false;
            for (BlockPos e : epicenters) {
                if (e.distToCenterSqr(player.position()) <= (double) o.quakeMaxDistance() * o.quakeMaxDistance()) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }
            BlockPos epicenter = findLinePointNear(level, player.blockPosition(), o.quakeMinDistance(), o.quakeMaxDistance(), level.getRandom());
            if (epicenter == null) {
                continue;
            }
            epicenters.add(epicenter);
            shake(level, epicenter, o);
        }
        return epicenters.size();
    }

    /** {@code origin} から min〜max ブロックの線の上の地表の点(無ければ null)。まずランダムに 24 点、だめなら最寄りの線の点。 */
    public static BlockPos findLinePointNear(ServerLevel level, BlockPos origin, int min, int max, RandomSource random) {
        long seed = level.getSeed();
        for (int i = 0; i < 24; i++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double d = min + random.nextDouble() * Math.max(0, max - min);
            int x = origin.getX() + Mth.floor(Math.cos(a) * d);
            int z = origin.getZ() + Mth.floor(Math.sin(a) * d);
            if (LeyVeinField.isVeinAt(seed, x, z)) {
                return surface(level, x, z);
            }
        }
        int[] p = LeyVeinField.nearestLinePoint(seed, origin.getX(), origin.getZ(), max);
        if (p == null) {
            return null;
        }
        double dist = Math.hypot(p[0] - origin.getX(), p[1] - origin.getZ());
        return dist <= max ? surface(level, p[0], p[1]) : null;
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static void shake(ServerLevel level, BlockPos at, PhaseTable.Omen o) {
        // 揺れの形: 立ち上がり 10 tick → 揺れ続け → 収まり 4 割。地割れの揺れは形成の 300 tick かけて最大へ寄せる作り
        // (ClientEarthquake の RAMP_TICKS)なので、短い揺れは最大まで届かない —— 届く高さで割って、quakeIntensity が本当の最大になるようにする。
        int total = o.quakeTicks();
        int pre = Math.min(10, total / 4);
        int fade = Math.max(1, total * 2 / 5);
        int form = Math.max(1, total - pre - fade);
        double peak = 0.30D + 0.70D * Math.min(1.0D, form / 300.0D);
        EarthquakeShakePayload payload = new EarthquakeShakePayload(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0,
                pre, form, fade, (float) (o.quakeIntensity() / peak), Math.max(32, o.quakeMaxDistance()));
        double reach = o.quakeMaxDistance() + 32.0D;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D) <= reach * reach) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
        // 低い音: 地割れの土砂崩れの音を低く(仮決め)。音量 3 = 48 ブロック先まで届く(遠いほど小さい)。
        level.playSound(null, at, ModSounds.EARTHQUAKE_FISSURE.get(), SoundSource.AMBIENT, 3.0F, 0.6F);
    }

    // ------------------------------------------------------------------ 保存

    /** 前兆の保存データ(オーバーワールド)。 */
    static final class Data extends SavedData {
        private static final String ID = "overgrown_vegetation_ley_omen";
        static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, (tag, registries) -> load(tag), null);

        Mode override = Mode.AUTO;
        long startedAt = -1L;
        long nextQuake = -1L;
        long noticePeriod = -1L;

        static Data get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
        }

        private static Data load(CompoundTag tag) {
            Data d = new Data();
            int o = tag.getInt("override");
            d.override = o >= 0 && o < Mode.values().length ? Mode.values()[o] : Mode.AUTO;
            d.startedAt = tag.contains("startedAt") ? tag.getLong("startedAt") : -1L;
            d.nextQuake = tag.contains("nextQuake") ? tag.getLong("nextQuake") : -1L;
            d.noticePeriod = tag.contains("noticePeriod") ? tag.getLong("noticePeriod") : -1L;
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("override", override.ordinal());
            tag.putLong("startedAt", startedAt);
            tag.putLong("nextQuake", nextQuake);
            tag.putLong("noticePeriod", noticePeriod);
            return tag;
        }
    }
}
