package com.yayuy.overgrownvegetation.variant;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 亜種(硬化/毒性/耐火)が解禁されているか(ワールド単位の旗、2026-09-24)。
 *
 * <p>以前は「アルボラの巣の孵化室が世界に 1 室でもあれば解禁」だった(ユーザー指定 2026-09-19)。
 * 巣を消したので(2026-09-24)、ただの旗にした。<b>既定は閉じている</b> —— 解禁されるまで、
 * 湧く個体はすべて通常個体(抽選そのものをしない)。
 *
 * <p>開けるのは将来の<b>極相バイオームの器官</b>の予定(ユーザー指定 2026-09-24、未実装)。
 * それまでは {@code /ov variants unlock} でしか開かなかったが、2026-10-10 にユーザー指定でそのコマンドを消した(今は開ける手段が無い)。
 * 開けた旗は保存されるので、器官が倒されても勝手には閉じない(閉じたければ {@link #setUnlocked} で)。
 *
 * <p><b>古いワールドの引き継ぎ(2026-09-24 巣の削除の見直し)</b>: 巣があった版で孵化室まで掘られていた
 * ワールドは、更新前から亜種が湧いていた。旗の既定が閉じているだけだと、更新した途端に通常個体だけへ
 * 戻ってしまう(プレイヤーに見える挙動の変化)。そこで<b>旗のファイルがまだ無いとき 1 回だけ</b>、
 * 残っている巣の記録({@link LegacyNestProbe})を読み、孵化室が 1 室でもあれば開いた状態で作る。
 * 巣の記録のほうは読むだけで書き戻さない。
 *
 * <p>湧くたびに読むのは {@link MobVariants#isUnlocked}(100 tick おきに読み直す控え)。
 * ここの {@link #setUnlocked} は控えも捨てるので、切り替えはその場で効く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VariantUnlockData extends SavedData {

    private static final String ID = "overgrown_vegetation_variant_unlock";

    public static final SavedData.Factory<VariantUnlockData> FACTORY =
            new SavedData.Factory<>(VariantUnlockData::new, (tag, registries) -> load(tag), null);

    private boolean unlocked;

    private VariantUnlockData() {
    }

    /**
     * オーバーワールドの 1 つだけを使う(どのディメンションから呼んでも同じ旗)。
     *
     * <p>中身は {@code computeIfAbsent} と同じだが、<b>旗が無いときの作り方だけ</b>違う:
     * 古い巣の記録に孵化室があれば開いた旗で作り、保存されるよう dirty にする(旧版の
     * 「孵化室があれば解禁」をそのまま引き継ぐ)。無ければ閉じた旗で、dirty にしないので
     * ファイルは作られない —— 次に開いたときもう一度だけ巣の記録を見に行く(軽い、サーバー起動ごとに 1 回)。
     * {@code /ov variants lock} で閉じた旗はファイルに残るので、引き継ぎで勝手に開き直すことはない。
     */
    public static VariantUnlockData get(ServerLevel level) {
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        VariantUnlockData data = storage.get(FACTORY, ID);
        if (data == null) {
            data = new VariantUnlockData();
            if (LegacyNestProbe.hadHatchery(storage)) {
                data.unlocked = true;
                data.setDirty();
            }
            storage.set(ID, data);
        }
        return data;
    }

    private static VariantUnlockData load(CompoundTag tag) {
        VariantUnlockData data = new VariantUnlockData();
        data.unlocked = tag.getBoolean("unlocked");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("unlocked", this.unlocked);
        return tag;
    }

    /** 保存されている旗をそのまま読む(控えを通さない)。湧きの判定には {@link MobVariants#isUnlocked} を使う。 */
    public static boolean isUnlocked(ServerLevel level) {
        return get(level).unlocked;
    }

    /** 亜種を解禁する(将来の器官から呼ぶ入口)。 */
    public static void unlock(ServerLevel level) {
        setUnlocked(level, true);
    }

    /** 旗を書き換える。{@link MobVariants} の控えも捨てるので、次に湧く個体から効く。 */
    public static void setUnlocked(ServerLevel level, boolean unlocked) {
        VariantUnlockData data = get(level);
        if (data.unlocked != unlocked) {
            data.unlocked = unlocked;
            data.setDirty();
        }
        MobVariants.forgetUnlockCache();
    }

    /** ワールドを閉じたら控えを捨てる(次に開いた別のワールドへ前の旗を持ち越さない)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MobVariants.forgetUnlockCache();
    }

    /**
     * 消したアルボラの巣の保存データ({@code overgrown_vegetation_nest}、2026-09-24 に削除)を
     * <b>読むだけ</b>の使い捨て。旧 {@code NestData.hatcheryCount() > 0} と同じ判定をする:
     * {@code nests} の各巣の {@code chambers} に、種類 {@code "hatchery"} で位置が読める側室が 1 つでもあるか
     * (旧 {@code NestData.load} は種類か位置が読めない側室を捨てていたので、そこも揃える)。
     *
     * <p>一度も dirty にしないので {@code DimensionDataStorage.save} で書き戻されることはない
     * (元のファイルはそのまま残る)。読んだ結果は保存データの控え(サーバーごと)に載るだけで、
     * サーバーが止まれば一緒に捨てられる。
     */
    private static final class LegacyNestProbe extends SavedData {

        private static final String LEGACY_ID = "overgrown_vegetation_nest";

        private static final SavedData.Factory<LegacyNestProbe> FACTORY =
                new SavedData.Factory<>(LegacyNestProbe::new, (tag, registries) -> read(tag), null);

        private boolean hadHatchery;

        private LegacyNestProbe() {
        }

        /** 古い巣の記録に孵化室があったか。記録が無ければ false。 */
        static boolean hadHatchery(DimensionDataStorage storage) {
            LegacyNestProbe probe = storage.get(FACTORY, LEGACY_ID);
            return probe != null && probe.hadHatchery;
        }

        private static LegacyNestProbe read(CompoundTag tag) {
            LegacyNestProbe probe = new LegacyNestProbe();
            ListTag nests = tag.getList("nests", Tag.TAG_COMPOUND);
            for (int i = 0; i < nests.size() && !probe.hadHatchery; i++) {
                ListTag chambers = nests.getCompound(i).getList("chambers", Tag.TAG_COMPOUND);
                for (int c = 0; c < chambers.size(); c++) {
                    CompoundTag chamber = chambers.getCompound(c);
                    if ("hatchery".equals(chamber.getString("type"))
                            && NbtUtils.readBlockPos(chamber, "pos").isPresent()) {
                        probe.hadHatchery = true;
                        break;
                    }
                }
            }
            return probe;
        }

        /** 呼ばれない(dirty にしないため)。念のため何も書かない。 */
        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            return tag;
        }
    }
}
