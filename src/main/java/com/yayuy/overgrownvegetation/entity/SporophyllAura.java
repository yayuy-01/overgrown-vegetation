package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「スポロフィル」({@link SporophyllEntity})が傘から撒く胞子の<b>効果そのもの</b>。
 * 半径 {@code sporophyllAuraRange}(既定 64。ユーザー指定 2026-09-26 に 32 から倍に)の中で、次の 4 つが同時に起きる。
 *
 * <p><b>味方はグループ4 以上だけ</b>(2026-09-15 に変更)。グループ1〜3 はグループ4 の獲物に
 * なったので、支援役が食料を強化していては筋が通らない —— バフから外し、デバフ側へ落とす。
 * とくに「纏わりついた胞子」はグループ4 からの被検知距離を上げるので、そのまま
 * <b>獲物を狩りやすくする</b>効果になる。寄生植物装備を着けたプレイヤーはグループ 6 として味方に入る。
 *
 * <ol>
 *     <li><b>味方(グループ4 以上)へバフ</b> — 再生 I・攻撃力上昇 II と、独自バフ
 *         {@link ModMobEffects#SPOROPHYLL_SPORES 「スポロフィルの胞子」}。
 *         段階は effects.json の {@code sporophyllSpores} で種類ごと(-1 = 配らない)。
 *         ユーザー指定 2026-09-26: 範囲を 64 にする代わりに<b>耐性をやめ、再生を II → I</b>
 *         (それまでは 3 つとも II)。</li>
 *     <li><b>敵(プレイヤー含む)へデバフ</b> — 独自デバフ
 *         {@link ModMobEffects#CLINGING_SPORES 「纏わりついた胞子」}<b>ひとつ</b>
 *         (移動速度低下と攻撃力低下はこの効果自身が持っている)。さらに
 *         {@code sporophyllGearContaminationIntervalTicks}(既定 5 秒)ごとに装備の胞子汚染 +1%。</li>
 *     <li><b>胞子濃度の上乗せ</b> — {@link #concentrationBoostAt}。中心で
 *         {@code sporophyllConcentrationBoostPercent}、縁で 0 まで直線的に落ちる。</li>
 *     <li><b>過繫茂バイオームと同じ扱い</b> — {@link #isInside} が true の場所では、
 *         胞子感染1 が満了すると 2 へ、2 が満了すると 3 へ<b>上がる</b>
 *         ({@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker})。</li>
 * </ol>
 *
 * <h2>胞子除去装置の圏内では効果が消える(ユーザー要望 2026-09-16)</h2>
 * <b>稼働中の胞子除去装置</b>({@link SporeDeviceData#insidePurifier})の効果範囲では、
 * 配らないだけでなく<b>既に付いているぶんも剥がす</b> ——
 * 味方のバフ({@link #purgeBuffs})も敵のデバフ({@link #purgeDebuffs})も両方。
 * 配らないだけだと持続(バフ 1 分 / デバフ 30 秒)が切れるまで効いたままで、
 * 「装置を建てたのに効果が出ない」ことになる。装備の胞子汚染も圏内では進まない
 * (<b>既に付いている汚染は消えない</b> —— あれは効果ではなく道具そのものの状態)。
 *
 * <p>剥がす仕掛けは 2 か所にある。ここ({@link #apply})が受け持つのは
 * <b>傘の下かつ装置の圏内</b>という重なった場所だけなので、
 * 効果を持ったまま傘の外へ出て装置に入った個体は
 * {@link com.yayuy.overgrownvegetation.effect.SporophyllAuraPurge} が拾う。
 *
 * <h2>効果は「配り直し」で持続する</h2>
 * 持続(バフ 1 分 / デバフ 30 秒)より<b>ずっと短い間隔</b>({@code sporophyllAuraIntervalTicks}、
 * 既定 1 秒)で配り直すので、<b>範囲内に居る限り途切れない</b>(ユーザー指定)。
 * 範囲を出るとそこから持続ぶんだけ残って消える —— アイコンが「まだ効いている残り時間」を兼ねる。
 *
 * <h2>生きている個体の居場所を静的に持つ理由</h2>
 * 胞子濃度({@link com.yayuy.overgrownvegetation.spore.SporeConcentration})と感染の判定は
 * <b>スポロフィル本体とは無関係な場所から</b>呼ばれる(チャンク単位の計算・他の Mob の tick)。
 * そこから「近くにスポロフィルが居るか」を毎回エンティティ検索で調べるのは高くつくので、
 * 胞子装置({@link com.yayuy.overgrownvegetation.spore.SporeDeviceData})と同じように
 * <b>位置の一覧</b>を持っておく。一覧は本体が生きているあいだ自分で更新し、
 * {@link #EXPIRE_TICKS} 更新が来なければ(死亡・アンロード)自動的に落ちる。
 *
 * <p>落ちた項目は {@link #SWEEP_INTERVAL} tick ごとに一覧から<b>実際に捨てる</b>(2026-09-24)。
 * 歩くスポロフィルは踏んだマスごとに項目を足すので、捨てないと 1 体で 1 時間に数千件たまり、
 * 読む側({@link #isInside} / {@link #concentrationBoostAt})の全件ループが重くなっていた。
 * 捨てるのは<b>読む側がもともと飛ばしていた項目だけ</b>なので、判定の結果は変わらない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporophyllAura {

    /** 位置の登録がこれだけ更新されなければ「もう居ない」とみなす(tick)。 */
    private static final int EXPIRE_TICKS = 60;
    /** 「もう居ない」項目をまとめて捨てる間隔(tick)。 */
    private static final long SWEEP_INTERVAL = 20L;

    /**
     * ディメンションごとの「生きているスポロフィルの位置 → 最後に見た gameTime」。
     *
     * <p>サーバースレッドからしか触らないので同期はしていない。
     */
    private static final Map<ResourceKey<Level>, Long2LongMap> ACTIVE = new HashMap<>();
    /** ディメンションごとの、最後に {@link #sweep} した gameTime。 */
    private static final Map<ResourceKey<Level>, Long> LAST_SWEEP = new HashMap<>();

    // --- 本体からの登録 ---

    /** 生きているあいだ本体が呼ぶ。位置と「今」を記録する。 */
    public static void mark(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        Long2LongMap map = ACTIVE.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        sweep(level.dimension(), map, now);
        map.put(pos.asLong(), now);
    }

    /**
     * {@link #EXPIRE_TICKS} より古い項目を捨てる(同じディメンションでは {@link #SWEEP_INTERVAL} tick に 1 回まで)。
     *
     * <p>条件は読む側が飛ばす条件({@code now - 最後に見た時刻 > EXPIRE_TICKS})と<b>まったく同じ</b>。
     * ゲーム内時刻は戻らないので、ここで捨てた項目は後から読んでも必ず飛ばされる —— 結果は捨てる前と同じ。
     */
    private static void sweep(ResourceKey<Level> dimension, Long2LongMap map, long now) {
        Long last = LAST_SWEEP.get(dimension);
        if (last != null && now - last >= 0L && now - last < SWEEP_INTERVAL) {
            return;
        }
        LAST_SWEEP.put(dimension, now);
        map.values().removeIf(seen -> now - seen > EXPIRE_TICKS);
    }

    /**
     * ワールドを閉じたら空にする。static なので、次に別のワールド(や同じワールド)を開いたとき
     * 前の位置が残らないように({@link com.yayuy.overgrownvegetation.defense.DefenseLifecycle} と同じ)。
     * 開き直した後の状態は、ゲームを起動し直して開いたときと同じになる。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
        LAST_SWEEP.clear();
    }

    /** 死亡・退場時に本体が呼ぶ。 */
    public static void forget(ServerLevel level, BlockPos pos) {
        Long2LongMap map = ACTIVE.get(level.dimension());
        if (map != null) {
            map.remove(pos.asLong());
        }
    }

    // --- 外から見る判定 ---

    /**
     * {@code pos} がどれかのスポロフィルの傘の下か。
     * {@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker} が
     * 「過繫茂バイオームと同じ扱い」の判定に使う。
     */
    public static boolean isInside(ServerLevel level, BlockPos pos) {
        if (!com.yayuy.overgrownvegetation.data.DesignData.effects().clingingEscalatesInfection()) {
            return false;
        }
        double range = OvergrownVegetationConfig.COMMON.sporophyllAuraRange.get();
        return nearest(level, pos.getX() + 0.5D, pos.getZ() + 0.5D, pos.getY() + 0.5D, range) >= 0.0D;
    }

    /**
     * {@code chunk} の胞子濃度に上乗せする値(%)。中心で最大、半径の縁で 0。
     * 複数体いれば合算する(胞子装置と同じ扱い)。
     *
     * <p>濃度はキャッシュされる(既定 200 tick)ので、<b>歩き回るスポロフィルの効果は少し遅れて付いてくる</b>。
     * 本体はチャンクをまたいだときにキャッシュを捨てるので、居座っている限りはずれない。
     */
    public static double concentrationBoostAt(ServerLevel level, ChunkPos chunk) {
        Long2LongMap map = ACTIVE.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return 0.0D;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double boost = cfg.sporophyllConcentrationBoostPercent.get();
        if (boost <= 0.0D) {
            return 0.0D;
        }
        double range = cfg.sporophyllAuraRange.get();
        double centerX = (chunk.x << 4) + 8.0D;
        double centerZ = (chunk.z << 4) + 8.0D;
        long now = level.getGameTime();
        double total = 0.0D;
        // fastIterable は項目ごとに Entry を作らない(並び順は long2LongEntrySet と同じ)。
        for (Long2LongMap.Entry entry : Long2LongMaps.fastIterable(map)) {
            if (now - entry.getLongValue() > EXPIRE_TICKS) {
                continue;
            }
            long packed = entry.getLongKey();
            double dx = BlockPos.getX(packed) + 0.5D - centerX;
            double dz = BlockPos.getZ(packed) + 0.5D - centerZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < range) {
                total += boost * (1.0D - distance / range);
            }
        }
        return total;
    }

    /**
     * {@code (x, z)} から {@code range} 以内にいるスポロフィルまでの最短距離。居なければ {@code -1}。
     * 高さも見る(真上・真下だけ離れている相手を巻き込まないため)。
     */
    private static double nearest(ServerLevel level, double x, double z, double y, double range) {
        Long2LongMap map = ACTIVE.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return -1.0D;
        }
        long now = level.getGameTime();
        double best = -1.0D;
        double rangeSq = range * range;
        for (Long2LongMap.Entry entry : Long2LongMaps.fastIterable(map)) {
            if (now - entry.getLongValue() > EXPIRE_TICKS) {
                continue;
            }
            long packed = entry.getLongKey();
            double dx = BlockPos.getX(packed) + 0.5D - x;
            double dy = BlockPos.getY(packed) + 0.5D - y;
            double dz = BlockPos.getZ(packed) + 0.5D - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= rangeSq && (best < 0.0D || distSq < best)) {
                best = distSq;
            }
        }
        return best;
    }

    // --- 本体が毎 interval 呼ぶ配布処理 ---

    /**
     * {@code source} の周りへバフ・デバフ・装備汚染を配る。
     *
     * @param gearTick この回で装備の胞子汚染も進めるか
     */
    public static void apply(ServerLevel level, SporophyllEntity source, boolean gearTick) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double range = cfg.sporophyllAuraRange.get();
        double rangeSq = range * range;
        AABB box = source.getBoundingBox().inflate(range);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box);

        // 効果そのものの数値は effects.json 側(mod マネージャーのバフ/デバフタブ)。
        com.yayuy.overgrownvegetation.data.EffectTable fx = com.yayuy.overgrownvegetation.data.DesignData.effects();
        int buffTicks = fx.sporophyllBuffTicks();
        int debuffTicks = fx.clingingDebuffTicks();
        int debuffAmp = fx.clingingDebuffAmplifier();

        // 2026-09-24 軽量化(監査 940): 除去装置がこの回の範囲に届きうるかを 1 回だけ調べ、
        // 届かなければ候補ごとの insidePurifier を省く(届かない範囲では必ず false なので結果は同じ)。
        // 調べるのは「最初に装置の判定が要る候補が出たとき」—— 稼働中一覧のキャッシュを読む時点を以前と揃えるため。
        // 距離の判定を通った候補の足元は、必ず source ± range の四角に入る(浮動小数の端数に備えて 1 マス広げて聞く)。
        boolean purifierClears = cfg.sporophyllPurifierClearsAura.get();
        int purifierReach = 0; // 0 = まだ調べていない、1 = 届きうる、-1 = 届かない

        for (LivingEntity entity : nearby) {
            if (entity == source || !entity.isAlive() || entity.distanceToSqr(source) > rangeSq) {
                continue;
            }
            boolean ally = isAlly(entity);
            if (!ally && !isVictim(entity, ally)) {
                continue;
            }
            if (purifierClears && purifierReach == 0) {
                purifierReach = SporeDeviceData.purifierMayReach(level,
                        Mth.floor(source.getX() - range) - 1, Mth.floor(source.getZ() - range) - 1,
                        Mth.floor(source.getX() + range) + 1, Mth.floor(source.getZ() + range) + 1) ? 1 : -1;
            }
            // 胞子除去装置の圏内では効果を配らない。既に付いているぶんも剥がす。
            if (purifierClears && purifierReach > 0
                    && SporeDeviceData.insidePurifier(level, entity.blockPosition())) {
                if (ally) {
                    purgeBuffs(entity);
                } else {
                    purgeDebuffs(entity);
                }
                continue;
            }
            if (ally) {
                buff(entity, buffTicks, fx);
            } else {
                debuff(entity, debuffTicks, debuffAmp);
                if (gearTick) {
                    contaminateGear(entity, cfg.sporophyllGearContaminationPercent.get());
                }
            }
        }
    }

    /**
     * 味方 = グループ4 以上の Mob と、寄生植物装備を着けたプレイヤー(グループ 6 扱い)。
     * ほかのスポロフィルも味方に入るが、<b>撒いている本人には掛からない</b>({@link #apply} が {@code source} を飛ばす)。
     */
    private static boolean isAlly(LivingEntity entity) {
        // Entity 版: 「寄生植物装備」を着けたプレイヤーはグループ 6 として味方(バフを受ける)。
        MobGroup group = MobGroup.of(entity);
        // グループ1〜3 は<b>味方ではなく獲物</b>(ユーザー指定 2026-09-15)。
        // グループ4 がグループ1〜3 を狩って成り上がる関係になったので、
        // 支援役が食料を強化していては筋が通らない。バフから外し、下の isVictim で
        // デバフ側へ落とす —— とくに「纏わりついた胞子」がグループ4 からの被検知距離を
        // 上げるので、そのまま<b>獲物を狩りやすくする</b>効果になる。
        return group != null && group.number() >= MobGroup.GROUP_4.number();
    }

    /**
     * デバフの対象 = 味方でない生き物。クリエイティブ/観戦のプレイヤーは除く。
     *
     * <p>{@code ally} は呼び出し側で求めた {@link #isAlly} の答え(2026-09-24 軽量化、監査 940:
     * 以前は非プレイヤーについてここで {@link #isAlly} をもう一度呼んでいた。答えは同じ)。
     */
    private static boolean isVictim(LivingEntity entity, boolean ally) {
        if (entity instanceof Player player) {
            return !player.isCreative() && !player.isSpectator()
                    && !com.yayuy.overgrownvegetation.item.PlantArmor.isHost(player);
        }
        return !ally;
    }


    /**
     * この個体にスポロフィルの効果(バフ or デバフ)が付いているか。
     *
     * <p>見るのは独自効果の 2 つだけ ——
     * {@link #buff} / {@link #debuff} はそれぞれ<b>必ず同じ持続で一緒に</b>配るので、
     * この 2 つが目印になる(一緒に配るバニラの効果は他の出所もありうるが、こちらは無い)。
     */
    public static boolean hasAuraEffect(LivingEntity entity) {
        return entity.hasEffect(ModMobEffects.SPOROPHYLL_SPORES)
                || entity.hasEffect(ModMobEffects.CLINGING_SPORES);
    }

    /**
     * {@link #buff} が配ったものを剥がす(ユーザー要望 2026-09-16:
     * 「スポロフィルのバフが胞子除去装置の範囲内に入ると消失するようにしてほしい」)。
     *
     * <p>バニラの効果も一緒に消すが、<b>いま配っている種類だけ</b>(段階が -1 のものは触らない)。
     * 2026-09-26 に耐性を配るのをやめたので、耐性はもう消さない —— 木の実など他所から付いた耐性を巻き込まないため。
     * 効果には出所が記録されないので、消す種類については<b>他所から付いたぶんも一緒に消える</b>。
     * 味方には寄生植物装備を着けたプレイヤーも入る({@link #isAlly})ので、そのプレイヤーが
     * ポーションで付けた再生・攻撃力上昇も、除去装置の圏内でこれが呼ばれると消える。
     */
    public static void purgeBuffs(LivingEntity ally) {
        com.yayuy.overgrownvegetation.data.EffectTable fx = com.yayuy.overgrownvegetation.data.DesignData.effects();
        if (fx.sporophyllRegenerationAmplifier() >= 0) {
            ally.removeEffect(MobEffects.REGENERATION);
        }
        if (fx.sporophyllStrengthAmplifier() >= 0) {
            ally.removeEffect(MobEffects.DAMAGE_BOOST);
        }
        if (fx.sporophyllResistanceAmplifier() >= 0) {
            ally.removeEffect(MobEffects.DAMAGE_RESISTANCE);
        }
        ally.removeEffect(ModMobEffects.SPOROPHYLL_SPORES);
    }

    /**
     * {@link #debuff} が配ったものを剥がす(ユーザー要望 2026-09-16「デバフも消えるように」)。
     *
     * <p>消すのは<b>「纏わりついた胞子」ひとつだけ</b>。デバフの対象には
     * <b>プレイヤーが入る</b>ので、以前のようにバニラの鈍化・弱体化を配っていると、
     * 消すときに<b>他所から掛かったぶんまで巻き込んでいた</b>
     * (鈍化のポーション・別 MOD の効果など。効果に出所は記録されないので見分けられない)。
     * 速度と攻撃力を {@link com.yayuy.overgrownvegetation.effect.ClingingSporesEffect} の中へ
     * 移したことでその問題が消えた —— 強さは変わらないまま、消えるのは胞子由来のぶんだけになる。
     */
    public static void purgeDebuffs(LivingEntity victim) {
        victim.removeEffect(ModMobEffects.CLINGING_SPORES);
    }

    /**
     * 味方を強化する。配るのは「スポロフィルの胞子」と、バニラの再生・攻撃力上昇・耐性のうち
     * effects.json で段階が 0 以上のもの(既定: 再生 I・攻撃力上昇 II、耐性は配らない。ユーザー指定 2026-09-26)。
     *
     * <p><b>粒子を出す({@code visible = true})のは必須</b>(ユーザー報告 2026-09-15
     * 「Jade で確認しても何もついていないように表示される」)。Mob に付いた効果は
     * <b>バニラではクライアントへまったく同期されない</b> —— 送られるのは
     * {@code DATA_EFFECT_PARTICLES}(= 粒子を出す効果の一覧)だけで、
     * 効果そのものが届くのは「その Mob に<b>騎乗している</b>プレイヤー」に限られる
     * ({@code LivingEntity#sendEffectToPassengers})。
     * そして Jade の一覧はサーバー側から効果を読むものの、
     * <b>{@code MobEffectInstance#isVisible()} で絞り込む</b>ので、粒子を出さない効果は載らない。
     * つまり {@code visible = false} だと、<b>本当に掛かっていても確かめる手段が無い</b>。
     *
     * <p>{@code ambient = true} のままなので、粒子はビーコンと同じ<b>まばらで控えめ</b>な出方になる
     * (毎 tick 1/20 ほど)。「傘の下の仲間が強化されている」ことが一目で分かる程度に留まる。
     */
    private static void buff(LivingEntity ally, int ticks, com.yayuy.overgrownvegetation.data.EffectTable fx) {
        addIfGiven(ally, MobEffects.REGENERATION, ticks, fx.sporophyllRegenerationAmplifier());
        addIfGiven(ally, MobEffects.DAMAGE_BOOST, ticks, fx.sporophyllStrengthAmplifier());
        addIfGiven(ally, MobEffects.DAMAGE_RESISTANCE, ticks, fx.sporophyllResistanceAmplifier());
        addOrRefresh(ally, ModMobEffects.SPOROPHYLL_SPORES, ticks, 0);
    }

    /** 段階が -1(配らない)なら何もしない。 */
    private static void addIfGiven(LivingEntity entity, Holder<MobEffect> effect, int ticks, int amplifier) {
        if (amplifier >= 0) {
            addOrRefresh(entity, effect, ticks, amplifier);
        }
    }

    /**
     * 敵を弱体化する。配るのは<b>「纏わりついた胞子」ひとつだけ</b>
     * (ユーザー提案 2026-09-16)。移動速度低下と攻撃力低下は
     * {@link com.yayuy.overgrownvegetation.effect.ClingingSporesEffect} が
     * 自前の能力値修正として持っているので、バニラ効果を別に配る必要がない。
     *
     * <p>こうしてあるのは<b>胞子除去装置の圏内で消すため</b>({@link #purgeDebuffs})。
     * バニラの鈍化・弱体化を別々に配っていると、消すときに
     * <b>他所から掛かったぶんまで巻き込む</b> —— 効果には出所が記録されない。
     */
    private static void debuff(LivingEntity victim, int ticks, int amplifier) {
        addOrRefresh(victim, ModMobEffects.CLINGING_SPORES, ticks, amplifier);
    }

    /**
     * 既に付いている同じ効果を<b>毎回上書きしない</b>。
     *
     * <p>{@code addEffect} は同じ強さなら持続を伸ばしてくれるが、毎秒呼ぶと
     * そのたびにクライアントへ同期パケットが飛ぶ。残りが半分を切ってから配り直せば、
     * 見た目は途切れないまま通信が 1/N になる。
     *
     * <p>2026-09-24 軽量化(監査 940): 配り直すと決まってから {@link MobEffectInstance} を作る
     * (以前は判定の前に毎回作って、大半を捨てていた)。判定の式は以前と同じ ——
     * コンストラクタが強さを 0〜255 に丸めるので、比べるのも丸めた値にしてある。
     */
    private static void addOrRefresh(LivingEntity entity, Holder<MobEffect> effect, int ticks, int amplifier) {
        MobEffectInstance current = entity.getEffect(effect);
        if (current != null && current.getAmplifier() >= Mth.clamp(amplifier, 0, 255)
                && current.getDuration() > ticks / 2) {
            return;
        }
        entity.addEffect(new MobEffectInstance(effect, ticks, amplifier, true, true, true));
    }

    /** 身につけている装備(防具・手持ち)の胞子汚染を進める。 */
    private static void contaminateGear(LivingEntity victim, double percent) {
        if (percent <= 0.0D) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = victim.getItemBySlot(slot);
            if (GearContamination.isContaminable(stack)) {
                GearContamination.add(stack, percent);
            }
        }
    }

    private SporophyllAura() {
    }
}
