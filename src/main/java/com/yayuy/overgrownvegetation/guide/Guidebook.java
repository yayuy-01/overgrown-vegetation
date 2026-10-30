package com.yayuy.overgrownvegetation.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.OpenGuidebookPayload;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ガイドブックのサーバー側(2026-10-15、ユーザー指定)。今は Mob の章だけ。
 * <ul>
 *     <li><b>出会い</b>: 人ごと({@link ModAttachments#GUIDEBOOK_MOBS})。グループ1〜10 の Mob を<b>倒す</b>か、その Mob に<b>倒される</b>と載る。
 *     初めて載ったときはアクションバーで知らせる(Claude の仮決め)。</li>
 *     <li><b>中身</b>: グループ順・グループのタグの順の全 Mob。体力・攻撃力・防御力は既定の属性(mobs.json から作られる値。
 *     亜種や難易度で変わる前の値)、ドロップはルートテーブルに書かれたアイテム(別のルートテーブルを参照していればたどる)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Guidebook {

    // --- 出会い ---

    /** 最後に聞く —— トーテムなどで死亡が取り消されたら数えない。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }
        record(event.getEntity(), event.getSource());
    }

    /** {@code victim} が {@code source} で倒された。倒したのがプレイヤー / 倒されたのがプレイヤーなら、相手の Mob を載せる。 */
    public static void record(LivingEntity victim, DamageSource source) {
        Entity killer = source.getEntity();
        if (killer == null && source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            killer = projectile.getOwner(); // 元の欄が空でも、弾の持ち主をたどる(撃った Mob の弾・胞子の弾など)
        }
        if (killer instanceof ServerPlayer player && MobGroup.of(victim.getType()) != null) {
            discover(player, victim.getType());
        }
        if (victim instanceof ServerPlayer player && killer != null && MobGroup.of(killer.getType()) != null) {
            discover(player, killer.getType());
        }
    }

    /** 載せる。初めてなら true を返し、アクションバーで知らせる。 */
    public static boolean discover(ServerPlayer player, EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        Set<ResourceLocation> set = player.getData(ModAttachments.GUIDEBOOK_MOBS);
        if (!set.add(id)) {
            return false;
        }
        player.setData(ModAttachments.GUIDEBOOK_MOBS, set); // 書き換えた印(保存のため)
        player.displayClientMessage(Component.translatable("message.overgrown_vegetation.guidebook.discovered",
                type.getDescription()).withStyle(ChatFormatting.GRAY), true);
        return true;
    }

    /** 最初のログインでガイドブックを 1 冊渡す(2026-10-15 ユーザー指定)。 */
    @SubscribeEvent
    public static void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            giveOnce(player);
        }
    }

    /** まだ渡していなければ 1 冊渡す(持ち物がいっぱいなら足元に落とす)。渡したら true。 */
    public static boolean giveOnce(ServerPlayer player) {
        if (player.getData(ModAttachments.GUIDEBOOK_GIVEN)) {
            return false;
        }
        player.setData(ModAttachments.GUIDEBOOK_GIVEN, true);
        net.minecraft.world.item.ItemStack book = new net.minecraft.world.item.ItemStack(
                com.yayuy.overgrownvegetation.registry.ModItems.GUIDEBOOK.get());
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        return true;
    }

    public static boolean isDiscovered(ServerPlayer player, EntityType<?> type) {
        return player.getData(ModAttachments.GUIDEBOOK_MOBS).contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    // --- 中身 ---

    /** Mob の章に載せる Mob(グループ順・タグの順、重複なし)。 */
    public static List<EntityType<?>> chapterMobs() {
        Set<EntityType<?>> out = new LinkedHashSet<>();
        for (int n = 1; n <= 10; n++) {
            MobGroup group = MobGroup.byNumber(n);
            if (group == null) continue;
            for (Holder<EntityType<?>> holder : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(group.tag())) {
                // 2 つのグループに入っている種は、番号の小さいグループにだけ載せる(MobGroup.of と同じ)。
                if (MobGroup.of(holder.value()) == group) {
                    out.add(holder.value());
                }
            }
        }
        return List.copyOf(out);
    }

    /** {@code player} に送る本の中身。 */
    public static OpenGuidebookPayload build(ServerPlayer player) {
        MinecraftServer server = player.server;
        List<OpenGuidebookPayload.Mob> rows = new ArrayList<>();
        for (EntityType<?> type : chapterMobs()) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            int group = MobGroup.of(type).number();
            if (!isDiscovered(player, type)) {
                rows.add(new OpenGuidebookPayload.Mob(id, group, false, 0, 0, 0, List.of()));
                continue;
            }
            float[] stats = stats(type);
            rows.add(new OpenGuidebookPayload.Mob(id, group, true, stats[0], stats[1], stats[2], drops(server, type)));
        }
        return new OpenGuidebookPayload(List.copyOf(rows));
    }

    /** 体力・攻撃力・防御力(既定の属性)。生き物でない・属性が無いものは 0。 */
    @SuppressWarnings("unchecked")
    public static float[] stats(EntityType<?> type) {
        if (!DefaultAttributes.hasSupplier(type)) {
            return new float[3];
        }
        AttributeSupplier sup = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
        return new float[]{
                value(sup, Attributes.MAX_HEALTH), value(sup, Attributes.ATTACK_DAMAGE), value(sup, Attributes.ARMOR)};
    }

    private static float value(AttributeSupplier sup, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        return sup.hasAttribute(attr) ? (float) sup.getBaseValue(attr) : 0.0F;
    }

    /** ルートテーブルに書かれたアイテム(登場順・重複なし)。参照しているルートテーブルもたどる(深さ 4 まで)。 */
    public static List<ResourceLocation> drops(MinecraftServer server, EntityType<?> type) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        collect(server, type.getDefaultLootTable(), out, 0);
        out.remove(BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.AIR));
        return List.copyOf(out);
    }

    private static void collect(MinecraftServer server, ResourceKey<LootTable> key, Set<ResourceLocation> out, int depth) {
        if (depth > 4 || key == null) {
            return;
        }
        LootTable table = server.reloadableRegistries().getLootTable(key);
        if (table == LootTable.EMPTY) {
            return;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        LootTable.DIRECT_CODEC.encodeStart(ops, table).result().ifPresent(json -> walk(server, json, out, depth));
    }

    private static void walk(MinecraftServer server, JsonElement json, Set<ResourceLocation> out, int depth) {
        if (json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) walk(server, e, out, depth);
            return;
        }
        if (!json.isJsonObject()) {
            return;
        }
        JsonObject obj = json.getAsJsonObject();
        String type = obj.has("type") && obj.get("type").isJsonPrimitive() ? obj.get("type").getAsString() : "";
        if (type.equals("minecraft:item") && obj.has("name")) {
            ResourceLocation item = ResourceLocation.tryParse(obj.get("name").getAsString());
            if (item != null) out.add(item);
        } else if (type.equals("minecraft:loot_table") && obj.has("value") && obj.get("value").isJsonPrimitive()) {
            ResourceLocation ref = ResourceLocation.tryParse(obj.get("value").getAsString());
            if (ref != null) collect(server, ResourceKey.create(Registries.LOOT_TABLE, ref), out, depth + 1);
        }
        for (var e : obj.entrySet()) {
            if (e.getValue().isJsonArray() || e.getValue().isJsonObject()) {
                walk(server, e.getValue(), out, depth);
            }
        }
    }

    private Guidebook() {
    }
}
