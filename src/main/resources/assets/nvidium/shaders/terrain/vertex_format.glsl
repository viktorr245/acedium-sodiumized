#define MODEL_SCALE        32.0 / 1048576.0
#define MODEL_ORIGIN       8.0

#define COLOR_SCALE        1.0 / 255.0

layout(binding = 0) uniform sampler2D tex_diffuse;

vec3 decodeVertexPosition(Vertex v) {
    uvec3 high = (uvec3(v.x) >> uvec3(0u, 10u, 20u)) & uvec3(0x3FFu);
    uvec3 low = (uvec3(v.y) >> uvec3(0u, 10u, 20u)) & uvec3(0x3FFu);
    uvec3 packed_position = (high << 10u) | low;

    return (vec3(packed_position) * MODEL_SCALE) - MODEL_ORIGIN;
}

vec4 decodeVertexColour(Vertex v) {
    uvec3 packed_color = (uvec3(v.z) >> uvec3(0, 8, 16)) & uvec3(0xFFu);
    return vec4(vec3(packed_color) * COLOR_SCALE, 1);
}

vec2 getTextureCoordShrink() {
    vec2 atlasSize = vec2(textureSize(tex_diffuse, 0));
    return vec2(1.0 / float(TEXTURE_MAX_SCALE)) - (1.0 / atlasSize / float(SUB_TEXEL_PRECISION));
}

vec2 decodeVertexUV(Vertex v) {
    uvec2 packed_uv = uvec2(v.w & 0xFFFFu, v.w >> 16);
    vec2 coord = vec2(packed_uv & 0x7FFFu) * (1.0 / float(TEXTURE_MAX_SCALE));
    bvec2 positiveBias = bvec2((packed_uv.x & 0x8000u) != 0u, (packed_uv.y & 0x8000u) != 0u);
    vec2 bias = mix(vec2(-1.0), vec2(1.0), positiveBias);
    return coord + bias * getTextureCoordShrink();
}

uint decodeVertexMaterial(Vertex v) {
    return (v.data >> 16) & 0xFFu;
}

uint decodeVertexAlphaCutoffIndex(Vertex v) {
    return (decodeVertexMaterial(v) >> 1) & 3u;
}

float decodeVertexMippingBias(Vertex v) {
    return (decodeVertexMaterial(v) & 1u) != 0u ? 0.0f : -4.0f;
}

float decodeVertexAlphaCutoff(Vertex v) {
    uint cutoff = decodeVertexAlphaCutoffIndex(v);
    if (cutoff == 1u || cutoff == 2u) {
        return 0.1f;
    } else if (cutoff == 3u) {
        return 1.0f;
    }
    return 0.0f;
}

vec2 decodeLightUV(Vertex v) {
    uvec2 light = uvec2(v.data, v.data >> 8) & uvec2(0xFFu);
    return vec2(light)/256.0;
}
