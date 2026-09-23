package com.yayuy.overgrownvegetation.data;

/**
 * {@code predation.json} — 捕食まわりで<b>全種に共通</b>する数値だけを持つ。
 *
 * <p>2026-09-21 に「捕食者 → 被食者の対応表」({@code table})は <b>{@code mobs.json} の
 * {@code preys}</b>(Mob ごとの「襲いに行く相手」)へ移した。相関図を 1 枚で見るより、
 * Mob のカードで「この種は何を襲うか」を見る方が編集しやすいため。
 * 引く側は {@link MobStats#preysOn} / {@link com.yayuy.overgrownvegetation.entity.Predation}。
 *
 * <p>残っているのは {@link #preyKillBonusPerKill()} だけ —— 1 体捕食するごとの強化率は
 * 種によらず同じなので、Mob ごとに持たせる意味がない。
 */
public final class PredationTable {

    /** Gson でデシリアライズされる DTO。 */
    public static final class Json {
        public Double preyKillBonusPerKill;
    }

    public static final PredationTable DEFAULT = new PredationTable(0.05D);

    private final double preyKillBonusPerKill;

    private PredationTable(double bonus) {
        this.preyKillBonusPerKill = bonus;
    }

    static PredationTable fromJson(Json json) {
        if (json == null || json.preyKillBonusPerKill == null) {
            return DEFAULT;
        }
        return new PredationTable(json.preyKillBonusPerKill);
    }

    /**
     * 捕食者({@link com.yayuy.overgrownvegetation.entity.PredatorMob})が
     * 襲う相手を 1 体倒すごとに上がる、攻撃力・体力の割合(既定 0.05 = 5%)。
     */
    public double preyKillBonusPerKill() {
        return preyKillBonusPerKill;
    }
}
