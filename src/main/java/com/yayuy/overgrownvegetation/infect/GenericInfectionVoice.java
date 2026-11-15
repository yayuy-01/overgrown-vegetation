package com.yayuy.overgrownvegetation.infect;

import com.yayuy.overgrownvegetation.voice.CrushedVoice;
import com.yayuy.overgrownvegetation.voice.G1Voice;
import com.yayuy.overgrownvegetation.voice.VoiceBands;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;

/**
 * 汎用の感染体の声(★ K6: G1 と同じ版 = 版 2 つぶれ・高さ {@link G1Voice#PITCH} 倍。帯は体の大きさから {@link VoiceBands#small(net.minecraft.world.entity.Entity)})。
 * 呼ぶのは {@code mixin/LivingEntityGenericVoiceMixin}(鳴き声・被弾・死の声の 1 か所)。
 */
public final class GenericInfectionVoice {

    private GenericInfectionVoice() {
    }

    /** 数え(確かめ用)。起動してから加工して鳴らした回数。 */
    public static int playedCount;

    /**
     * 変わった Mob の声なら、加工して鳴らして true(呼ぶ側は元の音を止める)。サーバー側だけ。静かな Mob は鳴らさないが元の音も止める。
     * 死の声: 感染の死の所で変わるとき、その死の声は変わる前なので元のまま鳴る(バニラの順)。
     */
    public static boolean play(LivingEntity entity, SoundEvent sound, float volume) {
        if (entity.level().isClientSide() || !GenericInfection.isTurned(entity)) return false;
        CrushedVoice.playFrom(entity, sound, volume, entity.getVoicePitch() * G1Voice.PITCH, G1Voice.MODE, VoiceBands.small(entity));
        playedCount++;
        return true;
    }
}
