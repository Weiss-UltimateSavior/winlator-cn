#ifndef GLADIO_GL_TEXTURE_H
#define GLADIO_GL_TEXTURE_H

#include "gladio.h"

typedef struct GLTexture {
    GLuint id;
    GLenum type;
    GLint originFormat;
    short width;
    short height;
    bool generateMipmap;
    bool normalizeCoords;
    /* S3TC 透传：compressedNative 为真表示 GPU 侧存的就是压缩数据。压缩纹理在 GLES 里
       不是 color-renderable，无法 attach 到 FBO 做 readPixels，故按 level 保留一份压缩
       数据的 CPU 副本供 glGetTexImage / glGetCompressedTexImage 回读。副本只有 RGBA 的
       1/4~1/8，比旧路径在 GPU 侧常驻解压后的 RGBA8 更省内存。
       compressedModeDecided 用于把透传判定锁定到整张纹理：同一张 GL 纹理混用压缩与非压
       缩 level 在 GLES 下非法，且 level 可能不按 0→N 的顺序上传。 */
    bool compressedNative;
    bool compressedModeDecided;
    void* compressedLevel[MAX_TEXTURE_LEVELS];
    int compressedLevelSize[MAX_TEXTURE_LEVELS];
} GLTexture;

extern GLuint GLTexture_create();
extern GLTexture* GLTexture_getBound(GLenum target);
extern void GLTexture_bind(GLenum target, GLuint id);
extern void GLTexture_setActiveUnit(GLenum unit);
extern GLTexture* GLTexture_get(GLuint id);
extern GLuint GLTexture_getBindingId(GLenum target);
extern void GLTexture_delete(GLuint id);
extern void GLTexture_setCompressedLevel(GLTexture* texture, int level, const void* data, int size);
extern void GLTexture_dropCompressedLevel(GLTexture* texture, int level);
extern void GLTexture_clearCompressedLevels(GLTexture* texture);

#endif