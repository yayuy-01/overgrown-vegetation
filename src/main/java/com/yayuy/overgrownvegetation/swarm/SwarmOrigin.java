package com.yayuy.overgrownvegetation.swarm;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import net.minecraft.world.entity.MobSpawnType;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 群れの<b>出どころ</b>(群れ v2、2026-09-25)。群れの attachment({@link SwarmMembership})に保存する ——
 * 読み込み直した群れがイベントのものか自然のものかを、その場の状況から推し量らずに済むように(レビュー指摘)。
 *
 * <p>出どころで変わるのは次の 5 つだけ。消えるかどうかは出どころではなく<b>個体の永続</b>で決める
 * ({@link SwarmController} の消え方。イベントが終わって {@link EventMobs} が永続を戻せば、その群れも自然に消えるようになる)。
 * <ul>
 *     <li>{@link #keepsStragglers()} … 付いて来られない個体を群れから外すか(イベントの群れは外さない)</li>
 *     <li>{@link #mergesRemnants()} … 残党を別の群れへ合流させるか(テスト・計測は合流させない)</li>
 *     <li>{@link #despawns()} … 群れの消え方の判定をするか(テスト・計測はしない)</li>
 *     <li>{@link #eventKind()} … 湧かせるときに付ける {@link EventMobs} の印</li>
 *     <li>{@link #huntsInitialTarget()} … 湧かせた側が指定した標的を狙い(距離の上限なし)にするか</li>
 * </ul>
 */
public enum SwarmOrigin {
    /** 生態系スポーン(過繁茂バイオームの中)。古いワールドの群れ(PackId)もこれ。 */
    NATURAL("natural"),
    /** 生態系の防衛({@code ecosystem/EcosystemDefense})。防衛の群れは防衛の群れとしか合流しない。 */
    DEFENSE("defense"),
    /** 小規模レイド。eventId は狙うプレイヤーの UUID({@code hunt_target} と同じ値)。 */
    SMALL_RAID("small_raid"),
    /** レイドビーコンのウェーブ。eventId はビーコンの UUID。 */
    RAID("raid"),
    /** 攻城戦の反撃の増援。eventId は攻城戦の開始時刻。 */
    SIEGE("siege"),
    /** スポロフィルの召喚で作った群れ。 */
    SUMMON("summon"),
    /** タワーディフェンスの侵攻(td-design)。eventId はイベントの UUID。 */
    TD("td"),
    /** GameTest。合流しない・消えない・索敵はテスト場の中だけ。 */
    TEST("test"),
    /** {@code /ov bench horde}。合流しない・消えない。 */
    BENCH("bench");

    public static final Codec<SwarmOrigin> CODEC = Codec.STRING.xmap(SwarmOrigin::byKey, SwarmOrigin::key);

    private final String key;

    SwarmOrigin(String key) {
        this.key = key;
    }

    /** 保存に使う名前。 */
    public String key() {
        return this.key;
    }

    /** 保存された名前から。読めなければ {@link #NATURAL}(古い・壊れたデータでも群れとしては動く)。 */
    public static SwarmOrigin byKey(@Nullable String key) {
        if (key != null) {
            String lower = key.toLowerCase(Locale.ROOT);
            for (SwarmOrigin origin : values()) {
                if (origin.key.equals(lower)) {
                    return origin;
                }
            }
        }
        return NATURAL;
    }

    /**
     * 付いて来られない個体を<b>群れに残す</b>か。イベントの群れ(小規模レイド・レイド・攻城戦・TD)は外さない ——
     * 外れた個体はイベントの標的を攻めなくなり、イベントが終わらなくなる(レビュー指摘)。テスト・計測も外さない。
     */
    public boolean keepsStragglers() {
        return switch (this) {
            case SMALL_RAID, RAID, SIEGE, TD, TEST, BENCH -> true;
            default -> false;
        };
    }

    /**
     * 湧かせた側が指定した標的を<b>狙い</b>({@link Swarm#commandTarget}: プレイヤーなら距離の上限なし・見失い 1200 tick などで諦める)
     * として持つか。false ならふつうの標的({@link Swarm#offerTarget}: 追う距離はふつうのプレイヤーと同じ)。
     * <ul>
     *     <li>狙い … 防衛(設計 v2「target = the player, via HUNT」)・小規模レイド・召喚・TD・テスト・計測。</li>
     *     <li>ふつう … レイドのウェーブ・攻城戦の増援(仮決め 2026-09-25、レビュー指摘: 以前は 1 体ずつ {@code setTarget} して
     *     ふつうの索敵が働いていた。狙いにすると、永続の群れがビーコン・攻城の場を離れて 1 人をどこまでも追う)。</li>
     * </ul>
     */
    public boolean huntsInitialTarget() {
        return this != RAID && this != SIEGE;
    }

    /** 残党を別の群れへ合流させてよいか。テストはほかのテストの群れと混ざらないように合流させない。 */
    public boolean mergesRemnants() {
        return this != TEST && this != BENCH;
    }

    /** 群れの消え方の判定をするか。テスト・計測は自分で後始末する。 */
    public boolean despawns() {
        return this != TEST && this != BENCH;
    }

    /**
     * 湧かせるときに付ける {@link EventMobs} の印の種類。付けない出どころは null。
     * TD は 2026-09-25 に {@code EventMobs.Kind.TD} を足した(印は addFreshEntity の前に書かれる)。
     */
    @Nullable
    public EventMobs.Kind eventKind() {
        return switch (this) {
            case SMALL_RAID -> EventMobs.Kind.SMALL_RAID;
            case RAID -> EventMobs.Kind.NEXUS;
            case SIEGE -> EventMobs.Kind.SIEGE;
            // TD の攻め手(2026-09-25)。eventId = イベントの UUID。生きている攻め手の数はこの印で数える(td/TdTicker)。
            case TD -> EventMobs.Kind.TD;
            default -> null;
        };
    }

    /** 湧かせるときの {@link MobSpawnType}。 */
    public MobSpawnType spawnType() {
        return switch (this) {
            case NATURAL -> MobSpawnType.NATURAL;
            case SUMMON -> MobSpawnType.MOB_SUMMONED;
            case TEST, BENCH -> MobSpawnType.COMMAND;
            default -> MobSpawnType.EVENT;
        };
    }
}
