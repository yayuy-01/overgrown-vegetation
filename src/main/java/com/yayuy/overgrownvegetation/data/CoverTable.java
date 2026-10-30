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
    }

    private final List<String> textures;
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

    private CoverTable(Json j) {
        Json src = j != null ? j : new Json();
        this.textures = src.textures != null && !src.textures.isEmpty() ? List.copyOf(src.textures)
                : List.of("cover_1", "cover_2", "cover_3", "cover_4", "cover_5");
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
    }

    public static CoverTable fromJson(Json j) {
        return j == null ? DEFAULT : new CoverTable(j);
    }

    public List<String> textures() {
        return textures;
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
}
