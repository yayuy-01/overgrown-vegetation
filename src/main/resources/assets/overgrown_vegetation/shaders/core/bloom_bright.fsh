#version 150

// Overgrown Vegetation: the bloom of glowing things, pass 1 (11-13, shader polish B; client/atmosphere/GlowBloom.java holds the
// explanation, tools/bloom/BloomMock.java runs the same rule over screenshots). Keep this file ASCII-only.
//
// Drawn into a quarter-size target: every output pixel looks at its 4 x 4 pixels of the screen and keeps the ones that glow.
// The screen has no record of what emits light, so a pixel counts as glowing when
//   - its brightest channel is above the threshold (BloomA.x, fully at + BloomA.y), and
//   - it is much brighter than its surroundings (the screen blurred over about 1/20 of its width): from BloomA.z, fully at BloomA.w.
//     A torch flame, a lantern, a glowing spore are small and bright in the dark; a sunlit wall or a lit floor is not.
// Sky pixels (nothing drawn into the depth buffer) need a far larger difference (BloomB.x to BloomB.y): a lone point like a glowing
// spore or the moon passes, the band of a sunset does not.
// BloomB.z = how dark it is around the camera (0 by day in the open: nothing blooms, 1 at night, in caves and under a dense canopy).
// SceneTex = the screen, SmallTex = the screen at quarter size with mipmaps, DepthTex = the depth of the screen.

uniform sampler2D SceneTex;
uniform sampler2D SmallTex;
uniform sampler2D DepthTex;
uniform vec4 BloomA;
uniform vec4 BloomB;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 around = textureLod(SmallTex, texCoord, 4.0).rgb;
    float local = max(around.r, max(around.g, around.b));
    ivec2 base = ivec2(gl_FragCoord.xy) * 4;
    ivec2 size = textureSize(SceneTex, 0);
    vec3 best = vec3(0.0);
    float bestK = 0.0;
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            ivec2 p = min(base + ivec2(i, j), size - 1);
            vec3 c = texelFetch(SceneTex, p, 0).rgb;
            float m = max(c.r, max(c.g, c.b));
            float k = smoothstep(BloomA.x, BloomA.x + BloomA.y, m);
            if (k > 0.0) {
                float d = m - local;
                float sky = step(0.99999, texelFetch(DepthTex, p, 0).r);
                k *= mix(smoothstep(BloomA.z, BloomA.w, d), smoothstep(BloomB.x, BloomB.y, d), sky);
                sum += c * k;
                if (k > bestK) {
                    bestK = k;
                    best = c * k;
                }
            }
        }
    }
    // Half the average of the block, half its strongest pixel: a light one pixel wide keeps a steady halo instead of fading to 1/16.
    fragColor = vec4((sum / 16.0 + best) * 0.5 * BloomB.z, 1.0);
}
