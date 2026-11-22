#version 150

// Overgrown Vegetation: simple ambient occlusion, pass 2 (client/atmosphere/SimpleAo.java). Keep this file ASCII-only.
// AoTex = the occlusion at half size (0 = none). Four taps a texel apart smooth out the per-pixel turn of the disc; the result is drawn
// over the screen with a multiply blend (DST_COLOR, ZERO): the screen gets darker by the occlusion.

uniform sampler2D AoTex;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 t = 1.0 / vec2(textureSize(AoTex, 0));
    float ao = 0.25 * (texture(AoTex, texCoord + vec2(t.x, t.y)).r + texture(AoTex, texCoord + vec2(-t.x, t.y)).r
                     + texture(AoTex, texCoord + vec2(t.x, -t.y)).r + texture(AoTex, texCoord + vec2(-t.x, -t.y)).r);
    fragColor = vec4(vec3(1.0 - ao), 1.0);
}
