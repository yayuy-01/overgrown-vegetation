package com.yayuy.overgrownvegetation.gametest;

import com.mojang.brigadier.tree.CommandNode;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
            Map.entry("phase", Set.of("set", "true", "false")),
            Map.entry("perf", Set.of("bench", "fluid", "record", "spore")));

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
