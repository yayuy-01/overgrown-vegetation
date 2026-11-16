package com.yayuy.overgrownvegetation.worldevent;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * ワールドイベントの日程の保存(2026-11-16。オーバーワールドのデータ置き場に 1 つ)。中身は {@link WorldEventSchedule}。
 */
public final class WorldEventData extends SavedData {

    public static final String ID = "overgrown_vegetation_world_events";
    public static final SavedData.Factory<WorldEventData> FACTORY =
            new SavedData.Factory<>(WorldEventData::new, (tag, registries) -> new WorldEventData(WorldEventSchedule.load(tag)), null);

    private final WorldEventSchedule schedule;

    private WorldEventData() {
        this(new WorldEventSchedule());
    }

    private WorldEventData(WorldEventSchedule schedule) {
        this.schedule = schedule;
    }

    public WorldEventSchedule schedule() {
        return schedule;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return schedule.save(tag);
    }

    public static WorldEventData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }
}
