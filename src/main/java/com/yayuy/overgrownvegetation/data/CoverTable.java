package com.yayuy.overgrownvegetation.data;

import java.util.List;

/**
 * 覆いの設計値({@code cover.json}、2026-10-30)。覆い = 汚染が元のブロックを置き換えずにチャンクの記録で覆うこと
 * ({@code corruption/OvergrownLayerData})。作り直し側({@code overgrown_vegetation/mod})の {@code corruption.json} の
 * {@code cover} 節と {@code covered} 節をそのまま持ってきて、前は Java に置いていた {@code chance}・{@code encase}
 * ({@code OvergrownLayerField}。もとは climax.json の {@code overgrownLayer})を足した。
 *
 * <ul>
 *     <li>{@code textures} … 面に貼る絵の名前({@code textures/block/cover/<名前>.png}。{@code <名前>_top.png} があれば上面だけそれ)。
 *     ユーザーが描いた 5 枚(作り直し側から)</li>
 *     <li>{@code waterTextures}・{@code seaTextures} … 水に接した面に貼る絵(川・池の水中の藻の覆い 3 枚・海底の藻の丘 2 枚、2026-11-01 水中の植物。海かどうかはバイオーム)</li>
 *     <li>{@code rotate} … 面ごとに 4 向きに回すか(作り直し側の値)</li>
 *     <li>{@code mipCoverage} … 遠くの段(ミップの 1〜4 段目)の不透明の割合を、近くの割合の何倍にするか。1 = 近くと同じ(作り直し側の値)</li>
 *     <li>{@code push} … 元の面が cutout・半透明の層のときだけ外へ出す距離(固い層は同じ頂点に重ねる。作り直し側の値)</li>
 *     <li>{@code peelDamage}・{@code peelParticles}・{@code peelSpread}・{@code peelSpeed} … ハサミで剥がすときの耐久の減りと粒(作り直し側の値)</li>
 *     <li>{@code lightKeep} … 覆われた光源の明るさの倍率(2026-10-05 ユーザー指定 −60%)</li>
 *     <li>{@code stuckChance} … 覆われた扉・ボタンなどが開ける・押す向きで動かない割合(2026-10-05 の 75%)</li>
 *     <li>{@code chance} … 立方体を覆う割合(外れは過繁茂ブロック。前の {@code overgrownLayer.chance} 0.7)</li>
 *     <li>{@code encase} … 中身を持つブロックを機能の無いブロック(overgrown_husk)へ差し替えるか(前の {@code overgrownLayer.encase})</li>
 * </ul>
 */
public final class CoverTable {

    public static final CoverTable DEFAULT = new CoverTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public List<String> textures;
        public List<String> waterTextures, seaTextures;
        /** ネザーで使う覆いの絵(2026-11-11)。{@code <名前>_top} は下地がタグ {@code nether_cover_ground_top} のブロックの上面だけ。 */
        public List<String> netherTextures;
        public Boolean rotate;
        public List<Double> mipCoverage;
        public Double push;
        public Integer peelDamage;
        public Integer peelParticles;
        public Double peelSpread;
        public Double peelSpeed;
        public Double lightKeep;
        public Double stuckChance;
        public Double chance;
        public Boolean encase;
        public Double creepPush;
        public Double creepSeconds;
        public Double baseDarken;
        public Integer skirtHeight;
        public Double skirtTallChance;
        public Double hollowChance;
        public Integer hollowMinStep, hollowMaxStep;
        public Double hollowReach, hollowCooldownSeconds;
        public Integer hollowBugsMin, hollowBugsMax;
        public Double hollowEyesPerMinute, hollowEyesSeconds;
        public Double settleMinSeconds, settleMaxSeconds;
    }

    private final List<String> textures;
    private final List<String> waterTextures;
    private final List<String> seaTextures;
    private final List<String> netherTextures;
    private final boolean rotate;
    private final double[] mipCoverage;
    private final double push;
    private final int peelDamage;
    private final int peelParticles;
    private final double peelSpread;
    private final double peelSpeed;
    private final double lightKeep;
    private final double stuckChance;
    private final double chance;
    private final boolean encase;
    private final double creepPush;
    private final double creepSeconds;
    private final double baseDarken;
    private final int skirtHeight;
    private final double skirtTallChance;
    private final double hollowChance;
    private final int hollowMinStep;
    private final int hollowMaxStep;
    private final double hollowReach;
    private final double hollowCooldownSeconds;
    private final int hollowBugsMin;
    private final int hollowBugsMax;
    private final double hollowEyesPerMinute;
    private final double hollowEyesSeconds;
    private final double settleMinSeconds;
    private final double settleMaxSeconds;

    private CoverTable(Json j) {
        Json src = j != null ? j : new Json();
        this.textures = src.textures != null && !src.textures.isEmpty() ? List.copyOf(src.textures)
                : java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(n -> "cover_" + n).toList(); // 10-31 に 25 枚へ(tools/cover-art)
        this.waterTextures = src.waterTextures != null ? List.copyOf(src.waterTextures)
                : List.of("water_algae_cover_a", "water_algae_cover_b", "water_algae_cover_c"); // 11-01 水中の植物(tools/water-plants)
        this.seaTextures = src.seaTextures != null ? List.copyOf(src.seaTextures) : List.of("sea_algae_cover_a", "sea_algae_cover_b");
        this.netherTextures = src.netherTextures != null ? List.copyOf(src.netherTextures) : List.of();
        this.rotate = src.rotate == null || src.rotate;
        double[] mips = {1.0D, 1.0D, 1.0D, 1.0D};
        if (src.mipCoverage != null) {
            for (int i = 0; i < mips.length && i < src.mipCoverage.size(); i++) {
                Double v = src.mipCoverage.get(i);
                mips[i] = v != null ? Math.max(0.0D, Math.min(4.0D, v)) : 1.0D;
            }
        }
        this.mipCoverage = mips;
        this.push = src.push != null ? Math.max(0.0D, Math.min(0.1D, src.push)) : 0.002D;
        this.peelDamage = src.peelDamage != null ? Math.max(0, src.peelDamage) : 1;
        this.peelParticles = src.peelParticles != null ? Math.max(0, src.peelParticles) : 12;
        this.peelSpread = src.peelSpread != null ? Math.max(0.0D, src.peelSpread) : 0.35D;
        this.peelSpeed = src.peelSpeed != null ? Math.max(0.0D, src.peelSpeed) : 0.05D;
        this.lightKeep = src.lightKeep != null ? Math.max(0.0D, Math.min(1.0D, src.lightKeep)) : 0.4D;
        this.stuckChance = src.stuckChance != null ? Math.max(0.0D, Math.min(1.0D, src.stuckChance)) : 0.75D;
        this.chance = src.chance != null ? Math.max(0.0D, Math.min(1.0D, src.chance)) : 0.7D;
        this.encase = src.encase == null || src.encase;
        this.creepPush = src.creepPush != null ? Math.max(0.0D, Math.min(0.02D, src.creepPush)) : 0.003D;
        this.creepSeconds = src.creepSeconds != null ? Math.max(0.0D, src.creepSeconds) : 120.0D;
        this.baseDarken = src.baseDarken != null ? Math.max(0.0D, Math.min(1.0D, src.baseDarken)) : 0.7D;
        this.skirtHeight = src.skirtHeight != null ? Math.max(0, Math.min(2, src.skirtHeight)) : 2;
        this.skirtTallChance = src.skirtTallChance != null ? Math.max(0.0D, Math.min(1.0D, src.skirtTallChance)) : 0.3D;
        this.hollowChance = src.hollowChance != null ? Math.max(0.0D, Math.min(1.0D, src.hollowChance)) : 0.125D;
        this.hollowMinStep = src.hollowMinStep != null ? Math.max(1, src.hollowMinStep) : 2;
        this.hollowMaxStep = src.hollowMaxStep != null ? Math.max(this.hollowMinStep, src.hollowMaxStep) : Math.max(this.hollowMinStep, 4);
        this.hollowReach = src.hollowReach != null ? Math.max(0.0D, src.hollowReach) : 4.0D;
        this.hollowCooldownSeconds = src.hollowCooldownSeconds != null ? Math.max(0.0D, src.hollowCooldownSeconds) : 30.0D;
        this.hollowBugsMin = src.hollowBugsMin != null ? Math.max(0, src.hollowBugsMin) : 3;
        this.hollowBugsMax = src.hollowBugsMax != null ? Math.max(this.hollowBugsMin, src.hollowBugsMax) : Math.max(this.hollowBugsMin, 6);
        this.hollowEyesPerMinute = src.hollowEyesPerMinute != null ? Math.max(0.0D, src.hollowEyesPerMinute) : 1.0D;
        this.hollowEyesSeconds = src.hollowEyesSeconds != null ? Math.max(0.05D, src.hollowEyesSeconds) : 0.5D;
        // 覆った砂・砂利が草ブロックになるまで(11-01 ユーザー指定の仕組み、秒は仮決め 5〜15 分)
        this.settleMinSeconds = src.settleMinSeconds != null ? Math.max(0.0D, src.settleMinSeconds) : 300.0D;
        this.settleMaxSeconds = src.settleMaxSeconds != null ? Math.max(this.settleMinSeconds, src.settleMaxSeconds) : Math.max(this.settleMinSeconds, 900.0D);
    }

    public static CoverTable fromJson(Json j) {
        return j == null ? DEFAULT : new CoverTable(j);
    }

    public List<String> textures() {
        return textures;
    }

    /** 水に接した面に貼る絵(川・池。海は {@link #seaTextures})。空なら陸の絵のまま。 */
    public List<String> waterTextures() {
        return waterTextures;
    }

    /** ネザーで使う覆いの絵(2026-11-11。空ならオーバーワールドと同じ絵)。 */
    public List<String> netherTextures() {
        return netherTextures;
    }

    /** 海の水に接した面に貼る絵(海底の藻の丘)。空なら {@link #waterTextures}。 */
    public List<String> seaTextures() {
        return seaTextures;
    }

    public boolean rotate() {
        return rotate;
    }

    /** ミップの {@code level} 段目(1〜)の倍率。5 段目より先は 4 段目の値。 */
    public double mipCoverage(int level) {
        return mipCoverage[Math.max(0, Math.min(mipCoverage.length - 1, level - 1))];
    }

    public double push() {
        return push;
    }

    public int peelDamage() {
        return peelDamage;
    }

    public int peelParticles() {
        return peelParticles;
    }

    public double peelSpread() {
        return peelSpread;
    }

    public double peelSpeed() {
        return peelSpeed;
    }

    public double lightKeep() {
        return lightKeep;
    }

    public double stuckChance() {
        return stuckChance;
    }

    public double chance() {
        return chance;
    }

    public boolean encase() {
        return encase;
    }

    /** 這い上がる苔の絵を面の外へ出す距離(ブロック。28、11-01 仮決め 0.003。元の面と奥行きを競らせない)と、置いてから最終段までの秒(仮 120)。 */
    public double creepPush() {
        return creepPush;
    }

    public double creepSeconds() {
        return creepSeconds;
    }

    /** 覆いの下に見える元の面の明るさの倍率(2026-10-31 夜ユーザー決定、仮 0.7。置き換えブロックの絵の「苔の間から見える元の面」と揃える。1 で暗くしない)。 */
    public double baseDarken() {
        return baseDarken;
    }

    /** 幹の苔のスカートの段数(31、10-31 仮決め 2。0 で無し)と、1 段高い木の割合(仮 0.3)。 */
    public int skirtHeight() {
        return skirtHeight;
    }

    public double skirtTallChance() {
        return skirtTallChance;
    }

    /** 木の洞(43、10-31 仮決め): 洞のある木の割合・幹の何段目(根元が 1)・気配が出る距離・虫が出たあと休む秒・虫の数・目が光る 1 分あたりの回数と長さ(秒)。 */
    public double hollowChance() {
        return hollowChance;
    }

    public int hollowMinStep() {
        return hollowMinStep;
    }

    public int hollowMaxStep() {
        return hollowMaxStep;
    }

    public double hollowReach() {
        return hollowReach;
    }

    public double hollowCooldownSeconds() {
        return hollowCooldownSeconds;
    }

    public int hollowBugsMin() {
        return hollowBugsMin;
    }

    public int hollowBugsMax() {
        return hollowBugsMax;
    }

    public double hollowEyesPerMinute() {
        return hollowEyesPerMinute;
    }

    public double hollowEyesSeconds() {
        return hollowEyesSeconds;
    }

    public double settleMinSeconds() {
        return settleMinSeconds;
    }

    public double settleMaxSeconds() {
        return settleMaxSeconds;
    }
}
