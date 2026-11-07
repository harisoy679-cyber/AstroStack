package com.tssystems;

public class Libraw {

    static {
        System.loadLibrary("libraw");
    }

    public native int open(String file);
    public native void cleanup();

    public native int getBitmapWidth();
    public native int getBitmapHeight();

    public native int getWidth();
    public native int getHeight();
    public native int getOrientation();
    public native int getColors();

    public native int[] getPixels8();
    public native long getPixels16();

    public native float[] getDaylightMultiplier();
    public native float[] getWhitebalanceMultiplier();
    public native float[] getCamRgb();
    public native float[] getRgbCam();
    public native float[] getCamMatrix();

    public native void setUserMul(float r, float g1, float b, float g2);
    public native void setAutoWhitebalance(boolean value);
    public native void setHighlightMode(int value);
    public native void setAutoBrightness(boolean value);
    public native void setUseCameraMatrix(int value);
    public native void setOutputColorSpace(int value);
    public native void setOutputBps(int value);
    public native void setQuality(int value);
    public native void setHalfSize(boolean value);
    public native void setGamma(double g1, double g2);

    public native String getCameraList();
    public native byte[] getThumbnail(String file);
}
