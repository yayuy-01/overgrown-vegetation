package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * サーバー → クライアント「砲弾が発射された」。砲弾はエンティティではないので、クライアントは
 * これを受け取って {@link com.yayuy.overgrownvegetation.siege.CannonBallistics#positionAt} の同じ式で
 * 毎フレームの位置を自分で計算して描く(座標同期なし)。1000 ブロック先のボスへ飛んでいく砲弾を
 * 誰でも見られるよう、ディメンション内の全プレイヤーへ距離を問わず送る。
 *
 * @param id            砲弾の識別子({@link ShellImpactPayload} で消すときに使う)
 * @param kind          弾の種類({@link com.yayuy.overgrownvegetation.siege.ShellTracker#KIND_CANNON} =
 *                      砲台の砲弾、{@code KIND_SPORE} = ボスが曲射してくる胞子爆弾)。描画の見た目が変わる
 * @param origin        発射位置
 * @param velocity      初速(ブロック/tick)
 * @param gravity       重力(ブロック/tick²)
 * @param startGameTime 発射時刻(サーバーのゲーム内時刻 tick)。クライアントの
 *                      {@code level.getGameTime()} は 20 tick ごとにサーバーと合わせられるので、そのまま比較できる
 */
public record ShellFiredPayload(int id, int kind, Vec3 origin, Vec3 velocity, float gravity, long startGameTime)
        implements CustomPacketPayload {

    public static final Type<ShellFiredPayload> TYPE = new Type<>(OvergrownVegetation.id("shell_fired"));

    public static final StreamCodec<FriendlyByteBuf, ShellFiredPayload> STREAM_CODEC =
            StreamCodec.ofMember(ShellFiredPayload::write, ShellFiredPayload::new);

    private ShellFiredPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readFloat(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeVarInt(kind);
        buf.writeDouble(origin.x);
        buf.writeDouble(origin.y);
        buf.writeDouble(origin.z);
        buf.writeDouble(velocity.x);
        buf.writeDouble(velocity.y);
        buf.writeDouble(velocity.z);
        buf.writeFloat(gravity);
        buf.writeLong(startGameTime);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
