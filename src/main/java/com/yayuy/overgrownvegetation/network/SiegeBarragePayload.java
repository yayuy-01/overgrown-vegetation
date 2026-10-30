package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * サーバー → クライアント「触手が小ビームを 1 発撃った」(乱射)。
 *
 * <p>乱射は 1 発ずつ向きが違うので、{@link SiegeBossStatePayload} の段階(溜め→照射)には乗せられない。
 * かわりに撃つたびにこの 1 通を送り、クライアントは数 tick だけ光線を描いて消す
 * ({@link com.yayuy.overgrownvegetation.client.ClientSiegeBarrage})。1 発あたり 1 パケットで、
 * 中身は 60 バイトほど。撃つのは既定で毎 tick 1 発({@code siegeBarrageShotIntervalTicks} = 1、
 * {@code siegeBarrageShotsPerStep} = 1)なので、乱射の間だけ 1 人あたり毎秒 1〜2 KB ほど —— 負荷にはならない
 * (2026-09-24 の監査で確認。以前ここは「3 tick に 1 発」と書いていたが、既定値が変わっていた)。
 *
 * <p>撃った瞬間の触手の先端の<b>ワールド座標</b>をそのまま送る。ボスは 0.08 ブロック/tick でしか
 * 動かないので、描いている数 tick のあいだに根元がずれて見えることはない。
 *
 * @param from          触手の先端(発射点)
 * @param to            狙点。実際にはここを通り越して {@code siegeBarrageOvershoot} 先まで伸びる
 * @param startGameTime 発射時刻(サーバーのゲーム内時刻 tick)
 */
public record SiegeBarragePayload(Vec3 from, Vec3 to, long startGameTime) implements CustomPacketPayload {

    public static final Type<SiegeBarragePayload> TYPE = new Type<>(OvergrownVegetation.id("siege_barrage"));

    public static final StreamCodec<FriendlyByteBuf, SiegeBarragePayload> STREAM_CODEC =
            StreamCodec.ofMember(SiegeBarragePayload::write, SiegeBarragePayload::new);

    private SiegeBarragePayload(FriendlyByteBuf buf) {
        this(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(from.x);
        buf.writeDouble(from.y);
        buf.writeDouble(from.z);
        buf.writeDouble(to.x);
        buf.writeDouble(to.y);
        buf.writeDouble(to.z);
        buf.writeLong(startGameTime);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
