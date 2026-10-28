package com.lingyue.layerbridge.crypto

import com.google.gson.Gson
import java.io.File

class TrustStore(private val dir: File) {

    private val rootFile = File(dir, "root.json")
    private val gson = Gson()

    fun loadRoot(): Certificate? {
        if (!rootFile.isFile) return null
        return runCatching { gson.fromJson(rootFile.readText(Charsets.UTF_8), Certificate::class.java) }
            .getOrNull()
    }

    fun saveRoot(cert: Certificate) {
        dir.mkdirs()
        rootFile.writeText(gson.toJson(cert), Charsets.UTF_8)
    }

    fun hasRoot(): Boolean = loadRoot() != null
}
