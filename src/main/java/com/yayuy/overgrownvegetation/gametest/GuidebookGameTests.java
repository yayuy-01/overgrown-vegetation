package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.guide.Guidebook;
import com.yayuy.overgrownvegetation.network.OpenGuidebookPayload;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ガイドブック(2026-10-15、ユーザー指定)。今は Mob の章だけ。
 * <ul>
 *     <li>章にはグループ1〜10 の Mob が 1 回ずつ、グループの番号順に並ぶ({@link #chapterListsEveryGroupMobOnce})。</li>
 *     <li>体力・攻撃力・防御力は設計値(同梱の mobs.json)、ドロップはルートテーブルに書かれたアイテム({@link #statsAndDropsFollowDesign})。</li>
 *     <li>人ごと: その Mob を倒すか、その Mob に倒されると載る。グループの外の Mob は載らない。載っていない Mob は数値もドロップも送らない
 *     ({@link #discoveredByKillingOrBeingKilled})。</li>
 *     <li>通信の書き出しと読み戻しが一致する({@link #payloadRoundTrips})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GuidebookGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GuidebookGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "guidebook";

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void chapterListsEveryGroupMobOnce(GameTestHelper helper) {
        List<EntityType<?>> chapter = Guidebook.chapterMobs();
        Set<EntityType<?>> expected = new HashSet<>();
        for (int n = 1; n <= 10; n++) {
            for (Holder<EntityType<?>> h : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(MobGroup.byNumber(n).tag())) {
                expected.add(h.value());
            }
        }
        if (chapter.size() != new HashSet<>(chapter).size()) {
            helper.fail("同じ Mob が 2 回載っている");
            return;
        }
        if (!new HashSet<>(chapter).equals(expected)) {
            helper.fail("章の Mob がグループのタグと違う: 章 " + chapter.size() + " 種 / タグ " + expected.size() + " 種");
            return;
        }
        int last = 0;
        for (EntityType<?> t : chapter) {
            int g = MobGroup.of(t).number();
            if (g < last) {
                helper.fail("グループの番号順に並んでいない: " + BuiltInRegistries.ENTITY_TYPE.getKey(t));
                return;
            }
            last = g;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void statsAndDropsFollowDesign(GameTestHelper helper) throws Exception {
        JsonObject mobs = json("/overgrown_vegetation/design/mobs.json");
        JsonObject arbora = mobs.getAsJsonObject("arbora");
        float[] stats = Guidebook.stats(ModEntities.ARBORA.get());
        float[] expect = {arbora.get("maxHealth").getAsFloat(), arbora.get("attackDamage").getAsFloat(), arbora.get("armor").getAsFloat()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(stats[i] - expect[i]) > 1e-4) {
                helper.fail("アルボラの数値 " + java.util.Arrays.toString(stats) + "(期待 " + java.util.Arrays.toString(expect) + ")");
                return;
            }
        }
        // 期待するドロップ = 同梱のルートテーブルに "minecraft:item" で書かれた名前。
        Set<ResourceLocation> expectDrops = new HashSet<>();
        collectNames(json("/data/overgrown_vegetation/loot_table/entities/arbora.json"), expectDrops);
        List<ResourceLocation> drops = Guidebook.drops(helper.getLevel().getServer(), ModEntities.ARBORA.get());
        if (expectDrops.isEmpty() || !new HashSet<>(drops).containsAll(expectDrops)) {
            helper.fail("アルボラのドロップ " + drops + "(期待 " + expectDrops + ")");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void discoveredByKillingOrBeingKilled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_guidebook_test".getBytes(StandardCharsets.UTF_8)), "ov_guidebook_test"));
        player.setData(ModAttachments.GUIDEBOOK_MOBS, new HashSet<>());
        LivingEntity arbora = helper.spawnWithNoFreeWill(ModEntities.ARBORA.get(), new BlockPos(1, 2, 1));
        LivingEntity velophyll = helper.spawnWithNoFreeWill(ModEntities.VELOPHYLL.get(), new BlockPos(3, 2, 3));
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(5, 2, 5));
        try {
            Guidebook.record(arbora, level.damageSources().playerAttack(player));      // 倒した
            Guidebook.record(player, level.damageSources().mobAttack(velophyll));      // 倒された
            Guidebook.record(cow, level.damageSources().playerAttack(player));         // グループの外
            LivingEntity shooter = helper.spawnWithNoFreeWill(ModEntities.ACUMNES.get(), new BlockPos(1, 2, 5));
            net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(level, shooter,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
            Guidebook.record(player, level.damageSources().arrow(arrow, null));          // 元の欄が空の弾 → 持ち主をたどる
            Set<ResourceLocation> got = player.getData(ModAttachments.GUIDEBOOK_MOBS);
            ResourceLocation a = BuiltInRegistries.ENTITY_TYPE.getKey(ModEntities.ARBORA.get());
            ResourceLocation v = BuiltInRegistries.ENTITY_TYPE.getKey(ModEntities.VELOPHYLL.get());
            ResourceLocation ac = BuiltInRegistries.ENTITY_TYPE.getKey(ModEntities.ACUMNES.get());
            if (!got.equals(Set.of(a, v, ac))) {
                helper.fail("載った Mob が違う: " + got);
                return;
            }
            OpenGuidebookPayload payload = Guidebook.build(player);
            for (OpenGuidebookPayload.Mob m : payload.mobs()) {
                boolean shouldKnow = m.id().equals(a) || m.id().equals(v) || m.id().equals(ac);
                if (m.discovered() != shouldKnow) {
                    helper.fail(m.id() + " の載り方が違う: " + m.discovered());
                    return;
                }
                if (!m.discovered() && (m.health() != 0 || !m.drops().isEmpty())) {
                    helper.fail("載っていない " + m.id() + " の数値・ドロップを送っている");
                    return;
                }
                if (m.id().equals(a) && (m.health() <= 0 || m.drops().isEmpty())) {
                    helper.fail("載ったアルボラの数値・ドロップが空");
                    return;
                }
            }
            helper.succeed();
        } finally {
            player.setData(ModAttachments.GUIDEBOOK_MOBS, new HashSet<>());
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void payloadRoundTrips(GameTestHelper helper) {
        OpenGuidebookPayload sent = new OpenGuidebookPayload(List.of(
                new OpenGuidebookPayload.Mob(OvergrownVegetation.id("arbora"), 4, true, 8, 8, 4,
                        List.of(OvergrownVegetation.id("plant_fiber"), OvergrownVegetation.id("arthropod_limb"))),
                new OpenGuidebookPayload.Mob(OvergrownVegetation.id("velophyll"), 4, false, 0, 0, 0, List.of())));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        OpenGuidebookPayload.STREAM_CODEC.encode(buf, sent);
        OpenGuidebookPayload back = OpenGuidebookPayload.STREAM_CODEC.decode(buf);
        if (!back.equals(sent)) {
            helper.fail("通信の読み戻しが違う: " + back);
            return;
        }
        helper.succeed();
    }

    /** 最初のログインで 1 冊だけ渡す(2 回目は渡さない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void givenOnceOnFirstLogin(GameTestHelper helper) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes("ov_guidebook_gift".getBytes(StandardCharsets.UTF_8)), "ov_guidebook_gift"));
        player.getInventory().clearContent();
        player.setData(ModAttachments.GUIDEBOOK_GIVEN, false);
        try {
            boolean first = Guidebook.giveOnce(player);
            boolean second = Guidebook.giveOnce(player);
            int books = player.getInventory().countItem(com.yayuy.overgrownvegetation.registry.ModItems.GUIDEBOOK.get());
            if (!first || second || books != 1) {
                helper.fail("初回に 1 冊だけ渡すはず: 1 回目 " + first + " / 2 回目 " + second + " / 冊数 " + books);
                return;
            }
            helper.succeed();
        } finally {
            player.getInventory().clearContent();
            player.setData(ModAttachments.GUIDEBOOK_GIVEN, false);
        }
    }

    /** 本 + 繊維質で作れる(2026-10-15 ユーザー指定)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void craftedFromBookAndFiber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK),
                new net.minecraft.world.item.ItemStack(com.yayuy.overgrownvegetation.registry.ModItems.PLANT_FIBER.get())));
        var out = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
                .map(h -> h.value().assemble(input, level.registryAccess())).orElse(net.minecraft.world.item.ItemStack.EMPTY);
        if (!out.is(com.yayuy.overgrownvegetation.registry.ModItems.GUIDEBOOK.get())) {
            helper.fail("本 + 繊維質でガイドブックができない: " + out);
            return;
        }
        helper.succeed();
    }

    private static JsonObject json(String path) throws Exception {
        try (InputStream in = GuidebookGameTests.class.getResourceAsStream(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void collectNames(JsonElement e, Set<ResourceLocation> out) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(c -> collectNames(c, out));
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && "minecraft:item".equals(o.get("type").getAsString()) && o.has("name")) {
                out.add(ResourceLocation.parse(o.get("name").getAsString()));
            }
            o.entrySet().forEach(en -> collectNames(en.getValue(), out));
        }
    }

    private GuidebookGameTests() {
    }
}
