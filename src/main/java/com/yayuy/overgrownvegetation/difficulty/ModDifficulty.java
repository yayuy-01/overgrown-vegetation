package com.yayuy.overgrownvegetation.difficulty;

import net.minecraft.network.chat.Component;

/**
 * MOD 自体の難易度の段(2026-11-19 ユーザー決定。Minecraft のピースフル〜ハードとは別)。
 *
 * <p>段を選ぶと {@link DifficultySetting#tiered()} の項目(Mob の強さ 4 項目・フェーズの進み・森の予算の上限と生産量・
 * 暴露量のたまる速さ・感染の進みやすさ)がまとめて {@link #factor()} になり、Mob ごとの詳細は消える。
 * <b>段は保存しない</b>: 今の値から {@link DifficultyValues#tier()} で求める(全部が同じ段の値で詳細が無ければその段、
 * 1 つでも違えば {@link #CUSTOM})。Normal は「どれにもワールドの値が無い」と同じ = 前からのワールドは Normal。
 *
 * <p>倍率は Easy 70% / Normal 100% / Hard 150% / Nightmare 300%(ユーザー指定)。
 */
public enum ModDifficulty {
    EASY("easy", 0.7D),
    NORMAL("normal", 1.0D),
    HARD("hard", 1.5D),
    NIGHTMARE("nightmare", 3.0D),
    /** どの段とも違う(1 つでもいじった)。選ぶ物ではなく、求めた結果としてだけ出る。 */
    CUSTOM("custom", Double.NaN);

    /** 画面のボタンで順に回す段(カスタムは入らない)。 */
    public static final ModDifficulty[] SELECTABLE = {EASY, NORMAL, HARD, NIGHTMARE};

    private final String id;
    private final double factor;

    ModDifficulty(String id, double factor) {
        this.id = id;
        this.factor = factor;
    }

    public String id() {
        return id;
    }

    /** この段で揃える倍率(カスタムは NaN)。 */
    public double factor() {
        return factor;
    }

    public Component displayName() {
        return Component.translatable("gui.overgrown_vegetation.world_difficulty.tier." + id);
    }

    /** ボタンで次に選ぶ段(カスタムからは Easy)。 */
    public ModDifficulty next() {
        for (int i = 0; i < SELECTABLE.length; i++) {
            if (SELECTABLE[i] == this) {
                return SELECTABLE[(i + 1) % SELECTABLE.length];
            }
        }
        return EASY;
    }
}
