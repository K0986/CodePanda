package com.codepanda.otg.adb

/**
 * Constants and helpers for the Android Debug Bridge (ADB) wire protocol.
 *
 * The protocol is a symmetric, multiplexed stream protocol. Every packet is a
 * 24-byte header optionally followed by a payload:
 *
 * ```
 * struct amessage {
 *     u32 command;      // one of the A_* constants below
 *     u32 arg0;
 *     u32 arg1;
 *     u32 data_length;  // payload length in bytes
 *     u32 data_crc32;   // crc32 of the payload (unsigned sum on modern adbd)
 *     u32 magic;        // command xor 0xffffffff
 * };
 * ```
 *
 * Reference: platform/system/core/adb/protocol.txt
 */
object AdbProtocol {

    /** Connect. arg0 = version, arg1 = max payload, payload = system identity. */
    const val A_CNXN = 0x4e584e43

    /** Authenticate. arg0 = auth type (token/signature/pubkey). */
    const val A_AUTH = 0x48545541

    /** Open a stream to a remote service. arg0 = local-id, payload = "service:args". */
    const val A_OPEN = 0x4e45504f

    /** Ready / flow-control ack. arg0 = local-id, arg1 = remote-id. */
    const val A_OKAY = 0x59414b4f

    /** Close a stream. arg0 = local-id, arg1 = remote-id. */
    const val A_CLSE = 0x45534c43

    /** Write payload to a stream. arg0 = local-id, arg1 = remote-id. */
    const val A_WRTE = 0x45545257

    /** TLS upgrade request (STLS), adbd >= 30. Not used by this client. */
    const val A_STLS = 0x534c5453

    // ---- Auth sub-types (A_AUTH arg0) --------------------------------------

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    // ---- Version / sizing --------------------------------------------------

    /** Protocol version advertised by this host. */
    const val A_VERSION = 0x01000001

    /**
     * Maximum payload we advertise, matching what `adb` itself advertises for
     * protocol version [A_VERSION]. adbd negotiates down if it is older, and the
     * value it reports back in its `CNXN` is what we actually honour when
     * splitting writes.
     */
    const val MAX_PAYLOAD = 1024 * 1024

    /** Connect payload advertising the host and the features we understand. */
    const val CONNECT_PAYLOAD =
        "host::features=cmd,shell_v2,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send"

    const val HEADER_LENGTH = 24

    /** adb's "checksum": the unsigned sum of every payload byte. */
    fun payloadChecksum(payload: ByteArray): Int {
        var sum = 0L
        for (b in payload) sum += (b.toInt() and 0xff)
        return (sum and 0xffffffffL).toInt()
    }

    fun commandName(command: Int): String = when (command) {
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_STLS -> "STLS"
        else -> "0x%08x".format(command)
    }
}
