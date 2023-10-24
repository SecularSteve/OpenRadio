/*
 * Copyright 2023 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.model.storage.firestore

import android.app.Activity
import android.content.Context
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommonUi
import com.yuriy.openradio.shared.dependencies.StorageManagerDependency
import com.yuriy.openradio.shared.model.storage.StorageManagerLayer
import com.yuriy.openradio.shared.utils.AppLogger

class FirestoreManager(private val mContext: Context) : StorageManagerDependency {

    private val mAuth = Firebase.auth
    private val mDb = Firebase.firestore
    private lateinit var mStorageManagerLayer: StorageManagerLayer

    init {
        DependencyRegistryCommonUi.injectStorageManagerLayer(this)
    }

    override fun configureWith(storageManagerLayer: StorageManagerLayer) {
        mStorageManagerLayer = storageManagerLayer
    }

    fun isUserExist(): Boolean {
        return mAuth.currentUser != null
    }

    fun getUserEmail(): String {
        return mAuth.currentUser?.email ?: "no email found"
    }

    fun signOut() {
        mAuth.signOut()
    }

    fun sendPasswordReset(
        email: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        if (email.isEmpty()) {
            onFailure()
            return
        }
        mAuth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener {
                onFailure()
            }
    }

    fun getToken(
        onSuccess: (token: String) -> Unit,
        onFailure: (msg: String) -> Unit
    ) {
        val user = mAuth.currentUser
        if (user == null) {
            onFailure("User invalid")
            return
        }
        onSuccess(user.uid)
    }

    fun createUser(
        activity: Activity,
        email: String,
        password: String,
        onSuccess: (token: String) -> Unit,
        onFailure: (msg: String) -> Unit
    ) {
        if (email.isEmpty()) {
            return onFailure("Email can not be empty")
        }
        if (password.isEmpty()) {
            return onFailure("Password can not be empty")
        }
        mAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    AppLogger.d("$TAG createUserWithEmail:success")
                    getToken(onSuccess, onFailure)
                } else {
                    // If sign in fails, display a message to the user.
                    AppLogger.e("$TAG createUserWithEmail:failure", task.exception)
                    onFailure("Task not successful")
                }
            }
    }

    fun signIn(
        activity: Activity,
        email: String,
        password: String,
        onSuccess: (token: String) -> Unit,
        onFailure: (msg: String) -> Unit
    ) {
        if (email.isEmpty()) {
            return onFailure("Email can not be empty")
        }
        if (password.isEmpty()) {
            return onFailure("Password can not be empty")
        }
        mAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    AppLogger.d("$TAG signInWithEmail:success")
                    getToken(onSuccess, onFailure)
                } else {
                    // If sign in fails, display a message to the user.
                    AppLogger.e("$TAG signInWithEmail:failure", task.exception)
                    onFailure("Task not successful")
                }
            }
    }

    fun upload(
        token: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val data = hashMapOf(
            KEY_FAV to mStorageManagerLayer.getAllFavoritesAsString(),
            KEY_LOC to mStorageManagerLayer.getAllDeviceLocalsAsString()
        )

        // Add or update data in Firestore
        val userRef = mDb.collection(COLLECTION_NAME).document(token)
        userRef.set(data)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { e ->
                AppLogger.e("$TAG can't upload", e)
                onFailure()
            }
    }

    fun download(
        token: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        // Retrieve user data from Firestore
        val userRef = mDb.collection(COLLECTION_NAME).document(token)
        userRef.get()
            .addOnSuccessListener { documentSnapshot ->
                if (documentSnapshot.exists()) {
                    val user = documentSnapshot.toObject(UserData::class.java)
                    if (user != null) {
                        // Use the user data
                        onSuccess()
                        mStorageManagerLayer.mergeFavorites(user.favorites)
                        mStorageManagerLayer.mergeDeviceLocals(user.locals)
                    }
                } else {
                    // User not found
                    AppLogger.e("$TAG download user not found")
                    onFailure()
                }
            }
            .addOnFailureListener { e ->
                AppLogger.e("$TAG download", e)
                onFailure()
            }
    }

    /**
     * Empty constructor is needed for deserialization by Firestone SDK.
     */
    @Suppress("unused")
    data class UserData(
        val favorites: String,
        val locals: String
    ) {
        constructor() : this("", "")
    }

    companion object {

        private const val TAG = "FSM"
        private const val COLLECTION_NAME = "users"
        private const val KEY_FAV = "favorites"
        private const val KEY_LOC = "locals"
    }
}
