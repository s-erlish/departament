package com.v2ray.ang.dto

/**
 * The answer to «есть ли новая версия departament».
 *
 * [assetName] is the file the release actually carries — kept because the download is written to
 * the cache under its published name, which is what lets `getPackageArchiveInfo` and the user's own
 * file manager both show the same, recognisable artefact.
 */
data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val assetName: String? = null,
    val isPreRelease: Boolean = false
)
