/*
 * Copyright 2017-2020 The "Open Radio" Project. Author: Chernyshov Yuriy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yuriy.openradio.shared.model.storage.drive

import androidx.core.util.Pair
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * A utility for performing read/write operations on Drive files via the REST API.
 */
class GoogleDriveHelper(private val mDriveService: Drive) {

    companion object {
        const val CMD_TIMEOUT_MS = 3000L
        private const val MIME_TYPE_TEXT = "text/plain"
        private const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        private val executor = Executors.newCachedThreadPool()
    }

    /**
     * Creates a text file in the user's My Drive folder and returns its file ID.
     */
    fun createFile(folderId: String, name: String, content: String): Task<String> {
        val taskCompletionSource = TaskCompletionSource<String>()
        executor.execute {
            try {
                val fileId = createFileInternal(folderId, name, content)
                taskCompletionSource.setResult(fileId)
            } catch (e: Exception) {
                taskCompletionSource.setException(e)
            }
        }
        return taskCompletionSource.task
    }

    /**
     *
     * @param name
     * @return
     */
    fun createFolder(name: String): Task<String> {
        val taskCompletionSource = TaskCompletionSource<String>()
        executor.execute {
            try {
                val metadata = File()
                    .setMimeType(MIME_TYPE_FOLDER)
                    .setName(name)
                val folder = mDriveService.files().create(metadata)
                    .setFields("id")
                    .execute()
                taskCompletionSource.setResult(folder.id)
            } catch (e: Exception) {
                taskCompletionSource.setException(e)
            }
        }
        return taskCompletionSource.task
    }

    /**
     * Opens the file identified by `fileId` and returns a [Pair] of its name and
     * contents.
     */
    fun readFile(fileId: String): Task<Pair<String, String>> {
        val taskCompletionSource = TaskCompletionSource<Pair<String, String>>()
        executor.execute {
            try {
                // Retrieve the metadata as a File object.
                val metadata = mDriveService.files()[fileId].execute()
                val name = metadata.name
                val content = readContentFromInputStream(fileId)
                taskCompletionSource.setResult(Pair.create(name, content))
            } catch (e: Exception) {
                taskCompletionSource.setException(e)
            }
        }
        return taskCompletionSource.task
    }

    fun deleteFile(fileId: String): Task<Unit> {
        val taskCompletionSource = TaskCompletionSource<Unit>()
        executor.execute {
            try {
                // Delete file with specified id
                mDriveService.files().delete(fileId).execute()
                taskCompletionSource.setResult(Unit)
            } catch (e: Exception) {
                taskCompletionSource.setException(e)
            }
        }
        return taskCompletionSource.task
    }

    /**
     * The returned list will only contain folder visible to this app, i.e. those which were
     * created by this app.
     */
    fun queryFolder(name: String): Task<FileList> {
        val taskCompletionSource = TaskCompletionSource<FileList>()
        executor.execute {
            try {
                val fileList = mDriveService.files().list()
                    .setSpaces("drive")
                    .setQ("mimeType='$MIME_TYPE_FOLDER' and trashed=false and name='$name'")
                    .execute()
                taskCompletionSource.setResult(fileList)
            } catch (e: Exception) {
                taskCompletionSource.setException(e)
            }
        }
        return taskCompletionSource.task
    }

    /**
     * The returned list will only contain folder visible to this app, i.e. those which were
     * created by this app.
     */
    fun queryFile(fileName: String): Task<FileList> {
        val taskCompletionSource = TaskCompletionSource<FileList>()
        executor.execute {
            try {
                val fileList = mDriveService.files().list()
                    .setSpaces("drive")
                    .setQ("mimeType='$MIME_TYPE_TEXT' and trashed=false and name='$fileName'")
                    .execute()
                taskCompletionSource.setResult(fileList)
            } catch (e: Exception) {
                taskCompletionSource.setException(e)
            }
        }
        return taskCompletionSource.task
    }

    private fun readContentFromInputStream(fileId: String): String {
        val inputStream = mDriveService.files()[fileId].executeMediaAsInputStream()
        return inputStream.use { `is` ->
            BufferedReader(InputStreamReader(`is`)).use { reader ->
                val stringBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line)
                }
                stringBuilder.toString()
            }
        }
    }

    private fun createFileInternal(folderId: String, name: String, content: String): String {
        val metadata = File()
            .setParents(listOf(folderId))
            .setMimeType(MIME_TYPE_TEXT)
            .setName(name)
        // Convert content to an AbstractInputStreamContent instance.
        val contentStream = ByteArrayContent.fromString(MIME_TYPE_TEXT, content)
        val file = mDriveService.files().create(metadata, contentStream).execute()
            ?: throw IOException("Null result when requesting file creation.")
        return file.id
    }
}
