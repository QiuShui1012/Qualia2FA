package dev.qualia.qualia2fa

actual fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
