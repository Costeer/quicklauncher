package org.quicklauncher.host.backup

internal fun fixtureBytes(name: String): ByteArray =
    requireNotNull(object {}.javaClass.getResource("/fixtures/backup/$name")) {
        "Missing backup fixture: $name"
    }.readBytes()

internal fun canonicalJsonFixture(name: String): ByteArray =
    fixtureBytes(name).dropLastWhile { it == '\n'.code.toByte() || it == '\r'.code.toByte() }.toByteArray()

internal fun hexFixture(name: String): ByteArray =
    fixtureBytes(name)
        .toString(Charsets.US_ASCII)
        .filterNot(Char::isWhitespace)
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
