#version 150

// Overgrown Vegetation: the solar eclipse, glow along the horizon (client/worldevent/Eclipse.java holds the explanation). Keep this file ASCII-only.
// A quad over the whole screen, added on top of the darkened sky: during totality the sky is as dark as night and only the
// horizon keeps a sunset colour, all the way around (the light from outside the shadow).

uniform vec4 Glow;      // rgb = colour, a = strength (0 = nothing)
uniform vec4 Band;      // x = height of the band (as the y of the view direction), y = how much brighter towards the sun, zw unused
uniform vec4 SunFlat;   // xy = horizontal direction to the sun (x, z)

in vec3 ray;

out vec4 fragColor;

void main() {
    if (Glow.a <= 0.0) {
        discard;
    }
    vec3 dir = normalize(ray);
    // Strongest just above the horizon, fading upwards; nothing below (the lowest ring stays the fog colour).
    float up = max(dir.y, 0.0);
    float band = smoothstep(-0.02, 0.03, dir.y) * exp(-up / max(Band.x, 0.01));
    vec2 dh = normalize(dir.xz + vec2(0.0001, 0.0));
    float toward = 0.5 + 0.5 * dot(dh, normalize(SunFlat.xy + vec2(0.0001, 0.0)));
    float k = band * (1.0 + Band.y * toward) * Glow.a;
    // A little yellow right at the horizon, redder above.
    vec3 c = mix(Glow.rgb * vec3(1.0, 0.75, 0.6), Glow.rgb * vec3(1.0, 1.1, 1.2), exp(-up / max(Band.x * 0.35, 0.005)));
    fragColor = vec4(c * k, 1.0);
}
