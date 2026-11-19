package com.yayuy.overgrownvegetation.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 襲撃の規模(小・中・大)を「見送りと貯め」で決める設計値({@code director.json} の {@code raidScale}、2026-11-19)。
 * 正本は Vault「F5 の襲撃(最初の襲撃と送り込み)(11-16)」の末尾「11-19 ユーザー決定: 襲撃の規模」。
 *
 * <p>ユーザーが決めたのは仕組み: 襲撃の機会ごとに一定の確率で見送り、見送った日は 1 日の生産量の数割を襲撃の予算として貯める・
 * 起きたら貯めた予算で出せるだけ出す・フェーズごとに貯められる日数の上限(届いたら必ず起きる)・小と中は F5 から、大は F6 から・
 * 超大は 0.2.0 では入れない(表に行を足せば入る形にだけしておく)。<b>数はどれも【仮】</b>(全体管理の案をユーザーが【仮】で採った)。
 */
public final class RaidScaleTable {

    public static final RaidScaleTable DEFAULT = new RaidScaleTable(null);

    public static final class Json {
        public Boolean enabled;
        /** フェーズ(F0〜F10)ごとの、機会に見送る確率。 */
        public List<Double> skipChance;
        /** フェーズごとの、貯められる日数の上限。 */
        public List<Double> maxSaveDays;
        /** 見送った間に貯める割合(1 日の生産量に掛ける)。 */
        public Double purseShare;
        /** 見送って貯め始めてからこの日数がたった後だけ G1 が遠巻きにうろつく(11-19 ユーザー「貯めの 2 日目から」)。 */
        public Double holdOffFromDays;
        public List<TierJson> tiers;
    }

    public static final class TierJson {
        public String name;
        public Double fromDays;
        public Integer minPhase;
        public Double costFactor;
        public Integer maxAlive;
        public Integer maxTotal;
        public Double maxDays;
        public Double omenDays;
    }

    /**
     * 規模の 1 段。name は {@code RaidScale} の名前(small・medium・large。後で huge)。
     * fromDays = 貯めた日数がこれ以上なら この段・minPhase = このフェーズから出せる・costFactor = Mob の値段の倍率(超大の −80% は 0.2)・
     * maxAlive / maxTotal = 同時の上限・その回の合計の上限(0 = その段の作りの既定)・maxDays = その回の長さの上限(0 = 既定)・omenDays = 決まってから来るまでの前触れ(森の湧きが止まって静かになる。0 = すぐ来る)。
     */
    public record Tier(String name, double fromDays, int minPhase, double costFactor, int maxAlive, int maxTotal, double maxDays, double omenDays) {
    }

    public final boolean enabled;
    public final double[] skipChance;
    public final double[] maxSaveDays;
    public final double purseShare;
    public final double holdOffFromDays;
    /** fromDays の小さい順。 */
    public final List<Tier> tiers;

    private RaidScaleTable(Json j) {
        Json s = j != null ? j : new Json();
        this.enabled = s.enabled == null || s.enabled;
        this.skipChance = list(s.skipChance, new double[]{0, 0, 0, 0, 0, 0.4, 0.5, 0.6, 0.6, 0.6, 0.6}, 1.0D);
        this.maxSaveDays = list(s.maxSaveDays, new double[]{0, 0, 0, 0, 0, 2, 5, 10, 10, 10, 10}, Double.MAX_VALUE);
        this.purseShare = s.purseShare == null ? 0.3D : Math.max(0.0D, s.purseShare);
        this.holdOffFromDays = s.holdOffFromDays == null ? 1.0D : Math.max(0.0D, s.holdOffFromDays);
        List<Tier> t = new ArrayList<>();
        if (s.tiers != null) {
            for (TierJson tj : s.tiers) {
                if (tj == null || tj.name == null) continue;
                t.add(new Tier(tj.name, nn(tj.fromDays, 0.0D), tj.minPhase == null ? 0 : Math.max(0, tj.minPhase),
                        tj.costFactor == null ? 1.0D : Math.max(0.01D, tj.costFactor), tj.maxAlive == null ? 0 : Math.max(0, tj.maxAlive),
                        tj.maxTotal == null ? 0 : Math.max(0, tj.maxTotal), nn(tj.maxDays, 0.0D), nn(tj.omenDays, 0.0D)));
            }
        }
        if (t.isEmpty()) {
            t.add(new Tier("small", 0.0D, 5, 1.0D, 0, 0, 0.0D, 0.0D));
            t.add(new Tier("medium", 0.5D, 5, 1.0D, 40, 80, 1.0D, 0.0D));
            t.add(new Tier("large", 3.0D, 6, 1.0D, 0, 0, 1.0D, 2.0D));
        }
        t.sort((a, b) -> Double.compare(a.fromDays(), b.fromDays()));
        this.tiers = Collections.unmodifiableList(t);
    }

    public static RaidScaleTable fromJson(Json j) {
        return j == null ? DEFAULT : new RaidScaleTable(j);
    }

    /** その名前の段(無ければ null)。 */
    @javax.annotation.Nullable
    public Tier tier(String name) {
        for (Tier t : tiers) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    public double skipChance(int phase) {
        return skipChance[Math.max(0, Math.min(skipChance.length - 1, phase))];
    }

    public double maxSaveDays(int phase) {
        return maxSaveDays[Math.max(0, Math.min(maxSaveDays.length - 1, phase))];
    }

    private static double[] list(List<Double> in, double[] def, double max) {
        double[] out = def.clone();
        if (in != null) {
            for (int i = 0; i < Math.min(out.length, in.size()); i++) {
                Double v = in.get(i);
                if (v != null) out[i] = Math.max(0.0D, Math.min(max, v));
            }
        }
        return out;
    }

    private static double nn(Double v, double def) {
        return v == null ? def : Math.max(0.0D, v);
    }
}
