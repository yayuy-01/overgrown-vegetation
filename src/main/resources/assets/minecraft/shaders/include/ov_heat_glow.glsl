#version 150
// Overgrown Vegetation: the glowing faces of the heat forest (the overgrown Nether; client/atmosphere/heat/HeatFxGlow.java and
// client/HeatForestGlow.java hold the explanation, 11-10 heat forest shader stage 2). Keep this file ASCII-only.
//
// The glowing faces of the heat forest models carry a tintindex (1 = sky tips and motes, 2 = ground landmarks, 3 = ground motes,
// 4 = ground, 5 = sky body). While this shader is in use, HeatForestGlow returns a code colour for them instead of white: blue is the
// largest channel, green / blue = 0.8, red / blue = 0.9 - 0.1 * class. Ambient occlusion and the face shade multiply the three channels
// alike, so the ratios survive in the vertex colour. Here (vertex shader, before the light map) the code is read, the colour goes back to
// the grey shade (blue), and the class gets a gain: the time of day of the fake sky times a slow breathing whose phase moves with a
// smooth noise of the world position (each patch of plants breathes on its own). Only the code is baked into the chunks; the time of
// day and the breathing come from the uniforms, so nothing is rebuilt as time passes.
//
// OvHeatGlowA = (1 = read the codes; breathing time in seconds (wrapped at a whole number of periods); breathing period in seconds;
//                noise cell size in blocks)
// OvHeatGlowB = gains of classes 1 to 4 (time of day)
// OvHeatGlowC = (gain of class 5; how much class 5 (the sky body) takes the full block light by day, 0 to 1; -, -)
// OvHeatPulseA = breathing depth of classes 1 to 4 (0 = steady; d = dims to 1 - d at the low point)
// OvHeatPulseB = (breathing depth of class 5, -, -, -)
// OvHeatSkyTint = (colour multiplier of the sky classes 1 and 5 (violet at dusk and dawn), -)
// OvHeatCam = (camera position modulo 1024, -)
uniform vec4 OvHeatGlowA;
uniform vec4 OvHeatGlowB;
uniform vec4 OvHeatGlowC;
uniform vec4 OvHeatPulseA;
uniform vec4 OvHeatPulseB;
uniform vec4 OvHeatSkyTint;
uniform vec4 OvHeatCam;

// The class read by ovHeatGlowDecode (0 = not a heat forest glow face).
float ovHeatClass = 0.0;

vec4 ovHeatGlowDecode(vec4 color) {
    ovHeatClass = 0.0;
    if (OvHeatGlowA.x < 0.5) {
        return color;
    }
    float b = color.b;
    if (b < 0.03 || color.r > b + 0.004 || color.g > b + 0.004) {
        return color;
    }
    float g = color.g / b;
    float r = color.r / b;
    if (abs(g - 0.8) > 0.05) {
        return color;
    }
    float k = floor((0.9 - r) / 0.1 + 0.5);
    if (k < 1.0 || k > 5.0 || abs(r - (0.9 - 0.1 * k)) > 0.045) {
        return color;
    }
    ovHeatClass = k;
    return vec4(vec3(b), color.a);
}

float ovHeatHash(vec3 c) {
    c = mod(c, 256.0);
    return fract(sin(dot(c, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

// Smooth value noise, 0 to 1, periodic every 256 cells (the camera offset is wrapped at 1024 blocks, a whole number of cells).
float ovHeatNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(ovHeatHash(i), ovHeatHash(i + vec3(1.0, 0.0, 0.0)), f.x);
    float b = mix(ovHeatHash(i + vec3(0.0, 1.0, 0.0)), ovHeatHash(i + vec3(1.0, 1.0, 0.0)), f.x);
    float c = mix(ovHeatHash(i + vec3(0.0, 0.0, 1.0)), ovHeatHash(i + vec3(1.0, 0.0, 1.0)), f.x);
    float d = mix(ovHeatHash(i + vec3(0.0, 1.0, 1.0)), ovHeatHash(i + vec3(1.0, 1.0, 1.0)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

// The colour multiplier of the class read last (time of day, breathing, the violet of dusk on the sky). pos = camera-relative position.
vec3 ovHeatGlowGain(vec3 pos) {
    if (ovHeatClass < 0.5) {
        return vec3(1.0);
    }
    int k = int(ovHeatClass + 0.5);
    float gain = k == 5 ? OvHeatGlowC.x : OvHeatGlowB[k - 1];
    float depth = k == 5 ? OvHeatPulseB.x : OvHeatPulseA[k - 1];
    if (depth > 0.0 && OvHeatGlowA.z > 0.0) {
        vec3 w = mod(pos + OvHeatCam.xyz, 1024.0) / max(OvHeatGlowA.w, 0.5);
        float phase = ovHeatNoise(w) * 12.566;
        float s = 0.5 + 0.5 * sin(OvHeatGlowA.y * 6.2832 / OvHeatGlowA.z + phase);
        gain *= 1.0 - depth * s;
    }
    vec3 tint = k == 1 || k == 5 ? OvHeatSkyTint.rgb : vec3(1.0);
    return gain * tint;
}

// The light map coordinate (x = block light, 0 to 1 of the texture) of the class read last: the sky body takes the full block light by day.
float ovHeatBlockLight(float x, float full) {
    return ovHeatClass > 4.5 ? mix(x, full, OvHeatGlowC.y) : x;
}
