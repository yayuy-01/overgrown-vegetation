#version 150

// Overgrown Vegetation: the gentle swell of the water surface (11-12, water item 3; user pick "small waves": the surface goes up and
// down by about 0.1 block, the block shapes stay). client/atmosphere/WaterGlint.java sets the uniforms. Keep this file ASCII-only.
//
// Only the corners of the water faces move, each by the same function of its world position, so neighbouring water blocks stay
// joined and every block face stays a flat (tilting) quad: a blocky swell that fits the pixel-art water. A vertex moves only when it is
// not on a block boundary: the top of water sits at 8/9 (or lower for flowing water), while glass, ice and the bottom corners of the
// water's side faces lie on whole blocks.
//
// OvWave  = (camera x and z wrapped to 1024 blocks, camera y modulo 1, amplitude in blocks; 0 = off)
// OvWaveB = (time in seconds wrapped to 600, -, -, -)
uniform vec4 OvWave;
uniform vec4 OvWaveB;

// Height offset (blocks) of a water vertex at camera-relative position pos.
float ovWaveHeight(vec3 pos) {
    if (OvWave.w <= 0.0) {
        return 0.0;
    }
    float fy = fract(pos.y + OvWave.z);
    if (fy < 0.02 || fy > 0.98) {
        return 0.0;
    }
    vec2 w = OvWave.xy + pos.xz;
    float t = OvWaveB.x;
    const float TAU = 6.2831853;
    // Whole numbers of cycles in 1024 blocks and in 600 seconds (no seam where they wrap). Wavelengths about 10, 8 and 5 blocks,
    // periods 12, 8.6 and 5.5 seconds.
    float h = 0.5 * sin(dot(w, vec2(90.0, 40.0)) * (TAU / 1024.0) - t * (50.0 * TAU / 600.0))
            + 0.3 * sin(dot(w, vec2(-60.0, 110.0)) * (TAU / 1024.0) - t * (70.0 * TAU / 600.0))
            + 0.2 * sin(dot(w, vec2(150.0, -130.0)) * (TAU / 1024.0) - t * (110.0 * TAU / 600.0));
    return h * OvWave.w;
}
