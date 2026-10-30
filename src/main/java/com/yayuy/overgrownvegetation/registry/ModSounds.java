package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODの独自サウンドイベント。実体の ogg は {@code assets/overgrown_vegetation/sounds/} に置き、
 * {@code assets/overgrown_vegetation/sounds.json} でイベント名と結び付ける。
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, OvergrownVegetation.MODID);


    /**
     * フェーズ1開始の地割れ演出の土砂崩れの音(実尺 15.6 秒、末尾に2秒のフェードアウト)。
     * 裂け目の形成開始と同時にちょうど1回鳴らし、鳴り終わりと裂け目の完成が一致する。
     * 固定の減衰距離 1024 ブロック。
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> EARTHQUAKE_FISSURE =
            registerFixedRange("earthquake.fissure", 1024.0F);

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUND_EVENTS.register(path, () -> SoundEvent.createVariableRangeEvent(OvergrownVegetation.id(path)));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> registerFixedRange(String path, float range) {
        return SOUND_EVENTS.register(path, () -> SoundEvent.createFixedRangeEvent(OvergrownVegetation.id(path), range));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }

    private ModSounds() {
    }
}
