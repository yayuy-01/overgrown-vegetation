package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 小規模レイド(2026-09-19)の予約時刻(ワールド単位)。
 *
 * <p>レイドビーコン({@link RaidData})とは別物で、<b>苗床も根も無い</b>。
 * プレイヤーの遠く({@code phases.json} の {@code smallRaid.minDistance}〜{@code maxDistance})に
 * 拡大種の一団(既定: アルボラ 8 + フィロセンス / ヴェロフィル / スポロフィル / シールダー 1 体ずつ)が湧き、
 * {@link SmallRaidHuntGoal} でそのプレイヤーを目指して歩いてくる。既定ではフェーズ3から、
 * {@code minIntervalTicks}〜{@code maxIntervalTicks}(1.5〜3 日)おき。
 *
 * <p>{@link RaidTicker} から 100 tick ごとに {@link #tick} が呼ばれる。
 */
public final class SmallRaidData extends SavedData {

    private static final String ID = "overgrown_vegetation_small_raid";

    public static final SavedData.Factory<SmallRaidData> FACTORY =
            new SavedData.Factory<>(SmallRaidData::new, (tag, registries) -> load(tag), null);

    /** 次の小規模レイドを起こすゲーム内時刻。負の値 = 未予約。 */
    private long nextGameTime = -1L;
    /** 直近に起こしたフェーズ(フェーズが変わったら予約を引き直す)。 */
    private int scheduledPhase = -1;

    private SmallRaidData() {
    }

    public static SmallRaidData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static SmallRaidData load(CompoundTag tag) {
        SmallRaidData data = new SmallRaidData();
        data.nextGameTime = tag.contains("nextGameTime") ? tag.getLong("nextGameTime") : -1L;
        data.scheduledPhase = tag.contains("scheduledPhase") ? tag.getInt("scheduledPhase") : -1;
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("nextGameTime", nextGameTime);
        tag.putInt("scheduledPhase", scheduledPhase);
        return tag;
    }

    public long getNextGameTime() {
        return nextGameTime;
    }

    public void tick(ServerLevel overworld) {
        PhaseTable phases = DesignData.phases();
        int phase = PhaseData.get(overworld).getPhase();
        if (phase < 1 || !phases.smallRaidEnabled(phase)) {
            if (nextGameTime >= 0L) {
                nextGameTime = -1L;
                scheduledPhase = -1;
                setDirty();
            }
            return;
        }
        long now = overworld.getGameTime();
        if (nextGameTime < 0L || scheduledPhase != phase) {
            // 解禁された(またはフェーズが変わった)直後は、間隔を 1 回分空けてから最初の一団を出す。
            schedule(overworld, phase, now);
            setDirty();
            return;
        }
        if (now < nextGameTime) {
            return;
        }
        if (SmallRaidController.tryStart(overworld, phase)) {
            schedule(overworld, phase, now);
        } else {
            // プレイヤーが居ない・適地が無い等。少し後で再挑戦する。
            nextGameTime = now + 1200L;
        }
        setDirty();
    }

    /** いますぐ次の一団を出す({@code /ov smallraid now} 用)。 */
    public void triggerNow(ServerLevel overworld) {
        nextGameTime = overworld.getGameTime();
        scheduledPhase = PhaseData.get(overworld).getPhase();
        setDirty();
    }

    private void schedule(ServerLevel overworld, int phase, long now) {
        PhaseTable phases = DesignData.phases();
        int min = phases.smallRaidMinIntervalTicks(phase);
        int max = phases.smallRaidMaxIntervalTicks(phase);
        long span = (long) max - min;
        long delay = min + (span <= 0L ? 0L : (long) (overworld.random.nextDouble() * (span + 1L)));
        nextGameTime = now + delay;
        scheduledPhase = phase;
    }
}
