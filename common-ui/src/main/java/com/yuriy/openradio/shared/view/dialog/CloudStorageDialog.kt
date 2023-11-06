/*
 * Copyright 2017-2023 The "Open Radio" Project. Author: Chernyshov Yuriy
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

package com.yuriy.openradio.shared.view.dialog

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yuriy.openradio.shared.R
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommonUi
import com.yuriy.openradio.shared.dependencies.FirestoreManagerDependency
import com.yuriy.openradio.shared.model.storage.firestore.FirestoreManager
import com.yuriy.openradio.shared.utils.SafeToast
import com.yuriy.openradio.shared.utils.findImageButton
import com.yuriy.openradio.shared.utils.findLinearLayout
import com.yuriy.openradio.shared.utils.findProgressBar
import com.yuriy.openradio.shared.utils.findTextView
import com.yuriy.openradio.shared.utils.gone
import com.yuriy.openradio.shared.utils.visible

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 12/20/14
 * E-Mail: chernyshov.yuriy@gmail.com
 */
class CloudStorageDialog : BaseDialogFragment(), FirestoreManagerDependency {

    private lateinit var mFirestoreManager: FirestoreManager
    private lateinit var mProgress: ProgressBar
    private lateinit var mAccView: LinearLayout
    private lateinit var mAccEmailView: TextView
    private val mAccountDialogDismissedListener = AccountDialogDismissedListenerImpl()

    enum class Command {
        UPLOAD, DOWNLOAD
    }

    override fun configureWith(firestoreManager: FirestoreManager) {
        mFirestoreManager = firestoreManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DependencyRegistryCommonUi.injectFirestoreManager(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        AccountDialog.dismiss(parentFragmentManager)
        hideProgress()
    }

    override fun onPause() {
        super.onPause()
        AccountDialog.dismiss(parentFragmentManager)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = inflater.inflate(
            R.layout.dialog_cloud_storage,
            requireActivity().findViewById(R.id.storage_root)
        )
        setWindowDimensions(view, 0.9f, 0.5f)
        val uploadTo = view.findImageButton(R.id.cloud_storage_upload_btn)
        uploadTo.setOnClickListener { uploadRadioStations() }
        val downloadFrom = view.findImageButton(R.id.cloud_storage_download_btn)
        downloadFrom.setOnClickListener { downloadRadioStations() }
        val accSignOut = view.findImageButton(R.id.account_sign_out_btn)
        accSignOut.setOnClickListener { signOut() }
        mProgress = view.findProgressBar(R.id.cloud_storage_progress)
        mAccView = view.findLinearLayout(R.id.account_layout)
        mAccEmailView = view.findTextView(R.id.account_email_text_view)
        hideProgress()
        return createAlertDialog(view)
    }

    override fun onResume() {
        super.onResume()
        if (mFirestoreManager.isUserExist().not()) {
            AccountDialog.show(parentFragmentManager, mAccountDialogDismissedListener)
        } else {
            showAccLayout()
        }
    }

    private fun uploadRadioStations() {
        if (mFirestoreManager.isUserExist().not()) {
            AccountDialog.show(parentFragmentManager, mAccountDialogDismissedListener)
        } else {
            showAccLayout()
            handleCommand(Command.UPLOAD)
        }
    }

    private fun downloadRadioStations() {
        if (mFirestoreManager.isUserExist().not()) {
            AccountDialog.show(parentFragmentManager, mAccountDialogDismissedListener)
        } else {
            showAccLayout()
            handleCommand(Command.DOWNLOAD)
        }
    }

    private fun handleCommand(command: Command) {
        showProgress()
        mFirestoreManager.getToken(
            {
                when (command) {
                    Command.UPLOAD -> {
                        mFirestoreManager.upload(
                            it,
                            {
                                hideProgress()
                                SafeToast.showAnyThread(
                                    context, getString(R.string.success)
                                )
                            },
                            {
                                hideProgress()
                                SafeToast.showAnyThread(
                                    context, getString(R.string.failure)
                                )
                            }
                        )
                    }

                    Command.DOWNLOAD -> {
                        mFirestoreManager.download(
                            it,
                            {
                                hideProgress()
                                SafeToast.showAnyThread(
                                    context, getString(R.string.success)
                                )
                            },
                            {
                                hideProgress()
                                SafeToast.showAnyThread(
                                    context, getString(R.string.failure)
                                )
                            }
                        )
                    }
                }
            },
            {
                hideProgress()
                SafeToast.showAnyThread(context, "Can't get Token")
            }
        )
    }

    private fun signOut() {
        if (mFirestoreManager.isUserExist()) {
            mFirestoreManager.signOut()
            hideAccLayout()
        }
    }

    private fun showProgress() {
        val activity = activity ?: return
        activity.runOnUiThread {
            mProgress.visible()
        }
    }

    private fun hideProgress() {
        val activity = activity ?: return
        activity.runOnUiThread {
            mProgress.gone()
        }
    }

    private fun showAccLayout() {
        val activity = activity ?: return
        activity.runOnUiThread {
            mAccView.visible()
            mAccEmailView.text = mFirestoreManager.getUserEmail()
        }
    }

    private fun hideAccLayout() {
        val activity = activity ?: return
        activity.runOnUiThread {
            mAccView.gone()
        }
    }

    private inner class AccountDialogDismissedListenerImpl : AccountDialog.DialogDismissedListener {

        override fun onDialogDismissed() {
            showAccLayout()
        }
    }

    companion object {
        /**
         * Tag string to use in logging message.
         */
        private val CLASS_NAME = CloudStorageDialog::class.java.simpleName

        /**
         * Tag string to use in dialog transactions.
         */
        val DIALOG_TAG = CLASS_NAME + "_DIALOG_TAG"
    }
}
