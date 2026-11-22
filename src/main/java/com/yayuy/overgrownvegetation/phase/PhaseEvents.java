package com.yayuy.overgrownvegetation.phase;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * フェーズ・小さな段が変わったときに {@code NeoForge.EVENT_BUS} へ流すイベント(2026-11-14)。
 * サーバー側だけ。受けたい仕組みは {@code @SubscribeEvent} で受ける(第 2 波の「フェーズが上がったときの合図」はここにつなぐ)。
 *
 * <ul>
 *   <li>{@link PhaseChanged}: フェーズが変わった(自然の昇格・{@code /ov phase set})。新しいフェーズは必ず導入から始まるので、
 *   このときは {@link StageChanged} は流さない。</li>
 *   <li>{@link StageChanged}: 同じフェーズの中で段が変わった(ポイントの進み・{@code /ov phase stage})。1 回で 2 段跳んだときも 1 回だけ
 *   (古い段 → 新しい段)。ポイントの進みでは段は下がらない(閾値を書き換えても下げない)。コマンドでだけ下がる。</li>
 * </ul>
 */
public final class PhaseEvents {

    private PhaseEvents() {
    }

    /** フェーズが変わった。{@code natural} = ポイントで自然に上がった(false = コマンド)。 */
    public static final class PhaseChanged extends Event {
        private final ServerLevel overworld;
        private final int oldPhase;
        private final int newPhase;
        private final boolean natural;

        public PhaseChanged(ServerLevel overworld, int oldPhase, int newPhase, boolean natural) {
            this.overworld = overworld;
            this.oldPhase = oldPhase;
            this.newPhase = newPhase;
            this.natural = natural;
        }

        public ServerLevel overworld() {
            return overworld;
        }

        public int oldPhase() {
            return oldPhase;
        }

        public int newPhase() {
            return newPhase;
        }

        public boolean natural() {
            return natural;
        }

        /** 上がった(下がった・同じではない)か。合図はこれが true のときだけ鳴らす想定。 */
        public boolean advanced() {
            return newPhase > oldPhase;
        }
    }

    /** 同じフェーズの中で段が変わった。{@code natural} = ポイントの進みで(false = コマンド)。 */
    public static final class StageChanged extends Event {
        private final ServerLevel overworld;
        private final int phase;
        private final PhaseStage oldStage;
        private final PhaseStage newStage;
        private final boolean natural;

        public StageChanged(ServerLevel overworld, int phase, PhaseStage oldStage, PhaseStage newStage, boolean natural) {
            this.overworld = overworld;
            this.phase = phase;
            this.oldStage = oldStage;
            this.newStage = newStage;
            this.natural = natural;
        }

        public ServerLevel overworld() {
            return overworld;
        }

        public int phase() {
            return phase;
        }

        public PhaseStage oldStage() {
            return oldStage;
        }

        public PhaseStage newStage() {
            return newStage;
        }

        public boolean natural() {
            return natural;
        }
    }
}
