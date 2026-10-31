// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

package io.pcontacts.app.auth

import io.pcontacts.feature.onboarding.LoginUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class SignInStepTest {

    private val success = LoginUiState.Success(uid = "uid", username = "u")

    private fun step(state: LoginUiState, shown: SignInStep, secondPassword: Boolean = false) =
        signInStep(state, secondPassword, shown)

    @Test fun success_after_the_two_factor_code_stays_on_the_two_factor_screen() {
        assertEquals(SignInStep.TWO_FACTOR, step(success, SignInStep.TWO_FACTOR))
    }

    @Test fun success_after_the_second_password_stays_on_the_second_password_screen() {
        assertEquals(SignInStep.SECOND_PASSWORD, step(success, SignInStep.SECOND_PASSWORD, secondPassword = true))
    }

    @Test fun success_straight_from_the_password_stays_on_the_sign_in_form() {
        assertEquals(SignInStep.CREDENTIALS, step(success, SignInStep.CREDENTIALS))
    }

    @Test fun other_states_pick_their_own_step() {
        val twoFactor = LoginUiState.TwoFactorRequired("uid", "u")
        val second = LoginUiState.SecondPasswordRequired("uid", "u")

        assertEquals(SignInStep.TWO_FACTOR, step(twoFactor, SignInStep.CREDENTIALS))
        assertEquals(SignInStep.SECOND_PASSWORD, step(second, SignInStep.CREDENTIALS, secondPassword = true))
        assertEquals(SignInStep.CREDENTIALS, step(LoginUiState.Idle, SignInStep.TWO_FACTOR))
    }
}
