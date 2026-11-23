package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * この mod のレシピのファイルが全部ゲームに読み込まれているか(2026-10-10)。
 *
 * <p>きっかけ: mod マネージャーの保存で、精錬・溶鉱炉のレシピ 8 本の {@code result} が文字列(1.20 までの書き方)で書き出され、
 * ゲームがその 8 本だけを読み飛ばしていた(ログにエラーが出るだけで起動は通る。原石が精錬できなくなる)。
 * 書き方の誤りでも条件付き(neoforge:conditions)でもないのに読み込まれていないレシピがあれば落ちる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RecipeLoadGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterRecipeLoadGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(RecipeLoadGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7")
    public static void everyModRecipeFileLoads(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<ResourceLocation, Resource> found = server.getResourceManager().listResources("recipe",
                rl -> rl.getNamespace().equals(OvergrownVegetation.MODID) && rl.getPath().endsWith(".json"));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            String path = entry.getKey().getPath();
            ResourceLocation id = OvergrownVegetation.id(path.substring("recipe/".length(), path.length() - ".json".length()));
            if (server.getRecipeManager().byKey(id).isPresent()) {
                continue;
            }
            try (Reader reader = entry.getValue().openAsReader()) {
                StringBuilder text = new StringBuilder();
                char[] buf = new char[4096];
                for (int n; (n = reader.read(buf)) > 0; ) {
                    text.append(buf, 0, n);
                }
                if (text.indexOf("neoforge:conditions") >= 0) {
                    continue; // 条件付き(条件が合わないと読まれないのが正しい)
                }
            } catch (Exception e) {
                missing.add(id + " (読めない: " + e + ")");
                continue;
            }
            missing.add(id.toString());
        }
        if (found.isEmpty()) {
            helper.fail("この mod のレシピが 1 つも見つからない(探し方の誤り)");
        } else if (!missing.isEmpty()) {
            helper.fail("読み込まれていないレシピ(書き方の誤り。精錬系の result は {\"id\": …} の形): " + missing);
        } else {
            helper.succeed();
        }
    }

    private RecipeLoadGameTests() {
    }
}
