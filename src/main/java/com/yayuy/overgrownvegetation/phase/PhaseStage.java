package com.yayuy.overgrownvegetation.phase;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * フェーズの中の小さな段(2026-11-14 ユーザー決定: どのフェーズも 3 つ)。
 * そのフェーズの新しい物は、導入でまれに現れ、定着で普通に、最盛でいちばん多く強くなる。段ごとに新しい物は作らない。
 * 段はポイントの進みで決まる({@link com.yayuy.overgrownvegetation.data.PhaseTable#stageFor})。
 * 並び(ordinal)は保存・通信・倍率の表の添え字に使うので変えない。
 */
public enum PhaseStage {
    /** 導入: そのフェーズの物がまれに現れる。 */
    INTRO("intro"),
    /** 定着: 普通に現れる。 */
    ESTABLISHED("established"),
    /** 最盛: いちばん多く強い。 */
    PEAK("peak");

    private final String key;

    PhaseStage(String key) {
        this.key = key;
    }

    /** phases.json の欄の名前・コマンドの引数({@code intro} / {@code established} / {@code peak})。 */
    public String key() {
        return key;
    }

    /** 段の名前(言語ファイル {@code message.overgrown_vegetation.phase_stage.<key>})。 */
    public Component displayName() {
        return Component.translatable("message.overgrown_vegetation.phase_stage." + key);
    }

    public static PhaseStage byIndex(int index) {
        PhaseStage[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, index))];
    }

    @Nullable
    public static PhaseStage byKey(String key) {
        for (PhaseStage s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
