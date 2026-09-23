package com.yayuy.overgrownvegetation.worldgen.grammar;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * タートル。ブラウザ側 {@code src/turtle.js} の {@code SG.Turtle} と一致させる。SPEC §2。
 * Minecraft 非依存。
 */
final class GrammarTurtle {

    private static final double DEG = Math.PI / 180.0;

    double[] pos = {0, 0, 0};
    double[] heading = {0, 1, 0};
    double[] up = {0, 0, 1};
    double width = 0;
    double widthTarget = 0;
    double tint = 0;

    private final Deque<double[]> stack = new ArrayDeque<>();

    double[] right() {
        return GrammarVec.norm(GrammarVec.cross(heading, up));
    }

    private void reortho() {
        heading = GrammarVec.norm(heading);
        double[] r = GrammarVec.norm(GrammarVec.cross(heading, up));
        up = GrammarVec.norm(GrammarVec.cross(r, heading));
    }

    void turn(double yawDeg, double pitchDeg, double rollDeg) {
        if (yawDeg != 0) {
            double[] uAxis = GrammarVec.norm(up);
            heading = GrammarVec.rotate(heading, uAxis, yawDeg * DEG);
        }
        if (pitchDeg != 0) {
            double[] rAxis = right();
            heading = GrammarVec.rotate(heading, rAxis, pitchDeg * DEG);
            up = GrammarVec.rotate(up, rAxis, pitchDeg * DEG);
        }
        if (rollDeg != 0) {
            double[] hAxis = GrammarVec.norm(heading);
            up = GrammarVec.rotate(up, hAxis, rollDeg * DEG);
        }
        reortho();
    }

    void growToward(double[] dir, double weight) {
        heading = GrammarVec.slerp(heading, dir, Math.min(Math.max(weight, 0), 1));
        reortho();
    }

    void push() {
        stack.push(new double[] {
            pos[0], pos[1], pos[2],
            heading[0], heading[1], heading[2],
            up[0], up[1], up[2],
            width, widthTarget, tint
        });
    }

    void pop() {
        double[] s = stack.pop();
        if (s == null) {
            throw new IllegalStateException("pop: スタックが空です");
        }
        pos = new double[] {s[0], s[1], s[2]};
        heading = new double[] {s[3], s[4], s[5]};
        up = new double[] {s[6], s[7], s[8]};
        width = s[9];
        widthTarget = s[10];
        tint = s[11];
    }
}
