#version 150

// MorningMistView (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).
//
// MistColor = (lit mist colour rgb, alpha of one sheet)
// MistSun   = (sun direction xyz, warm glow toward the sun)
// MistNoise = (noise offset x, noise offset z (the camera in noise units, wrapped to 64), time in seconds, 1 = read the hash table)
// MistFade  = (near fade start, near fade end (blocks), alpha factor of the canopy cloud-sea sheets (vertexColor.g = 1) over the mist sheets, 1 = bake mode)
// MistHash  = 64x64 R32F table of mistHash at the integer lattice, baked once by this same program in bake mode (bound to unit 6
//             by the Java side). Reading it gives the same values as calling mistHash, without the 12 sin per fragment (2026-10-24).

uniform vec4 ColorModulator;
uniform vec4 FogColor;
uniform float FogStart;
uniform float FogEnd;
uniform int FogShape;
uniform vec4 MistColor;
uniform vec4 MistSun;
uniform vec4 MistNoise;
uniform vec4 MistFade;
uniform sampler2D MistHash;

in vec3 relPos;
in vec4 vertexColor;

out vec4 fragColor;

const float NOISE_SCALE = 40.0;

float mistHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float mistNoiseSin(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = mistHash(i);
    float b = mistHash(i + vec2(1.0, 0.0));
    float c = mistHash(i + vec2(0.0, 1.0));
    float d = mistHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Same as mistNoiseSin, reading the baked table (the hash only depends on the lattice point mod 64).
float mistNoiseTable(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    ivec2 k0 = ivec2(mod(i, 64.0));
    ivec2 k1 = ivec2(mod(i + 1.0, 64.0));
    float a = texelFetch(MistHash, k0, 0).r;
    float b = texelFetch(MistHash, ivec2(k1.x, k0.y), 0).r;
    float c = texelFetch(MistHash, ivec2(k0.x, k1.y), 0).r;
    float d = texelFetch(MistHash, k1, 0).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float mistNoise(vec2 p) {
    return MistNoise.w > 0.5 ? mistNoiseTable(p) : mistNoiseSin(p);
}

void main() {
    if (MistFade.w > 0.5) {
        // Bake mode: one fragment per lattice point of the 64x64 table.
        fragColor = vec4(mistHash(floor(gl_FragCoord.xy)), 0.0, 0.0, 1.0);
        return;
    }
    float w = vertexColor.a;
    if (w <= 0.003) {
        discard;
    }
    float slice = vertexColor.r;
    float dist = length(relPos);
    // Sheets close to the camera, and sheets seen edge-on, would show as flat planes: fade them out.
    float near = smoothstep(MistFade.x, MistFade.y, dist);
    float grazing = smoothstep(0.02, 0.18, abs(relPos.y) / max(dist, 0.001));
    if (near * grazing <= 0.0) {
        discard;
    }
    // Patchy cloud tops; deeper sheets are fuller (the hollow is filled).
    vec2 drift = vec2(MistNoise.z * 0.012, MistNoise.z * 0.005);
    vec2 p = relPos.xz / NOISE_SCALE + MistNoise.xy + drift + vec2(slice * 3.7, slice * 1.3);
    float from = mix(0.48, 0.22, slice);
    // The same discard as below, tested early with upper bounds (cloud <= 1; the two finer octaves add at most 0.45),
    // so faded or clear fragments skip the rest of the noise. The kept fragments get exactly the same value.
    float aMax = MistColor.a * w * near * grazing * mix(1.0, MistFade.z, vertexColor.g);
    if (aMax <= 0.002) {
        discard;
    }
    float n = mistNoise(p) * 0.55;
    if (aMax * smoothstep(from, from + 0.3, n + 0.45) <= 0.002) {
        discard;
    }
    n += mistNoise(p * 2.03 + 17.0) * 0.3 + mistNoise(p * 4.07 + 41.0) * 0.15;
    float cloud = smoothstep(from, from + 0.3, n);
    float a = aMax * cloud;
    if (a <= 0.002) {
        discard;
    }
    // Sunlit tops are brighter, the inside a little bluer; warmer toward the sun.
    vec3 dir = relPos / max(dist, 0.001);
    float lobe = pow(max(dot(dir, MistSun.xyz), 0.0), 5.0) * MistSun.w;
    vec3 col = MistColor.rgb * mix(1.0, 0.82, slice) * mix(vec3(1.0), vec3(0.96, 1.0, 1.04), slice);
    col = mix(col, min(col * vec3(1.3, 1.08, 0.82) + vec3(0.1, 0.06, 0.0), vec3(1.0)), lobe);
    float fd = FogShape == 0 ? dist : max(length(relPos.xz), abs(relPos.y));
    float fogValue = fd < FogEnd ? smoothstep(FogStart, FogEnd, fd) : 1.0;
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);
    fragColor = vec4(col, a * (1.0 - 0.6 * fogValue)) * ColorModulator;
}
