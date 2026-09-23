package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * サーバー → クライアントへ「超大型レイドボス(攻城戦)」の状態を伝える S2C ペイロード。
 *
 * <p>ボスはチャンク読み込み範囲のはるか外を飛ぶため、通常のエンティティ同期
 * ({@code ChunkMap} のトラッキング、サーバー view-distance が上限)には一切乗らない。
 * 代わりに {@link com.yayuy.overgrownvegetation.siege.SiegeData} が
 * {@code siegeSyncIntervalTicks} ごと、およびビームの段階が変わるたびに<b>距離を問わず</b>
 * オーバーワールドの全プレイヤーへこれを送る。
 *
 * <p>座標そのものではなく<b>軌道パラメータ + 経過 tick</b> を送るのがこの設計の要点で、
 * クライアントは {@link com.yayuy.overgrownvegetation.siege.SiegeBossMotion} の同じ式から
 * 毎フレームの位置を自分で求める。そのため毎 tick の座標同期が要らず、パケットを取りこぼしても
 * 次の再送で必ず追従でき、途中ログインしたプレイヤーもそのまま合流できる
 * ({@link EarthquakeShakePayload} と同じ考え方)。<b>撃破後の落下も同じ流儀</b>で、
 * 「いつ死んだか」と「着地する Y」の 2 つだけ送れば
 * {@link com.yayuy.overgrownvegetation.siege.SiegeDeathFall} が両側で同じ絵を出す。
 *
 * @param active      攻城戦が進行中か。false のときは他のフィールドは無視してよい(終了通知)
 * @param centerX     周回の中心 X(開始時のプレイヤー位置)
 * @param centerY     周回の中心 Y
 * @param centerZ     周回の中心 Z
 * @param radius      周回半径(ブロック)
 * @param altitude    飛行高度の基準 Y
 * @param speed       周回の速さ(ブロック/tick)
 * @param elapsed     攻城戦開始からの経過 tick
 * @param scale       描画時のモデル倍率
 * @param health      現在体力
 * @param maxHealth   最大体力
 * @param neckYaw     首のヨー差分（胴基準・度）。クライアントは自前の追従がずれたときだけこれに合わせる
 * @param neckPitch   首のピッチ差分（胴基準・度）
 * @param approach    1 tick に縮める周回半径(ブロック)。0 でただの周回
 * @param minRadius   これ以上は中心へ寄らない周回半径(ブロック)
 * @param flower      花のビーム(遠距離砲撃)の状態
 * @param tentacle    触手ビーム(近距離)の段階とタイミング。狙点は {@code volley} 側
 * @param volley      触手ビーム 1 斉射ぶんの「どの触手から・どこへ」の一覧(既定 8 本)
 * @param deathAt     撃破された瞬間の経過 tick。生きているあいだは負の値
 * @param deathGroundY 落ちて着地する Y
 */
public record SiegeBossStatePayload(boolean active, double centerX, double centerY, double centerZ,
                                    float radius, float altitude, float speed, int elapsed,
                                    float scale, float health, float maxHealth, float neckYaw, float neckPitch,
                                    float approach, float minRadius,
                                    BeamState flower, BeamState tentacle, TentacleVolley volley,
                                    float deathAt, float deathGroundY) implements CustomPacketPayload {

    /**
     * ビーム 1 本ぶんの状態。
     *
     * @param phase        段階({@code SiegeData.BEAM_IDLE/CHARGE/FIRE/RECOVER})
     * @param elapsed      現在の段階に入ってからの経過 tick
     * @param chargeTicks  溜めの長さ
     * @param fireTicks    照射の長さ
     * @param recoverTicks 照射後の復帰の長さ
     * @param index        触手ビームのとき、どの触手から撃つか(斉射の 1 本目)
     * @param target       着弾点(ワールド座標。斉射の 1 本目)
     */
    public record BeamState(int phase, int elapsed, int chargeTicks, int fireTicks, int recoverTicks,
                            int index, Vec3 target) {

        public static final BeamState IDLE = new BeamState(0, 0, 1, 1, 1, 0, Vec3.ZERO);

        private static BeamState read(FriendlyByteBuf buf) {
            return new BeamState(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(phase);
            buf.writeVarInt(elapsed);
            buf.writeVarInt(chargeTicks);
            buf.writeVarInt(fireTicks);
            buf.writeVarInt(recoverTicks);
            buf.writeVarInt(index);
            buf.writeDouble(target.x);
            buf.writeDouble(target.y);
            buf.writeDouble(target.z);
        }
    }

    /**
     * 触手ビーム 1 斉射ぶん。{@code indices[i]} の触手から {@code targets[i]} へ 1 本ずつ、
     * <b>同時に</b>撃つ({@code siegeTentacleBeamCount}、既定 8 本)。狙いはそれぞれ別の相手。
     */
    public record TentacleVolley(int[] indices, Vec3[] targets) {

        public static final TentacleVolley EMPTY = new TentacleVolley(new int[0], new Vec3[0]);

        public int size() {
            return Math.min(indices.length, targets.length);
        }

        private static TentacleVolley read(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            int[] indices = new int[count];
            Vec3[] targets = new Vec3[count];
            for (int i = 0; i < count; i++) {
                indices[i] = buf.readVarInt();
                targets[i] = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            }
            return new TentacleVolley(indices, targets);
        }

        private void write(FriendlyByteBuf buf) {
            int count = size();
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeVarInt(indices[i]);
                buf.writeDouble(targets[i].x);
                buf.writeDouble(targets[i].y);
                buf.writeDouble(targets[i].z);
            }
        }
    }

    public static final Type<SiegeBossStatePayload> TYPE =
            new Type<>(OvergrownVegetation.id("siege_boss_state"));

    public static final StreamCodec<FriendlyByteBuf, SiegeBossStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(SiegeBossStatePayload::write, SiegeBossStatePayload::new);

    /** 撃破されて落下中か。 */
    public boolean dying() {
        return deathAt >= 0.0F;
    }

    /** 「攻城戦は行われていない」を表すペイロード(終了通知)。 */
    public static SiegeBossStatePayload inactive() {
        return new SiegeBossStatePayload(false, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F, 0, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, BeamState.IDLE, BeamState.IDLE, TentacleVolley.EMPTY, -1.0F, 0.0F);
    }

    private SiegeBossStatePayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                BeamState.read(buf), BeamState.read(buf), TentacleVolley.read(buf),
                buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeDouble(centerX);
        buf.writeDouble(centerY);
        buf.writeDouble(centerZ);
        buf.writeFloat(radius);
        buf.writeFloat(altitude);
        buf.writeFloat(speed);
        buf.writeVarInt(elapsed);
        buf.writeFloat(scale);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeFloat(neckYaw);
        buf.writeFloat(neckPitch);
        buf.writeFloat(approach);
        buf.writeFloat(minRadius);
        flower.write(buf);
        tentacle.write(buf);
        volley.write(buf);
        buf.writeFloat(deathAt);
        buf.writeFloat(deathGroundY);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
