package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 卓越風の入力になる天気の印(2026-10-23 第 2 段 B)。直近 2 回の雨の始まりと終わり(オーバーワールドの {@code getDayTime})を覚え、
 * 前は雨・雷が切り替わったときと、プレイヤーが入ったときにクライアントへ送っていた(画面の風の演出用。2026-10-30 に通信ごと parked/ へ退避)。
 * 向きはサーバーの {@link PrevailingWind#sample} だけが使う。
 *
 * <p>風の種({@link #windSeed})はワールドのシードそのものではなく、シードを混ぜた値(クライアントへシードを渡さない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WindWeatherData extends SavedData {

    public static final String ID = "overgrown_vegetation_wind_weather";
    public static final SavedData.Factory<WindWeatherData> FACTORY =
            new SavedData.Factory<>(WindWeatherData::new, (tag, registries) -> load(tag), null);

    private long lastRainStart = PrevailingWind.NONE, lastRainEnd = PrevailingWind.NONE;
    private long prevRainStart = PrevailingWind.NONE, prevRainEnd = PrevailingWind.NONE;
    private boolean raining, thundering;
    private boolean seen;

    private WindWeatherData() {
    }

    private static WindWeatherData load(CompoundTag tag) {
        WindWeatherData d = new WindWeatherData();
        d.lastRainStart = tag.getLong("lastRainStart");
        d.lastRainEnd = tag.getLong("lastRainEnd");
        d.prevRainStart = tag.getLong("prevRainStart");
        d.prevRainEnd = tag.getLong("prevRainEnd");
        d.raining = tag.getBoolean("raining");
        d.thundering = tag.getBoolean("thundering");
        d.seen = tag.getBoolean("seen");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("lastRainStart", lastRainStart);
        tag.putLong("lastRainEnd", lastRainEnd);
        tag.putLong("prevRainStart", prevRainStart);
        tag.putLong("prevRainEnd", prevRainEnd);
        tag.putBoolean("raining", raining);
        tag.putBoolean("thundering", thundering);
        tag.putBoolean("seen", seen);
        return tag;
    }

    public static WindWeatherData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 風の種(シードを混ぜた値)。 */
    public static long windSeed(MinecraftServer server) {
        return PrevailingWind.hash(server.overworld().getSeed(), 0x57A1D5EEDL);
    }

    /** 今の天気の印。 */
    public PrevailingWind.Weather weather() {
        return new PrevailingWind.Weather(raining, thundering, lastRainStart, lastRainEnd, prevRainStart, prevRainEnd);
    }

    /** 最後の雨の終わり(雨上がりの判定)。降っている最中や無ければ NONE。 */
    public long lastRainEnd() {
        return lastRainEnd != PrevailingWind.NONE && lastRainEnd >= lastRainStart ? lastRainEnd : PrevailingWind.NONE;
    }

    /** 雨・雷の切り替えを見て印を更新する。変わったら true。 */
    boolean observe(boolean nowRaining, boolean nowThundering, long dayTime) {
        boolean changed = false;
        if (!seen) {
            seen = true;
            raining = nowRaining;
            thundering = nowThundering;
            if (nowRaining) lastRainStart = dayTime; // 降っている最中に始まったワールド: 今を始まりとみなす
            setDirty();
            return true;
        }
        if (nowRaining != raining) {
            if (nowRaining) {
                prevRainStart = lastRainStart;
                prevRainEnd = lastRainEnd;
                lastRainStart = dayTime;
                lastRainEnd = PrevailingWind.NONE;
            } else {
                lastRainEnd = dayTime;
            }
            raining = nowRaining;
            changed = true;
        }
        if (nowThundering != thundering) {
            thundering = nowThundering;
            changed = true;
        }
        if (changed) setDirty();
        return changed;
    }

    // ---------------------------------------------------------------- 見張り

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        WindWeatherData data = get(level.getServer());
        // isThundering は雨の間だけ(バニラ: 雷の時計は雨と別だが、見えるのは雨のときだけ)
        boolean rain = level.isRaining();
        boolean thunder = rain && level.isThundering();
        data.observe(rain, thunder, level.getDayTime());
    }
}
