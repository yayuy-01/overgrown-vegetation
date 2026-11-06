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

    // --- ファルキムネス(11-05 に作り直して戻した。tools/falcimnes-sound/FalcSound.java で合成) ---
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_IDLE = register("entity.falcimnes.idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_HURT = register("entity.falcimnes.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_DEATH = register("entity.falcimnes.death");
    /** 威嚇の咆哮。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_ROAR = register("entity.falcimnes.roar");
    /** 鎌の風切り(横薙ぎ・連撃の 1 振りごと)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_SLASH = register("entity.falcimnes.slash");
    /** 掴んだ獲物への噛みつき。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_BITE = register("entity.falcimnes.bite");
    /** 攻撃の予兆の唸り。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_GROWL = register("entity.falcimnes.growl");
    /** 掴みの突進を始めるときの金切り声。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_SCREECH = register("entity.falcimnes.screech");
    /** 飛ぶ斬撃の風切り(放つ瞬間)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_WAVE = register("entity.falcimnes.wave");
    /** 地走りの光の帯: 鎌を地面に刺す音。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_RIFT_STAB = register("entity.falcimnes.rift_stab");
    /** 地走りの光の帯: 筋が走って帯が立つまでの低い地鳴り。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_RIFT_RUMBLE = register("entity.falcimnes.rift_rumble");

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
