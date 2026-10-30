#version 150

// Sky leviathan glow pass (overgrown_vegetation). Sampler0 is the flow map made by the model kit (SkyGlaucusFlow):
//   alpha 1.0: lightning-figure line. red = share of the way from the rod, green = line thickness.
//   alpha 0.5: glow spot only. blue = kind (1.0 rod, 0.67 fan tip, 0.33 bud core).
//   blue may also be set on line pixels.
// Additive (src alpha, one). Lit where red < GlowFront (filled from the rod), a pulse running out from the rod (GlowPulse 0..1, fading after 1),
// and a strong flicker while discharging (GlowFlicker). Colors come from the model art (fan tip colors).

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float GlowFront;
uniform float GlowLevel;
uniform float GlowPulse;
uniform float RodGlow;
uniform float TipGlow;
uniform float BudGlow;
uniform float GlowFlicker;
uniform float GlowTime;
uniform vec3 GlowColor;
uniform vec3 SoftColor;

in float hazeAmount;
in vec2 texCoord0;

out vec4 fragColor;

float hash(float n) {
    return fract(sin(n) * 43758.5453);
}

void main() {
    vec4 m = texture(Sampler0, texCoord0);
    if (m.a < 0.1) {
        discard;
    }
    float lit = 0.0;
    float hot = 0.0;
    if (m.a > 0.75) {
        float p = m.r;
        float width = 0.55 + 0.45 * m.g;
        float fill = GlowLevel * (1.0 - smoothstep(GlowFront - 0.04, GlowFront + 0.01, p));
        float pulse = 0.0;
        if (GlowPulse >= 0.0) {
            float head = min(GlowPulse, 1.0);
            float fade = GlowPulse <= 1.0 ? 1.0 : exp(-(GlowPulse - 1.0) * 3.5);
            if (p <= head) {
                pulse = exp(-(head - p) / 0.2);
            } else if (p < head + 0.02) {
                pulse = 1.0 - (p - head) / 0.02;
            }
            pulse *= fade;
            if (GlowPulse <= 1.0 && head - p < 0.035 && p <= head + 0.02) {
                hot = 1.0;
            }
        }
        float fl = 0.0;
        if (GlowFlicker > 0.0) {
            float n = hash(floor(p * 60.0) + floor(GlowTime * 12.0) * 7.0);
            fl = GlowFlicker * (0.55 + 0.45 * n) * (0.75 + 0.25 * sin(p * 40.0 - GlowTime * 25.0));
            hot = max(hot, fl * 0.8);
        }
        lit = max(max(fill, pulse), fl) * width;
    }
    float kind = m.b;
    if (kind > 0.8) {
        lit = max(lit, RodGlow);
        hot = max(hot, RodGlow);
    } else if (kind > 0.5) {
        lit = max(lit, TipGlow);
    } else if (kind > 0.2) {
        lit = max(lit, BudGlow);
    }
    float a = clamp(lit, 0.0, 1.0) * (1.0 - 0.6 * hazeAmount);
    if (a < 0.01) {
        discard;
    }
    vec3 col = mix(SoftColor, GlowColor, clamp(hot, 0.0, 1.0));
    fragColor = vec4(col, a) * ColorModulator;
}
