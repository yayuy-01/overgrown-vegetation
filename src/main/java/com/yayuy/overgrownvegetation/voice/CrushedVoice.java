package com.yayuy.overgrownvegetation.voice;

import com.yayuy.overgrownvegetation.network.CrushedVoicePayload;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * どの Mob・どの SoundEvent でも「鳴らすときに声を汚して」鳴らすサーバー側の口(2026-11-14 試作)。
 * 音のファイルは要らない: 近くのクライアントへ {@link CrushedVoicePayload} を送り、クライアントがバニラ(やほかの MOD)の音を開いて加工しながら流す。
 * 届く範囲はバニラの {@code ServerLevel#playSeededSound} と同じ({@link SoundEvent#getRange}: 音量 1 以下なら 16 ブロック、それより大きければ 16 × 音量)。
 *
 * <p>将来の「非対応の Mob 用の汎用感染システム」で、感染した Mob の {@code getAmbientSound} などの代わりに呼ぶ想定
 * (今は G1 にも組み込んでいない。版を選んでから)。
 * <b>まだ無いもの</b>: NeoForge の {@code PlayLevelSoundEvent} は出さない(ほかの MOD がこの音を消したり変えたりできない)。
 */
public final class CrushedVoice {

    /** 加工の版({@code client.voice.VoiceCrusher} と同じ番号)。 */
    public static final int DRY = 0, HOARSE = 1, CRUSHED = 2, CRUSHED_BREAKS = 3;

    private CrushedVoice() {
    }

    /** 登録された SoundEvent を、その場所で汚して鳴らす(届く範囲はその SoundEvent の決まりどおり)。 */
    public static void play(ServerLevel level, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                            float volume, float pitch, int mode, boolean small) {
        play(level, x, y, z, sound.value(), source, volume, pitch, mode, small);
    }

    /** 登録された SoundEvent を、その場所で汚して鳴らす(届く範囲はその SoundEvent の決まりどおり)。 */
    public static void play(ServerLevel level, double x, double y, double z, SoundEvent sound, SoundSource source,
                            float volume, float pitch, int mode, boolean small) {
        send(level, null, x, y, z, sound.getRange(volume), sound.getLocation(), source, volume, pitch, mode, small, 1.0F);
    }

    /** Mob の声として鳴らす(その Mob の位置・その Mob の音の分類)。 */
    public static void playFrom(Entity entity, SoundEvent sound, float volume, float pitch, int mode, boolean small) {
        if (entity.level() instanceof ServerLevel level && !entity.isSilent()) {
            send(level, null, entity.getX(), entity.getY(), entity.getZ(), sound.getRange(volume), sound.getLocation(),
                    entity.getSoundSource(), volume, pitch, mode, small, 1.0F);
        }
    }

    /**
     * 場所(ResourceLocation)で指定して鳴らす。サーバーに登録が無い音(クライアントだけの MOD の音)にも使える。
     * 届く範囲は音量が変えられる音の決まり(16 ブロック、音量 1 より大きければ 16 × 音量)。
     *
     * @param except   送らないプレイヤー(自分で鳴らしたプレイヤーなど。null なら全員)
     * @param strength 加工の強さ(1 = 試聴用と同じ)
     */
    public static void play(ServerLevel level, @Nullable ServerPlayer except, double x, double y, double z, ResourceLocation sound,
                            SoundSource source, float volume, float pitch, int mode, boolean small, float strength) {
        send(level, except, x, y, z, volume > 1.0F ? 16.0F * volume : 16.0F, sound, source, volume, pitch, mode, small, strength);
    }

    private static void send(ServerLevel level, @Nullable ServerPlayer except, double x, double y, double z, double range,
                             ResourceLocation sound, SoundSource source, float volume, float pitch, int mode, boolean small, float strength) {
        long seed = level.getRandom().nextLong();
        PacketDistributor.sendToPlayersNear(level, except, x, y, z, range,
                new CrushedVoicePayload(sound, source, x, y, z, volume, pitch, Math.max(0, Math.min(3, mode)), small, strength, seed));
    }
}
