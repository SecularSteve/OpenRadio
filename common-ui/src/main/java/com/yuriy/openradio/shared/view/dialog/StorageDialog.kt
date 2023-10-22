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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yuriy.openradio.shared.R
import com.yuriy.openradio.shared.dependencies.DependencyRegistryCommonUi
import com.yuriy.openradio.shared.dependencies.MediaPresenterDependency
import com.yuriy.openradio.shared.model.storage.firestore.FirestoreManager
import com.yuriy.openradio.shared.presenter.MediaPresenter
import com.yuriy.openradio.shared.utils.SafeToast
import com.yuriy.openradio.shared.utils.findButton
import com.yuriy.openradio.shared.utils.findEditText
import com.yuriy.openradio.shared.utils.findTextView
import com.yuriy.openradio.shared.utils.gone
import com.yuriy.openradio.shared.utils.visible

/**
 * Created by Yuriy Chernyshov
 * At Android Studio
 * On 12/20/14
 * E-Mail: chernyshov.yuriy@gmail.com
 */
class StorageDialog : BaseDialogFragment(), MediaPresenterDependency {

    private lateinit var mProgressBarUpload: ProgressBar
    private lateinit var mProgressBarDownload: ProgressBar
    private lateinit var mAccProgress: ProgressBar
    private lateinit var mFirestoreManager: FirestoreManager
    private lateinit var mMediaPresenter: MediaPresenter
    private lateinit var mEmail: EditText
    private lateinit var mPwd: EditText
    private lateinit var mAccDescriptionView: TextView
    private lateinit var mAccEmailView: LinearLayout
    private lateinit var mAccPwdView: LinearLayout
    private lateinit var mContentView: LinearLayout
    private lateinit var mAccBtnsView: LinearLayout

    private enum class Command {
        UPLOAD, DOWNLOAD
    }

    override fun configureWith(mediaPresenter: MediaPresenter) {
        mMediaPresenter = mediaPresenter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DependencyRegistryCommonUi.inject(this)

        mFirestoreManager = FirestoreManager()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgress(Command.UPLOAD)
        hideProgress(Command.DOWNLOAD)
        hideCredentials()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = inflater.inflate(
            R.layout.dialog_storage,
            requireActivity().findViewById(R.id.storage_drive_root)
        )
        setWindowDimensions(view, 0.9f, 0.5f)
        mContentView = view.findViewById(R.id.content_view)
        val uploadTo = view.findButton(R.id.upload_to_storage_btn)
        uploadTo.setOnClickListener { uploadRadioStations() }
        val downloadFrom = view.findButton(R.id.download_from_storage_btn)
        downloadFrom.setOnClickListener { downloadRadioStations() }
        val createUserBtn = view.findButton(R.id.account_create)
        createUserBtn.setOnClickListener { createUser() }
        val signInBtn = view.findButton(R.id.account_sign_in)
        signInBtn.setOnClickListener { signIn() }
        mProgressBarUpload = view.findViewById(R.id.upload_to_storage_progress)
        mProgressBarDownload = view.findViewById(R.id.download_to_storage_progress)
        mAccProgress = view.findViewById(R.id.acc_progress_bar)
        mEmail = view.findEditText(R.id.account_email)
        mPwd = view.findEditText(R.id.account_pwd)
        mAccDescriptionView = view.findTextView(R.id.account_credential_view)
        mAccEmailView = view.findViewById(R.id.account_email_view)
        mAccPwdView = view.findViewById(R.id.account_pwd_view)
        mAccBtnsView = view.findViewById(R.id.account_btns_view)
        hideProgress(Command.UPLOAD)
        hideProgress(Command.DOWNLOAD)
        if (mFirestoreManager.isUserExist().not()) {
            showCredentials()
            hideContent()
        } else {
            hideCredentials()
            showContent()
        }
        return createAlertDialog(view)
    }

    private fun uploadRadioStations() {
        handleCommand(Command.UPLOAD)
    }

    private fun downloadRadioStations() {
        handleCommand(Command.DOWNLOAD)
    }

    private fun createUser() {
        mAccProgress.visible()
        mFirestoreManager.createUser(
            requireActivity(),
            mEmail.text.toString(),
            mPwd.text.toString(),
            {
                mAccProgress.gone()
                hideCredentials()
                showContent()
            },
            {
                mAccProgress.gone()
                SafeToast.showAnyThread(
                    context, getString(R.string.can_not_create_user)
                )
            }
        )
    }

    private fun signIn() {
        mAccProgress.visible()
        mFirestoreManager.signIn(
            requireActivity(),
            mEmail.text.toString(),
            mPwd.text.toString(),
            {
                mAccProgress.gone()
                hideCredentials()
                showContent()
            },
            {
                mAccProgress.gone()
                SafeToast.showAnyThread(
                    context, getString(R.string.can_not_sign_in)
                )
            }
        )
    }

    private fun handleCommand(command: Command) {
        showProgress(command)
        mFirestoreManager.getToken(
            {
                hideCredentials()
                when (command) {
                    Command.UPLOAD -> {
                        mFirestoreManager.upload(
                            it,
                            {
                                hideProgress(command)
                                SafeToast.showAnyThread(
                                    context, getString(R.string.storage_data_saved)
                                )
                            },
                            {
                                hideProgress(command)
                                SafeToast.showAnyThread(
                                    context, getString(R.string.storage_error_when_save)
                                )
                            }
                        )
                    }

                    Command.DOWNLOAD -> {
                        mFirestoreManager.download(
                            it,
                            {
                                hideProgress(command)
                                SafeToast.showAnyThread(
                                    context, getString(R.string.storage_data_read)
                                )
                            },
                            {
                                hideProgress(command)
                                SafeToast.showAnyThread(
                                    context, getString(R.string.storage_error_when_read)
                                )
                            }
                        )
                    }
                }
            },
            {
                SafeToast.showAnyThread(context, "Can't get Token")
            }
        )
    }

    private fun showContent() {
        mContentView.visible()
    }

    private fun hideContent() {
        mContentView.gone()
    }

    private fun showCredentials() {
        mAccPwdView.visible()
        mAccEmailView.visible()
        mAccDescriptionView.visible()
        mAccBtnsView.visible()
    }

    private fun hideCredentials() {
        mAccPwdView.gone()
        mAccEmailView.gone()
        mAccDescriptionView.gone()
        mAccBtnsView.gone()
    }

    private fun showProgress(command: Command) {
        val activity = activity ?: return
        when (command) {
            Command.UPLOAD -> activity.runOnUiThread {
                mProgressBarUpload.visible()
            }

            Command.DOWNLOAD -> activity.runOnUiThread {
                mProgressBarDownload.visible()
            }
        }
    }

    private fun hideProgress(command: Command) {
        val activity = activity ?: return
        when (command) {
            Command.UPLOAD -> activity.runOnUiThread {
                mProgressBarUpload.gone()
            }

            Command.DOWNLOAD -> activity.runOnUiThread {
                mProgressBarDownload.gone()
            }
        }
    }

    companion object {
        /**
         * Tag string to use in logging message.
         */
        private val CLASS_NAME = StorageDialog::class.java.simpleName

        /**
         * Tag string to use in dialog transactions.
         */
        val DIALOG_TAG = CLASS_NAME + "_DIALOG_TAG"
    }
}
