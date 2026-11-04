package com.calvinmazhindu.skitcam.project

import java.io.*
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.*

/** Only metadata and bounded buffers are retained; each payload reopens its original source. */
data class ArchivePayload(val name: String,val size: Long,val crc: Long,val sha256: String,val open: ()->InputStream) {
    init { require(ArchiveIO.safePath(name) && size>=0 && crc in 0..0xffffffffL && ArchiveIO.validSha(sha256)) }
}
data class ArchiveReceipt(val bytes: Long,val sha256: String)
class ProjectCancelled : IOException("Project export cancelled. Originals are preserved.")

object ArchiveIO {
    private const val BUFFER=65536
    fun validSha(s: String)=s.matches(Regex("[0-9a-f]{64}"))
    fun safePath(s: String)=s.length in 1..240 && s.matches(Regex("[A-Za-z0-9_./-]+")) &&
        !s.startsWith('/') && !s.endsWith('/') && s.split('/').none { it.isEmpty() || it=="." || it==".." }
    private fun hex(b: ByteArray)=b.joinToString("") { "%02x".format(it) }
    fun scan(name: String, open: ()->InputStream, expected: String?=null, checkpoint: ()->Unit = {}, progress: (Long)->Unit = {}): ArchivePayload {
        require(safePath(name));val sha=MessageDigest.getInstance("SHA-256");val crc=CRC32();var size=0L
        open().use { input -> val buffer=ByteArray(BUFFER)
            while(true) { checkpoint();val n=input.read(buffer);if(n<0) break;if(n==0) continue
                size=Math.addExact(size,n.toLong());sha.update(buffer,0,n);crc.update(buffer,0,n);progress(n.toLong()) }
        }
        val digest=hex(sha.digest());require(expected==null || digest==expected) { "Source integrity changed: $name" }
        return ArchivePayload(name,size,crc.value,digest,open)
    }
    fun bytes(name: String,data: ByteArray)=scan(name,{ ByteArrayInputStream(data) })
    fun entries(payloads: List<ArchivePayload>,manifest: ByteArray): List<ArchivePayload> {
        require(manifest.size<=4*1024*1024) { "Portable metadata is too large." }
        val all=payloads+bytes("session.json",manifest)
        require(all.map { it.name }.distinct().size==all.size && all.none { it.name=="checksums.sha256" })
        val checksums=all.joinToString("") { "${it.sha256}  ${it.name}\n" }.toByteArray(Charsets.UTF_8)
        return all+bytes("checksums.sha256",checksums)
    }
    fun estimate(payloads: List<ArchivePayload>): Long = payloads.fold(1024L) { sum,e -> Math.addExact(sum,Math.addExact(e.size,512L+e.name.length*2L)) }

    /** Caller owns the destination. A receipt is returned only after ZIP/footer/close succeeds. */
    fun write(output: OutputStream, entries: List<ArchivePayload>, checkpoint: ()->Unit = {}, progress: (Long)->Unit = {}): ArchiveReceipt {
        require(entries.isNotEmpty() && entries.map { it.name }.distinct().size==entries.size)
        val digest=MessageDigest.getInstance("SHA-256");val count=TailOutput(output)
        // ZipOutputStream.close can fail while finishing a partial entry before
        // it closes the underlying destination. Always close that destination too.
        output.use { ZipOutputStream(DigestOutputStream(count,digest)).use { zip ->
            for(entry in entries) {
                checkpoint()
                zip.putNextEntry(ZipEntry(entry.name).apply { method=ZipEntry.STORED;size=entry.size;compressedSize=entry.size;crc=entry.crc;time=0L })
                val sha=MessageDigest.getInstance("SHA-256");var length=0L
                entry.open().use { input -> val buffer=ByteArray(BUFFER)
                    while(true) { checkpoint();val n=input.read(buffer);if(n<0) break;if(n==0) continue
                        length=Math.addExact(length,n.toLong());require(length<=entry.size) { "Source grew: ${entry.name}" }
                        zip.write(buffer,0,n);sha.update(buffer,0,n);progress(n.toLong()) }
                }
                require(length==entry.size && hex(sha.digest())==entry.sha256) { "Source changed while archiving: ${entry.name}" }
                zip.closeEntry()
            }
            checkpoint();zip.finish()
        } }
        return ArchiveReceipt(count.count,hex(digest.digest()))
    }
    /** Read back every entry and every raw archive byte, including central directory and footer. */
    fun verify(input: InputStream, entries: List<ArchivePayload>, receipt: ArchiveReceipt, checkpoint: ()->Unit = {}, progress: (Long)->Unit = {}) {
        val sha=MessageDigest.getInstance("SHA-256");val tail=TailInput(DigestInputStream(input,sha));val zip=ZipInputStream(tail)
        try {
            val buffer=ByteArray(BUFFER);var index=0
            while(true) {
                checkpoint();val entry=zip.nextEntry ?: break
                require(index<entries.size && safePath(entry.name) && entry.name==entries[index].name && !entry.isDirectory) { "Unexpected archive entry." }
                val expected=entries[index++];val hash=MessageDigest.getInstance("SHA-256");val crc=CRC32();var length=0L
                while(true) { checkpoint();val n=zip.read(buffer);if(n<0) break;if(n==0) continue
                    length=Math.addExact(length,n.toLong());require(length<=expected.size) { "Archive entry is oversized." }
                    hash.update(buffer,0,n);crc.update(buffer,0,n);progress(n.toLong()) }
                require(length==expected.size && crc.value==expected.crc && hex(hash.digest())==expected.sha256) { "Archive checksum mismatch: ${entry.name}" }
                zip.closeEntry()
            }
            require(index==entries.size) { "Archive entries are missing." }
            while(true) { checkpoint();val n=tail.read(buffer);if(n<0) break }
            require(tail.count==receipt.bytes && hex(sha.digest())==receipt.sha256) { "Archive bytes differ from the completed write." }
            checkFooter(tail.last(),tail.count,entries.size.toLong())
        } finally { zip.close() }
    }
    fun verifyHash(input: InputStream, receipt: ArchiveReceipt, checkpoint: ()->Unit = {}) {
        val p=scan("archive.zip",{ input },receipt.sha256,checkpoint)
        require(p.size==receipt.bytes) { "Final archive length changed." }
    }
    // This checks the fixed, comment-free footer of OUR writer, not arbitrary imported ZIP files.
    internal fun checkFooter(b: ByteArray,bytes: Long,count: Long) {
        fun u16(p: Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
        fun u32(p: Int)=(0..3).fold(0L) { n,i -> n or ((b[p+i].toLong() and 255) shl (8*i)) }
        fun u64(p: Int): Long { val n=(0..7).fold(0L) { a,i -> a or ((b[p+i].toLong() and 255) shl (8*i)) };require(n>=0);return n }
        val end=b.size-22;require(end>=0 && u32(end)==0x06054b50L && u16(end+20)==0 && u16(end+4)==0 && u16(end+6)==0) { "ZIP closing directory is incomplete." }
        if(u32(end+16)==0xffffffffL || u32(end+12)==0xffffffffL || u16(end+10)==65535) {
            val z=end-76;require(z>=0 && u32(z)==0x06064b50L && u64(z+4)==44L && u32(end-20)==0x07064b50L)
            require(u64(z+24)==count && u64(z+32)==count && Math.addExact(u64(z+40),u64(z+48))==bytes-98)
        } else require(u16(end+8).toLong()==count && u16(end+10).toLong()==count && Math.addExact(u32(end+12),u32(end+16))==bytes-22)
    }
    private class TailOutput(out: OutputStream): FilterOutputStream(out) {
        var count=0L
        override fun write(b: Int) { out.write(b);count=Math.addExact(count,1) }
        override fun write(b: ByteArray,off: Int,len: Int) { out.write(b,off,len);count=Math.addExact(count,len.toLong()) }
    }
    private class TailInput(input: InputStream): FilterInputStream(input) {
        var count=0L;private val tail=ByteArray(128)
        private fun accept(b: ByteArray,off: Int,len: Int) {
            for(i in maxOf(0,len-128) until len) tail[((count+i)%128).toInt()]=b[off+i]
            count=Math.addExact(count,len.toLong())
        }
        fun last()=ByteArray(minOf(count,128).toInt()) { i -> tail[((count-minOf(count,128)+i)%128).toInt()] }
        override fun read(): Int { val n=super.read();if(n>=0) accept(byteArrayOf(n.toByte()),0,1);return n }
        override fun read(b: ByteArray,off: Int,len: Int): Int { val n=super.read(b,off,len);if(n>0) accept(b,off,n);return n }
    }
}
