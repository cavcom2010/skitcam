package com.calvinmazhindu.skitcam.photo

/** Photo framing, independent of recording profiles and device orientation policy. */
enum class PhotoAspectRatio(val label: String) {
    Square("1:1"), FourThree("4:3"), SixteenNine("16:9"), Full("Full");

    fun frame(landscape: Boolean, availableWidth: Int, availableHeight: Int): PhotoFrame? {
        if(availableWidth<=0 || availableHeight<=0) return null
        val wide=when(this) {
            Square -> PhotoFrame(1,1)
            FourThree -> PhotoFrame(4,3)
            SixteenNine -> PhotoFrame(16,9)
            Full -> return PhotoFrame(availableWidth,availableHeight).reduced()
        }
        return if(landscape) wide else wide.swapped()
    }

    companion object {
        fun restored(value: String?): PhotoAspectRatio = entries.firstOrNull { it.name==value } ?: FourThree
    }
}

data class PhotoFrame(val width: Int,val height: Int) {
    init { require(width>0 && height>0) }
    val aspect get() = width.toFloat()/height
    fun swapped() = PhotoFrame(height,width)
    /** Converting to/from natural-display coordinates is the same parity operation. */
    fun rotated(rotation: Int): PhotoFrame {
        require(rotation in 0..3)
        return if(rotation%2==0) this else swapped()
    }
    fun reduced(): PhotoFrame {
        var a=width;var b=height
        while(b!=0) { val next=a%b;a=b;b=next }
        return PhotoFrame(width/a,height/a)
    }
}

/** A timer/capture retains its selected sensor interval even if the window rotates. */
fun photoCaptureFrame(previous: PhotoFrame?, desired: PhotoFrame, busy: Boolean): PhotoFrame =
    if(busy && previous!=null) previous else desired
