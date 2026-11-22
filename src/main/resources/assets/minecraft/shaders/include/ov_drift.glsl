#version 150

// Overgrown Vegetation: how a spore is tossed about by the air (user 10-30 night: "a cube moving only sideways looks wrong;
// make it bob up and down and tumble, as if swayed by the wind"; 10-31 the bobbing was taken out again). Used by the ambient spores (ambient_spores.vsh) and the
// glowing spores in the light shafts (gpu_motes.vsh). Keep this file ASCII-only.
//
// Drift  = (-, -, wobble in calm air, wobble per wind strength), in blocks
// Drift2 = (-, -, tumble in calm air in radians per second, tumble per block pushed by the wind)
//
// All frequencies are fixed (only the amplitudes follow the wind), so a gust coming or going never makes a spore jump.
uniform vec4 Drift;
uniform vec4 Drift2;

// Offset of one spore. sec = time in seconds, wc = wind strength there (0 to 1.2), wdir = horizontal direction the air
// flows (unit), r = four random numbers of this spore (0 to 1). Horizontal only: the bobbing up and down was taken out
// (user 10-31 "remove the up-and-down sway").
vec3 ovDriftOffset(float sec, float wc, vec2 wdir, vec4 r) {
    float ph = r.z * 6.2831853;
    // Drifting slowly across the flow: lazy in calm air, a little wider in a gust (user 10-31 "too much shaking; let them drift slowly",
    // so the frequencies are slow even in a gust).
    vec2 side = vec2(-wdir.y, wdir.x);
    float calmL = 0.6 * sin(6.2831853 * 0.11 * sec + ph * 1.7) + 0.4 * sin(6.2831853 * 0.05 * sec + ph * 2.3);
    float gustL = 0.6 * sin(6.2831853 * 0.35 * sec + ph * 3.1) + 0.4 * sin(6.2831853 * 0.55 * sec + ph * 4.7);
    float lateral = Drift.z * calmL + Drift.w * wc * gustL;
    // In a gust the flow also surges and slackens along the wind.
    float surge = 0.5 * Drift.w * wc * sin(6.2831853 * 0.25 * sec + ph * 5.3);
    vec2 h = side * lateral + wdir * surge;
    return vec3(h.x, 0.0, h.y);
}

// Rotation of a tumbling spore about its own random axis. pushed = blocks the wind has carried it since birth (a spore
// rolls faster the faster the air carries it); ageSec = its age in seconds.
mat3 ovTumble(vec4 r, float ageSec, float pushed) {
    vec3 axis = normalize(vec3(r.x, r.y, r.z) * 2.0 - 1.0 + vec3(0.0, 0.001, 0.0));
    float dirSign = r.w < 0.5 ? -1.0 : 1.0;
    float angle = r.w * 6.2831853 + dirSign * (Drift2.z * (0.5 + r.x) * ageSec + Drift2.w * pushed);
    float c = cos(angle);
    float s = sin(angle);
    float t = 1.0 - c;
    vec3 a = axis;
    return mat3(t * a.x * a.x + c, t * a.x * a.y + s * a.z, t * a.x * a.z - s * a.y,
                t * a.x * a.y - s * a.z, t * a.y * a.y + c, t * a.y * a.z + s * a.x,
                t * a.x * a.z + s * a.y, t * a.y * a.z - s * a.x, t * a.z * a.z + c);
}

// Direction the air flows at a column of the wind history (recent push), or (1, 0) in still air.
vec2 ovFlowDir(vec4 now, vec4 before) {
    vec2 d = now.xy - before.xy;
    float l = length(d);
    return l > 1.0e-4 ? d / l : vec2(1.0, 0.0);
}
