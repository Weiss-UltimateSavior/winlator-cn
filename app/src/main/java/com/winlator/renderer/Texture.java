package com.winlator.renderer;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES32;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

public class Texture {
    protected int textureId = 0;
    protected int wrapS = GLES20.GL_CLAMP_TO_EDGE;
    protected int wrapT = GLES20.GL_CLAMP_TO_EDGE;
    protected int magFilter = GLES20.GL_LINEAR;
    protected int minFilter = GLES20.GL_LINEAR;
    protected int format = GLES11Ext.GL_BGRA;
    protected boolean needsUpdate = true;
    private boolean flipY = false;
    protected Drawable owner;

    public Texture(Drawable owner) {
        this.owner = owner;
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
    }

    public void allocateTexture(short width, short height, ByteBuffer data) {
        generateTextureId();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (data != null) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        }

        setTextureParameters();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public Drawable getOwner() {
        return owner;
    }

    public void setOwner(Drawable owner) {
        this.owner = owner;
    }

    public boolean isFlipY() {
        return flipY;
    }

    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public int getWrapS() {
        return wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return needsUpdate;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
    }

    public void updateFromDrawable() {
        if (owner == null || owner.getData() == null) return;

        ByteBuffer data = owner.getData();
        if (!isAllocated()) {
            allocateTexture(owner.width, owner.height, data);
        }
        else if (needsUpdate) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, owner.width, owner.height, format, GLES20.GL_UNSIGNED_BYTE, data);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            needsUpdate = false;
        }
    }

    public boolean isAllocated() {
        return textureId > 0;
    }

    public int getTextureId() {
        return textureId;
    }

    public void copyFromSource(Texture source) {
        if (!source.isAllocated()) source.allocateTexture(source.owner.width, source.owner.height, null);
        if (!this.isAllocated()) this.allocateTexture(source.owner.width, source.owner.height, null);
        GLES32.glCopyImageSubData(
            source.textureId, GLES20.GL_TEXTURE_2D, 0, 0, 0, 0,
            this.textureId, GLES20.GL_TEXTURE_2D, 0, 0, 0, 0,
            source.owner.width, source.owner.height, 1
        );
        GLES20.glFlush();
    }

    public void copyFromReadBuffer(short width, short height) {
        copyFromReadBuffer(width, height, GLES20.GL_RGBA);
    }

    public void copyFromReadBuffer(short width, short height, int internalformat) {
        if (!isAllocated()) allocateTexture(width, height, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        // internalformat 由调用方决定：gladio（GLX）窗口是不透明视觉（X server 只暴露
        // RGB24 fbconfig），但 wined3d/ddraw 客户端写 back buffer 时 alpha 通道未定义
        //（PvZ 等填 0），用 GL_RGB 采样 alpha 恒为 1，避免合成器按 GL_SRC_ALPHA 把
        // 整个窗口混成全透明；VirGL 前端 alpha 有意义，保持 GL_RGBA。
        GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalformat, 0, 0, width, height, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glFlush();
    }

    public void destroy() {
        if (textureId > 0) {
            int[] textureIds = new int[]{textureId};
            GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            textureId = 0;
        }
    }
}
