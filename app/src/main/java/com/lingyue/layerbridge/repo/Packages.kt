package com.lingyue.layerbridge.repo

import com.lingyue.layerbridge.core.DpkgVersion

data class RepoPackage(
    val repoId: String,
    val name: String,
    val version: String,
    val architecture: String,
    val filename: String?,
    val size: Long?,
    val sha256: String?,
    val installedSize: Long?,
    val depends: String?,
    val preDepends: String?,
    val provides: String?,
    val conflicts: String?,
    val breaks: String?,
    val replaces: String?,
    val description: String?,
    val format: String?,
    val signature: String?,
) {
    val isArchitectureSupported: Boolean
        get() = architecture == "all" || architecture == "aarch64"

    val inferredFormat: String
        get() = format?.lowercase()
            ?: filename?.substringAfterLast('.', "")?.lowercase().takeIf { it == "lbpa" || it == "lpa" || it == "lb" || it == "lbp" }
            ?: "deb"
}

object PackagesParser {

    fun parse(text: String, repoId: String, architecture: String = "aarch64"): List<RepoPackage> =
        ControlParser.parse(text).mapNotNull { stanza -> toPackage(stanza, repoId, architecture) }

    private fun toPackage(stanza: Stanza, repoId: String, architecture: String): RepoPackage? {
        val name = stanza.field("Package")?.trim().orEmpty()
        val version = stanza.field("Version")?.trim().orEmpty()
        if (name.isEmpty() || version.isEmpty()) return null
        if (!DpkgVersion.isValid(version)) return null

        val arch = stanza.field("Architecture")?.trim().orEmpty()
        if (arch.isNotEmpty() && arch != architecture && arch != "all") return null

        return RepoPackage(
            repoId = repoId,
            name = name,
            version = version,
            architecture = arch.ifEmpty { architecture },
            filename = stanza.field("Filename")?.trim(),
            size = stanza.field("Size")?.trim()?.toLongOrNull(),
            sha256 = stanza.field("SHA256")?.trim()?.lowercase(),
            installedSize = stanza.field("Installed-Size")?.trim()?.toLongOrNull(),
            depends = stanza.field("Depends")?.trim(),
            preDepends = stanza.field("Pre-Depends")?.trim(),
            provides = stanza.field("Provides")?.trim(),
            conflicts = stanza.field("Conflicts")?.trim(),
            breaks = stanza.field("Breaks")?.trim(),
            replaces = stanza.field("Replaces")?.trim(),
            description = stanza.field("Description")?.trim(),
            format = stanza.field("Format")?.trim(),
            signature = stanza.field("Signature")?.trim(),
        )
    }
}
