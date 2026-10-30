package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「空の巨大生物」の今の動きを伝える S2C ペイロード(2026-10-18)。
 *
 * <p>ボスは全長 約 2100 ブロックで、バニラのエンティティの同期(サーバーの view-distance が上限)には乗らないので、攻城戦のボス
 * ({@link SiegeBossStatePayload})と同じく<b>距離を問わず</b>オーバーワールドの全員へ送り、クライアントが自分で位置を出す。
 * 中身は「今の頭の姿勢・今の区間の操作・区間の残り・頭が通った道」で、クライアントは同じ式
 * ({@link SkyBossFlight#step})で 1 tick ずつ進める。送るのは区間が変わるとき(8 秒ごと)と、入ってきたプレイヤーへ。
 * 道は頭からの差(float)で、新しい点から古い点の順。
 *
 * @param active  居るか。false は「消えた」通知(ほかの値は無視)
 * @param tick    この姿勢の時刻(サーバーのゲームの時刻)
 * @param bankTarget 今の区間の体の傾きの目標(度)。bank・bankMid = 今の傾きとその追い方の途中(両側が同じ式で進める)
 * @param fanMode 扇の層の動きとそれに変わった tick、一つ前の動き({@link com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock})
 * @param seed    この個体の決まった種(見た目の気まぐれに使う)
 * @param trail   道の点 {dx, dy, dz, dx, dy, dz, ...}(頭からの差)
 * @param extF    10-19 から: 速さの目標・転がり・扇を頭が決める動き・頭を先に向ける角度({@link SkyBossFlight#extraFloats})
 * @param extL    10-19 から: 転がり始めた tick・雷が落ちる tick({@link SkyBossFlight#extraLongs})
 */
public record SkyBossStatePayload(boolean active, long tick, double x, double y, double z, float yaw, float pitch,
                                  float speed, float yawRate, float pitchRate, float bankTarget, float bank, float bankMid,
                                  int fanMode, long fanModeSince, int prevFanMode, long prevFanModeSince, long segmentEnd, long seed,
                                  float[] trail, float[] extF, long[] extL) implements CustomPacketPayload {

    public static final Type<SkyBossStatePayload> TYPE = new Type<>(OvergrownVegetation.id("sky_boss_state"));
    public static final StreamCodec<FriendlyByteBuf, SkyBossStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(SkyBossStatePayload::write, SkyBossStatePayload::read);

    /** 道の点の数の上限(読むときの安全のため)。 */
    private static final int MAX_POINTS = 1024;
    /** 追加の値の数の上限。 */
    private static final int MAX_EXTRA = 32;

    public static SkyBossStatePayload inactive() {
        return new SkyBossStatePayload(false, 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0, 0L, 0L, 0L, new float[0], new float[0], new long[0]);
    }

    /** 今の動きから作る。 */
    public static SkyBossStatePayload of(SkyBossFlight f, long seed) {
        int n = f.trail.size();
        float[] t = new float[n * 3];
        for (int i = 0; i < n; i++) {
            t[i * 3] = (float) (f.trail.x(i) - f.x);
            t[i * 3 + 1] = (float) (f.trail.y(i) - f.y);
            t[i * 3 + 2] = (float) (f.trail.z(i) - f.z);
        }
        return new SkyBossStatePayload(true, f.tick, f.x, f.y, f.z, f.yaw, f.pitch, f.speed, f.yawRate, f.pitchRate,
                f.bankTarget, f.bank, f.bankMid, f.fanMode, f.fanModeSince, f.prevFanMode, f.prevFanModeSince, f.segmentEnd, seed, t,
                f.extraFloats(), f.extraLongs());
    }

    /** {@code f} をこの中身で置き換える(道も)。 */
    public void applyTo(SkyBossFlight f) {
        f.tick = tick;
        f.x = x;
        f.y = y;
        f.z = z;
        f.px = x;
        f.py = y;
        f.pz = z;
        f.yaw = yaw;
        f.pitch = pitch;
        f.speed = speed;
        f.yawRate = yawRate;
        f.pitchRate = pitchRate;
        f.bankTarget = bankTarget;
        f.bank = bank;
        f.pbank = bank;
        f.bankMid = bankMid;
        f.fanMode = fanMode;
        f.fanModeSince = fanModeSince;
        f.prevFanMode = prevFanMode;
        f.prevFanModeSince = prevFanModeSince;
        f.segmentEnd = segmentEnd;
        f.setExtra(extF, extL);
        f.trail.clear();
        // 古い点から足す(push はいちばん新しい点になる)
        for (int i = trail.length / 3 - 1; i >= 0; i--) {
            f.trail.push(x + trail[i * 3], y + trail[i * 3 + 1], z + trail[i * 3 + 2]);
        }
    }

    private static SkyBossStatePayload read(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        if (!active) {
            return inactive();
        }
        long tick = buf.readLong();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float yaw = buf.readFloat();
        float pitch = buf.readFloat();
        float speed = buf.readFloat();
        float yawRate = buf.readFloat();
        float pitchRate = buf.readFloat();
        float bankTarget = buf.readFloat();
        float bank = buf.readFloat();
        float bankMid = buf.readFloat();
        int fanMode = buf.readVarInt();
        long fanModeSince = buf.readLong();
        int prevFanMode = buf.readVarInt();
        long prevFanModeSince = buf.readLong();
        long segmentEnd = buf.readLong();
        long seed = buf.readLong();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_POINTS) {
            throw new DecoderException("空の巨大生物の道の点が多すぎる: " + n);
        }
        float[] trail = new float[n * 3];
        for (int i = 0; i < trail.length; i++) {
            trail[i] = buf.readFloat();
        }
        int nf = buf.readVarInt();
        int nl = buf.readVarInt();
        if (nf < 0 || nf > MAX_EXTRA || nl < 0 || nl > MAX_EXTRA) {
            throw new DecoderException("空の巨大生物の追加の値が多すぎる: " + nf + ", " + nl);
        }
        float[] extF = new float[nf];
        for (int i = 0; i < nf; i++) {
            extF[i] = buf.readFloat();
        }
        long[] extL = new long[nl];
        for (int i = 0; i < nl; i++) {
            extL[i] = buf.readLong();
        }
        return new SkyBossStatePayload(true, tick, x, y, z, yaw, pitch, speed, yawRate, pitchRate, bankTarget, bank, bankMid,
                fanMode, fanModeSince, prevFanMode, prevFanModeSince, segmentEnd, seed, trail, extF, extL);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        if (!active) {
            return;
        }
        buf.writeLong(tick);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeFloat(speed);
        buf.writeFloat(yawRate);
        buf.writeFloat(pitchRate);
        buf.writeFloat(bankTarget);
        buf.writeFloat(bank);
        buf.writeFloat(bankMid);
        buf.writeVarInt(fanMode);
        buf.writeLong(fanModeSince);
        buf.writeVarInt(prevFanMode);
        buf.writeLong(prevFanModeSince);
        buf.writeLong(segmentEnd);
        buf.writeLong(seed);
        buf.writeVarInt(trail.length / 3);
        for (float v : trail) {
            buf.writeFloat(v);
        }
        buf.writeVarInt(extF.length);
        buf.writeVarInt(extL.length);
        for (float v : extF) {
            buf.writeFloat(v);
        }
        for (long v : extL) {
            buf.writeLong(v);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
