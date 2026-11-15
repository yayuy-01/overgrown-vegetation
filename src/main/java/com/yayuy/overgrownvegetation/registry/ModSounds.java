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

    // --- G1(11-14 に組み込み。Krotos の素材から tools/g1-sound で作り、ユーザーが試聴して採用) ---
    /** 嚢が膨らむ・溜める(スライムの SQUISH_SMALL の置き換え)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_SAC_SWELL = register("entity.g1.sac_swell");
    /** 湿った塊を出す・当たる(スライムの SQUISH の置き換え)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_SAC_LUMP_HIT = register("entity.g1.sac_lump_hit");
    /** 嚢が爆ぜる(SLIME_BLOCK_BREAK + MUD_BREAK + SQUID_SQUIRT の 3 つ重ねの置き換え)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_SAC_BURST = register("entity.g1.sac_burst");
    /** 蔦を打つ・薙ぎ払う。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_VINE_LASH = register("entity.g1.vine_lash");
    /** 根が地面を割る。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_ROOT_BREAK = register("entity.g1.root_break");
    /** 生える(根付き・苗床の emerge 60 tick。音 3.0 秒)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_PLANT_EMERGE = register("entity.g1.plant_emerge");
    /** 生える・短い版(根の守り手の立ち上がり 32 tick。音 1.8 秒。ユーザーの試聴はまだ)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_PLANT_EMERGE_SHORT = register("entity.g1.plant_emerge_short");
    /** 汎用の感染体が変わる瞬間 = 体を破って植物が突き出る(音 0.84 秒。11-15 ユーザーが版 1 を採用)。字幕は「生える」と共用。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> INFECT_TEAR = register("entity.infect.tear");
    /** 枯れて崩れる(death_wither。音 2.4 秒)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_PLANT_WITHER = register("entity.g1.plant_wither");
    /** 進化の前の震え(音 2.4 秒。前兆の頭から 10 tick 遅らせて鳴らすと、終わりが前兆の 60 tick に合う)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_EVOLVE_OMEN = register("entity.g1.evolve_omen");
    /** 進化の入れ替わり(evolve_in の頭。体を破って茎が伸び、葉が開き、嚢が張る。音 1.3 秒)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_EVOLVE_BURST = register("entity.g1.evolve_burst");
    /** 眠った村人の背の嚢が開く(ゆっくり膨らみ、開いて息が漏れる。音 1.3 秒・静か)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_SAC_OPEN = register("entity.g1.sac_open");
    /** 取り付いて噛む(湿った噛む音 3 本)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> G1_LATCH_BITE = register("entity.g1.latch_bite");
    /** 根の守り手が殴る(当たりは音の頭から約 5 tick)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROOT_GUARDIAN_STRIKE = register("entity.root_guardian.strike");
    /** 根の守り手の足音(3 本)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROOT_GUARDIAN_STEP = register("entity.root_guardian.step");
    /** 苗床が萌芽体を出す(弾けるのは音の頭から 12 tick = SPROUT_AT)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> NURSERY_SPROUT = register("entity.nursery.sprout");

    // --- アルボラ・ハイヴノード(11-14 に組み込み。ユーザーが試聴して採用) ---
    public static final DeferredHolder<SoundEvent, SoundEvent> ARBORA_IDLE = register("entity.arbora.idle");
    public static final DeferredHolder<SoundEvent, SoundEvent> ARBORA_HURT = register("entity.arbora.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> ARBORA_DEATH = register("entity.arbora.death");
    /** 胞子の噴出の鼓動(2 拍)。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> HIVE_NODE_PULSE = register("entity.hive_node.pulse");
    public static final DeferredHolder<SoundEvent, SoundEvent> HIVE_NODE_HURT = register("entity.hive_node.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> HIVE_NODE_DEATH = register("entity.hive_node.death");

    // --- 萌芽体(11-14 に組み込み。ユーザーが試聴して採用) ---
    public static final DeferredHolder<SoundEvent, SoundEvent> BLIGHT_FERN_HURT = register("entity.blight_fern.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLIGHT_FERN_DEATH = register("entity.blight_fern.death");

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
