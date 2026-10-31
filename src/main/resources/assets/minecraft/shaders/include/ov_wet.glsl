#version 150

// Overgrown Vegetation: wet surfaces in rain and after rain (38, 10-31; client/atmosphere/WetSheen.java holds the
// explanation). Keep this file ASCII-only. Include after ov_light.glsl (reads ovCanopyShade).
//
// A thin highlight on every face open to the sky (covers, leaves, trunks, ground): Blinn half vector between the view and a
// light direction (the sun after rain, the bright sky straight up while it rains). The ground (faces looking up) is also a
// little darker, like soaked soil.
// OvWet    = (wetness 0..1, highlight strength (already scaled by daylight), ground darkening, highlight sharpness)
// OvWetDir = (light direction xyz, how much of it is the sun 0..1 (the canopy shade hides the sun highlight))
// OvWetView = (highlight left when looking straight at a face, Fresnel power, near fade from, near fade to (blocks))
//   10-31: the highlight peaked looking straight down (white ground underfoot after rain). A wet surface reflects mostly at
//   grazing angles (Fresnel), so it is weak face-on and close to the feet.
uniform vec4 OvWet;
uniform vec4 OvWetDir;
uniform vec4 OvWetView;

vec3 ovWetSurface(vec3 color, vec3 pos, vec3 n, vec2 light) {
    if (OvWet.x <= 0.0) {
        return color;
    }
    // Only where rain reaches (sky light), and less on torch-lit faces under roofs.
    float mask = OvWet.x * smoothstep(0.25, 0.75, light.y);
    if (mask <= 0.0) {
        return color;
    }
    float up = smoothstep(0.6, 0.9, n.y);
    color *= 1.0 - OvWet.z * mask * up;
    float dist = length(pos);
    vec3 v = -pos / max(dist, 1.0e-4);
    // Plants draw one quad for both sides: turn the normal toward the viewer.
    float ndv = dot(n, v);
    if (ndv < 0.0) {
        n = -n;
        ndv = -ndv;
    }
    vec3 h = normalize(OvWetDir.xyz + v);
    float spec = pow(max(dot(n, h), 0.0), OvWet.w);
    spec *= mix(OvWetView.x, 1.0, pow(1.0 - ndv, OvWetView.y));
    spec *= mix(OvWetView.x, 1.0, smoothstep(OvWetView.z, OvWetView.w, dist));
    // The sun highlight stays out of the canopy shade; the sky sheen does not need the sun.
    spec *= mix(1.0, 0.25, ovCanopyShade * OvWetDir.w);
    // Dark gaps of the texture (cracks, holes) stay dark.
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    spec *= smoothstep(0.02, 0.25, lum);
    return color + vec3(0.92, 0.96, 0.90) * spec * OvWet.y * mask;
}
