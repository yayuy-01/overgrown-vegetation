package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.AdvancementEvent;

import java.util.Optional;

/**
 * プレイヤーが実績を解除するたびに、
 * <ul>
 *     <li>フェーズポイントを加算({@code points.perAdvancement}、既定 100)し、</li>
 *     <li>フェーズ0の間は<b>フェーズ1開始の実績ゲート</b>のために実績の種類を数える
 *     ({@link PhaseData#recordStartAdvancement}、{@code global.startAdvancements} 既定 6 種類)。</li>
 * </ul>
 *
 * <p>バニラ・MOD 問わず全ての実績が対象だが、レシピ解放用の非表示実績まで拾ってしまうと
 * クラフトするだけでポイントが際限なく増えてしまうため、バニラが実績のトースト通知/チャット表示を
 * 出すかどうかの判定({@code display != null && !display.isHidden()})と同じ条件で絞り込む
 * (実績ゲートの 10 個も同じ絞り込み後の実績を数える)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class AdvancementPoints {

    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        Player player = event.getEntity();
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Advancement advancement = event.getAdvancement(); // 1.20.1: AdvancementHolder が無い
        DisplayInfo display = advancement.getDisplay();
        if (display == null || display.isHidden()) {
            return;
        }

        PhaseData data = PhaseData.get(serverLevel);
        // フェーズ0→1 の実績ゲート(既定 6 種類)。フェーズ0 の間だけ数える。
        data.recordStartAdvancement(serverLevel, advancement.getId().toString());

        int amount = com.yayuy.overgrownvegetation.data.DesignData.phases().pointsPerAdvancement();
        data.addPoints(serverLevel, amount);
    }

    private AdvancementPoints() {
    }
}
