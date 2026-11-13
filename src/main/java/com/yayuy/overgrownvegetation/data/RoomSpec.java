package com.yayuy.overgrownvegetation.data;

/**
 * 閉じた部屋の判定の設計値({@code spore_field.json} の {@code room}、2026-11-13 サバイバルの背骨 段 2a)。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 12・17。
 * 読むのは {@link com.yayuy.overgrownvegetation.survival.room.RoomCache}。部屋の中の濃度の倍率は今までどおり {@code reading.roomFactor}。
 *
 * <h2>ユーザー指定(11-13)</h2>
 * <ul>
 *     <li>立っている所からつながる空間をたどり、外へ抜けるすき間が無ければ部屋。ドア・ガラス・地下・広間も正しく扱う。大きさに上限・結果を覚えて重くしない。</li>
 *     <li>ドアは閉じていれば壁・開けている間はすき間。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 上限 4096 マス(16×16×16 相当。幅 20 × 奥行 20 × 高さ 4 の広間 1600 が入る)・どの向きも 64 ブロックまで・
 * 部屋だった結果は 6000 tick、部屋でなかった結果は 200 tick 覚える(ブロックが変われば待たずに捨てる)・1 tick に新しくたどるのは 1 次元 4 回まで・
 * 覚えるマスの合計が 300000 を超えたら全部捨てる・「人の手のブロック」が境界に 1 つも無い空間(自然の洞窟のふさがった袋)は部屋にしない。
 */
public record RoomSpec(
        boolean enabled,
        int maxCells,
        int maxSpan,
        int roomTtlTicks,
        int openTtlTicks,
        int maxSearchesPerTick,
        int maxCachedCells,
        boolean requireBuiltWall) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** 部屋の判定を使う(false = どこも部屋ではない)。 */
        public Boolean enabled;
        /** 部屋の大きさの上限(空気のマスの数)。超えたら部屋ではない(外につながる大きな空間と同じ扱い)。 */
        public Integer maxCells;
        /** 部屋の差し渡しの上限(ブロック。東西・上下・南北のどれか)。細長いトンネルを上限まで追わない。 */
        public Integer maxSpan;
        /** 「部屋だった」結果を覚える長さ(tick)。中や境界のブロックが変われば待たずに捨てる。 */
        public Integer roomTtlTicks;
        /** 「部屋でなかった」結果を覚える長さ(tick)。遠くでふさいだのは見えないので短め。 */
        public Integer openTtlTicks;
        /** 1 tick・1 次元あたり、新しくたどる回数の上限(超えた分はその回は「部屋でない」と読む)。 */
        public Integer maxSearchesPerTick;
        /** 1 次元で覚えるマスの合計の上限(超えたら全部捨てる)。 */
        public Integer maxCachedCells;
        /** 境界に「人の手のブロック」(タグ room_natural_wall に入っていないブロック)が 1 つも無ければ部屋にしない(自然の洞窟を除く)。 */
        public Boolean requireBuiltWall;
    }

    public static final RoomSpec DEFAULT = from(null);

    public static RoomSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new RoomSpec(
                s.enabled == null || s.enabled,
                clamp(s.maxCells, 4096, 8, 65536),
                clamp(s.maxSpan, 64, 3, 256),
                clamp(s.roomTtlTicks, 6000, 20, 72000),
                clamp(s.openTtlTicks, 200, 1, 72000),
                clamp(s.maxSearchesPerTick, 4, 1, 64),
                clamp(s.maxCachedCells, 300_000, 1000, 5_000_000),
                s.requireBuiltWall == null || s.requireBuiltWall);
    }

    private static int clamp(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }
}
