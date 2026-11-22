#version 150

// Overgrown Vegetation: lays the near mist (drawn smaller by mist_march.fsh) over the full screen (client/atmosphere/MistMarch.java).
// Keep this file ASCII-only. Each pixel takes the four nearest small texels, weighted by how close their depth is to its own, so the mist
// of the sky behind a leaf does not bleed onto the leaf (no halo around foliage).
// Size = (small width, small height, near plane, far plane)
uniform sampler2D MarchTex;
uniform sampler2D DepthTex;
uniform vec4 Size;

in vec2 texCoord;

out vec4 fragColor;

float ovLinear(float d) {
    float z = d * 2.0 - 1.0;
    return 2.0 * Size.z * Size.w / (Size.w + Size.z - z * (Size.w - Size.z));
}

void main() {
    float here = ovLinear(texture(DepthTex, texCoord).r);
    vec2 hp = texCoord * Size.xy - 0.5;
    vec2 base = floor(hp);
    vec2 f = hp - base;
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            vec2 uv = (base + vec2(float(i), float(j)) + 0.5) / Size.xy;
            float d = ovLinear(texture(DepthTex, uv).r);
            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y) + 0.001;
            w /= 0.05 + 20.0 * abs(d - here) / max(here, 0.1);
            sum += texture(MarchTex, uv) * w;
            wsum += w;
        }
    }
    fragColor = wsum > 0.0 ? sum / wsum : vec4(0.0, 0.0, 0.0, 1.0);
}
