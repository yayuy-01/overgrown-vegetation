package com.yayuy.overgrownvegetation.gametest;

import com.mojang.brigadier.tree.CommandNode;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /ov} のコマンドの形(2026-10-10 ユーザー指定で整理)。足す・消すときはここも直す(ユーザーと決めた一覧の正本)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CommandTreeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CommandTreeGameTests.class);
        }

        private Registration() {
        }
    }

    /**
     * {@code /ov} の下に残すもの(ユーザー指定: 侵食速度・レイド・フェーズ・TD・性能・バイオマス・暦・基地・攻城戦。
     * 2026-10-17 ユーザー依頼で積乱雲を探す storm を足した。2026-10-18 ユーザー了承で空の巨大生物を確かめる sky を足した)と、その下の枝。
     */
    private static final Map<String, Set<String>> EXPECTED = Map.ofEntries(
            // 2026-10-30 の整理(退避)で、汚染に関係ない系統(raid・td・bench・biomass・age・base・siege・storm・sky)と
            // phase の omen・perf の g1 は parked/ へ。前の一覧は git の before-park-2026-10-30 に残る。
            Map.entry("corruption", Set.of("speed", "true", "false")),
            // 2026-11-14: フェーズの小さな段(導入・定着・最盛)を見る・決める stage を足した。
            Map.entry("phase", Set.of("set", "true", "false", "stage")),
            // 2026-11-19 ユーザー決定: フェーズが上がったときのチャットの代わりに、フェーズ・バイオマス・器官とその働きを見る forest(枝なし)。
            Map.entry("forest", Set.of()),
            // 2026-11-08: G1 段階 0 の組み込みで perf の g1(死体をすぐ起き上がらせる hatch)を戻した。
            // 2026-11-13: サバイバルの背骨 段 2b で地脈を確かめる ley(線までの距離・効率・近くの線へ飛ぶ near)を足した。
            // 2026-11-14: 段 2c で換気機を確かめる vent(動いている機械・ここが範囲か・まわりを過繁茂の台帳に載せる forest)を足した。
            // 2026-11-14: 汚染の広がりの速さの計測(見直し (B) 10)で、台帳の広がり・前線・累計ポイントを 1 行で出す spread を足した。
            // 2026-11-14: Mob の感染と飛び火を確かめる infection(感染した数・飛び火の回数・感染させる infect・1 体を見る show)を足した。
            Map.entry("perf", Set.of("bench", "fluid", "record", "spore", "g1", "ley", "vent", "spread", "infection", "spawns", "eco", "organ", "horde", "flow", "virtual", "pushback", "cliff", "fissure", "cave", "drift", "front")), // 11-21 前線と部隊の確かめ(front-units)・11-20 漂流する擬態 Mob の確かめ(drift-mimic)・11-20 地下の亀裂の確かめ(fissure-underground)・11-20 洞窟の飾り(fissure-underground)・11-15 enemy-spawns: 敵の湧き方の確かめ・11-16 eco-ledger: 生態系の台帳・11-17 森の器官の確かめ・11-18 大群の重さの内訳(horde-perf)・11-18 流れの地図の入り切りと確かめ(horde-flow-a)・11-18 仮想の群れ(horde-flow-a)・11-18 押し返しの確かめ(push-back)・11-20 崖の汚染の撮影と確かめ(cliff-corruption)
            // 2026-11-08: 環境ギミック G4 胞子の風・G5 光る胞子の夜を自分のゲームで確かめる fx(依頼「/ov か DevBridge に足す」。要らなければ消す)。
            // 2026-11-14: 任意の SoundEvent を鳴らすときに汚して鳴らす voice(声の加工の試作・voice/VoiceCommand)を足した。
            Map.entry("fx", Set.of("farsound", "figure", "gale", "glownight", "phasecue", "shadow", "status", "voice")),
            // 2026-11-09: 雲の段階 2a で積乱雲を探す storm(一覧・tp)を戻した(10-17 ユーザー依頼、11-09 段階 2a の依頼)。
            Map.entry("storm", Set.of("tp", "here")),
            // 2026-11-16 weather-system: 天気 4 つ(濃霧・凪・強風)を見る・強制する weather(確かめ用)。
            Map.entry("weather", Set.of("fog", "calm", "gale", "pre", "after", "clear", "strike")),
            // 2026-11-16 world-events: ワールドイベント 7 つを見る・起こす・止める・くじを引く event(確かめ用)。
            Map.entry("event", Set.of("status", "start", "stop", "next")),
            // 2026-11-11: ネザーの汚染の骨組み(根の台帳と段階)を見る・進める nether(実装の依頼「段階を見る・進める・速さの倍率を変える命令」)。
            Map.entry("nether", Set.of("status", "stage", "advance", "speed", "seed")),
            // 2026-11-12 ユーザー決定「おすすめ通り」: 実験的要素(今は新しいネザー)を見る・切り替える experimental(設定の画面が parked の間の代わり)。
            Map.entry("experimental", Set.of("on", "off")),
            // 2026-11-13 ユーザー了承: 設計データを起動したまま読み直す design reload を開発用に戻した(新しい mod マネージャーで保存 → 読み直し)。
            // 配布した jar では登録しない(DesignReloadCommand。GameTest は開発環境なので居る)。
            Map.entry("design", Set.of("reload")),
            // 2026-11-16: 蠢く壁の試作(creeping-wall)。起こす start・場所を指す at・止める stop・重さを見る status。
            Map.entry("wall", Set.of("start", "at", "stop", "status")));

    @GameTest(template = "gametest_empty7")
    public static void ovCommandTreeMatchesAgreedList(GameTestHelper helper) {
        CommandNode<CommandSourceStack> ov = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("ov");
        if (ov == null) {
            helper.fail("/ov が無い");
            return;
        }
        Set<String> top = names(ov);
        if (!top.equals(new TreeSet<>(EXPECTED.keySet()))) {
            helper.fail("/ov の下が決めた一覧と違う: " + top + "(期待 " + new TreeSet<>(EXPECTED.keySet()) + ")");
            return;
        }
        for (Map.Entry<String, Set<String>> e : EXPECTED.entrySet()) {
            Set<String> children = names(ov.getChild(e.getKey()));
            if (!children.equals(new TreeSet<>(e.getValue()))) {
                helper.fail("/ov " + e.getKey() + " の下が違う: " + children + "(期待 " + new TreeSet<>(e.getValue()) + ")");
                return;
            }
        }
        helper.succeed();
    }

    private static Set<String> names(CommandNode<CommandSourceStack> node) {
        Set<String> out = new TreeSet<>();
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            out.add(child.getName());
        }
        return out;
    }
}
