package com.glennalex.audioextractor.util

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Uri 转文件路径工具
 * 处理 content:// 和 file:// 两种 Uri 格式
 */
object UriUtils {

    fun getPathFromUri(context: Context, uri: Uri): String? {
        // file:// 直接返回路径
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        // content:// 需要查询
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            // MediaStore
            if (isMediaStoreUri(uri)) {
                return getDataColumn(context, uri, null, null)
            }

            // DownloadsProvider
            if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) {
                    return id.removePrefix("raw:")
                }
                val contentUriPrefixesToTry = arrayOf(
                    "content://downloads/public_downloads",
                    "content://downloads/my_downloads"
                )
                for (contentUriPrefix in contentUriPrefixesToTry) {
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse(contentUriPrefix),
                        id.toLongOrNull() ?: continue
                    )
                    val path = getDataColumn(context, contentUri, null, null)
                    if (path != null) return path
                }
            }

            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relativePath = split[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        return "${Environment.getExternalStorageDirectory()}/$relativePath"
                    }
                    // Secondary storage
                    return "/storage/$type/$relativePath"
                }
            }
        }

        return null
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        try {
            cursor = context.contentResolver.query(uri, arrayOf(column), selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        } catch (e: Exception) {
            // 某些 content:// 不支持 _data 列
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return "media".equals(uri.authority, ignoreCase = true)
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents".equals(uri.authority, ignoreCase = true)
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents".equals(uri.authority, ignoreCase = true)
    }
}
