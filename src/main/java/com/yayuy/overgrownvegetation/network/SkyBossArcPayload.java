package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「空の巨大生物」の放電の雷 1 本を伝える S2C ペイロード(2026-10-19)。体の表面の点 (fx, fy, fz) から
 * 当たった相手 (tx, ty, tz) へ(世界の座標)。サーバーが当たりを決めてダメージを与え、クライアントは描くだけ(見えた雷と当たった雷が同じ)。
 * 当たらないまわりの雷(プラズマボールの筋)はクライアントが自分で決めた式で描くので送らない。{@code seed} はぎざぎざの形。
 */
public record SkyBossArcPayload(double fx, double fy, double fz, double tx, double ty, double tz, int seed) implements CustomPacketPayload {

    public static final Type<SkyBossArcPayload> TYPE = new Type<>(OvergrownVegetation.id("sky_boss_arc"));
    public static final StreamCodec<FriendlyByteBuf, SkyBossArcPayload> STREAM_CODEC =
            StreamCodec.ofMember(SkyBossArcPayload::write, SkyBossArcPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(fx);
        buf.writeDouble(fy);
        buf.writeDouble(fz);
        buf.writeDouble(tx);
        buf.writeDouble(ty);
        buf.writeDouble(tz);
        buf.writeInt(seed);
    }

    private static SkyBossArcPayload read(FriendlyByteBuf buf) {
        return new SkyBossArcPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
