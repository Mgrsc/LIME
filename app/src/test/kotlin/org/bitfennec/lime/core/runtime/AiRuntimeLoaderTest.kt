package org.bitfennec.lime.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AiRuntimeLoaderTest {

    @Test
    fun validatesApprovedUrls() {
        val ortArtifact = AiPackageSpec.ortArtifact
        val hwArtifact = AiPackageSpec.hwModelArtifact

        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            URL("https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/libonnxruntime.so"),
            ortArtifact.fileName,
            ortArtifact.sha256,
            ortArtifact.pinnedUrls
        ))
        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            URL("https://ghproxy.net/https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/libonnxruntime.so"),
            ortArtifact.fileName,
            ortArtifact.sha256,
            ortArtifact.pinnedUrls
        ))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            URL("http://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/libonnxruntime.so"),
            ortArtifact.fileName,
            ortArtifact.sha256,
            ortArtifact.pinnedUrls
        ))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            URL("https://evil.com/libonnxruntime.so"),
            ortArtifact.fileName,
            ortArtifact.sha256,
            ortArtifact.pinnedUrls
        ))

        assertTrue(AiDownloadGuard.isAllowedArtifactUrl(
            URL("https://github.com/Mgrsc/LIME/releases/download/v3.0.4-runtime/ppocrv6_rec.onnx"),
            hwArtifact.fileName,
            hwArtifact.sha256,
            hwArtifact.pinnedUrls
        ))
        assertFalse(AiDownloadGuard.isAllowedArtifactUrl(
            URL("https://evil.com/ppocrv6_rec.onnx"),
            hwArtifact.fileName,
            hwArtifact.sha256,
            hwArtifact.pinnedUrls
        ))
    }

    @Test
    fun verifiesSha256Correctly() {
        val file = File.createTempFile("ai-runtime-spec-test", ".bin")
        try {
            file.writeText("lime-ai-runtime-test")
            // sha256 of "lime-ai-runtime-test":
            // echo -n "lime-ai-runtime-test" | sha256sum -> a2c2e1a0fd6908d3201c28fc33f87031bf991572dad048ae5290d075d16234c3
            val expectedHash = "a2c2e1a0fd6908d3201c28fc33f87031bf991572dad048ae5290d075d16234c3"
            assertTrue(AiDownloadGuard.verifySha256(file, expectedHash))
            assertFalse(AiDownloadGuard.verifySha256(file, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun detects16KbElfAlignment() {
        val alignedFile = createMockElf64(align = 0x4000L)
        val unalignedFile = createMockElf64(align = 0x1000L)
        val invalidFile = File.createTempFile("invalid-elf", ".bin").apply { writeText("not an elf file") }

        try {
            assertTrue("Should accept 16KB aligned ELF", AiRuntimeLoader.isElf16KbAligned(alignedFile))
            assertFalse("Should reject 4KB aligned ELF on Android 15+", AiRuntimeLoader.isElf16KbAligned(unalignedFile))
            assertFalse("Should reject invalid ELF header", AiRuntimeLoader.isElf16KbAligned(invalidFile))
        } finally {
            alignedFile.delete()
            unalignedFile.delete()
            invalidFile.delete()
        }
    }

    @Test
    fun testLoadOrtFailsWhenJniRequiredButMissing() {
        val nonExistentOrt = File("/nonexistent/libonnxruntime.so")
        val nonExistentJni = File("/nonexistent/libonnxruntime4j_jni.so")
        assertFalse("Should return false when ORT file does not exist",
            AiRuntimeLoader.loadOrt(nonExistentOrt, nonExistentJni, requireJni = true))
    }

    @Test
    fun testLoadSherpaJniFailsWhenSherpaFileMissing() {
        val nonExistentOrt = File("/nonexistent/libonnxruntime.so")
        val nonExistentSherpa = File("/nonexistent/libsherpa-onnx-jni.so")
        assertFalse("Should return false when sherpa JNI does not exist",
            AiRuntimeLoader.loadSherpaJni(sherpaJniFile = nonExistentSherpa, ortFile = nonExistentOrt))
    }

    @Test
    fun testVoiceThenHandwritingRequiresJniPenetration() {
        try {
            AiRuntimeLoader.isOrtLoaded = true
            AiRuntimeLoader.isOrtJniLoaded = false
            val nonExistentOrt = File("/nonexistent/libonnxruntime.so")
            val nonExistentJni = File("/nonexistent/libonnxruntime4j_jni.so")
            // Simulate voice loading native ORT first (isOrtLoaded = true)
            // When handwriting requests requireJni = true without 4j_jni, do not early return true; must fail-closed and return false
            assertFalse("Handwriting must fail when JNI missing even if native ORT was loaded",
                AiRuntimeLoader.loadOrt(nonExistentOrt, nonExistentJni, requireJni = true))
        } finally {
            AiRuntimeLoader.resetForTesting()
        }
    }

    private fun createMockElf64(align: Long): File {
        val file = File.createTempFile("mock-elf64", ".so")
        val buf = ByteBuffer.allocate(64 + 56).order(ByteOrder.LITTLE_ENDIAN)

        // ELF Header (64 bytes)
        buf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buf.put(2) // EI_CLASS = ELFCLASS64
        buf.put(1) // EI_DATA = ELFDATA2LSB (little endian)
        buf.put(1) // EI_VERSION
        buf.position(16)
        buf.putShort(3) // e_type = ET_DYN (shared object)
        buf.putShort(183) // e_machine = EM_AARCH64
        buf.putInt(1) // e_version
        buf.putLong(0) // e_entry
        buf.putLong(64) // e_phoff = offset 64
        buf.putLong(0) // e_shoff
        buf.putInt(0) // e_flags
        buf.putShort(64) // e_ehsize
        buf.putShort(56) // e_phentsize
        buf.putShort(1) // e_phnum
        buf.putShort(0) // e_shentsize
        buf.putShort(0) // e_shnum
        buf.putShort(0) // e_shstrndx

        // Program Header (56 bytes)
        buf.putInt(1) // p_type = PT_LOAD
        buf.putInt(5) // p_flags = PF_R | PF_X
        buf.putLong(0) // p_offset
        buf.putLong(0) // p_vaddr
        buf.putLong(0) // p_paddr
        buf.putLong(120) // p_filesz
        buf.putLong(120) // p_memsz
        buf.putLong(align) // p_align (0x4000 = 16KB)

        FileOutputStream(file).use { it.write(buf.array()) }
        return file
    }
}
