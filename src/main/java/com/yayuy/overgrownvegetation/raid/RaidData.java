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
     */
    public void onNexusRemoved(ServerLevel overworld, UUID nexusId, boolean killed) {
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
        if (!cfg.raidEnabled.get()) {
            return;
        }

        pruneFinishedRaids(overworld);

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
     * そのフェーズの昇格レイドを<b>発生させずに済ませる</b>(2026-09-17)。
     * {@code lastRaidPhase} だけを進めるので、{@link #tick} も {@link RaidTicker} も
     * 「このフェーズの昇格レイドは処理済み」とみなして何も出さない。
     *
     * <p>用途: {@code /siege} でフェーズ7へ上げて攻城戦を始めるとき。ボス戦と苗床レイドが
     * 同時に始まらないようにする({@code SiegeCommand#enterPhase7})。
     *
     * <p>止めるのは<b>昇格レイドだけ</b>。周期レイドは従来どおり予約どおりに発生する。
     */
    public static void skipPromotionRaid(ServerLevel overworld, int phase) {
        RaidData data = get(overworld);
        if (data.lastRaidPhase < phase) {
            data.lastRaidPhase = phase;
            data.setDirty();
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
     */
    private void pruneFinishedRaids(ServerLevel overworld) {
        boolean changed = activeRaids.removeIf(raid -> {
            Entity nexus = overworld.getEntity(raid.nexusId());
            if (nexus != null && nexus.isAlive()) {
                return false;
            }
            if (!overworld.hasChunkAt(raid.origin())) {
                return false;
            }
            RaidChunkLoader.release(overworld, raid.forcedChunks());
            return true;
        });
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
            }
            activeRaids.clear();
            setDirty();
        }
        OvergrownVegetation.LOGGER.info("全てのレイドを停止しました。");
    }
}
