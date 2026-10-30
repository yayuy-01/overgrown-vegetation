#version 150

// Overgrown Vegetation: a full-screen quad for the near mist (client/atmosphere/MistMarch.java). Keep this file ASCII-only.
in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
