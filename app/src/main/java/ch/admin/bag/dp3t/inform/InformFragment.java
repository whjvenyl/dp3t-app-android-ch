/*
 * Copyright (c) 2020 Ubique Innovation AG <https://www.ubique.ch>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */
package ch.admin.bag.dp3t.inform;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.Date;
import java.util.concurrent.CancellationException;

import org.dpppt.android.sdk.DP3T;
import org.dpppt.android.sdk.backend.ResponseCallback;
import org.dpppt.android.sdk.models.ExposeeAuthMethodAuthorization;

import ch.admin.bag.dp3t.R;
import ch.admin.bag.dp3t.inform.views.ChainedEditText;
import ch.admin.bag.dp3t.networking.AuthCodeRepository;
import ch.admin.bag.dp3t.networking.errors.InvalidCodeError;
import ch.admin.bag.dp3t.networking.errors.ResponseError;
import ch.admin.bag.dp3t.networking.models.AuthenticationCodeRequestModel;
import ch.admin.bag.dp3t.networking.models.AuthenticationCodeResponseModel;
import ch.admin.bag.dp3t.storage.SecureStorage;
import ch.admin.bag.dp3t.util.InfoDialog;
import ch.admin.bag.dp3t.util.JwtUtil;

public class InformFragment extends Fragment {

	private static final long TIMEOUT_VALID_CODE = 1000 * 60 * 5;

	private static final String REGEX_CODE_PATTERN = "\\d{" + ChainedEditText.NUM_CHARACTERS + "}";

	private ChainedEditText authCodeInput;
	private AlertDialog progressDialog;
	private Button buttonSend;

	private SecureStorage secureStorage;

	public static InformFragment newInstance() {
		return new InformFragment();
	}

	public InformFragment() {
		super(R.layout.fragment_inform);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		secureStorage = SecureStorage.getInstance(getContext());
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		((InformActivity) requireActivity()).allowBackButton(true);
		buttonSend = view.findViewById(R.id.trigger_fragment_button_trigger);
		authCodeInput = view.findViewById(R.id.trigger_fragment_input);
		authCodeInput.addTextChangedListener(new ChainedEditText.ChainedEditTextListener() {
			@Override
			public void onTextChanged(String input) {
				buttonSend.setEnabled(input.matches(REGEX_CODE_PATTERN));
			}

			@Override
			public void onEditorSendAction() {
				if (buttonSend.isEnabled()) buttonSend.callOnClick();
			}
		});

		long lastRequestTime = secureStorage.getLastInformRequestTime();
		String lastCode = secureStorage.getLastInformCode();
		String lastToken = secureStorage.getLastInformToken();

		if (System.currentTimeMillis() - lastRequestTime < TIMEOUT_VALID_CODE) {
			authCodeInput.setText(lastCode);
		} else if (lastCode != null || lastToken != null) {
			secureStorage.clearInformTimeAndCodeAndToken();
		}

		buttonSend.setOnClickListener(v -> {
			long lastTimestamp = secureStorage.getLastInformRequestTime();
			String lastAuthToken = secureStorage.getLastInformToken();

			buttonSend.setEnabled(false);
			setInvalidCodeErrorVisible(false);
			String authCode = authCodeInput.getText();

			progressDialog = createProgressDialog();
			if (System.currentTimeMillis() - lastTimestamp < TIMEOUT_VALID_CODE && lastAuthToken != null) {
				Date onsetDate = JwtUtil.getOnsetDate(lastAuthToken);
				informExposed(onsetDate, getAuthorizationHeader(lastAuthToken));
			} else {
				authenticateInput(authCode);
			}
		});

		view.findViewById(R.id.cancel_button).setOnClickListener(v -> {
			getActivity().finish();
		});
	}

	private void authenticateInput(String authCode) {
		AuthCodeRepository authCodeRepository = new AuthCodeRepository(getContext());
		authCodeRepository.getAccessToken(new AuthenticationCodeRequestModel(authCode, 0),
				new ResponseCallback<AuthenticationCodeResponseModel>() {
					@Override
					public void onSuccess(AuthenticationCodeResponseModel response) {
						String accessToken = response.getAccessToken();

						secureStorage.saveInformTimeAndCodeAndToken(authCode, accessToken);

						Date onsetDate = JwtUtil.getOnsetDate(accessToken);
						if (onsetDate == null) {
							showErrorDialog(getString(R.string.unexpected_error_title), "ONDT");
							if (progressDialog != null && progressDialog.isShowing()) {
								progressDialog.dismiss();
							}
							buttonSend.setEnabled(true);
							return;
						}
						informExposed(onsetDate, getAuthorizationHeader(accessToken));
					}

					@Override
					public void onError(Throwable throwable) {
						throwable.printStackTrace();
						if (progressDialog != null && progressDialog.isShowing()) {
							progressDialog.dismiss();
						}
						if (throwable instanceof InvalidCodeError) {
							setInvalidCodeErrorVisible(true);
							return;
						} else if (throwable instanceof ResponseError) {
							showErrorDialog(getString(R.string.unexpected_error_title),
									String.valueOf(((ResponseError) throwable).getStatusCode()));
						} else {
							showErrorDialog(getString(R.string.network_error), null);
						}
						buttonSend.setEnabled(true);
					}
				});
	}

	private void informExposed(Date onsetDate, String authorizationHeader) {
		DP3T.sendIAmInfected(getActivity(), onsetDate,
				new ExposeeAuthMethodAuthorization(authorizationHeader), new ResponseCallback<Void>() {
					@Override
					public void onSuccess(Void response) {
						if (progressDialog != null && progressDialog.isShowing()) {
							progressDialog.dismiss();
						}
						secureStorage.clearInformTimeAndCodeAndToken();
						getParentFragmentManager().beginTransaction()
								.setCustomAnimations(R.anim.slide_enter, R.anim.slide_exit, R.anim.slide_pop_enter,
										R.anim.slide_pop_exit)
								.replace(R.id.inform_fragment_container, ThankYouFragment.newInstance())
								.commit();
					}

					@Override
					public void onError(Throwable throwable) {
						if (progressDialog != null && progressDialog.isShowing()) {
							progressDialog.dismiss();
						}
						if (throwable instanceof ResponseError) {
							showErrorDialog(getString(R.string.unexpected_error_title),
									String.valueOf(((ResponseError) throwable).getStatusCode()));
						} else if (throwable instanceof CancellationException) {
							showErrorDialog(getString(R.string.user_cancelled_key_sharing_error), null);
						} else if (throwable.getMessage() != null && throwable.getMessage().contains("EXPOSURE_NOTIFICATION_API")) {
							showErrorDialog(getString(R.string.unexpected_error_title), "ENAPI");

						} else {
							showErrorDialog(getString(R.string.network_error), null);
						}
						throwable.printStackTrace();
						buttonSend.setEnabled(true);
					}
				});
	}


	@Override
	public void onResume() {
		super.onResume();
		authCodeInput.requestFocus();
	}

	private void setInvalidCodeErrorVisible(boolean visible) {
		getView().findViewById(R.id.inform_invalid_code_error).setVisibility(visible ? View.VISIBLE : View.GONE);
		getView().findViewById(R.id.inform_input_text).setVisibility(visible ? View.GONE : View.VISIBLE);
	}

	private AlertDialog createProgressDialog() {
		return new AlertDialog.Builder(getContext())
				.setView(R.layout.dialog_loading)
				.show();
	}

	private void showErrorDialog(String error, @Nullable String errorCode) {
		InfoDialog.newInstanceWithDetail(error, errorCode).show(getChildFragmentManager(), InfoDialog.class.getCanonicalName());
	}

	private String getAuthorizationHeader(String accessToken) {
		return "Bearer " + accessToken;
	}

}
