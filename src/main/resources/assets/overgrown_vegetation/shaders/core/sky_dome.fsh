#version 150

// Overgrown Vegetation: the colours of the sky (11-13, shader polish A; client/sky/SkyColors.java holds the explanation and sets the
// uniforms; tools/sky-color/SkyMock.java draws the same formula outside the game). Keep this file ASCII-only.
//
// Drawn over vanilla's sky disc, under the sun, the moon and the stars. The colours of the frame come from the CPU (by the height of
// the sun: day and night are vanilla's own colours, golden hour, sunset and twilight are the design colours):
//   Zenith    = colour straight up, a = how much of this sky shows over vanilla's (the alpha of the whole quad outside the haze)
//   HorAway   = colour low in the sky opposite the sun
//   HorToward = colour low in the sky on the sun's side
//   Glow      = (colour of the glow around the sun, strength)
//   SunDir    = direction to the sun (also below the horizon)
//   FogColor  = the fog colour of the frame: the lowest ring of the sky and everything below the horizon stay this colour, so the
//               terrain, mobs and particles (which all fade to this one colour) meet the sky without a seam.
// Inside the overgrown forest the dome is filled with the haze (the fog colour): Haze = (share of the dome filled 0..1, strength of
// the sun's glow through the haze, how much the haze thins straight up, dusk weight), HazeWarm = (colour of that glow, how much the
// sun is up).

uniform vec4 FogColor;
uniform vec4 SunDir;
uniform vec4 Zenith;
uniform vec4 HorAway;
uniform vec4 HorToward;
uniform vec4 Glow;
uniform vec4 Haze;
uniform vec4 HazeWarm;

in vec3 ray;

out vec4 fragColor;

void main() {
    vec3 d = normalize(ray);
    vec3 s = SunDir.xyz;
    float e = max(d.y, 0.0);
    // From the zenith colour down to the horizon colours. On the sun side the horizon colour holds as a band (full up to about 5 degrees,
    // gone by about 24) so the sunset keeps its edge against the darker sky above; elsewhere it eases in toward the horizon.
    float t0 = 1.0 - e;
    float dl = length(d.xz);
    float sl = length(s.xz);
    float az = (dl < 0.0001 || sl < 0.0001) ? 0.0 : dot(d.xz, s.xz) / (dl * sl);
    float a = smoothstep(0.0, 1.0, 0.5 + 0.5 * az);
    vec3 hor = mix(HorAway.rgb, HorToward.rgb, a * a);
    float t = mix(pow(t0, 4.0), 1.0 - smoothstep(0.08, 0.40, e), a * a);
    vec3 c = mix(Zenith.rgb, hor, t);
    // The glow around the sun: a wide soft one and a tight one, stronger low in the sky.
    float cs = max(dot(d, s), 0.0);
    float halo = (pow(cs, 8.0) * 0.5 + pow(cs, 96.0)) * Glow.a * (0.35 + 0.65 * t);
    c = min(c + Glow.rgb * halo, vec3(1.0));
    float alpha = Zenith.a;
    if (Haze.x > 0.0) {
        // The haze-filled sky of the forest: the sun shows as a soft glow through it, the side away from the sun sinks a little at
        // dusk, and straight up the haze thins toward the sky colour.
        vec3 h = FogColor.rgb;
        float through = (pow(cs, 4.0) * 0.22 + pow(cs, 32.0) * 0.35) * HazeWarm.a * Haze.y;
        h = mix(h, HazeWarm.rgb, min(through, 1.0));
        h *= 1.0 - smoothstep(0.0, 1.0, 0.5 - 0.5 * az) * Haze.w * 0.10;
        h = mix(h, Zenith.rgb, Haze.z * pow(e, 1.5));
        c = mix(c, h, Haze.x);
        alpha = max(alpha, Haze.x);
    }
    // The ring at the horizon and everything below it: the fog colour of the frame.
    float ring = 1.0 - smoothstep(0.0, 0.07, d.y);
    c = mix(c, FogColor.rgb, ring);
    fragColor = vec4(c, alpha);
}
