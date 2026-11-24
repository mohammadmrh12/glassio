package com.glasslauncher.tv.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Hands this TV's settings (a backup, sign-ins included) to Glassio's other build on the same TV (GitHub ↔ Play), so
 * its *Restore backup* finds them without a file: Android keeps each app's files in Download apart, and the Play
 * build couldn't see the GitHub build's backups ("No backup on the TV yet"). Only the other build, checked by its
 * package and signing certificate ([Certs]), gets an answer; any other caller gets nothing.
 */
class BackupProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        val caller = callingPackage
        if (!Certs.isOtherGlassio(ctx, caller)) { Glog.w("Backup asked by $caller: refused"); return null }
        val json = runCatching { Backup.export(ctx, Settings(ctx).backupSignIns).toString() }
            .onFailure { Glog.w("Backup for the Play build", it) }.getOrNull() ?: return null
        Glog.i("Backup handed to Glassio's other build ($caller)")
        return MatrixCursor(arrayOf("backup")).apply { addRow(arrayOf(json)) }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
