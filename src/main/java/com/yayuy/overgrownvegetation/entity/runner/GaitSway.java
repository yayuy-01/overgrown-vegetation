package com.yayuy.overgrownvegetation.entity.runner;

/**
 * 足の運びから胴・首・尾の動きを出す(2026-11-14、ヴェナムネスの歩きの見直し。見た目だけ・クライアント)。
 *
 * <h2>なぜ</h2>
 * 足の置き場({@code FootPlanter})で歩く間、腰の上下は手付けの歩きのキーが別の時計で流れているだけで、実際の踏み替えと合っていなかった
 * (ユーザー「体の上下の揺れが少し機械的すぎる」)。ここでは<b>着いている足の位置から</b>腰の高さを決める:
 * 腰は立ち脚の上を弧を描いて越える(足が腰の真下で一番高く、前後に離れるほど低い = 踏み込みで沈み、越えるときに上がる)。
 * その腰の動きを、胸・首・尾が<b>少しずつ遅れて</b>追う(頭は上下と向きを打ち消して前を見据え、尾は遅れて振れて釣り合いを取る)。
 *
 * <h2>使い方</h2>
 * 1 tick に 1 回 {@link #tick}(足の置き場から)か {@link #drive}(決めた値から。手付けの走りを焼く道具 {@code tools/venamnes-anim} が使う)。
 * 描画は {@code pt} で補間した値を読む。向きの決まり: 上下 + = 上(ブロック)、yaw + = 鼻先が左、roll + = 左が上がる、pitch + = 前が上がる(度)。
 * Minecraft のクラスに依らない(道具からそのままコンパイルして同じ式で手付けのアニメを焼くため)。数値は全部【仮・Claude 11-14】。
 */
public final class GaitSway {

    // --- 種ごとの値 ---
    /** 倒立振子の脚の長さ(ブロック)。腰の沈み = 利き × (長さ − √(長さ² − 足と腰の前後の距離²))。 */
    public double legLen = 2.0D;
    /** 沈みの利き(1 = 振り子そのまま)。 */
    public double bobGain = 1.1D;
    /** 歩いている間、腰を上げる量(ブロック)。止まると 0 へ戻る(待機の形は変えない)。 */
    public double raise = 0.47D;
    /** 両足の前後の差がこれ(ブロック)のとき、腰を {@link #yawMax} 度ひねる(前に出た足の側の腰が前へ)。 */
    public double yawSpan = 2.0D, yawMax = 5.0D;
    /** 片足が宙の間、宙の側の腰が下がる角度(度)。 */
    public double rollMax = 2.0D;
    /** 腰の上下のばね(固有角振動数 /tick と減衰比)。減衰を 1 より小さくして、着地で少し沈み込んでから戻る。 */
    public double springLift = 0.55D, dampLift = 0.75D;
    /** 腰のひねり・傾きのばね(臨界減衰)。 */
    public double springTurn = 0.5D;
    /** 遅れて追う割合(1 tick あたり): 胸・尾の上下、首と尾のひねりの 1 節ぶん。 */
    public double lagFront = 0.3D, lagTail = 0.22D, lagYaw = 0.25D;
    /** 遅れの差を角度へ(度/ブロック): 胸(前が遅れて付いてくる)と尾。 */
    public double frontPitchPer = 38.0D, tailPitchPer = 55.0D;
    /** 頭を保つ割合(1 = 上下と向きを全部打ち消す)と、首の付け根から頭までの長さ(ブロック)。 */
    public double headHold = 0.7D, neckLen = 2.5D, hipsToNeck = 1.7D;
    /** 尾のひねりの増し(遅れの差に掛ける)。 */
    public double tailWhip = 1.6D;

    // --- 状態(今と 1 tick 前) ---
    private double lift, vLift, yaw, vYaw, roll, vRoll, moving;
    private double lagF, lagT1, lagT2, raiseNow;
    private final double[] yawChain = new double[5];   // 尾の節ごとの世界のひねり(腰から遅れて付いてくる)
    private double yawHead;                            // 頭の向きの打ち消し(少し遅れる)
    private final double[] cur = new double[OUT], old = new double[OUT];

    /** 出力の並び。 */
    public static final int LIFT = 0, YAW = 1, ROLL = 2, FRONT_PITCH = 3, NECK_PITCH = 4, FRONT_YAW = 5,
            TAIL_YAW0 = 6, TAIL_YAW1 = 7, TAIL_YAW2 = 8, TAIL_YAW3 = 9, TAIL_PITCH0 = 10, TAIL_PITCH2 = 11, OUT = 12;

    /**
     * 足の置き場から 1 tick 進める。
     *
     * @param fwd     脚ごとの、足先の腰の関節からの前後のずれ(ブロック、+ が前。体格 1 に直した値)
     * @param planted 脚ごとの、地面に着いているか
     * @param swing   脚ごとの、踏み替えの進み(0〜1。着いていれば 0)
     * @param side    脚ごとの左右(+1 = 左、−1 = 右)
     * @param walking 歩いているか(腰を上げる・揺れを効かせる)
     */
    public void tick(double[] fwd, boolean[] planted, float[] swing, int[] side, boolean walking) {
        this.moving += ((walking ? 1.0D : 0.0D) - this.moving) * 0.18D;
        double dip = 0.0D, spread = 0.0D, hang = 0.0D;
        boolean any = false;
        for (int i = 0; i < fwd.length; i++) {
            if (planted[i]) {
                double d = Math.min(Math.abs(fwd[i]), this.legLen * 0.95D);
                dip = Math.max(dip, this.legLen - Math.sqrt(this.legLen * this.legLen - d * d));
                any = true;
            } else {
                // 宙の側の腰が少し下がる(振り出しの真ん中で一番)
                hang += side[i] * Math.sin(Math.PI * swing[i]);
            }
            spread += side[i] * fwd[i];   // 左が前なら +
        }
        double liftT = any ? -this.bobGain * dip : 0.0D;
        // 左足が前 → 左の腰が前 → 鼻先は右(−)
        double yawT = -this.yawMax * clamp(spread / this.yawSpan, -1.0D, 1.0D);
        double rollT = -this.rollMax * clamp(hang, -1.0D, 1.0D);   // 左が宙 → 左が下がる(−)
        drive(liftT * this.moving, yawT * this.moving, rollT * this.moving, this.raise * this.moving);
    }

    /** 決めた目標から 1 tick 進める(上下 = ブロック、ひねり・傾き = 度、腰を上げる量 = ブロック)。 */
    public void drive(double liftT, double yawT, double rollT, double raiseT) {
        System.arraycopy(this.cur, 0, this.old, 0, OUT);
        this.raiseNow += (raiseT - this.raiseNow) * 0.2D;
        for (int k = 0; k < 4; k++) {
            double dt = 0.25D;
            this.vLift += (this.springLift * this.springLift * (liftT - this.lift) - 2.0D * this.dampLift * this.springLift * this.vLift) * dt;
            this.lift += this.vLift * dt;
            this.vYaw += (this.springTurn * this.springTurn * (yawT - this.yaw) - 2.0D * this.springTurn * this.vYaw) * dt;
            this.yaw += this.vYaw * dt;
            this.vRoll += (this.springTurn * this.springTurn * (rollT - this.roll) - 2.0D * this.springTurn * this.vRoll) * dt;
            this.roll += this.vRoll * dt;
        }
        // 遅れて追う: 胸は腰の上下に遅れる(腰が先に上がると前が下がって見える)。尾も同じく、2 節ぶん
        this.lagF += (this.lift - this.lagF) * this.lagFront;
        this.lagT1 += (this.lift - this.lagT1) * this.lagTail;
        this.lagT2 += (this.lagT1 - this.lagT2) * this.lagTail;
        double frontPitch = this.frontPitchPer * (this.lagF - this.lift);
        // 頭の高さのずれ(腰の上下 + 胸の傾きで首の付け根が動く分)を首の付け根で打ち消す
        double headOff = this.lift + Math.toRadians(frontPitch) * this.hipsToNeck;
        double neckPitch = -this.headHold * Math.toDegrees(headOff / this.neckLen) - this.headHold * frontPitch;
        // 頭の向き: 腰のひねりを打ち消す(少し遅れる)
        this.yawHead += (this.yaw - this.yawHead) * 0.6D;
        // 尾: 節ごとに前の節の向きへ遅れて寄る。節の曲げ = 自分の向き − 前の節の向き
        this.yawChain[0] = this.yaw;
        for (int i = 1; i < 5; i++) {
            this.yawChain[i] += (this.yawChain[i - 1] - this.yawChain[i]) * this.lagYaw;
        }
        this.cur[LIFT] = this.lift + this.raiseNow;
        this.cur[YAW] = this.yaw;
        this.cur[ROLL] = this.roll;
        this.cur[FRONT_PITCH] = frontPitch;
        this.cur[NECK_PITCH] = neckPitch;
        this.cur[FRONT_YAW] = -this.headHold * this.yawHead;
        for (int i = 0; i < 4; i++) {
            this.cur[TAIL_YAW0 + i] = this.tailWhip * (this.yawChain[i + 1] - this.yawChain[i]);
        }
        this.cur[TAIL_PITCH0] = this.tailPitchPer * (this.lagT1 - this.lift);
        this.cur[TAIL_PITCH2] = this.tailPitchPer * (this.lagT2 - this.lagT1);
    }

    /** 出力(補間)。 */
    public double get(int which, float pt) {
        return this.old[which] + (this.cur[which] - this.old[which]) * pt;
    }

    /** 歩きの効き(0 = 止まっている、1 = 歩いている)。 */
    public double moving() {
        return this.moving;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
