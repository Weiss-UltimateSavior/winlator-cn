#ifndef GLADIO_COMPRESSED_TEXTURE_H
#define GLADIO_COMPRESSED_TEXTURE_H

#include "gladio.h"
#include "thread_pool.h"

// DXT 以 4x4 块为单位编码，块数 = ceil(w/4)*ceil(h/4)，故宽高必须"向上"取整到 4 的
// 倍数（下限 4 = 单块最小尺寸）。原先用 TEXLEVEL(x, 2) << 2（= MAX(1, x>>2)<<2）是向
// 下取整：w=10 会算出 8 而非 12，使 GL_TEXTURE_COMPRESSED_IMAGE_SIZE 与
// glGetCompressedTexImage 报告的缓冲偏小，而 bc_decoder 内部按 BC_ROUNDUP 向上取整读
// 块 → 非 4 倍数尺寸的 NPOT 压缩纹理存在越界读。
#define TEXROUNDUP4(x) MAX(4, ((x) + 3) & ~3)

static inline int getCompressedImageSize(uint32_t format, int width, int height, int level) {
    width = TEXROUNDUP4(width >> level);
    height = TEXROUNDUP4(height >> level);
    return format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT || format == GL_COMPRESSED_RGBA_S3TC_DXT1_EXT ? (width * height) / 2 : width * height;
}

/* 每个 4x4 块的字节数：DXT1 = 8（0.5 B/px），DXT3/DXT5 = 16（1 B/px）。
   透传路径按块维护 CPU 副本的局部更新时需要它；非 S3TC 格式返回 0。 */
static inline int getS3TCBlockSize(uint32_t format) {
    switch (format) {
        case GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
            return 8;
        case GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
            return 16;
        default:
            return 0;
    }
}

extern void compressTexImage2D(uint32_t format, int width, int height, void* imageData, void* compressedData);
extern void* decompressTexImage2D(uint32_t format, int width, int height, void* imageData, ThreadPool* threadPool);

#endif
