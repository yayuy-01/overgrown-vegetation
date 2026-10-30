#version 150

// Overgrown Vegetation: the ambient spores (client/atmosphere/AmbientSpores.java). Plain coloured cube faces. Keep this file ASCII-only.

uniform vec4 ColorModulator;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    fragColor = vertexColor * ColorModulator;
}
