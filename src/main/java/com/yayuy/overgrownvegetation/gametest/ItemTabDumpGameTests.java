package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.GsonBuilder;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ItemTable;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code items.json} の「クリエイティブタブの並び」を<b>今ゲームが組み立てた中身で埋め直す</b>
 * (2026-10-15。mod マネージャーのアイテムタブが読む元)。
 *
 * <p>マネージャーはブラウザの中で動くので、どのアイテムがどのタブに出るかを自分では知りようがない
 * ——区分の割り振りが {@code ArmorItem} などの Java の型で決まるため。そこでゲームを 1 度動かして
 * 答えを書き出す。{@code tools/mod-manager/GenMcItems.java} がバニラのアイテム一覧を作るのと同じ考え方。
 *
 * <h2>使い方</h2>
 * <pre>gradlew.bat runGameTestServer</pre>
 * で走り、{@code src/main/resources/overgrown_vegetation/design/items.json} と
 * {@code run/config/overgrown_vegetation/items.json} の両方を書き換える(どちらも無ければ作る)。
 *
 * <h2>並べ替えたものを壊さない</h2>
 * <b>すでに {@code items.json} に書いてある順はそのまま残す</b>。新しく増えたアイテムだけを、
 * ゲームが決めた位置(= 書いてあるアイテムたちの後ろ)へ足す。消えたアイテムは落とす。
 * なので Mob やアイテムを足したあとに何度流してもよい。
 *
 * <p>{@code items} の節(スタック上限・レア度)には触らない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ItemTabDumpGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Register {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ItemTabDumpGameTests.class);
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void dumpCreativeTabs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CreativeModeTab.ItemDisplayParameters params =
                new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess());

        ItemTable table = DesignData.items();
        Map<String, List<String>> tabs = new LinkedHashMap<>();
        tabs.put(ItemTable.COVERED, order(ModCreativeTabs.COVERED.get(), params));
        tabs.put(ItemTable.PLANTS, order(ModCreativeTabs.PLANTS.get(), params));
        tabs.put(ItemTable.HUMANITY, order(ModCreativeTabs.HUMANITY.get(), params));

        // ゲームの並びは「書いてある順 → 書いていないもの」になっている(CreativeTabOrder)ので、
        // そのまま書き出せば、並べ替えた所は残り、新しいものだけが後ろに付く。
        int total = tabs.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            helper.fail("クリエイティブタブが空でした(アイテムの登録が済んでいない?)");
            return;
        }
        try {
            write(tabs, table);
        } catch (IOException e) {
            helper.fail("items.json を書けませんでした: " + e);
            return;
        }
        OvergrownVegetation.LOGGER.info("items.json のタブの並びを書き出しました(合計 {} 個)", total);
        helper.succeed();
    }

    /** {@code tab} に出るアイテムを、出る順のまま id の一覧にする。 */
    private static List<String> order(CreativeModeTab tab, CreativeModeTab.ItemDisplayParameters params) {
        tab.buildContents(params);
        Set<String> ids = new LinkedHashSet<>();          // 同じアイテムが 2 度出ても 1 つにする
        for (ItemStack stack : tab.getDisplayItems()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (OvergrownVegetation.MODID.equals(id.getNamespace())) {
                ids.add(id.getPath());                     // 名前空間は省く(items.json は省略可)
            } else {
                ids.add(id.toString());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 設計データとその実行時の写しの両方へ書く(「config の値は 3 か所」と同じ扱い)。 */
    private static void write(Map<String, List<String>> tabs, ItemTable table) throws IOException {
        ItemTable.Json out = new ItemTable.Json();
        out.tabs = tabs;
        // スタック上限・レア度は読んだものをそのまま書き戻す(この書き出しでは触らない)。
        out.items = new LinkedHashMap<>();
        List<ResourceLocation> tweaked = new ArrayList<>(table.tweakedItems());
        tweaked.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : tweaked) {
            ItemTable.Entry e = new ItemTable.Entry();
            e.stackSize = table.stackSize(id);
            net.minecraft.world.item.Rarity r = table.rarity(id);
            e.rarity = r == null ? null : r.name().toLowerCase(java.util.Locale.ROOT);
            out.items.put(OvergrownVegetation.MODID.equals(id.getNamespace()) ? id.getPath() : id.toString(), e);
        }

        com.google.gson.JsonObject json = new GsonBuilder().create().toJsonTree(out).getAsJsonObject();
        com.google.gson.JsonObject withComment = new com.google.gson.JsonObject();
        withComment.addProperty("_comment", "クリエイティブタブの所属と並び順(tabs)、アイテムごとのスタック上限・レア度(items)。"
                + "tabs は mod マネージャーの「アイテム」タブで並べ替えられます。ここに書いていないアイテムは"
                + "Java が決めた区分の順で後ろに付きます(消えません)。反映は再起動。"
                + "gradlew runGameTestServer の dumpCreativeTabs で、今ゲームに出ている中身へ埋め直せます"
                + "(並べ替えた順はそのまま残ります)。");
        for (Map.Entry<String, com.google.gson.JsonElement> e : json.entrySet()) {
            withComment.add(e.getKey(), e.getValue());
        }
        String text = new GsonBuilder().setPrettyPrinting().create().toJson(withComment) + "\n";

        int written = 0;
        for (Path dir : targets()) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("items.json"), text, StandardCharsets.UTF_8);
            OvergrownVegetation.LOGGER.info("items.json を書きました: {}", dir.resolve("items.json"));
            written++;
        }
        if (written == 0) {
            throw new IOException("書き出す先が見つかりませんでした(プロジェクトのフォルダの外で動いている?)");
        }
    }

    /**
     * 書き出す先。GameTest サーバーの作業フォルダはプロジェクトの下のどこか(run/ など)なので、
     * {@code src/main/resources} が見つかるまで親をたどる。
     */
    private static List<Path> targets() {
        Path root = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++) {
            Path design = root.resolve("src/main/resources/overgrown_vegetation/design");
            if (Files.isDirectory(design)) {
                List<Path> out = new ArrayList<>();
                out.add(design);
                Path run = root.resolve("run/config/overgrown_vegetation");
                if (Files.isDirectory(run)) {
                    out.add(run);
                }
                return out;
            }
            root = root.getParent();
        }
        return List.of();
    }

    private ItemTabDumpGameTests() {
    }
}
