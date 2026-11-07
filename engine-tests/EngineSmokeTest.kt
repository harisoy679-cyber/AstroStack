import com.astrostack.engine.ImageStackEngine as E
import java.io.File

fun checkThat(ok: Boolean, message: String) { if (!ok) error(message) }
fun frame(w:Int,h:Int, pixels:IntArray)=E.Frame(w,h,pixels)
fun rgb(r:Int,g:Int=r,b:Int=r)=(-0x1000000) or (r shl 16) or (g shl 8) or b
fun main() {
    // A sparse synthetic star field shifted by (+3,-2) in the moving frame.
    val w=64; val h=64; val base=IntArray(w*h){rgb(10)}
    val stars=listOf(10 to 12, 22 to 30, 44 to 18, 51 to 48, 31 to 52)
    for ((x,y) in stars) base[y*w+x]=rgb(240)
    val moved=IntArray(w*h){rgb(10)}
    for ((x,y) in stars) moved[(y-2)*w+(x+3)]=rgb(240)
    val shift=E.estimateTranslation(frame(w,h,base),frame(w,h,moved),8,1)
    checkThat(shift.dx == 3 && shift.dy == -2, "translation expected (+3,-2), got $shift")
    val aligned = E.stack(listOf(frame(w,h,base), frame(w,h,moved)), E.Method.AVERAGE, true, maxShift=8)
    checkThat(aligned.frame.width == w-3 && aligned.frame.height == h-2, "alignment overlap crop dimensions incorrect")
    checkThat(aligned.shifts[1].dx == 3 && aligned.shifts[1].dy == -2, "stack did not retain measured shift")

    val f1=frame(4,4,IntArray(16){rgb(10)})
    val f2=frame(4,4,IntArray(16){rgb(20)})
    val f3=frame(4,4,IntArray(16){rgb(250)})
    val med=E.stack(listOf(f1,f2,f3),E.Method.MEDIAN,false).frame
    checkThat((med.pixels[0] ushr 16 and 255)==20,"median stack failed")
    val sig=E.stack(listOf(f1,f2,f3),E.Method.SIGMA_CLIP,false, sigma=1.0)
    checkThat(sig.rejectedPixels > 0,"sigma clipping did not reject outlier")

    val bias=frame(2,2,IntArray(4){rgb(5)})
    val dark=frame(2,2,IntArray(4){rgb(15)})
    val light=frame(2,2,IntArray(4){rgb(100)})
    val calibrated=E.calibrate(light,dark=dark,bias=bias)
    checkThat((calibrated.pixels[0] ushr 16 and 255)==85,"dark/bias calibration expected 85")

    val file=File.createTempFile("astrostack-test", ".tif")
    E.writeTiff16(frame(2,1,intArrayOf(rgb(255,128,0),rgb(1,2,3))),file)
    val bytes=file.readBytes()
    checkThat(bytes[0]=='I'.code.toByte() && bytes[1]=='I'.code.toByte(),"TIFF endian header incorrect")
    checkThat(bytes.size==140+12,"TIFF byte count unexpected: ${bytes.size}")
    file.delete()
    println("PASS: translation registration, median, sigma clipping, dark/bias calibration, TIFF16 header/payload")
}
