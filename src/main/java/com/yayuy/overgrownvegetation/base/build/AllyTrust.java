package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.data.BaseTradeConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同盟軍への信頼(順 2 の着手順 5、2026-10-07)。ワールドに 1 つ(オーバーワールドの data に保存)。
 *
 * <p><b>ユーザー指定(10-07)</b>「信頼は同盟軍全体で 1 人に 1 つ。どの基地で寄付しても上がり、どの基地でも同じだけ取引できる。マルチでは人ごとに別」。
 * だから基地の台帳({@link BaseSites})ではなくプレイヤーの UUID で持つ。ほかに、その日(暦 {@link BaseEconomy#today})に上がった信頼
 * (1 日の上限のため)と、軍票の端数(値打ちを軍票に直したときの 1 枚に満たない分。人ごとに持ち越す)を持つ。
 */
public final class AllyTrust extends SavedData {

    private static final String ID = "overgrown_vegetation_ally_trust";
    public static final SavedData.Factory<AllyTrust> FACTORY =
            new SavedData.Factory<>(AllyTrust::new, (tag, registries) -> load(tag), null);

    private static final class Entry {
        double trust;
        long day = Long.MIN_VALUE;
        double today;
        double scripRemainder;
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    private AllyTrust() {
    }

    public static AllyTrust get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static AllyTrust load(CompoundTag tag) {
        AllyTrust data = new AllyTrust();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            if (!t.hasUUID("uuid")) {
                continue;
            }
            Entry e = new Entry();
            e.trust = t.getDouble("trust");
            e.day = t.contains("day") ? t.getLong("day") : Long.MIN_VALUE;
            e.today = t.getDouble("today");
            e.scripRemainder = t.getDouble("scrip");
            data.entries.put(t.getUUID("uuid"), e);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> me : entries.entrySet()) {
            CompoundTag t = new CompoundTag();
            Entry e = me.getValue();
            t.putUUID("uuid", me.getKey());
            t.putDouble("trust", e.trust);
            t.putLong("day", e.day);
            t.putDouble("today", e.today);
            t.putDouble("scrip", e.scripRemainder);
            list.add(t);
        }
        tag.put("players", list);
        return tag;
    }

    private Entry entry(UUID player) {
        return entries.computeIfAbsent(player, k -> new Entry());
    }

    /** いまの信頼(0〜上限)。 */
    public double trust(UUID player) {
        Entry e = entries.get(player);
        return e != null ? e.trust : 0.0D;
    }

    /** その日にあといくつ信頼が上がるか(1 日の上限 − その日に上がった分)。 */
    public double todayLeft(UUID player, long day, BaseTradeConfig cfg) {
        Entry e = entries.get(player);
        double used = e != null && e.day == day ? e.today : 0.0D;
        return Math.max(0.0D, cfg.trustDailyCap - used);
    }

    /** 信頼を {@code amount} だけ上げようとする。1 日の上限と信頼の上限で切った、実際に上がった量を返す。 */
    public double gain(UUID player, double amount, long day, BaseTradeConfig cfg) {
        if (amount <= 0.0D) {
            return 0.0D;
        }
        Entry e = entry(player);
        if (e.day != day) {
            e.day = day;
            e.today = 0.0D;
        }
        double room = Math.min(Math.max(0.0D, cfg.trustDailyCap - e.today), Math.max(0.0D, cfg.trustMax - e.trust));
        double got = Math.min(amount, room);
        if (got > 0.0D) {
            e.trust += got;
            e.today += got;
            setDirty();
        }
        return got;
    }

    /**
     * 軍票 {@code scrip} 枚ぶん(端数あり)を払う。持ち越した端数と足して、渡す枚数(整数)を返す。残りの端数は次へ持ち越す。
     */
    public int payScrip(UUID player, double scrip) {
        if (scrip <= 0.0D) {
            return 0;
        }
        Entry e = entry(player);
        double total = e.scripRemainder + scrip;
        int whole = (int) Math.floor(total + 1e-9);
        e.scripRemainder = Math.max(0.0D, total - whole);
        setDirty();
        return whole;
    }

    /** 持ち越している軍票の端数(0〜1)。 */
    public double scripRemainder(UUID player) {
        Entry e = entries.get(player);
        return e != null ? e.scripRemainder : 0.0D;
    }

    /** 開発用: 信頼を決める(その日に上がった分も 0 に戻す)。 */
    public void set(UUID player, double trust, BaseTradeConfig cfg) {
        Entry e = entry(player);
        e.trust = Math.max(0.0D, Math.min(cfg.trustMax, trust));
        e.today = 0.0D;
        setDirty();
    }

    /** 開発用・GameTest 用: その人の記録を消す。 */
    public void forget(UUID player) {
        if (entries.remove(player) != null) {
            setDirty();
        }
    }
}
