package app.metabatch

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Remembers the output folder between launches.
 *
 * The SAF tree URI is stored in SharedPreferences, and the persistable
 * read/write permission taken when the folder was picked survives app
 * restarts (it is only lost if the user revokes it, clears app data, or
 * uninstalls). On load we check the permission is still held; a stale
 * entry is dropped silently and the user picks again.
 */
object OutputFolderStore {
    private const val PREFS = "metabatch"
    private const val KEY_URI = "output_folder_uri"

    fun save(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URI, uri.toString()).apply()
    }

    fun load(context: Context): Uri? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: return null
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: run {
            clearPrefs(context)
            return null
        }
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!held) {
            clearPrefs(context)
            return null
        }
        return uri
    }

    fun clear(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)
        clearPrefs(context)
        if (saved != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(saved),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    private fun clearPrefs(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_URI).apply()
    }
}
