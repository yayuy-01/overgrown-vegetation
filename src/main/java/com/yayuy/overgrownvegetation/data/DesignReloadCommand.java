package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov design reload} —— 設計データ({@code config/overgrown_vegetation/*.json})を起動したまま読み直す
 * (2026-11-13 に<b>開発用として</b>戻した。ユーザー了承。10-10 にコマンドをまとめて整理したときに消してあった)。
 *
 * <p>新しい mod マネージャー({@code tools/mod-manager2})で値を保存 → このコマンド、で試せるようにするため。
 * <b>配布した jar では登録しない</b>(開発環境だけ)。
 *
 * <p>読み直しても変わらないもの: Mob の属性(体力・攻撃力など)と当たり判定は登録のときに決まるので<b>再起動</b>。
 * すでに湧いている個体・描かれたチャンクも古いまま。クリエイティブタブの並びは、読み直した後にワールドへ入り直すと変わる(バニラがタブの中身を控えているため)。
 * ドロップとレシピはデータパックなのでバニラの {@code /reload}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DesignReloadCommand {

    private DesignReloadCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (FMLEnvironment.production) {
            return;
        }
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("design")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(ctx -> {
                            DesignData.reload();
                            ItemTable.reload();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "設計データを読み直しました(Mob の属性と当たり判定は再起動、タブの並びはワールドに入り直す、ドロップとレシピは /reload)"), true);
                            return 1;
                        }))));
    }
}
