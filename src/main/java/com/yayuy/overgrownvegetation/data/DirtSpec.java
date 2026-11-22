package com.yayuy.overgrownvegetation.data;

/**
 * 装備の汚れ(段 3b)と、汚れた物で部屋が汚れる(段 3c)の設計値({@code spore_field.json} の {@code dirt}、2026-11-14 サバイバルの背骨)。
 * 仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 32・33。読むのは {@code survival/decon/GearDirt}・{@code survival/room/RoomAir}。
 *
 * <h2>ユーザー指定(11-14)</h2>
 * <ul>
 *     <li>濃い所にいた分だけ装備が汚れる。汚れた装備は、着ているときれいな空気の中でも暴露量が少しずつたまる・持ったまま閉じた部屋に入ると
 *     部屋の濃度が上がる(換気の機械で押し返せる)・汚れるほど性能が落ちる・見た目が変わる(決定 32)。</li>
 *     <li>汚れの量は数字で見せない。体に着た姿とアイコンの汚れ方でだけ読む(決定 33)。</li>
 * </ul>
 *
 * <h2>11-14 の見直し(決定 35〜37、ユーザー指定)</h2>
 * 汚れたままでも 1 日(ゲーム内 20 分)くらいは大丈夫な強さに弱める・汚れた物の耐久が速く減る・防護マスクの姿にも汚れ。
 * 【仮】吸う量を半分(段ごと 0.015/0.04/0.075)・部屋の上限を境目 5% より下の 4% にして、部屋の汚れは 1% あたり {@code roomSeepPerPercent} 0.1 %秒/秒の
 * ゆっくりたまる道で効かせる(全身 3 段 + 汚れた部屋で 0.7 %秒/秒 = 約 36 分 ≒ 1.8 日で感染 1)・耐久の減り +10/+25/+50%({@code wearExtraPerStage})。
 * 下の「仮決め」は最初の版の数(吸う量・部屋の上限はこの見直しで変わった)。
 *
 * <h2>仮決め(数はすべて【仮】)</h2>
 * 汚れは 0〜{@code full} 1000。濃度 {@code minPercent} 15% 以上の所で「濃度 ÷ 100 × perSecondAt100 3.33 / 秒」たまる(100% で 5 分、過繁茂の下支え 25% で 20 分 = 約 1 日で満ちる)。
 * 着ている 4 か所と、両手の耐久のある物(道具・武器・盾)が汚れる。見た目の段は 3 つ(汚れ 150・450・800 から)。
 * 段ごと(1/2/3)に: 着ている 1 か所あたり暴露量 0.03/0.08/0.15 %秒/秒(4 か所とも 3 段で 0.6 = 約 2 日で感染 1。マスクが効いていればたまらない)・
 * 防具の守りと強靭さ −5/−10/−15%(その防具の値に対して)・利き手の道具の掘る速さ −5/−10/−15%。
 * 部屋: 閉じた部屋の中で持っている汚れた物 1 段 = 1 点、覆われた○○ 1 枠 = 1 点。1 点あたり {@code roomPerPoint} 0.002 %/秒(64 マスの部屋で。
 * 広い部屋ほど薄まる)・上限 8%・半減期 600 秒(換気機の範囲では 30 秒)。
 */
public record DirtSpec(
        int full,
        double minPercent,
        double perSecondAt100,
        int stage1, int stage2, int stage3,
        double[] seepPerStage,
        double[] armorLossPerStage,
        double[] breakSpeedLossPerStage,
        double[] wearExtraPerStage,
        int commitStepArmor,
        int commitStepHand,
        int handWashAmount,
        int machineWashAmount,
        int showerPerTick,
        double roomPerPoint,
        double roomRefVolume,
        double roomMaxPercent,
        double roomHalfLifeSeconds,
        double roomVentHalfLifeSeconds,
        double roomVentFilterLoadPerPercent,
        double roomSeepPerPercent) {

    /** Gson の受け皿(null は既定値)。 */
    public static final class Json {
        public String _comment;
        /** 汚れの満ち(この値で頭打ち)。 */
        public Integer full;
        /** この濃度(%)以上の所で汚れる。 */
        public Double minPercent;
        /** 濃度 100% の所で 1 秒にたまる汚れ。 */
        public Double perSecondAt100;
        /** 見た目の段 1・2・3 になる汚れ。 */
        public Integer stage1, stage2, stage3;
        /** 段 1/2/3 の装備を着ているとき、1 か所あたり暴露量がたまる速さ(%秒/秒)。 */
        public double[] seepPerStage;
        /** 段 1/2/3 の防具の守り・強靭さが減る割合。 */
        public double[] armorLossPerStage;
        /** 段 1/2/3 の利き手の道具の掘る速さが減る割合。 */
        public double[] breakSpeedLossPerStage;
        /** 段 1/2/3 の物の耐久の減りが増える割合(0.5 = 1.5 倍)。 */
        public double[] wearExtraPerStage;
        /** 汚れを物に書き込む刻み(着ている物・手の物)。手の物は書くと掘っている途中が切れるので粗く。 */
        public Integer commitStepArmor, commitStepHand;
        /** 手洗い 1 回・洗い場の枠で 1 回に落とす汚れ。洗い場の上に立ったとき 1 tick に落とす汚れ。 */
        public Integer handWashAmount, machineWashAmount, showerPerTick;
        /** 部屋: 1 点が 1 秒に上げる濃度(%。roomRefVolume マスの部屋で)。 */
        public Double roomPerPoint;
        /** 部屋: roomPerPoint の基準の広さ(マス)。広い部屋ほど薄まる。 */
        public Double roomRefVolume;
        /** 部屋の汚れの上乗せの上限(%)。 */
        public Double roomMaxPercent;
        /** 部屋の汚れの半減期(秒)。換気機の範囲ではもう 1 つの方。 */
        public Double roomHalfLifeSeconds, roomVentHalfLifeSeconds;
        /** 換気機が部屋の汚れを 1% 抜くごとに、そのフィルターが詰まる量(%秒)。 */
        public Double roomVentFilterLoadPerPercent;
        /** 部屋の汚れ 1% あたり、中にいる人に暴露量がたまる速さ(%秒/秒。部屋の汚れは境目 5% より下に止め、このゆっくりたまる道で効かせる)。 */
        public Double roomSeepPerPercent;
    }

    public static final DirtSpec DEFAULT = from(null);

    public static DirtSpec from(Json j) {
        Json s = j != null ? j : new Json();
        int full = i(s.full, 1000, 1, 1_000_000);
        return new DirtSpec(
                full,
                d(s.minPercent, 15.0D, 0.0D, 10_000.0D),
                d(s.perSecondAt100, 1000.0D / 300.0D, 0.0D, 1_000_000.0D),
                i(s.stage1, 150, 1, full), i(s.stage2, 450, 1, full), i(s.stage3, 800, 1, full),
                three(s.seepPerStage, 0.015D, 0.04D, 0.075D),
                three(s.armorLossPerStage, 0.05D, 0.10D, 0.15D),
                three(s.breakSpeedLossPerStage, 0.05D, 0.10D, 0.15D),
                three(s.wearExtraPerStage, 0.10D, 0.25D, 0.50D),
                i(s.commitStepArmor, 10, 1, full),
                i(s.commitStepHand, 50, 1, full),
                i(s.handWashAmount, 400, 0, 1_000_000),
                i(s.machineWashAmount, 1_000_000, 0, 1_000_000),
                i(s.showerPerTick, 20, 0, 1_000_000),
                d(s.roomPerPoint, 0.002D, 0.0D, 100.0D),
                d(s.roomRefVolume, 64.0D, 1.0D, 1_000_000.0D),
                d(s.roomMaxPercent, 4.0D, 0.0D, 10_000.0D),
                d(s.roomHalfLifeSeconds, 600.0D, 1.0D, 1.0E7D),
                d(s.roomVentHalfLifeSeconds, 30.0D, 1.0D, 1.0E7D),
                d(s.roomVentFilterLoadPerPercent, 60.0D, 0.0D, 1.0E7D),
                d(s.roomSeepPerPercent, 0.1D, 0.0D, 1000.0D));
    }

    /** 汚れの見た目の段(0 = きれい、1〜3)。 */
    public int stageOf(int dirt) {
        return dirt >= stage3 ? 3 : dirt >= stage2 ? 2 : dirt >= stage1 ? 1 : 0;
    }

    private static double[] three(double[] v, double a, double b, double c) {
        if (v == null || v.length < 3) return new double[]{a, b, c};
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) out[k] = Double.isFinite(v[k]) ? Math.max(0.0D, Math.min(1000.0D, v[k])) : 0.0D;
        return out;
    }

    private static int i(Integer v, int def, int lo, int hi) {
        return v == null ? def : Math.max(lo, Math.min(hi, v));
    }

    private static double d(Double v, double def, double lo, double hi) {
        return v == null || !Double.isFinite(v) ? def : Math.max(lo, Math.min(hi, v));
    }
}
