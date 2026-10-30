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
     * 盾でとびかかりを弾いたときの重い衝撃音({@code tools/shield_parry_sound_synth.ps1} で合成)。
     * バニラの {@code shield.block} より低く重い — 攻撃を「防いだ」のではなく体当たりを
     * 「受け止めた」音。{@link com.yayuy.overgrownvegetation.entity.InvaderLeapGoal} が鳴らす。
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> SHIELD_PARRY =
            register("item.shield.parry");

    /** ファランギオターブス: 待機時の虫の鳴き声(コオロギ風の断続的なジリジリ)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> PHALANGIOTARBUS_IDLE =
            register("entity.phalangiotarbus.idle");
    /** ファランギオターブス: 被弾時の鋭い威嚇音。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> PHALANGIOTARBUS_HURT =
            register("entity.phalangiotarbus.hurt");
    /** ファランギオターブス: 死亡時の失速していくジジジ…。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> PHALANGIOTARBUS_DEATH =
            register("entity.phalangiotarbus.death");
    /** ファランギオターブス: 歩行(脚の乾いたクリック)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> PHALANGIOTARBUS_STEP =
            register("entity.phalangiotarbus.step");
    /** ファランギオターブス: 飛びかかりの短いシャッ。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> PHALANGIOTARBUS_POUNCE =
            register("entity.phalangiotarbus.pounce");
    /** ファランギオターブス: 取り付いて齧るカチカチ。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> PHALANGIOTARBUS_LATCH =
            register("entity.phalangiotarbus.latch");

    // --- ファルキムネス(2026-09-23、tools/falcimnes-sound/FalcSound.java で合成) ---
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_IDLE = register("entity.falcimnes.idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_HURT = register("entity.falcimnes.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_DEATH = register("entity.falcimnes.death");
    /** 威嚇の咆哮。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_ROAR = register("entity.falcimnes.roar");
    /** 鎌の風切り(横薙ぎ・連撃の 1 振りごと)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_SLASH = register("entity.falcimnes.slash");
    /** 掴んだ獲物への噛みつき。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_BITE = register("entity.falcimnes.bite");
    /** 攻撃の予兆の唸り(横薙ぎ・連撃の溜めの頭)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_GROWL = register("entity.falcimnes.growl");
    /** 掴みの突進を始めるときの金切り声。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> FALCIMNES_SCREECH = register("entity.falcimnes.screech");

    // --- スクティムネス(2026-09-23、tools/scutimnes-sound/ScutSound.java で合成) ---
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_IDLE = register("entity.scutimnes.idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_HURT = register("entity.scutimnes.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_DEATH = register("entity.scutimnes.death");
    /** 攻撃の予兆の唸り(バッシュ・尾の薙ぎ払いの溜めの頭)。声の山は鳴らしてから 0.57 秒。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_GROWL = register("entity.scutimnes.growl");
    /** 叩きつけの振り上げの咆哮(slam の 0 秒で鳴らす)。声の山は鳴らしてから 0.5〜0.85 秒、0.9 秒で喉を閉じて黙る(叩きつけの 1.10 秒を覆わない)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_ROAR = register("entity.scutimnes.roar");
    /** ガード姿勢に入る踏ん張りの低い唸り。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_GUARD = register("entity.scutimnes.guard");
    /** 盾が当たる重い打撃(当たる tick に鳴らす。頭で立ち上がる)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_BASH = register("entity.scutimnes.bash");
    /** 尾が空を切る重い風切り。鳴らしてから 0.13 秒で山(振りの中央の 3 tick 前に鳴らす)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_TAIL = register("entity.scutimnes.tail");
    /** 盾が地面を叩く衝撃 + 衝撃波の地鳴り(当たる tick に鳴らす。頭で立ち上がる)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_SLAM = register("entity.scutimnes.slam");
    /** 盾の上げ下げで甲殻がこすれて噛み合う音。噛み合う「ごっ」は鳴らしてから 0.33 秒。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_SHIELD = register("entity.scutimnes.shield");
    /** 飛び道具を盾で弾く硬い音。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCUTIMNES_DEFLECT = register("entity.scutimnes.deflect");

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
