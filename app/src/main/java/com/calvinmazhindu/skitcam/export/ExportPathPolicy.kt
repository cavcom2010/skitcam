package com.calvinmazhindu.skitcam.export

/** Only facts re-probed from the frozen sources may select a lossless path. */
data class ExportInputFacts(val width: Int, val height: Int, val rotation: Int,
    val videoMime: String, val videoConfig: List<String>, val colour: List<Int>,
    val squarePixels: Boolean, val sdr: Boolean, val startsWithKeyFrame: Boolean,
    val firstVideoUs: Long, val lastVideoUs: Long, val reordered: Boolean,
    val audioMime: String, val audioRate: Int, val audioChannels: Int,
    val audioLc: Boolean, val audioBitrate: Int?, val firstAudioUs: Long,
    val lastAudioUs: Long, val durationMs: Long, val encrypted: Boolean)

enum class ExportPath { DIRECT_COPY, VIDEO_PASSTHROUGH_AUDIO_RENDER, RENDER }
data class ExportDecision(val path: ExportPath, val reason: String)

object ExportPathPolicy {
    fun choose(spec: ExportSpec, facts: List<ExportInputFacts>): ExportDecision {
        fun render(reason: String) = ExportDecision(ExportPath.RENDER, reason)
        val profile=spec.captureProfile?.takeUnless { it.legacy } ?: return render("Legacy output policy requires rendering")
        if(facts.size!=spec.clips.size || facts.isEmpty()) return render("Incomplete input facts")
        if(spec.clips.any { it.range.isTrimmed }) return render("Saved trim requires the established renderer")
        val first=facts.first()
        for((index,f) in facts.withIndex()) {
            if(f.encrypted || f.videoMime!="video/avc" || f.audioMime!="audio/mp4a-latm") return render("Unsupported tracks")
            if(!f.sdr || !f.squarePixels || f.videoConfig.isEmpty()) return render("Video display/configuration is not eligible")
            if(maxOf(f.width,f.height)!=maxOf(profile.width,profile.height) || minOf(f.width,f.height)!=minOf(profile.width,profile.height))
                return render("Source geometry differs from output profile")
            if(f.rotation !in listOf(0,90,180,270) || f.rotation!=first.rotation || f.width!=first.width || f.height!=first.height)
                return render("Different display geometry or rotation")
            if(f.videoConfig!=first.videoConfig || f.colour!=first.colour) return render("Different AVC configuration or colour metadata")
            if(f.firstVideoUs!=0L || f.reordered || !f.startsWithKeyFrame || f.lastVideoUs<=0) return render("Video timestamp/keyframe layout is not proven safe")
            if(f.audioRate !in 1..192000 || f.audioChannels !in 1..2 || !f.audioLc) return render("Unsupported audio conversion")
            if(kotlin.math.abs(f.durationMs-spec.clips[index].range.sourceDurationMs)>1) return render("Source duration changed")
        }
        if(facts.size==1 && first.audioRate==48000 && first.audioChannels==2 && first.audioBitrate==128000 &&
            kotlin.math.abs(first.firstAudioUs-first.firstVideoUs)<=100_000 && kotlin.math.abs(first.lastAudioUs-first.lastVideoUs)<=100_000) {
            return ExportDecision(ExportPath.DIRECT_COPY,"Whole clip already meets the output contract")
        }
        // Audio still goes through the proven 128 kb/s / 48 kHz stereo renderer and gap adapter.
        // Full audio transmux is deliberately not enabled: per-item AAC priming/edit lists differ.
        return ExportDecision(ExportPath.VIDEO_PASSTHROUGH_AUDIO_RENDER,"Compatible whole AVC clips; preserve video and render/correct audio")
    }
}
