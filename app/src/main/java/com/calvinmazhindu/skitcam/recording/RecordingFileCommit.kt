package com.calvinmazhindu.skitcam.recording

import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

internal fun syncRecordingDirectory(directory: File) {
    FileChannel.open(directory.toPath(), READ).use { it.force(true) }
}

/** Keep the last committed base in place until the next value is ready to rename. */
internal fun commitRecordingMetadata(base: File, content: String) {
    val staged = File(base.path + ".new").toPath()
    val parent = checkNotNull(base.parentFile)
    FileChannel.open(staged, CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS).use { channel ->
        Channels.newOutputStream(channel).write(content.toByteArray(Charsets.UTF_8))
        channel.force(true)
    }
    Files.move(staged, base.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    syncRecordingDirectory(parent)
    // A legacy backup remains useful until the new base and its directory entry are durable.
    if (Files.deleteIfExists(File(base.path + ".bak").toPath())) syncRecordingDirectory(parent)
}
