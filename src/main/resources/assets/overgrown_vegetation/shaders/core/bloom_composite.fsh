#version 150

// Overgrown Vegetation: the bloom of glowing things, pass 2 (client/atmosphere/GlowBloom.java). Keep this file ASCII-only.
// GlowTex = the glowing pixels at quarter size with mipmaps. Three widths of blur come from its mip levels (4 taps each, half a texel
// of that level apart, so the squares of the mip grid do not show). Drawn over the screen with a screen blend (ONE, ONE_MINUS_SRC_COLOR):
// the light source itself does not burn out to white as fast as with a plain sum.
// BloomC.x = strength.

uniform sampler2D GlowTex;
uniform vec4 BloomC;

in vec2 texCoord;

out vec4 fragColor;

vec3 wide(float lod) {
    vec2 t = exp2(lod) / vec2(textureSize(GlowTex, 0)) * 0.5;
    return 0.25 * (textureLod(GlowTex, texCoord + vec2(t.x, t.y), lod).rgb + textureLod(GlowTex, texCoord + vec2(-t.x, t.y), lod).rgb
                 + textureLod(GlowTex, texCoord + vec2(t.x, -t.y), lod).rgb + textureLod(GlowTex, texCoord + vec2(-t.x, -t.y), lod).rgb);
}

void main() {
    vec3 b = 0.3 * wide(0.5) + 0.9 * wide(2.5) + 0.9 * wide(4.0);
    fragColor = vec4(min(b * BloomC.x, vec3(1.0)), 1.0);
}
