package com.tssystems

class Libraw {

    companion object {
        init {
            System.loadLibrary("libraw")
        }
    }

    external fun open(file: String): Int
    external fun cleanup()

    external fun getBitmapWidth(): Int
    external fun getBitmapHeight(): Int

    external fun getWidth(): Int
    external fun getHeight(): Int
    external fun getOrientation(): Int
    external fun getColors(): Int

    external fun getPixels8(): IntArray?
    external fun getPixels16(): Long

    external fun getDaylightMultiplier(): FloatArray?
    external fun getWhitebalanceMultiplier(): FloatArray?
    external fun getCamRgb(): FloatArray?
    external fun getRgbCam(): FloatArray?
    external fun getCamMatrix(): FloatArray?

    external fun setUserMul(
        r: Float,
        g1: Float,
        b: Float,
        g2: Float
    )

    external fun setAutoWhitebalance(value: Boolean)
    external fun setHighlightMode(value: Int)
    external fun setAutoBrightness(value: Boolean)
    external fun setUseCameraMatrix(value: Int)
    external fun setOutputColorSpace(value: Int)
    external fun setOutputBps(value: Int)
    external fun setQuality(value: Int)
    external fun setHalfSize(value: Boolean)
    external fun setGamma(g1: Double, g2: Double)

    external fun getCameraList(): String?
    external fun getThumbnail(file: String): ByteArray?
}
