package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * 「この位置で、この SoundEvent を、この版で汚して鳴らす」(S2C、2026-11-14)。送るのは {@code voice.CrushedVoice#play}。
 * SoundEvent は場所(ResourceLocation)で送るので、サーバーに登録が無い音(クライアントだけの MOD の音)でも届く。
 *
 * @param sound    SoundEvent の場所
 * @param source   音の分類(音量の設定の欄)
 * @param mode     加工の版 0〜3(0 = 加工なし)
 * @param small    小型の Mob の帯で加工する
 * @param strength 加工の強さ(1 = 試聴用と同じ)
 * @param seed     変種の選び方の種(同じ種なら同じ変種)
 */
public record CrushedVoicePayload(ResourceLocation sound, SoundSource source, double x, double y, double z,
                                  float volume, float pitch, int mode, boolean small, float strength, long seed)
        implements CustomPacketPayload {

    public static final Type<CrushedVoicePayload> TYPE = new Type<>(OvergrownVegetation.id("crushed_voice"));

    public static final StreamCodec<FriendlyByteBuf, CrushedVoicePayload> STREAM_CODEC =
            StreamCodec.ofMember(CrushedVoicePayload::write, CrushedVoicePayload::new);

    private CrushedVoicePayload(FriendlyByteBuf buf) {
        this(buf.readResourceLocation(), buf.readEnum(SoundSource.class), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readByte(), buf.readBoolean(), buf.readFloat(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(sound);
        buf.writeEnum(source);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(volume);
        buf.writeFloat(pitch);
        buf.writeByte(mode);
        buf.writeBoolean(small);
        buf.writeFloat(strength);
        buf.writeLong(seed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
