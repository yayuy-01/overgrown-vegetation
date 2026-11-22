package com.yayuy.overgrownvegetation.quake;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.raid.RaidChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.List;

/**
 * フェーズが 0 → 1 になった瞬間の演出のトリガ({@link com.yayuy.overgrownvegetation.phase.PhaseData}
 * から呼ばれる)。以前ここで「胞子感染を持った Mob を1体湧かせる」処理だったが、
 * 「離れた地表で地震が起き、大地が裂けて胞子が噴き出す」演出に置き換えた。
 *
 * <p>発生地点は「ランダムに選んだ 1 人のプレイヤーから {@code quakeDistanceMin}〜{@code quakeDistanceMax}
 * の距離」— そのプレイヤーが必ず演出を体験できる近さに保ちつつ、その帯の中で全プレイヤーからできる限り
 * 遠い方向を選ぶ。ロード状況は問わず、裂け目の足跡が掛かるチャンクを強制ロードしてから {@link EarthquakeData} に
 * 登録する(Y の確定・掘削は {@link EarthquakeData} が、読み込みが済んだ次 tick 以降に行う)。
 */
public final class EarthquakeEvent {

    private static final int DIRECTION_SAMPLES = 64;

    /** フェーズ1突入時に呼ぶ。 */
    public static void onEnterPhase1(ServerLevel overworld) {
        trigger(overworld, null);
    }

    /**
     * 地割れ演出を1件発生させる。
     *
     * @param forcedEpicenter 震央を指定する(コマンド用)。{@code null} なら通常の抽選。
     * @return 発生させられたら true(プレイヤー不在などで抽選できなければ false)。
     */
    public static boolean trigger(ServerLevel overworld, @Nullable BlockPos forcedEpicenter) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.on(overworld.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.QUAKE)) { // MOD の設定「地割れ」(11-19。既定は COMMON の quakeEnabled)
            return false;
        }

        RandomSource random = overworld.random;

        int minLen = cfg.quakeFissureMinLength.get();
        int maxLen = cfg.quakeFissureMaxLength.get();
        int minWid = cfg.quakeFissureMinWidth.get();
        int maxWid = cfg.quakeFissureMaxWidth.get();
        int length = randRange(random, minLen, maxLen);
        int width = randRange(random, minWid, maxWid);
        // 裂け目の先端がプレイヤーへ届かないよう、震央は最低でも「全長の半分 + 余白」だけ離す。
        int safetyMin = length / 2 + 24;

        double[] epicenter = forcedEpicenter != null
                ? new double[]{forcedEpicenter.getX() + 0.5D, forcedEpicenter.getZ() + 0.5D}
                : pickEpicenter(overworld, random, safetyMin);
        if (epicenter == null) {
            OvergrownVegetation.LOGGER.info("地割れ演出: 発生地点を決められなかった(プレイヤー不在)。");
            return false;
        }

        EarthquakeData.ActiveQuake q = new EarthquakeData.ActiveQuake();
        q.id = random.nextLong();
        q.ex = epicenter[0];
        q.ez = epicenter[1];
        q.ey = Integer.MIN_VALUE;
        q.yaw = random.nextFloat() * (float) (Math.PI * 2.0D);
        q.length = length;
        q.width = width;
        // 最深部の Y は裂け目の大きさ(長さ・幅の平均)に応じて minFloorY(浅い)〜maxFloorY(深い)。
        double lenFrac = frac(q.length, minLen, maxLen);
        double widFrac = frac(q.width, minWid, maxWid);
        double sizeFrac = 0.5D * lenFrac + 0.5D * widFrac;
        q.floorY = (int) Math.round(Mth.lerp(sizeFrac,
                cfg.quakeFissureMinFloorY.get(), cfg.quakeFissureMaxFloorY.get()));
        q.seed = random.nextLong();
        q.startGameTime = -1L;

        // 2026-09-24 軽量化(報告 1064): 印とチケットだけ付け、この tick に同期で読み込まない
        // (以前はここで全部 FULL まで読み込み、未生成なら生成まで待っていた)。
        // 2026-09-25(ユーザー承認「レイドの見直し」、報告 1450): 固定するのは裂け目の足跡が掛かるチャンクだけ
        // (以前は震央を中心に quakeForceLoadRadius = 8 の 17x17 = 289 チャンク。長い裂け目の両端はその外に
        // はみ出し、掘るときにその場で読み込んでいた)。足跡の形は震央の高さに依らないので、ここで先に求められる。
        // EarthquakeData#advance は足跡のチャンクが裏で読み込み終わってから演出を始める。
        q.forcedChunks = RaidChunkLoader.forceKeysWithoutLoading(overworld,
                new EarthquakeFissure(overworld, q, EarthquakeData.carveSpan()).footprintChunks());

        EarthquakeData.get(overworld).begin(q);
        OvergrownVegetation.LOGGER.info("地割れ演出: 震央 ({}, {}) / 長さ {} / 幅 {}",
                Mth.floor(q.ex), Mth.floor(q.ez), q.length, q.width);
        return true;
    }

    /**
     * ランダムに選んだ 1 人のプレイヤーを基準に、{@code quakeDistanceMin}〜{@code quakeDistanceMax} の
     * 距離・ランダムな方向をたくさん試し、その帯の中で「全プレイヤーの最寄りまでの距離」が最大になる
     * (x, z) を返す。基準プレイヤーからは必ず {@code max} 以内なので、少なくとも 1 人は近くで
     * 演出を体験できる。プレイヤーが 1 人もいなければ {@code null}。
     */
    @Nullable
    private static double[] pickEpicenter(ServerLevel level, RandomSource random, int safetyMin) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return null;
        }

        int cfgMin = OvergrownVegetationConfig.COMMON.quakeDistanceMin.get();
        int cfgMax = OvergrownVegetationConfig.COMMON.quakeDistanceMax.get();
        int min = Math.max(16, Math.max(safetyMin, Math.min(cfgMin, cfgMax)));
        int max = Math.max(min + 64, Math.max(cfgMin, cfgMax));

        ServerPlayer anchor = players.get(random.nextInt(players.size()));

        double[] best = null;
        double bestScore = -1.0D;
        for (int i = 0; i < DIRECTION_SAMPLES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist = min + random.nextDouble() * (max - min);
            double x = anchor.getX() + Math.cos(angle) * dist;
            double z = anchor.getZ() + Math.sin(angle) * dist;

            double nearest = Double.MAX_VALUE;
            for (ServerPlayer p : players) {
                double dx = x - p.getX();
                double dz = z - p.getZ();
                nearest = Math.min(nearest, dx * dx + dz * dz);
            }
            if (nearest > bestScore) {
                bestScore = nearest;
                best = new double[]{x, z};
            }
        }
        return best;
    }

    private static int randRange(RandomSource random, int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo + random.nextInt(hi - lo + 1);
    }

    /** {@code v} が [a, b] のどこにあるか(0〜1)。a==b なら 0.5。 */
    private static double frac(int v, int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return hi == lo ? 0.5D : Mth.clamp((double) (v - lo) / (hi - lo), 0.0D, 1.0D);
    }

    private EarthquakeEvent() {
    }
}
