#version 150

// VoxelClouds far pass after the terrain (client/sky/VoxelClouds.java; 2026-10-24, back from parked 2026-11-23). Keep this file ASCII-only.
// A full-screen quad: Position is already in clip space.

in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
