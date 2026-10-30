package com.yayuy.overgrownvegetation.swarm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * 群れの一員が<b>保存する</b>所属(attachment {@code swarm}、群れ v2、2026-09-25)。
 * 群れそのもの({@link Swarm})は保存しない実行時のデータで、読み込まれた個体の所属から組み直す。
 *
 * @param id      群れの ID
 * @param slot    群れの中の番号(陣形の並べ替えの鍵にだけ使う。重なっても陣形は重ならない)。
 *                負なら未定(古いワールドから引き継いだ個体)で、登録のときに振る
 * @param origin  出どころ({@link SwarmOrigin})
 * @param eventId イベントを見分ける値(無ければ "")
 * @param peak    群れがこれまでにいちばん大きかったときの頭数(残党の判定に使う)。0 なら不明
 * @param climber 壁・天井を登る群れか({@link Swarm#climber}。TD の攻め手の一部、2026-09-25 のユーザー指定
 *                「アルボラは壁や天井を通ってターゲットブロックへ行くルートも」)。群れは最初に登録された一員の値を使う
 */
public record SwarmMembership(UUID id, int slot, SwarmOrigin origin, String eventId, int peak, boolean climber) {

    /** 「所属なし」を表す ID(attachment の既定値。保存しない)。 */
    public static final UUID NIL = new UUID(0L, 0L);

    /**
     * attachment の既定値。{@code getData} で読むと作られてしまうので、群れの側は必ず
     * {@code getExistingData} で読む。万一作られても保存はしない(ModAttachments の serialize の条件)。
     */
    public static final SwarmMembership NONE = new SwarmMembership(NIL, -1, SwarmOrigin.NATURAL, "", 0);

    /** {@code climber} は無ければ false(それ以前に保存された所属)。 */
    public static final Codec<SwarmMembership> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(SwarmMembership::id),
            Codec.INT.optionalFieldOf("slot", -1).forGetter(SwarmMembership::slot),
            SwarmOrigin.CODEC.optionalFieldOf("origin", SwarmOrigin.NATURAL).forGetter(SwarmMembership::origin),
            Codec.STRING.optionalFieldOf("event", "").forGetter(SwarmMembership::eventId),
            Codec.INT.optionalFieldOf("peak", 0).forGetter(SwarmMembership::peak),
            Codec.BOOL.optionalFieldOf("climber", false).forGetter(SwarmMembership::climber)
    ).apply(instance, SwarmMembership::new));

    public SwarmMembership {
        eventId = eventId != null ? eventId : "";
        origin = origin != null ? origin : SwarmOrigin.NATURAL;
    }

    /** 登らない群れの所属。 */
    public SwarmMembership(UUID id, int slot, SwarmOrigin origin, String eventId, int peak) {
        this(id, slot, origin, eventId, peak, false);
    }

    /** 所属なし(既定値)か。 */
    public boolean isNone() {
        return NIL.equals(this.id);
    }

    public SwarmMembership withSlot(int newSlot) {
        return newSlot == this.slot ? this
                : new SwarmMembership(this.id, newSlot, this.origin, this.eventId, this.peak, this.climber);
    }

    public SwarmMembership withPeak(int newPeak) {
        return newPeak == this.peak ? this
                : new SwarmMembership(this.id, this.slot, this.origin, this.eventId, newPeak, this.climber);
    }

    public SwarmMembership withClimber(boolean newClimber) {
        return newClimber == this.climber ? this
                : new SwarmMembership(this.id, this.slot, this.origin, this.eventId, this.peak, newClimber);
    }
}
