package com.yayuy.overgrownvegetation.voice;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * G1 の声(宿主のバニラの声を、鳴らすときに版 2 = つぶれで汚す。2026-11-14 ユーザー決定)。
 * 鳴き声・被弾・段階 0 の死は {@code AbstractParasitizedMonster#makeSound} から、技の中の宿主の声(仲間呼び・喉袋・吐く)はそれぞれの所から呼ぶ。
 */
public final class G1Voice {

    /** 版(ユーザー決定: 2 つぶれ)。 */
    public static final int MODE = CrushedVoice.CRUSHED;
    /** 高さに掛ける倍率【仮】(試聴では 0.92 倍の高さで聞いた)。 */
    public static final float PITCH = 0.92F;

    private G1Voice() {
    }

    /** その Mob の声として(その Mob の位置・音の分類・静かなら鳴らさない)。 */
    public static void play(Entity mob, G1Species species, SoundEvent sound, float volume, float pitch) {
        CrushedVoice.playFrom(mob, sound, volume, pitch * PITCH, MODE, VoiceBands.small(species));
    }

    /** 技の中の声(位置・大きさ・高さを決めて鳴らす。届く範囲はその SoundEvent の決まりどおり)。 */
    public static void playAt(ServerLevel level, double x, double y, double z, G1Species species, SoundEvent sound, float volume, float pitch) {
        CrushedVoice.play(level, x, y, z, sound, SoundSource.HOSTILE, volume, pitch * PITCH, MODE, VoiceBands.small(species));
    }
}
