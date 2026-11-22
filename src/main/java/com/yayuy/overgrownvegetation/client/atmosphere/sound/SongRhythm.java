package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import java.util.Random;

/**
 * 鳴き声の<b>休み</b>(2026-11-01。ユーザー「夜のコオロギ様の鳴き声が休憩なしに常に鳴り続けているのはおかしい。所々で休憩が挟まるようにしてほしい」)。
 * 現実のコオロギ・キリギリスのように、1 匹ごとに「鳴く区間」と「黙る区間」を交互にし({@link Bouts})、森全体でもときどき誰も鳴かない「凪」を挟む({@link Lull})。
 * 黙るのは鳴きの切れ目から(呼び手が {@link InsectSynth.Cricket#setSinging} などで鳴き終えてから黙らせる)。
 * <b>Minecraft の型を使わない</b>({@link #main} で 10 分の模擬をして、鳴いている割合を出せる)。数値はすべて 11-01 仮決め。
 */
public final class SongRhythm {

    private SongRhythm() {
    }

    /** 1 匹の鳴く区間・黙る区間(tick)。最初は鳴く区間の途中から始まる(置いた途端に全員が同時に休まないように)。 */
    public static final class Bouts {
        private boolean singing = true;
        private long until = -1L;

        /** 毎 tick。区間が終わったら次の区間へ(秒の範囲は鳴く sing・黙る rest)。今鳴く区間か。 */
        public boolean tick(long now, Random random, float singMin, float singMax, float restMin, float restMax) {
            if (this.until < 0L) {
                this.until = now + (long) (span(random, singMin, singMax) * random.nextFloat() * 20.0F);
            } else if (now >= this.until) {
                this.singing = !this.singing;
                this.until = now + (long) ((this.singing ? span(random, singMin, singMax) : span(random, restMin, restMax)) * 20.0F);
            }
            return this.singing;
        }
    }

    /** 森全体の凪: {@code intervalMin}〜{@code Max} 秒おきに {@code lengthMin}〜{@code Max} 秒、新しく鳴き始めず、鳴いている個体も区切りで黙る。 */
    public static final class Lull {
        private long start = -1L;
        private long end = -1L;

        /** 毎 tick。今が凪か。 */
        public boolean tick(long now, Random random, float intervalMin, float intervalMax, float lengthMin, float lengthMax) {
            if (this.start < 0L) {
                this.start = now + (long) (span(random, intervalMin, intervalMax) * 20.0F);
                this.end = this.start + (long) (span(random, lengthMin, lengthMax) * 20.0F);
            } else if (now >= this.end) {
                this.start = now + (long) (span(random, intervalMin, intervalMax) * 20.0F);
                this.end = this.start + (long) (span(random, lengthMin, lengthMax) * 20.0F);
            }
            return now >= this.start && now < this.end;
        }

        public void reset() {
            this.start = -1L;
            this.end = -1L;
        }
    }

    static float span(Random random, float min, float max) {
        return min + random.nextFloat() * Math.max(0.0F, max - min);
    }

    /**
     * 確かめ用: 10 分(12000 tick)× 20 回の模擬で、鳴いている割合を出す。{@code java SongRhythm.java}。値は insects 節の既定と同じ。
     * コオロギ = 鳴く区間かつ凪でない tick の割合。キリギリス様 = 1 匹ぶんの「鳴く 10〜30 秒 → 休む 20〜60 秒」(凪で早く切り上げる)。
     */
    public static void main(String[] args) {
        int runs = 20;
        long length = 12000L;
        double cricket = 0.0;
        double lullShare = 0.0;
        double katydid = 0.0;
        int lulls = 0;
        for (int run = 0; run < runs; run++) {
            Random random = new Random(run * 7919L + 1L);
            Bouts bouts = new Bouts();
            Lull lull = new Lull();
            long sing = 0L;
            long quiet = 0L;
            boolean wasLull = false;
            // キリギリス様: 鳴き終わり(stopAt)か凪で止まり、次は休み(interval)のあと。
            long kStop = -1L;
            long kNext = 0L;
            long kSing = 0L;
            for (long t = 0L; t < length; t++) {
                boolean isLull = lull.tick(t, random, 180.0F, 360.0F, 30.0F, 90.0F);
                if (isLull && !wasLull) {
                    lulls++;
                }
                wasLull = isLull;
                if (isLull) {
                    quiet++;
                }
                if (bouts.tick(t, random, 20.0F, 60.0F, 15.0F, 40.0F) && !isLull) {
                    sing++;
                }
                if (kStop >= 0L) {
                    if (t >= kStop || isLull) {
                        kStop = -1L;
                        kNext = t + (long) (span(random, 20.0F, 60.0F) * 20.0F);
                    } else {
                        kSing++;
                    }
                } else if (t >= kNext && !isLull) {
                    kStop = t + (long) (span(random, 10.0F, 30.0F) * 20.0F);
                }
            }
            cricket += sing / (double) length;
            lullShare += quiet / (double) length;
            katydid += kSing / (double) length;
        }
        System.out.printf("10 分 × %d 回: コオロギ 1 匹が鳴いている割合 %.1f%% / 凪の割合 %.1f%%(10 分あたり %.1f 回)/ キリギリス様 1 匹ぶん %.1f%%%n",
                runs, 100.0 * cricket / runs, 100.0 * lullShare / runs, lulls / (double) runs, 100.0 * katydid / runs);
    }
}
