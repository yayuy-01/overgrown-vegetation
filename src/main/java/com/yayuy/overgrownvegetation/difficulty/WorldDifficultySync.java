package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.WorldDifficultyApplyPayload;
import com.yayuy.overgrownvegetation.network.WorldDifficultySyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * ポーズ画面の葉っぱのボタンから開く難易度の画面の、サーバー側の受け答え(2026-09-25、ユーザー指定「ポーズ画面にも」)。
 *
 * <p><b>変えてよい人</b> = OP(権限 2 以上)か、1 人プレイ(LAN に公開したときも)のホスト。チートを許可していない
 * 1 人プレイのワールドでもホストは変えられる(コマンドが使えないワールドの逃げ道)。それ以外の人には見るだけの画面を出す。
 */
public final class WorldDifficultySync {

    public static boolean canEdit(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    /** {@link com.yayuy.overgrownvegetation.network.WorldDifficultyRequestPayload} への返事。 */
    public static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                WorldDifficultySyncPayload.of(WorldDifficultyData.get(player.server), canEdit(player)));
    }

    /** 画面の「完了」。ワールドの値をまるごと置き換え、変わった項目を本人に知らせる。 */
    public static void apply(ServerPlayer player, WorldDifficultyApplyPayload payload) {
        if (!canEdit(player)) {
            player.sendSystemMessage(Component.translatable("gui.overgrown_vegetation.world_difficulty.denied")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        EnumMap<DifficultySetting, Double> values = new EnumMap<>(DifficultySetting.class);
        for (WorldDifficultyApplyPayload.Entry entry : payload.overrides()) {
            DifficultySetting setting = DifficultySetting.byId(entry.id());
            if (setting != null && Double.isFinite(entry.value())) {
                values.put(setting, setting.clamp(entry.value()));
            }
        }

        WorldDifficultyData data = WorldDifficultyData.get(player.server);
        Map<DifficultySetting, Double> before = new EnumMap<>(DifficultySetting.class);
        for (DifficultySetting setting : DifficultySetting.values()) {
            before.put(setting, data.value(setting));
        }
        data.replaceAll(values);
        ExperimentalFeatures.refresh(player.server);

        List<Component> changes = new ArrayList<>();
        List<String> log = new ArrayList<>();
        for (DifficultySetting setting : DifficultySetting.values()) {
            double was = before.get(setting);
            double now = data.value(setting);
            if (!setting.same(now, was)) {
                changes.add(Component.translatable("gui.overgrown_vegetation.world_difficulty.changed_line",
                        Component.translatable(setting.translationKey()), setting.display(was), setting.display(now)));
                log.add(setting.id() + " " + setting.format(was) + " -> " + setting.format(now));
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.translatable("gui.overgrown_vegetation.world_difficulty.applied")
                .withStyle(ChatFormatting.GREEN));
        for (Component change : changes) {
            player.sendSystemMessage(change.copy().withStyle(ChatFormatting.GRAY));
        }
        OvergrownVegetation.LOGGER.info("{} changed the world difficulty: {}",
                player.getGameProfile().getName(), String.join(", ", log));
    }

    private WorldDifficultySync() {
    }
}
