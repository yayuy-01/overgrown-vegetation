package com.yayuy.overgrownvegetation.client;

import java.util.function.Consumer;

/**
 * 描画の中の細かい区切り(見える区画の更新・区画の組み立ての依頼)を、測る道具(devbridge の FxProfiler、配布 jar に入らない)へ知らせる口(2026-10-20)。
 * ふだんは null で何もしない。
 */
public final class RenderPhaseHook {
    public static volatile Consumer<String> mark;

    public static void mark(String name) {
        Consumer<String> m = mark;
        if (m != null) {
            m.accept(name);
        }
    }

    private RenderPhaseHook() {
    }
}
