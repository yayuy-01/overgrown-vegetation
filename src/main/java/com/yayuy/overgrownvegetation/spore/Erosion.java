package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.ErosionPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * <b>浸食度</b> —— 極相({@code engulfed})バイオームに居ると溜まり、外へ出ると抜けていくプレイヤーの状態
 * (0〜100 %、2026-09-22)。値は {@link ModAttachments#EROSION} に持ち、ログアウトをまたいで残る。
 *
 * <h2>増減</h2>
 * <ul>
 *     <li>極相の中: {@code gainIntervalTicks}(既定 1200 = 1 分)ごとに {@code gainPerInterval}(既定 1)%</li>
 *     <li>極相の外: {@code decayIntervalTicks}(既定 600 = 30 秒)ごとに {@code decayPerInterval}(既定 1)%</li>
 * </ul>
 * ゲーム内時刻の剰余で判定しているので、<b>端数が溜まらず</b>「1 分ちょうどで 1%」になる。
 *
 * <h2>効果 —— すべて % に比例する</h2>
 * <p>{@code climax.json} の値は「100% のときの効き」で、実際には {@code ClimaxTable#scale} を掛ける。
 * 例: 最大体力 -50% は 34% のとき -17%(体力 20 なら -3.4)。
 * <ul>
 *     <li>最大体力: {@link AttributeModifier.Operation#ADD_MULTIPLIED_BASE}。<b>素の体力に対する割合</b>
 *     なので、他の体力増加と掛け合わせにならない。最大値が下がると
 *     {@code LivingEntity#onAttributeUpdated} が現在体力を切り詰める。</li>
 *     <li>経験値: {@link PlayerXpEvent.XpChange} で取得量を減らす。<b>増える側だけ</b>に掛ける
 *     (減る側にも掛けると、消費するはずの経験値まで割り引かれる)。</li>
 *     <li>装備の胞子汚染: {@link GearContaminationTracker} が {@link #gearContaminationFactor} を掛ける。</li>
 * </ul>
 *
 * <p>食料ゲージは {@link ErosionFood}(上限・回復量・自然回復の判定)。
 * 持ち出しの副作用と見た目は未実装。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Erosion {

    /** 最大体力を下げる属性修飾の id。 */
    private static final ResourceLocation MAX_HEALTH_ID = OvergrownVegetation.id("erosion_max_health");

    /**
     * 極相で浸食度が溜まるか。<b>2026-10-14 ユーザー指定で一時的に止めた</b>(「極相の身体への汚染は一時的に削除。
     * 後々また別の仕様に変更する」)。止めている間はプレイヤーの浸食度を 0 に保つ。仕組みのコードと GameTest(計算だけ)は残してある。
     */
    public static final boolean ENABLED = false;

    /** 値の比較に使う許容差。float の丸めで毎 tick 書き換えないため。 */
    private static final float EPSILON = 1.0e-4F;

    // ------------------------------------------------------------------------------------------------
    // 読み書き
    // ------------------------------------------------------------------------------------------------

    /** そのプレイヤーの浸食度(0〜100)。 */
    public static float percent(Player player) {
        return player.getData(ModAttachments.EROSION);
    }

    /** 効き具合(0〜1)。すべての効果はこれを掛けて出す。 */
    public static float scale(Player player) {
        return (float) DesignData.climax().scale(percent(player));
    }

    /**
     * 浸食度を設定する(0〜{@code maxPercent} に丸める)。属性を張り直し、
     * <b>整数 % が変わったときだけ</b>周りへ配る。
     */
    public static void set(ServerPlayer player, float value) {
        ClimaxTable table = DesignData.climax();
        float clamped = Mth.clamp(value, 0.0F, (float) table.maxPercent());
        float before = percent(player);
        if (Math.abs(before - clamped) < EPSILON) {
            return;
        }
        player.setData(ModAttachments.EROSION, clamped);
        applyAttributes(player, clamped, table);
        if (Mth.floor(before) != Mth.floor(clamped) || clamped <= 0.0F) {
            sync(player);
        }
    }

    /** 浸食度を {@code delta} % 足す(負なら減らす)。 */
    public static void add(ServerPlayer player, float delta) {
        set(player, percent(player) + delta);
    }

    /** 装備の胞子汚染の速さに掛ける倍率。浸食度 100% で {@code 1 + gearContaminationBonus}(既定 6)倍。 */
    public static double gearContaminationFactor(Player player) {
        ClimaxTable table = DesignData.climax();
        return 1.0D + table.gearContaminationBonus() * table.scale(percent(player));
    }

    // ------------------------------------------------------------------------------------------------
    // 増減
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        ErosionFood.clamp(player);
        if (!ENABLED) {
            // 止めている間は溜まらず、前のセーブで溜まっていた分も 0 に戻す(最大体力・食料・経験値の効き目もこれで消える)。
            if (percent(player) > 0.0F) {
                set(player, 0.0F);
            }
            return;
        }
        ClimaxTable table = DesignData.climax();
        long time = player.level().getGameTime();
        Holder<net.minecraft.world.level.biome.Biome> biome = player.level().getBiome(player.blockPosition());

        if (ModBiomes.isClimax(biome)) {
            if (time % table.gainIntervalTicks() == 0L) {
                add(player, (float) table.gainPerInterval());
            }
            return;
        }
        if (percent(player) > 0.0F && time % table.decayIntervalTicks() == 0L) {
            add(player, -(float) table.decayPerInterval());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 効果
    // ------------------------------------------------------------------------------------------------

    /** 経験値の取得量を減らす。<b>増える側だけ</b>(拾う・釣る・精錬)。 */
    @SubscribeEvent
    public static void onXpChange(PlayerXpEvent.XpChange event) {
        int amount = event.getAmount();
        if (amount <= 0 || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ClimaxTable table = DesignData.climax();
        double reduction = table.xpReduction() * table.scale(percent(player));
        if (reduction <= 0.0D) {
            return;
        }
        event.setAmount((int) Math.round(amount * (1.0D - reduction)));
    }

    private static void applyAttributes(ServerPlayer player, float percent, ClimaxTable table) {
        double reduction = table.maxHealthReduction() * table.scale(percent);
        applyModifier(player, Attributes.MAX_HEALTH, MAX_HEALTH_ID, -reduction);
    }

    /**
     * {@code amount} が 0 なら修飾を外し、そうでなければ張り直す
     * ({@code GroupPhaseBehavior#applyModifier} と同じ作り)。
     */
    private static void applyModifier(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id,
                                      double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (Math.abs(amount) < EPSILON) {
            if (existing != null) {
                instance.removeModifier(id);
            }
            return;
        }
        if (existing != null) {
            if (Math.abs(existing.amount() - amount) < EPSILON) {
                return;
            }
            instance.removeModifier(id);
        }
        instance.addOrUpdateTransientModifier(
                new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    // ------------------------------------------------------------------------------------------------
    // 同期
    // ------------------------------------------------------------------------------------------------

    /** 本人と、このプレイヤーを追跡している全員へ配る。 */
    private static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new ErosionPayload(player.getId(), percent(player)));
    }

    /**
     * 属性は transient(セーブされない)なので、<b>入り直すたびに張り直す</b>必要がある。
     * ログイン・リスポーン・次元移動の 3 つで、値の同期と一緒に行う。
     */
    private static void restore(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        applyAttributes(sp, percent(sp), DesignData.climax());
        if (percent(sp) > 0.0F) {
            sync(sp);
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        restore(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        restore(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        restore(event.getEntity());
    }

    /** 誰かがそのプレイヤーを追跡し始めたら、見た目を合わせるために 1 回送る。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer target)
                || !(event.getEntity() instanceof ServerPlayer watcher)) {
            return;
        }
        float value = percent(target);
        if (value > 0.0F) {
            PacketDistributor.sendToPlayer(watcher, new ErosionPayload(target.getId(), value));
        }
    }

    private Erosion() {
    }
}
