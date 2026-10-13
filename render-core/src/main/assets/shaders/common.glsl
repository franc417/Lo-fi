// Shared helpers, prepended to the stage shaders.
const vec3 LUMA = vec3(0.299, 0.587, 0.114);
float luma(vec3 c) { return dot(c, LUMA); }

vec3 rgb2yiq(vec3 c) {
    return vec3(dot(c, vec3(0.299, 0.587, 0.114)),
                dot(c, vec3(0.596, -0.274, -0.322)),
                dot(c, vec3(0.211, -0.523, 0.312)));
}
vec3 yiq2rgb(vec3 c) {
    return vec3(c.x + 0.956 * c.y + 0.621 * c.z,
                c.x - 0.272 * c.y - 0.647 * c.z,
                c.x - 1.106 * c.y + 1.703 * c.z);
}

// Integer hash (PCG): identical results on every GPU, unlike fract(sin(x)).
uint pcg(uint v) {
    uint s = v * 747796405u + 2891336657u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
float hash12(vec2 p, int seed) {
    uvec2 q = uvec2(ivec2(p) + ivec2(65536));
    return float(pcg(q.x + pcg(q.y + uint(seed)))) / 4294967295.0;
}
