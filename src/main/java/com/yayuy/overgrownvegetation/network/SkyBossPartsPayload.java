package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.skyboss.SkyBossParts;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「空の巨大生物」の弱点の壊れ具合を伝える S2C ペイロード(2026-10-18 夜)。
 * 避雷針が壊れた・生え直し始めた tick と、蕾ごとの同じ tick({@link SkyBossParts#NONE} = 無い)。体力は送らない。
 * 送るのは壊れたとき・生え直し始めた・元に戻ったときと、入ってきたプレイヤーへ。動きの {@link SkyBossStatePayload} とは別に送る
 * (あちらは 8 秒ごとなので、爆発と体の反動に間に合わない)。
 */
public record SkyBossPartsPayload(long rodBrokenAt, long rodRegrowAt, long[] budBrokenAt, long[] budRegrowAt) implements CustomPacketPayload {

    public static final Type<SkyBossPartsPayload> TYPE = new Type<>(OvergrownVegetation.id("sky_boss_parts"));
    public static final StreamCodec<FriendlyByteBuf, SkyBossPartsPayload> STREAM_CODEC =
            StreamCodec.ofMember(SkyBossPartsPayload::write, SkyBossPartsPayload::read);

    public static SkyBossPartsPayload of(SkyBossParts p) {
        return new SkyBossPartsPayload(p.rodBrokenAt, p.rodRegrowAt, p.budBrokenAt.clone(), p.budRegrowAt.clone());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(rodBrokenAt);
        buf.writeLong(rodRegrowAt);
        int n = Math.min(budBrokenAt.length, budRegrowAt.length);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeLong(budBrokenAt[i]);
            buf.writeLong(budRegrowAt[i]);
        }
    }

    private static SkyBossPartsPayload read(FriendlyByteBuf buf) {
        long rb = buf.readLong();
        long rr = buf.readLong();
        int n = buf.readVarInt();
        if (n < 0 || n > SkyBossParts.MAX_BUDS) {
            throw new DecoderException("sky boss parts: too many buds " + n);
        }
        long[] b = new long[n];
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            b[i] = buf.readLong();
            r[i] = buf.readLong();
        }
        return new SkyBossPartsPayload(rb, rr, b, r);
    }

    /** {@code p} の時刻をこの中身で置き換える。 */
    public void applyTo(SkyBossParts p) {
        p.setTimes(rodBrokenAt, rodRegrowAt, budBrokenAt, budRegrowAt);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
