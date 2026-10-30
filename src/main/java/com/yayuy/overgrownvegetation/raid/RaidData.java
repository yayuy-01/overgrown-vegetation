package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * レイド(過繫茂の苗床イベント)の進行状況(ワールド単位)を保持する {@link SavedData}。
 * {@link PhaseData} と同じくオーバーワールドの {@code DimensionDataStorage} に紐付く。
 *
 * <ul>
 *     <li>{@code lastRaidPhase} … フェーズ昇格レイドを既に発生させた最大フェーズ。
 *     現在フェーズがこれを上回った瞬間に「昇格レイド」を1回発生させる。</li>
 *     <li>{@code nextPeriodicGameTime} … 次の周期レイドを発生させるゲーム内時刻。</li>
 *     <li>{@code activeRaids} … 進行中のレイド一覧(苗床エンティティ UUID・出現座標・開始時刻)。</li>
 * </ul>
 *
 * <p>実際の発生・場所抽選・エンティティ生成は {@link RaidController}、tick からの呼び出しは
 * {@link RaidTicker} が行う。
 */
public final class RaidData extends SavedData {

    private static final String ID = "overgrown_vegetation_raid";

    public static final SavedData.Factory<RaidData> FACTORY =
            new SavedData.Factory<>(RaidData::new, (tag, registries) -> load(tag), null);

    /**
     * 進行中の1レイド。{@code forcedChunks} はこのレイドが {@link RaidChunkLoader} で強制ロードした
     * チャンクキーの一覧(レイド終了時にこの分だけ解除する)。
     */
    public record ActiveRaid(UUID nexusId, BlockPos origin, long startGameTime, long[] forcedChunks) {
    }

    private int lastRaidPhase;
    private long nextPeriodicGameTime = -1L;
    private final List<ActiveRaid> activeRaids = new ArrayList<>();

    private RaidData() {
    }

    public static RaidData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static RaidData load(CompoundTag tag) {
        RaidData data = new RaidData();
        data.lastRaidPhase = tag.getInt("lastRaidPhase");
        data.nextPeriodicGameTime = tag.contains("nextPeriodicGameTime") ? tag.getLong("nextPeriodicGameTime") : -1L;
        ListTag list = tag.getList("activeRaids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = entry.getUUID("nexusId");
            BlockPos origin = NbtUtils.readBlockPos(entry, "origin").orElse(BlockPos.ZERO);
            long start = entry.getLong("startGameTime");
            long[] forced = entry.contains("forcedChunks") ? entry.getLongArray("forcedChunks") : new long[0];
            data.activeRaids.add(new ActiveRaid(id, origin, start, forced));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("lastRaidPhase", lastRaidPhase);
        tag.putLong("nextPeriodicGameTime", nextPeriodicGameTime);
        ListTag list = new ListTag();
        for (ActiveRaid raid : activeRaids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("nexusId", raid.nexusId());
            entry.put("origin", NbtUtils.writeBlockPos(raid.origin()));
            entry.putLong("startGameTime", raid.startGameTime());
            entry.putLongArray("forcedChunks", raid.forcedChunks());
            list.add(entry);
        }
        tag.put("activeRaids", list);
        return tag;
    }

    public List<ActiveRaid> getActiveRaids() {
        return activeRaids;
    }

    public int getActiveRaidCount() {
        return activeRaids.size();
    }

    /** {@link RaidController} が新しいレイドを開始したときに呼ぶ。 */
    public void addRaid(ActiveRaid raid) {
        activeRaids.add(raid);
        setDirty();
    }

    /**
     * 苗床エンティティが討伐 / discard されたときに {@link com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity}
     * から呼ばれる。該当レイドを一覧から外す。
     *
     * <p>そのビーコンのウェーブで湧いた個体の永続もここで外す(2026-09-24、{@link EventMobs})。
     * 台帳に載っていないビーコン(コマンドで直に出したもの)のウェーブも同じ。
     */
    public void onNexusRemoved(ServerLevel overworld, UUID nexusId, boolean killed) {
        EventMobs.releaseOrigin(overworld.getServer(), EventMobs.Kind.NEXUS, nexusId.toString());
        boolean removed = false;
        for (var it = activeRaids.iterator(); it.hasNext(); ) {
            ActiveRaid raid = it.next();
            if (raid.nexusId().equals(nexusId)) {
                RaidChunkLoader.release(overworld, raid.forcedChunks());
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            setDirty();
            if (killed) {
                overworld.getServer().getPlayerList().broadcastSystemMessage(
                        Component.translatable("message.overgrown_vegetation.raid.ended"), false);
            }
        }
    }

    /**
     * オーバーワールドの tick から定期的に呼ぶ({@link RaidTicker})。フェーズ昇格レイドと
     * 周期レイドの発生判定、および終了済みレイドの掃除を行う。
     */
    public void tick(ServerLevel overworld) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        // 終わったレイドの掃除(強制ロードとウェーブの個体の永続の解除)は、レイドを設定で切っていても行う
        // (2026-09-24、報告 1967)。以前は raidEnabled の判定より後ろにあったので、切ると解除されずに残った。
        pruneFinishedRaids(overworld);
        // 強制ロードの範囲を今の設定まで縮める(2026-09-25、報告 1097)。これもレイドを切っていても行う。
        shrinkForcedAreas(overworld);
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(overworld)) {
            // ピースフル(difficulty/Peaceful、2026-09-26): 新しいレイドは始めず、周期レイドの時計も止める(進めたままだと、
            // ピースフルを抜けた途端に溜まったレイドが始まる)。進行中のレイドはビーコンが Peaceful に消され、onNexusRemoved が
            // 台帳から外す。ここで stopAllRaids は呼ばない —— まだ読み込まれていないビーコンの台帳を捨てると強制ロードが外れ、
            // ビーコンだけがチャンクに取り残される(強制ロードのままなら、読み込まれた tick に Peaceful が消す)。
            if (nextPeriodicGameTime > overworld.getGameTime()) {
                nextPeriodicGameTime += RaidTicker.INTERVAL;
                setDirty();
            }
            return;
        }
        if (!cfg.raidEnabled.get()) {
            return;
        }

        int phase = PhaseData.get(overworld).getPhase();
        if (phase < com.yayuy.overgrownvegetation.data.DesignData.phases().raidMinPhase()) {
            return;
        }

        long now = overworld.getGameTime();

        // フェーズ昇格レイド: 現在フェーズが「発生済み最大フェーズ」を上回っていれば1回発生させる。
        if (lastRaidPhase < phase) {
            tryPromotionRaid(overworld, phase, now);
            return;
        }

        // 周期レイド。
        if (nextPeriodicGameTime < 0L) {
            scheduleNextPeriodic(overworld, now);
            setDirty();
            return;
        }
        if (now >= nextPeriodicGameTime) {
            if (activeRaids.size() < cfg.raidMaxConcurrent.get() && RaidController.tryStartRaid(overworld, null)) {
                scheduleNextPeriodic(overworld, now);
            } else {
                nextPeriodicGameTime = now + cfg.raidRetryTicks.get();
            }
            setDirty();
        }
    }

    /**
     * フェーズが上がった瞬間に {@link PhaseData#advanceToPhase} から呼ぶ。レイドが解禁されている
     * フェーズ(既定ではフェーズ5以降 = {@code raidEnabled} が true の最小フェーズ)へ上がったときに、
     * <b>その場で</b>昇格レイドを 1 回発生させる。
     *
     * <p>ここで適地が見つからず失敗しても {@code lastRaidPhase} は進めないので、{@link RaidTicker} が
     * 5 秒ごとに再挑戦する(＝「フェーズ5開始時にレイドが確定で発生」を、適地が無いあいだ待ってでも守る)。
     */
    public static void onPhaseAdvanced(ServerLevel overworld, int newPhase) {
        if (!OvergrownVegetationConfig.COMMON.raidEnabled.get()) {
            return;
        }
        if (newPhase < com.yayuy.overgrownvegetation.data.DesignData.phases().raidMinPhase()) {
            return;
        }
        RaidData data = get(overworld);
        if (data.lastRaidPhase < newPhase) {
            data.tryPromotionRaid(overworld, newPhase, overworld.getGameTime());
        }
    }

    /**
     * 昇格レイドを 1 回試す。成功したら {@code lastRaidPhase} を進めて次の周期レイドを予約し、
     * 失敗したら {@code lastRaidPhase} を据え置いたまま少し後に再挑戦させる。
     */
    private void tryPromotionRaid(ServerLevel overworld, int phase, long now) {
        if (RaidController.tryStartRaid(overworld, null)) {
            lastRaidPhase = phase;
            scheduleNextPeriodic(overworld, now);
        } else {
            // 条件不成立(適地が見つからない等)。フェーズは据え置きのまま、少し後で再挑戦する。
            nextPeriodicGameTime = now + OvergrownVegetationConfig.COMMON.raidRetryTicks.get();
        }
        setDirty();
    }

    private void scheduleNextPeriodic(ServerLevel overworld, long now) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int min = cfg.raidPeriodicMinTicks.get();
        int max = Math.max(min, cfg.raidPeriodicMaxTicks.get());
        long span = (long) max - min;
        long delay = min + (span <= 0L ? 0L : (long) (overworld.random.nextDouble() * (span + 1L)));
        nextPeriodicGameTime = now + delay;
    }

    /**
     * 苗床エンティティが実在しなくなった(討伐されたが {@link #onNexusRemoved} を経由できなかった等)
     * レイドを掃除する。エンティティが見つからなくても出現チャンクが未ロードなら「一時的にアンロード
     * されているだけ」とみなして残す。
     *
     * <p>チャンクが読み込まれていても、<b>エンティティの読み込みはその数 tick 後</b>(別スレッドで読んでから入る)。
     * ワールドを開いた直後はその間に苗床がまだ居ないので、エンティティまで読み込み終わるまでは残す
     * (消すと強制ロードが外れ、生きている苗床が台帳から消えて 2 つ目のレイドが始まりうる)。
     */
    private void pruneFinishedRaids(ServerLevel overworld) {
        boolean changed = activeRaids.removeIf(raid -> {
            Entity nexus = overworld.getEntity(raid.nexusId());
            if (nexus != null && nexus.isAlive()) {
                return false;
            }
            if (!overworld.hasChunkAt(raid.origin())
                    || !overworld.areEntitiesLoaded(ChunkPos.asLong(raid.origin()))) {
                return false;
            }
            RaidChunkLoader.release(overworld, raid.forcedChunks());
            EventMobs.releaseOrigin(overworld.getServer(), EventMobs.Kind.NEXUS, raid.nexusId().toString());
            return true;
        });
        if (changed) {
            setDirty();
        }
    }

    /**
     * 進行中のレイドの強制ロードを {@code raidForceLoadRadius} の正方形まで縮める(2026-09-25、ユーザー承認
     * 「レイドの見直し」、報告 1097)。既定を 7(225 チャンク)から 2(25 チャンク)へ下げる前に始まったレイドは、
     * 放っておくと苗床が倒されるまで 225 チャンクを動かし続けるので、ここで外側を解除する。
     * 途中で設定を下げたときも同じ。広げたときは次のレイドから(ここでは足さない)。
     * 何も外さない回は配列をそのまま使い回すので、100 tick ごとに呼んでも記録の比較だけで終わる。
     */
    private void shrinkForcedAreas(ServerLevel overworld) {
        int radius = OvergrownVegetationConfig.COMMON.raidForceLoadRadius.get();
        boolean changed = false;
        for (int i = 0; i < activeRaids.size(); i++) {
            ActiveRaid raid = activeRaids.get(i);
            long[] kept = RaidChunkLoader.releaseOutside(overworld, raid.forcedChunks(),
                    new ChunkPos(raid.origin()), radius);
            if (kept != raid.forcedChunks()) {
                activeRaids.set(i, new ActiveRaid(raid.nexusId(), raid.origin(), raid.startGameTime(), kept));
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    @Nullable
    public ActiveRaid nearestRaid(BlockPos pos) {
        ActiveRaid best = null;
        double bestSq = Double.MAX_VALUE;
        for (ActiveRaid raid : activeRaids) {
            double sq = raid.origin().distSqr(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = raid;
            }
        }
        return best;
    }

    /** コマンド用: 進行中の全レイドの苗床を discard する。 */
    public void stopAllRaids(ServerLevel overworld) {
        for (ActiveRaid raid : new ArrayList<>(activeRaids)) {
            Entity nexus = overworld.getEntity(raid.nexusId());
            if (nexus != null) {
                nexus.discard();
            }
        }
        // discard 経由で onNexusRemoved が呼ばれるが、エンティティが実在しなかった分は残るので、
        // 強制ロードを解除したうえでここでも空にする。
        if (!activeRaids.isEmpty()) {
            for (ActiveRaid raid : activeRaids) {
                RaidChunkLoader.release(overworld, raid.forcedChunks());
                EventMobs.releaseOrigin(overworld.getServer(), EventMobs.Kind.NEXUS, raid.nexusId().toString());
            }
            activeRaids.clear();
            setDirty();
        }
        OvergrownVegetation.LOGGER.info("全てのレイドを停止しました。");
    }
}
