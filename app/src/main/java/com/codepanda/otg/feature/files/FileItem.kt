package com.codepanda.otg.feature.files

/** A file or directory on the connected device. */
data class FileItem(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val mtimeSeconds: Long,
    val mode: Int,
) {
    val permissions: String get() = formatMode(mode)

    companion object {
        /** Render a Unix st_mode as a `drwxr-xr-x`-style string. */
        fun formatMode(mode: Int): String {
            val type = when (mode and 0xF000) {
                0x4000 -> 'd'
                0xA000 -> 'l'
                0x8000 -> '-'
                0x2000 -> 'c'
                0x6000 -> 'b'
                0x1000 -> 'p'
                0xC000 -> 's'
                else -> '?'
            }
            val sb = StringBuilder().append(type)
            val bits = "rwxrwxrwx"
            for (i in 0 until 9) {
                val mask = 1 shl (8 - i)
                sb.append(if (mode and mask != 0) bits[i] else '-')
            }
            return sb.toString()
        }
    }
}
