#version 150

// Overgrown Vegetation: the dense fog seen from above it, a sea of fog (11-16; client/atmosphere/DenseFog.java holds the explanation).
// Keep this file ASCII-only.
//
// Pass 1, drawn into a half-size target from the depth of the screen (phase_tint_blit.fsh puts it over the screen with the normal alpha
// blend). The camera is above the fog layer. For every pixel: the direction of its ray and its distance, the part of the ray that lies
// below the top of the layer, and the fog over that part. The top of the layer rolls a little (two slow sine waves by place), and the
// fog thins towards the top so that hills rise out of it softly. The colour brightens towards the sun (forward scatter per pixel).
// InvProjView = inverse of projection * view rotation (camera-relative world position from the screen position and depth)
// SeaA = (camera y, top of the layer y, fog per block, amount 0..1)
// SeaB = (camera x, camera z, soft depth below the top in blocks, wave height in blocks)
// SeaColor = (r, g, b, forward scatter strength), SeaSun = (sun direction xyz, scatter power), SeaGlow = (glow colour rgb, -)

uniform sampler2D DepthTex;
uniform mat4 InvProjView;
uniform vec4 SeaA;
uniform vec4 SeaB;
uniform vec4 SeaColor;
uniform vec4 SeaSun;
uniform vec4 SeaGlow;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthTex, texCoord).r;
    vec4 p = InvProjView * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 rel = p.xyz / p.w;
    float dist = length(rel);
    vec3 dir = rel / max(dist, 0.0001);
    if (depth >= 0.99999) {
        dist = 4096.0;
    }
    if (dir.y >= -0.0005) {
        fragColor = vec4(0.0);
        return;
    }
    // Where the ray meets the rolling top of the layer.
    float flatEnter = (SeaA.x - SeaA.y) / -dir.y;
    vec2 at = SeaB.xy + dir.xz * flatEnter;
    float top = SeaA.y + SeaB.w * (sin(at.x * 0.021 + at.y * 0.013) * 0.6 + sin(at.x * 0.047 - at.y * 0.039 + 1.7) * 0.4);
    float enter = (SeaA.x - top) / -dir.y;
    if (enter >= dist) {
        fragColor = vec4(0.0);
        return;
    }
    float len = dist - max(enter, 0.0);
    // How deep below the top the ray ends: the fog is thin just under the top.
    float endDepth = top - (SeaA.x + dir.y * dist);
    float body = clamp(endDepth / max(2.0 * SeaB.z, 0.001), 0.0, 1.0);
    float fog = 1.0 - exp(-SeaA.z * len * mix(0.35, 1.0, body) * smoothstep(0.0, SeaB.z, endDepth));
    float facing = max(dot(dir, SeaSun.xyz), 0.0);
    // Billows: the sea is not one flat colour. Slow wide patches of lighter and darker fog by place, brighter on the side of the sun.
    float billow = sin(at.x * 0.0061 + at.y * 0.0043) * 0.5 + sin(at.x * 0.0173 - at.y * 0.0127 + 2.1) * 0.3
            + sin(at.x * 0.041 + at.y * 0.053 + 0.7) * 0.2;
    vec3 color = SeaColor.rgb * (1.0 + 0.07 * billow);
    color = min(color + SeaGlow.rgb * (SeaColor.a * pow(facing, SeaSun.w)), vec3(1.0));
    // The far edge melts into the sky: no razor line at the horizon.
    float edge = smoothstep(0.0, 0.035, -dir.y);
    fragColor = vec4(color, fog * SeaA.w * edge);
}
