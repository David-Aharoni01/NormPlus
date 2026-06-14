package com.norm2hacked.protocol.commands

/**
 * Body encoder for the watch's newer `cn.appscomm.commonprotocol` `@Body` commands
 * (e.g. `MessageNewBT`, cmd 0x76). The outer wire frame is unchanged — `BluetoothLeaf`
 * extends the legacy `Leaf`, so the body produced here is wrapped by the existing
 * `PacketBuilder` as `[6F][id][action][lenLo][lenHi][body][8F]`.
 *
 * Encoding rules, reverse-engineered from the annotation framework (smali):
 *   - `FieldHandler.handlerData` + `ParamsHandlerUtil.ObjectToByteArray`
 *   - `BluetoothRequest$Builder.addData / addDataDelay / flushDelayData`
 *
 * Per `@Order` field:
 *   - **Scalar** (int / boolean with no `@BLEField`) → 1 byte (`int → byte`).
 *   - **RawText** (String with no `@BLEField`) → raw UTF-8, *no* length prefix.
 *   - **LenText** (String with `@BLEField needAddLength=true`) → a length byte emitted
 *     **inline**, while the text bytes are **deferred** to a delay buffer that is flushed
 *     to the main buffer right after the **last** LenText field (smali marks it `endHead`).
 *
 * Note: this is the `@Body` encoding only. `@IndexBody` (e.g. `MessagePerfectBT`, 0x79) and
 * `@VarIntBody` use different handlers (index bytes / VarInt) and are intentionally not here.
 */
object CommonProtocolCodec {

    sealed interface Field
    /**
     * A single-byte field. All scalar Java types with no `@BLEField` (`int`, `byte`, `boolean`)
     * collapse to one byte here — `ParamsHandlerUtil.ObjectToByteArray` emits `int → byte` and
     * `boolean → 0/1` when `maxLength` is absent. Pass `0/1` for booleans, `x and 0xFF` for bytes.
     */
    data class Scalar(val value: Int) : Field
    /** String with no `@BLEField` → raw UTF-8, no length prefix. */
    data class RawText(val value: String) : Field
    /** String with `@BLEField needAddLength=true` → inline length byte + deferred bytes. */
    data class LenText(val value: String, val maxLen: Int) : Field

    fun encodeBody(fields: List<Field>): ByteArray {
        val main = ArrayList<Byte>(64)
        val delay = ArrayList<Byte>(64)
        // Only the LAST length-prefixed field is the "endHead" that flushes the delay buffer.
        val lastLenIndex = fields.indexOfLast { it is LenText }

        fields.forEachIndexed { i, field ->
            when (field) {
                is Scalar -> main.add(field.value.toByte())
                is RawText -> for (b in field.value.toByteArray(Charsets.UTF_8)) main.add(b)
                is LenText -> {
                    val bytes = truncateWithDots(field.value, field.maxLen)
                    main.add(bytes.size.toByte())          // inline length header
                    for (b in bytes) delay.add(b)          // data deferred
                    if (i == lastLenIndex) {               // endHead → flush deferred data
                        main.addAll(delay)
                        delay.clear()
                    }
                }
            }
        }
        return main.toByteArray()
    }

    /**
     * Byte-faithful port of `ParseUtil.getContentAddDot`: keep the string if its UTF-8 form fits
     * within [maxLen]; otherwise decode the first `maxLen - 3` bytes, drop trailing chars that are
     * **not present in the original** string (this removes a multi-byte char split at the cut
     * boundary, while preserving a legitimate U+FFFD that the original actually contains), then
     * append "..." — so the result stays within [maxLen] bytes.
     */
    fun truncateWithDots(s: String, maxLen: Int): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxLen) return bytes
        var head = String(bytes, 0, maxLen - 3, Charsets.UTF_8)
        while (head.isNotEmpty() && !s.contains(head.last())) head = head.dropLast(1)
        return (head + "...").toByteArray(Charsets.UTF_8)
    }
}
