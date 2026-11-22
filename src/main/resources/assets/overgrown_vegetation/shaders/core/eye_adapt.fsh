#version 150

// Overgrown Vegetation: eye adaptation at cave mouths (client/atmosphere/EyeAdaptation.java, 2026-11-07). Keep this file ASCII-only.
// Drawn over the whole screen after the level and the hand, before the GUI. SceneTex is a copy of the main screen with mipmaps
// (levels 3..5 give a cheap wide blur for the bloom).
// Glare (leaving a dark cave into daylight): brighter, bright parts bleed, the picture is washed toward white (less contrast) and
// loses saturation. Dark (entering): dim parts sink further, bright lights stay.
// EyeA = (glare 0..1, exposure gain, bloom gain, wash), EyeB = (desaturation, dark shadow amount, -, -)
uniform sampler2D SceneTex;
uniform vec4 EyeA;
uniform vec4 EyeB;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

void main() {
    vec3 c = texture(SceneTex, texCoord).rgb;
    float g = EyeA.x;
    if (g > 0.0) {
        c *= 1.0 + EyeA.y * g;
        vec3 wide = 0.5 * textureLod(SceneTex, texCoord, 3.5).rgb + 0.5 * textureLod(SceneTex, texCoord, 5.0).rgb;
        vec3 bloom = max(wide - vec3(0.45), vec3(0.0)) * 1.8;
        c += bloom * EyeA.z * g;
        c = mix(c, vec3(1.0), EyeA.w * g * g);
        float l = dot(c, LUMA);
        c = mix(c, vec3(l), EyeB.x * g);
    }
    if (EyeB.y > 0.0) {
        float l = dot(c, LUMA);
        c *= 1.0 - EyeB.y * (1.0 - smoothstep(0.04, 0.4, l));
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
