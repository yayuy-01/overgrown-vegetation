package com.yayuy.overgrownvegetation.skyboss;

import net.minecraft.nbt.CompoundTag;

/**
 * 空の巨大生物の弱点(避雷針 1 本・蕾 86 個。10-19 に小さな蕾 34 個を足した)の壊れ具合(2026-10-18 夜)。サーバーが決めて保存し、クライアントへは
 * {@link com.yayuy.overgrownvegetation.network.SkyBossPartsPayload} で壊れた tick・生え直し始めた tick を送る(体力はサーバーだけ)。
 *
 * <ul>
 *   <li>指定(10-17・10-18 夜): 避雷針・蕾は壊せる。避雷針は<b>爆発し、その反動で避雷針の付いた胴の真ん中が押し込まれる</b>({@link SkyBossRecoil})。
 *       壊した避雷針・蕾は<b>時間で生え直す</b>(行動パターンの 2 節「戦いをやめて離れると渡りに戻る。体力は日の光でゆっくり戻り、壊した避雷針・蕾も時間で生え直す」)。</li>
 *   <li>仮: 体力・生え直すまでの時間({@link SkyBossTuning})。生え直しの時計は<b>戦っていない間だけ</b>進む(体力が戻るのと同じ)。
 *       ショート(放電を止めて技を出せない隙)は溜め・放電ができてから —— 今は渡りの間に避雷針を壊しても、爆発と反動だけ。</li>
 *   <li>壊れた弱点は当たり判定の形から外れる(当たりは奥の苔へ通る)。生え直しの途中も、元に戻るまでは外れたまま。</li>
 * </ul>
 * 時刻はすべてゲームの tick({@link #NONE} = 無い)。
 */
public final class SkyBossParts {

    public static final long NONE = Long.MIN_VALUE;
    /** 蕾の枠(形の元の蕾は 52 個、10-19 に小さな蕾を足して 86 個。ユーザー「100 以内なら幾つでも」)。 */
    public static final int MAX_BUDS = 128;

    public float rodHealth;
    /** 避雷針が壊れた tick(rod_short の頭・体の反動の始まりの元)。 */
    public long rodBrokenAt;
    /** 避雷針が生え直し始めた tick(rod_regrow の頭)。 */
    public long rodRegrowAt;
    public final float[] budHealth = new float[MAX_BUDS];
    public final long[] budBrokenAt = new long[MAX_BUDS];
    /** 蕾が育ち始める tick(先の tick のこともある = 蕾ごとのずらし)。 */
    public final long[] budRegrowAt = new long[MAX_BUDS];
    /** 避雷針の爆発の粒・音を出し終えたか(サーバー、保存しない。読み込み直したら出し直さない)。 */
    public boolean rodBoomDone = true;

    public SkyBossParts() {
        reset();
    }

    /** 全部元どおり(新しい個体)。 */
    public void reset() {
        rodHealth = SkyBossTuning.ROD_HEALTH;
        rodBrokenAt = NONE;
        rodRegrowAt = NONE;
        java.util.Arrays.fill(budHealth, SkyBossTuning.BUD_HEALTH);
        java.util.Arrays.fill(budBrokenAt, NONE);
        java.util.Arrays.fill(budRegrowAt, NONE);
        rodBoomDone = true;
    }

    // ------------------------------------------------------------------ 形と見た目(両側)
    /** 避雷針が当たり判定の形にあるか(壊れていない)。 */
    public boolean rodSolid() {
        return rodBrokenAt == NONE;
    }

    /** 蕾 {@code b} が当たり判定の形にあるか(壊れていない)。 */
    public boolean budSolid(int b) {
        return b < 0 || b >= MAX_BUDS || budBrokenAt[b] == NONE;
    }

    /** 蕾 {@code b} の見た目の大きさ(1 = 元どおり、0 = 見えない)。生え直しは {@link SkyBossTuning#BUD_REGROW_TICKS} でなめらかに育つ。 */
    public float budScale(int b, double t) {
        if (budSolid(b)) {
            return 1.0F;
        }
        long r = budRegrowAt[b];
        if (r == NONE || t <= r) {
            return 0.0F;
        }
        double u = Math.min(1.0, (t - r) / SkyBossTuning.BUD_REGROW_TICKS);
        return (float) (u * u * (3 - 2 * u));
    }

    /** 避雷針の層のアニメ(rod_short)が流れ始める tick(補間の後)。体の反動・爆発はここから数える。 */
    public long rodShortStart() {
        return rodBrokenAt + SkyBossTuning.ROD_TRANSITION;
    }

    /** 時刻 {@code t} の体の反動の時刻(元の秒、{@link SkyBossRecoil#compute} へ渡す)。反動の間の外なら -1。 */
    public double recoilTime(double t) {
        if (rodBrokenAt == NONE) {
            return -1.0;
        }
        double s = (t - rodShortStart()) / 20.0 / SkyBossTuning.ANIM_SLOW;
        return s >= 0.0 && s <= SkyBossRecoil.END ? s : -1.0;
    }

    /** 避雷針が爆発する tick(rod_short の中の爆発の時刻)。 */
    public long rodBoomTick() {
        return rodShortStart() + Math.round(SkyBossTuning.ROD_BOOM * SkyBossTuning.ANIM_SLOW * 20.0);
    }

    // ------------------------------------------------------------------ 壊す・生え直す(サーバー)
    /** 弱点に当たったダメージ(倍率を掛けた後)。壊れたら true。苔・壊れている所は何もしない。 */
    public boolean damage(int part, int index, float amount, long now) {
        if (part == SkyBossPose.PART_ROD) {
            if (!rodSolid()) {
                return false;
            }
            rodHealth -= amount;
            if (rodHealth > 0.0F) {
                return false;
            }
            rodHealth = 0.0F;
            rodBrokenAt = now;
            rodRegrowAt = NONE;
            rodBoomDone = false;
            return true;
        }
        if (part == SkyBossPose.PART_BUD && index >= 0 && index < MAX_BUDS && budSolid(index)) {
            budHealth[index] -= amount;
            if (budHealth[index] > 0.0F) {
                return false;
            }
            budHealth[index] = 0.0F;
            budBrokenAt[index] = now;
            budRegrowAt[index] = NONE;
            return true;
        }
        return false;
    }

    /**
     * 生え直し(毎 tick)。戦っていない間が続いたら生え直し始め、育ちきったら元どおりにする。{@code quietSince} = 戦いが終わった tick。
     * 変わったら true(クライアントへ送り直す)。
     */
    public boolean tick(long now, boolean inCombat, long quietSince, long seed) {
        boolean changed = false;
        if (rodBrokenAt != NONE) {
            if (rodRegrowAt == NONE) {
                if (!inCombat && now - Math.max(quietSince, rodBrokenAt) >= SkyBossTuning.ROD_REGROW_DELAY) {
                    rodRegrowAt = now;
                    changed = true;
                }
            } else if (now >= rodRegrowAt + SkyBossTuning.ROD_TRANSITION + SkyBossTuning.ROD_REGROW_TICKS) {
                rodBrokenAt = NONE;
                rodRegrowAt = NONE;
                rodHealth = SkyBossTuning.ROD_HEALTH;
                changed = true;
            }
        }
        for (int b = 0; b < MAX_BUDS; b++) {
            long broken = budBrokenAt[b];
            if (broken == NONE) {
                continue;
            }
            if (budRegrowAt[b] == NONE) {
                if (!inCombat && now - Math.max(quietSince, broken) >= SkyBossTuning.BUD_REGROW_DELAY) {
                    budRegrowAt[b] = now + stagger(seed, b, broken);
                    changed = true;
                }
            } else if (now >= budRegrowAt[b] + SkyBossTuning.BUD_REGROW_TICKS) {
                budBrokenAt[b] = NONE;
                budRegrowAt[b] = NONE;
                budHealth[b] = SkyBossTuning.BUD_HEALTH;
                changed = true;
            }
        }
        return changed;
    }

    /** 壊れている蕾の数。 */
    public int brokenBuds() {
        int n = 0;
        for (long t : budBrokenAt) {
            if (t != NONE) {
                n++;
            }
        }
        return n;
    }

    /** 蕾ごとの生え直しのずらし(0〜{@link SkyBossTuning#BUD_REGROW_STAGGER} tick、決まった値)。 */
    private static long stagger(long seed, int b, long broken) {
        long z = seed ^ (b * 0x9E3779B97F4A7C15L) ^ broken;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return Math.floorMod(z, SkyBossTuning.BUD_REGROW_STAGGER + 1L);
    }

    // ------------------------------------------------------------------ 写し・保存
    /** 時刻だけを写す(クライアントが受け取ったとき)。 */
    public void setTimes(long rodBroken, long rodRegrow, long[] budBroken, long[] budRegrow) {
        rodBrokenAt = rodBroken;
        rodRegrowAt = rodRegrow;
        java.util.Arrays.fill(budBrokenAt, NONE);
        java.util.Arrays.fill(budRegrowAt, NONE);
        int n = Math.min(MAX_BUDS, Math.min(budBroken.length, budRegrow.length));
        System.arraycopy(budBroken, 0, budBrokenAt, 0, n);
        System.arraycopy(budRegrow, 0, budRegrowAt, 0, n);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putFloat("rodHealth", rodHealth);
        t.putLong("rodBrokenAt", rodBrokenAt);
        t.putLong("rodRegrowAt", rodRegrowAt);
        int[] hp = new int[MAX_BUDS];
        for (int b = 0; b < MAX_BUDS; b++) {
            hp[b] = Float.floatToIntBits(budHealth[b]);
        }
        t.putIntArray("budHealth", hp);
        t.putLongArray("budBrokenAt", budBrokenAt);
        t.putLongArray("budRegrowAt", budRegrowAt);
        return t;
    }

    /** 保存から(無い・前の版なら元どおり)。 */
    public void load(CompoundTag t) {
        reset();
        if (!t.contains("rodBrokenAt")) {
            return;
        }
        rodHealth = t.getFloat("rodHealth");
        rodBrokenAt = t.getLong("rodBrokenAt");
        rodRegrowAt = t.getLong("rodRegrowAt");
        int[] hp = t.getIntArray("budHealth");
        long[] br = t.getLongArray("budBrokenAt");
        long[] rg = t.getLongArray("budRegrowAt");
        for (int b = 0; b < MAX_BUDS; b++) {
            if (b < hp.length) {
                budHealth[b] = Float.intBitsToFloat(hp[b]);
            }
            if (b < br.length) {
                budBrokenAt[b] = br[b];
            }
            if (b < rg.length) {
                budRegrowAt[b] = rg[b];
            }
        }
        rodBoomDone = true;
    }
}
