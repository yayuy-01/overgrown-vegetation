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
// OvWetB (11-06, "light of the vanilla areas" 5: the whole world gets wet in rain) = (sky light from which a face is wet, sky light where
//   it is fully, how much only faces looking up get wet (1 outside the overgrown forest), saturation added to the wet ground).
//   Outside the overgrown forest only faces right under the open sky (sky light 15 of 15; Sodium reads 14 as 14.5: not under a roof, an eave or leaves) get
//   wet; in the forest the older rule (0.25..0.75, every face) stays. WetSheen mixes the two by the camera's overgrown presence.
uniform vec4 OvWetB;

vec3 ovWetSurface(vec3 color, vec3 pos, vec3 n, vec2 light) {
    if (OvWet.x <= 0.0) {
        return color;
    }
    // Only where rain reaches (sky light), and less on torch-lit faces under roofs.
    float mask = OvWet.x * smoothstep(OvWetB.x, max(OvWetB.y, OvWetB.x + 0.01), light.y);
    float up = smoothstep(0.6, 0.9, n.y);
    mask *= mix(1.0, up, OvWetB.z);
    if (mask <= 0.0) {
        return color;
    }
    color *= 1.0 - OvWet.z * mask * up;
    // Wet ground is a little more saturated (the colour of soaked soil and stone).
    color = max(mix(vec3(dot(color, vec3(0.299, 0.587, 0.114))), color, 1.0 + OvWetB.w * mask * up), vec3(0.0));
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
    // Outside the overgrown forest (OvWetB.z) wet ground also mirrors the bright overcast sky, more the flatter it is seen (Fresnel), while
    // it rains (the sun part of OvWetDir.w is small then). The highlight above is all but 0 for a light straight up seen at a slant
    // (11-06: no visible change even at 5x the strength), so the sky needs this term of its own. Ground only, near fade like the highlight.
    float sky = pow(1.0 - ndv, OvWetView.y) * up * OvWetB.z * (1.0 - OvWetDir.w) * smoothstep(OvWetView.z, OvWetView.w, dist);
    return color + vec3(0.92, 0.96, 0.90) * (spec + sky) * OvWet.y * mask;
}
