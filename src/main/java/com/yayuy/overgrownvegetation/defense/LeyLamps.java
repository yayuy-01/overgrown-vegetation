package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.item.LeyLampItem;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地脈灯の働き(2026-10-22、地脈技術の 2 段目。ユーザー指定)。
 * <ul>
 *     <li><b>湧き止め</b>: 点いている(電池の残っている)地脈灯の周り {@link #RADIUS} ブロック(水平・上下とも。立方体で数える)では、
 *     バニラの敵対の分類({@link MobCategory#MONSTER})の Mob を<b>自然湧きの前に</b>落とす({@link MobSpawnEvent.SpawnPlacementCheck})。
 *     この MOD の Mob(植物側)は弾かない —— 後で忌避灯へ転用するときに差が出るように(ユーザー指定)。</li>
 *     <li>点いている灯りの台帳: ワールドごとの座標の集合。登録・解除は {@code block/LeyLampBlockEntity} の点灯の切り替え・読み込み・取り外し。
 *     自然湧きの判定は裏のスレッドからも来るので {@link ConcurrentHashMap}。</li>
 *     <li>腰の枠に付けた地脈灯: 電池の残りがあれば動く光(明るさ {@link LeyLampItem#WORN_LIGHT})。付けている間 {@link #WORN_STEP} tick ごとに
 *     {@link #WORN_COST} 減らす(満タンで 4 時間。仮決め)。切れたら周りへ配る(動く光を消すため)。腰の地脈灯は湧きを止めない(置いた物だけ)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyLamps {

    /** 湧きを止める半径(ブロック。水平・上下とも。ユーザー指定)。 */
    public static final int RADIUS = 8;
    /** 止める湧き方(自然湧き。仮決め: スポナー・パトロールなどは止めない)。 */
    static final Set<MobSpawnType> BLOCKED_SPAWNS = EnumSet.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION);
    /** 腰に付けたときの減り: 20 tick ごとに 5(満タン 72000 で 4 時間。ユーザー指定「ゆっくり。仮で 4 時間分」)。 */
    public static final int WORN_STEP = 20;
    public static final int WORN_COST = 5;

    private static final Map<ResourceKey<Level>, Set<BlockPos>> LIT = new ConcurrentHashMap<>();

    private LeyLamps() {
    }

    public static void register(Level level, BlockPos pos) {
        if (!level.isClientSide()) {
            LIT.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
        }
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = LIT.get(level.dimension());
        if (set != null) {
            set.remove(pos);
        }
    }

    /** {@code pos} が点いている地脈灯の湧き止めの範囲の中か。 */
    public static boolean covers(ResourceKey<Level> dimension, BlockPos pos) {
        Set<BlockPos> set = LIT.get(dimension);
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (BlockPos lamp : set) {
            if (Math.abs(lamp.getX() - pos.getX()) <= RADIUS && Math.abs(lamp.getY() - pos.getY()) <= RADIUS
                    && Math.abs(lamp.getZ() - pos.getZ()) <= RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** 地脈灯が止める種か: バニラの敵対の分類で、この MOD の Mob(植物側)ではない。 */
    public static boolean blocksType(EntityType<?> type) {
        if (type.getCategory() != MobCategory.MONSTER) {
            return false;
        }
        return MobGroup.of(type) == null && !OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace());
    }

    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!BLOCKED_SPAWNS.contains(event.getSpawnType()) || !blocksType(event.getEntityType())) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        if (level != null && covers(level.dimension(), event.getPos())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % WORN_STEP != 0) {
            return;
        }
        ItemStack worn = ExtraArmor.worn(player, ExtraArmor.Part.WAIST);
        if (!(worn.getItem() instanceof LeyLampItem) || LeyLampItem.charge(worn) <= 0) {
            return;
        }
        int left = Math.max(0, LeyLampItem.charge(worn) - WORN_COST);
        LeyLampItem.setCharge(worn, left);
        if (left <= 0) {
            ExtraArmor.broadcast(player);
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.ley_lamp.empty")
                    .withStyle(ChatFormatting.GRAY), true);
        }
    }
}
