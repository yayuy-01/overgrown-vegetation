package com.yayuy.overgrownvegetation.data;

/**
 * ポータルからネザー・エンドへの侵食の設計値({@code portal.json}、2026-10-02)。
 *
 * <p>4 つの設計判断のうち、切り替えられるものをここに置く(既定値はどちらもユーザー指定 2026-10-02)。
 * <ul>
 *     <li>{@code backflow} … 向こうの侵食がポータル経由でこちらへ戻ってくるか(既定 true = 両向き)</li>
 *     <li>{@code endFrameNeedsEye} … 目のはまっていないエンドポータルの枠でも取り込めるか(既定 false = 目は要らない)</li>
 * </ul>
 * {@code seedRadius}・{@code seedNoise} は仮決め(G2 の死亡時の汚染球と同じ作り方で、向こう側の出口のまわりに置く球)。
 */
public final class PortalTable {

    public static final PortalTable DEFAULT = new PortalTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public Boolean enabled;
        public Boolean backflow;
        public Boolean endFrameNeedsEye;
        public Integer seedRadius;
        public Double seedNoise;
    }

    private final boolean enabled;
    private final boolean backflow;
    private final boolean endFrameNeedsEye;
    private final int seedRadius;
    private final double seedNoise;

    private PortalTable(Json j) {
        Json src = j != null ? j : new Json();
        this.enabled = src.enabled == null || src.enabled;
        this.backflow = src.backflow == null || src.backflow;
        this.endFrameNeedsEye = src.endFrameNeedsEye != null && src.endFrameNeedsEye;
        this.seedRadius = src.seedRadius != null ? Math.max(1, Math.min(16, src.seedRadius)) : 6;
        this.seedNoise = src.seedNoise != null ? Math.max(0.0D, Math.min(6.0D, src.seedNoise)) : 2.0D;
    }

    public static PortalTable fromJson(Json j) {
        return j == null ? DEFAULT : new PortalTable(j);
    }

    /** false ならポータルを越えた侵食は起きない(枠を壊さずに覆う保護だけは常に効く)。 */
    public boolean enabled() {
        return enabled;
    }

    public boolean backflow() {
        return backflow;
    }

    public boolean endFrameNeedsEye() {
        return endFrameNeedsEye;
    }

    public int seedRadius() {
        return seedRadius;
    }

    public double seedNoise() {
        return seedNoise;
    }
}
