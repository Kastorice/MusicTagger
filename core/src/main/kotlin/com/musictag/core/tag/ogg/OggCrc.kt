package com.musictag.core.tag.ogg

/**
 * Ogg framing CRC.
 *
 * Ogg uses a non-reflected (MSB-first) CRC-32 with polynomial `0x04C11DB7`,
 * initial value `0`, no final XOR and no input/output reflection. This is the
 * same checksum that libogg implements in `crc32.c`.
 */
internal object OggCrc {
    private const val POLYNOMIAL = 0x04C11DB7

    private val TABLE: IntArray = IntArray(256) { index ->
        var remainder = index shl 24
        repeat(8) {
            remainder = if (remainder and Int.MIN_VALUE != 0) {
                (remainder shl 1) xor POLYNOMIAL
            } else {
                remainder shl 1
            }
        }
        remainder
    }

    /** Computes the Ogg checksum over `bytes[offset, offset + length)`. */
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var crc = 0
        val end = offset + length
        var index = offset
        while (index < end) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) and 0xFF) xor (bytes[index].toInt() and 0xFF)]
            index++
        }
        return crc
    }
}
