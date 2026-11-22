package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.WorldDifficultySyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * 遊んでいる途中の設定のページの、サーバー側の受け答え(2026-09-25 に難易度の画面として作り、11-19 に設定のページへ広げた)。
 *
 * <p><b>変えてよい人</b> = OP(権限 2 以上)か、1 人プレイ(LAN に公開したときも)のホスト(11-19: シングルではいつでも、
 * サーバーでは OP だけ)。チートを許可していない 1 人プレイのワールドでもホストは変えられる。それ以外の人には見るだけの画面を出す。
 */
public final class WorldDifficultySync {

    public static boolean canEdit(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    /** 問い合わせへの返事。 */
    public static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, payloadFor(player.server, canEdit(player)));
    }

    public static WorldDifficultySyncPayload payloadFor(MinecraftServer server, boolean canEdit) {
        EnumMap<DifficultySetting, Double> defaults = new EnumMap<>(DifficultySetting.class);
        for (DifficultySetting s : DifficultySetting.values()) defaults.put(s, s.defaultValue());
        return new WorldDifficultySyncPayload(canEdit, defaults, WorldDifficultyData.get(server).snapshot(), MobStrength.roster());
    }

    /** 画面の「完了」。ワールドの値をまるごと置き換え、変わった項目を本人に知らせる。 */
    public static void apply(ServerPlayer player, DifficultyValues next) {
        if (!canEdit(player)) {
            player.sendSystemMessage(Component.translatable("gui.overgrown_vegetation.world_difficulty.denied")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        List<String> log = new ArrayList<>();
        List<Component> changes = applyAll(player.server, next, log);
        if (changes.isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.translatable("gui.overgrown_vegetation.world_difficulty.applied")
                .withStyle(ChatFormatting.GREEN));
        for (Component change : changes) {
            player.sendSystemMessage(change.copy().withStyle(ChatFormatting.GRAY));
        }
        OvergrownVegetation.LOGGER.info("{} changed the world settings: {}",
                player.getGameProfile().getName(), String.join(", ", log));
    }

    /**
     * 値を置き換えて、効かせ直しが要る所へ伝える(今いる Mob の強さ・実験的要素)。権限は見ない(GameTest からも呼ぶ)。
     *
     * @return 変わった項目の一行ずつ(チャット用)
     */
    public static List<Component> applyAll(MinecraftServer server, DifficultyValues next, List<String> log) {
        WorldDifficultyData data = WorldDifficultyData.get(server);
        DifficultyValues beforeValues = data.snapshot();
        EnumMap<DifficultySetting, Double> before = new EnumMap<>(DifficultySetting.class);
        for (DifficultySetting setting : DifficultySetting.values()) {
            before.put(setting, data.value(setting));
        }
        ModDifficulty tierBefore = data.tier();
        data.replaceAll(next);

        List<Component> changes = new ArrayList<>();
        ModDifficulty tierNow = data.tier();
        if (tierNow != tierBefore) {
            changes.add(Component.translatable("gui.overgrown_vegetation.world_difficulty.changed_line",
                    Component.translatable("gui.overgrown_vegetation.world_difficulty.tier"),
                    tierBefore.displayName(), tierNow.displayName()));
            log.add("tier " + tierBefore.id() + " -> " + tierNow.id());
        }
        for (DifficultySetting setting : DifficultySetting.values()) {
            double was = before.get(setting);
            double now = data.value(setting);
            if (!setting.same(now, was)) {
                changes.add(Component.translatable("gui.overgrown_vegetation.world_difficulty.changed_line",
                        Component.translatable(setting.translationKey()), setting.display(was), setting.display(now)));
                log.add(setting.id() + " " + setting.format(was) + " -> " + setting.format(now));
            }
        }
        boolean mobsChanged = !beforeValues.mobOverrides().equals(data.snapshot().mobOverrides());
        if (mobsChanged) {
            changes.add(Component.translatable("gui.overgrown_vegetation.world_difficulty.mob_details_changed"));
            log.add("mob details");
        }
        if (!changes.isEmpty()) {
            MobStrength.reapplyAll(server);
            RaidSettings.push(server);
        }
        return changes;
    }

    private WorldDifficultySync() {
    }
}
