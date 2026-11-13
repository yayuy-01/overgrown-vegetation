package com.yayuy.overgrownvegetation.data;

/**
 * 除染(手洗いと地脈の洗い場)の設計値({@code spore_field.json} の {@code decon}、2026-11-14 サバイバルの背骨 段 3a)。
 * 仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 30・31。読むのは {@code survival/decon/}。
 * 「覆われた○○ → 元の素材 + 胞子」の変換表はここではなくデータパックのレシピ(種類 {@code overgrown_vegetation:decontamination}、
 * {@code data/overgrown_vegetation/recipe/decon_*.json})に持つ(mod マネージャー 2 の作り方の画面で触れるように)。
 *
 * <h2>ユーザー指定(11-14)</h2>
 * <ul>
 *     <li>除染の手段は手洗い + 地脈の機械。序盤は水の入った大釜で 1 つずつ手で洗う(水が減る・遅い)。機械は地脈の力(FE)で、まとめて速く自動で洗う。</li>
 *     <li>どちらも素材・装備・フィルターを洗える。フィルターは段 2c の「洗える回数・洗うごとに容量が減る」をそのまま使う。</li>
 * </ul>
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 手洗いは 1 回ごとに {@code handCooldownTicks} 10 tick 待つ。水は「洗いの重み」を数え、{@code handWorkPerLevel} 3 たまるごとに大釜の水が 1 段減る
 * (素材 1 つ = 1、装備・フィルター 1 つ = 3。満タンの大釜で素材 9 つ、装備なら 3 つ)。
 * 洗い場は 20 FE/t で、素材・フィルターを 5 tick に 1 つ(1 秒 4 つ)。水は要らない(地下水を地脈の力で汲む設定)。上に落ちた物を吸い込む。
 */
public record DeconSpec(
        int handCooldownTicks,
        int handWorkPerLevel,
        int handWorkMaterial,
        int handWorkOther,
        int machineFePerTick,
        int machineEnergyBuffer,
        int machineTicksPerItem,
        boolean machinePickup) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** 手洗い 1 回の後に待つ tick(同じ種類の物)。 */
        public Integer handCooldownTicks;
        /** 洗いの重みがこれだけたまるごとに大釜の水が 1 段減る。 */
        public Integer handWorkPerLevel;
        /** 素材(覆われた○○)1 つを手で洗う重み。 */
        public Integer handWorkMaterial;
        /** 装備・フィルター 1 つを手で洗う重み。 */
        public Integer handWorkOther;
        /** 洗い場が洗っている間に使う FE / tick。 */
        public Integer machineFePerTick;
        /** 洗い場の中にためておける FE。 */
        public Integer machineEnergyBuffer;
        /** 洗い場が 1 つ洗うのにかかる tick。 */
        public Integer machineTicksPerItem;
        /** 洗い場の上に落ちた物を吸い込むか。 */
        public Boolean machinePickup;
    }

    public static final DeconSpec DEFAULT = from(null);

    public static DeconSpec from(Json j) {
        Json s = j != null ? j : new Json();
        return new DeconSpec(
                i(s.handCooldownTicks, 10, 0, 1200),
                i(s.handWorkPerLevel, 3, 1, 1000),
                i(s.handWorkMaterial, 1, 0, 1000),
                i(s.handWorkOther, 3, 0, 1000),
                i(s.machineFePerTick, 20, 0, 1_000_000),
                i(s.machineEnergyBuffer, 4_000, 1, 100_000_000),
                i(s.machineTicksPerItem, 5, 1, 12_000),
                s.machinePickup == null || s.machinePickup);
    }

    private static int i(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }
}
