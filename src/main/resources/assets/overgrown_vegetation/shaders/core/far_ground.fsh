#version 150

// Overgrown Vegetation: hazy ground under the cloud sea (client/FarGround.java). Keep this file ASCII-only.
//
// Ground = (ground y - camera y, radius, haze length, strength).
// Light = (sun, sky, moon, warm): how much sunlight (0..1, low sun is weak), daylight from the sky (0..1), moonlight (0..1, full moon high up)
// and how warm the sunlight is (0 white .. 1 sunset orange).
// Haze = (r, g, b, near haze): the colour of the air far away (the fog colour of the horizon).
// Sky = (r, g, b, -): the sky colour. The air in front of the ground takes this rather than the fog colour: facing the sun at sunset the
// fog colour is orange over the whole view, and the gaps turned into flat orange-brown (near) and bright orange puddles (far).
// Cam = (camera x mod period, camera z mod period, period, -): the patches of forest and clearings stay put on the world.

in vec3 ovPos;

uniform vec4 Ground;
uniform vec4 Light;
uniform vec4 Haze;
uniform vec4 Sky;
uniform vec4 Cam;

out vec4 fragColor;

float ovHash(vec2 c, float period) {
    c = mod(c, period);
    return fract(sin(dot(c, vec2(127.1, 311.7))) * 43758.5453);
}

// Value noise that repeats every `period` cells (the camera offset wraps at Cam.z blocks).
float ovNoise(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = ovHash(i, period);
    float b = ovHash(i + vec2(1.0, 0.0), period);
    float c = ovHash(i + vec2(0.0, 1.0), period);
    float d = ovHash(i + vec2(1.0, 1.0), period);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float r = length(ovPos.xz);
    vec2 w = ovPos.xz + Cam.xy;
    float big = ovNoise(w / 640.0, Cam.z / 640.0);
    float mid = ovNoise(w / 160.0, Cam.z / 160.0);
    float fine = ovNoise(w / 48.0, Cam.z / 48.0);
    // Dense dark forest, lighter woodland and a few open clearings.
    vec3 forest = vec3(0.07, 0.12, 0.055);
    vec3 wood = vec3(0.12, 0.18, 0.075);
    vec3 meadow = vec3(0.22, 0.27, 0.12);
    vec3 albedo = mix(forest, wood, smoothstep(0.3, 0.75, mid * 0.7 + fine * 0.3));
    albedo = mix(albedo, meadow, smoothstep(0.62, 0.9, big * 0.7 + mid * 0.3) * 0.7);
    albedo *= 0.85 + 0.3 * fine;
    // Moonlight takes the colour out of the forest.
    albedo = mix(albedo, vec3(dot(albedo, vec3(0.3, 0.55, 0.15))), 0.5 * Light.z * (1.0 - Light.y));
    vec3 sunColour = vec3(1.0, mix(0.97, 0.62, Light.w), mix(0.9, 0.35, Light.w));
    vec3 light = Light.x * 0.8 * sunColour + Light.y * 0.35 * vec3(0.8, 0.9, 1.1) + Light.z * 0.7 * vec3(0.62, 0.76, 1.0)
        + vec3(0.06, 0.075, 0.12);
    vec3 col = albedo * light;
    // Aerial perspective: the farther, the more the air in between shows (the colour of the horizon); all air at the edge.
    float d = length(ovPos);
    float haze = Haze.a + (1.0 - Haze.a) * (1.0 - exp(-d / Ground.z));
    haze = max(haze, smoothstep(Ground.y * 0.6, Ground.y * 0.95, r));
    // The same blue haze as the far voxel clouds (towards the sky colour), so the ground in a far gap matches the clouds around it; only
    // at the far edge the fog colour, where it meets the painted horizon and the background.
    vec3 air = mix(min(Sky.rgb * vec3(0.9, 0.97, 1.1), vec3(1.0)), Haze.rgb, smoothstep(Ground.y * 0.75, Ground.y * 0.95, r));
    // Moonlit air: at night the haze is a deep blue rather than the black of the night fog (like the night clouds).
    air = max(air, vec3(0.06, 0.08, 0.15) * Light.z);
    col = mix(col, air, haze);
    float edge = 1.0 - smoothstep(Ground.y * 0.9, Ground.y, r);
    fragColor = vec4(col, Ground.w * edge);
}
