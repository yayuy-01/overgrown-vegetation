#version 150

// Overgrown Vegetation: the sun halo (client/worldevent/WorldEventSky.java holds the explanation). Keep this file ASCII-only.
// A quad over the whole screen, added on top of the day sky (after the sun, before the clouds).
// A ring at Halo.y radians around the sun (sharp inside edge, soft outside, red inside / blue-white outside), two sun dogs
// left and right of the sun at the sun's height, and a thin milky veil near the sun (the high crystals that bend the light).

uniform vec4 Halo;      // x = strength, y = ring radius (radians), z = ring width (radians), w = veil strength
uniform vec4 SunDir;    // xyz = direction to the sun, w = sun dog strength
uniform vec4 Tint;      // rgb = colour of the light, a = colour amount of the rainbow edge (0 = white ring)

in vec3 ray;

out vec4 fragColor;

void main() {
    if (Halo.x <= 0.0) {
        discard;
    }
    vec3 dir = normalize(ray);
    vec3 sun = normalize(SunDir.xyz);
    float ang = acos(clamp(dot(dir, sun), -1.0, 1.0));
    float r = Halo.y;
    float w = max(Halo.z, 0.002);
    // Ring: nothing inside the radius (the sky inside a real halo is darker), a sharp rise at the radius, a long soft tail outside.
    float x = (ang - r) / w;
    float ring = smoothstep(-0.35, 0.0, x) * exp(-max(x, 0.0) * 0.9);
    // Rainbow edge: red at the inside edge, then yellow, then blue-white outside.
    vec3 edge = mix(vec3(1.0, 0.45, 0.25), vec3(1.0, 0.95, 0.75), smoothstep(-0.2, 0.5, x));
    edge = mix(edge, vec3(0.80, 0.90, 1.0), smoothstep(0.5, 2.5, x));
    vec3 ringColor = mix(vec3(1.0), edge, Tint.a);
    // Sun dogs: on the ring (a little outside it when the sun is high), at the height of the sun, left and right.
    vec3 upv = vec3(0.0, 1.0, 0.0);
    vec3 side = normalize(cross(upv, sun) + vec3(0.00001, 0.0, 0.0));
    float cosAlt = max(0.2, sqrt(max(0.0, 1.0 - sun.y * sun.y)));
    float dogAz = r / cosAlt;
    vec3 level = normalize(vec3(sun.x, 0.0, sun.z) + vec3(0.00001, 0.0, 0.0));
    float dogs = 0.0;
    vec3 dogColor = vec3(0.0);
    for (int s = -1; s <= 1; s += 2) {
        float a = float(s) * dogAz;
        vec3 d = normalize(level * cos(a) + side * sin(a)) * cosAlt + upv * sun.y;
        d = normalize(d);
        float da = acos(clamp(dot(dir, d), -1.0, 1.0));
        // Stretched sideways (a tail pointing away from the sun), short up and down.
        float along = dot(dir - d, side) * float(s);
        float core = exp(-da * da / (w * w * 1.6));
        float tail = exp(-da * da / (w * w * 14.0)) * smoothstep(-0.01, 0.06, along) * 0.35;
        float g = core + tail;
        dogs += g;
        dogColor += mix(vec3(1.0, 0.55, 0.30), vec3(0.85, 0.92, 1.0), smoothstep(-0.02, 0.04, along)) * g;
    }
    dogColor = mix(vec3(dogs), dogColor, Tint.a);
    // Veil: a thin milky light around the sun, out to about twice the ring.
    float veil = Halo.w * exp(-ang / (r * 1.4)) * 0.22;
    // Below the horizon: nothing (the lowest ring of the sky stays the fog colour).
    float horizon = smoothstep(0.0, 0.12, dir.y);
    vec3 c = (ringColor * ring * 0.17 + dogColor * SunDir.w * 0.30 + vec3(veil)) * Tint.rgb * Halo.x * horizon;
    fragColor = vec4(c, 1.0);
}
