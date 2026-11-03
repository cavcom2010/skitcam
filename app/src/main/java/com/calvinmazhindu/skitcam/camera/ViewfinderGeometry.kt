package com.calvinmazhindu.skitcam.camera

/** Image geometry only; never chooses a capture profile or crops a recording. */
object ViewfinderGeometry {
    fun isFourThree(width: Int, height: Int): Boolean {
        require(width>0 && height>0)
        val ratio=maxOf(width,height).toDouble()/minOf(width,height)
        return kotlin.math.abs(ratio-4.0/3)<kotlin.math.abs(ratio-16.0/9)
    }
    data class Size(val width: Float, val height: Float)
    fun aspect(width: Int, height: Int, rotationDegrees: Int): Float? {
        if(width<=0 || height<=0 || rotationDegrees !in setOf(0,90,180,270)) return null
        return if(rotationDegrees%180==0) width.toFloat()/height else height.toFloat()/width
    }
    fun fit(availableWidth: Float, availableHeight: Float, aspect: Float): Size? {
        if(!availableWidth.isFinite() || !availableHeight.isFinite() || !aspect.isFinite() ||
            availableWidth<=0 || availableHeight<=0 || aspect<=0) return null
        return if(availableWidth/availableHeight>aspect) Size(availableHeight*aspect,availableHeight)
        else Size(availableWidth,availableWidth/aspect)
    }
}
