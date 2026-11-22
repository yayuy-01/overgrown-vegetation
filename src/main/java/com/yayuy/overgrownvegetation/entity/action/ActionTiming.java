package com.yayuy.overgrownvegetation.entity.action;

/**
 * 1 つの技の「前触れ → 実行 → 隙」の長さ(tick、2026-11-05、層 2 の共通部品)。
 *
 * <p><b>時刻はアニメと対</b>なので、設計データ(json)には置かず種のコードに書く(アニメの生成器の秒 × 20 と、GeckoLib の切り替えの補間)。
 * 前触れには補間({@code lead})を含める —— GeckoLib は動作の切り替えで補間してからアニメを 0 秒目から流すので、画面の上では
 * どの段もサーバーより {@code lead} tick 遅れて始まる。
 *
 * @param lead    切り替えの補間(tick)。前触れの頭に足す
 * @param windup  前触れ(アニメの tick)
 * @param execute 実行(アニメの tick)
 * @param recover 隙(アニメの tick)
 */
public record ActionTiming(int lead, int windup, int execute, int recover) {

    public ActionTiming {
        if (lead < 0 || windup < 0 || execute < 0 || recover < 0) {
            throw new IllegalArgumentException("負の長さ");
        }
    }

    /** アニメの秒で書く(× 20 を丸める)。 */
    public static ActionTiming ofSeconds(int lead, double windup, double execute, double recover) {
        return new ActionTiming(lead, (int) Math.round(windup * 20.0D), (int) Math.round(execute * 20.0D), (int) Math.round(recover * 20.0D));
    }

    /** 実行が始まる tick(動作を始めてからの経過。補間込み)。 */
    public int executeStart() {
        return this.lead + this.windup;
    }

    /** 隙が始まる tick。 */
    public int recoverStart() {
        return executeStart() + this.execute;
    }

    /** 全体の長さ(tick)。これで終わる。 */
    public int total() {
        return recoverStart() + this.recover;
    }

    /**
     * 動作を始めてから {@code ticks} たったときの段。{@code ticks} は 1 から数える(始めた tick の次が 1)ことが多いが、
     * 0 も前触れとして扱う。
     */
    public ActionPhase phaseAt(int ticks) {
        if (ticks < executeStart()) {
            return ActionPhase.WINDUP;
        }
        if (ticks < recoverStart()) {
            return ActionPhase.EXECUTE;
        }
        if (ticks < total()) {
            return ActionPhase.RECOVER;
        }
        return ActionPhase.DONE;
    }

    /** その段の中の進み(0〜1)。段の外なら 0 か 1 に切る。 */
    public float progress(int ticks, ActionPhase phase) {
        int from, len;
        switch (phase) {
            case WINDUP -> {
                from = 0;
                len = executeStart();
            }
            case EXECUTE -> {
                from = executeStart();
                len = this.execute;
            }
            case RECOVER -> {
                from = recoverStart();
                len = this.recover;
            }
            default -> {
                return 1.0F;
            }
        }
        if (len <= 0) {
            return ticks >= from ? 1.0F : 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, (ticks - from) / (float) len));
    }
}
