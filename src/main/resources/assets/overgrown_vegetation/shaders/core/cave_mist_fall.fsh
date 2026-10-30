#version 150

// CaveMist (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).
// Mist pouring over the edge of a cliff or into a hole: streaks that slide down and fade out partway down (it does not
// reach the bottom, so the mist below and the mist above stay separate layers).
//
// MistColor = (colour of the mist in the dark rgb, alpha of the curtain)
// MistGlow  = (colour added where block light reaches the mist rgb, unused)
// MistSky   = (colour added where sky light reaches the mist rgb (already times daylight), unused)
// FallNoise = (noise offset along the edge (camera x + z, wrapped), noise offset up (camera y, wrapped), time in s, unused)
// FallFade  = (near fade start, near fade end (blocks), unused, unused)

uniform vec4 ColorModulator;
uniform vec4 FogColor;
uniform float FogStart;
uniform float FogEnd;
uniform int FogShape;
uniform vec4 MistColor;
uniform vec4 MistGlow;
uniform vec4 MistSky;
uniform vec4 FallNoise;
uniform vec4 FallFade;

in vec3 relPos;
in vec4 vertexColor;

out vec4 fragColor;

// Blocks per noise cell across the fall and down it, and how fast the streaks slide down (blocks per second).
const float ALONG_SCALE = 1.3;
const float DOWN_SCALE = 2.5;
const float SPEED = 0.8;

float fallHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float fallNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = fallHash(i);
    float b = fallHash(i + vec2(1.0, 0.0));
    float c = fallHash(i + vec2(0.0, 1.0));
    float d = fallHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fallFbm(vec2 p) {
    return fallNoise(p) * 0.6 + fallNoise(p * 2.07 + 13.0) * 0.4;
}

void main() {
    float w = vertexColor.a;
    if (w <= 0.003) {
        discard;
    }
    float fall = vertexColor.r;
    float dist = length(relPos);
    float near = smoothstep(FallFade.x, FallFade.y, dist);
    // A curtain seen edge-on (looking along the cliff) shows as a line: fade it out.
    vec3 dir = relPos / max(dist, 0.001);
    vec3 tilt = cross(dFdx(relPos), dFdy(relPos));
    float facing = length(tilt) > 1e-8 ? abs(dot(normalize(tilt), dir)) : 1.0;
    float side = smoothstep(0.05, 0.3, facing);
    if (near * side <= 0.0) {
        discard;
    }
    // Streaks that slide down: the noise is stretched downward and scrolls with time.
    float along = relPos.x + relPos.z + FallNoise.x;
    float up = relPos.y + FallNoise.y;
    vec2 p = vec2(along / ALONG_SCALE, (up + FallNoise.z * SPEED) / DOWN_SCALE);
    float streak = smoothstep(0.3, 0.8, fallFbm(p));
    // Soft at the lip, then thinning out as it falls (gone at the bottom of the curtain).
    float shape = smoothstep(0.0, 0.15, fall) * pow(1.0 - fall, 1.5);
    float lb = vertexColor.g * vertexColor.g;
    float ls = vertexColor.b * vertexColor.b;
    float lit = max(lb, ls);
    float a = MistColor.a * w * streak * shape * near * side * (1.0 + 0.8 * lit);
    a = min(a, 0.85);
    if (a <= 0.002) {
        discard;
    }
    vec3 col = min(MistColor.rgb + MistGlow.rgb * lb + MistSky.rgb * ls, vec3(1.0));
    float fd = FogShape == 0 ? dist : max(length(relPos.xz), abs(relPos.y));
    float fogValue = fd < FogEnd ? smoothstep(FogStart, FogEnd, fd) : 1.0;
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);
    fragColor = vec4(col, a * (1.0 - 0.6 * fogValue)) * ColorModulator;
}
