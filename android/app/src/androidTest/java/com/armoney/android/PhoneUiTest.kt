package com.armoney.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class PhoneUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun countryIsFixedAndOnlyLocalDigitsAreEntered() {
        compose.setContent {
            var digits by remember { mutableStateOf("") }
            MaterialTheme { Column { RecipientPhoneField(digits, { digits = it }, true) } }
        }
        compose.onNodeWithText("United Arab Emirates · +971").assertIsDisplayed()
            .assertHasNoClickAction()
        compose.onNodeWithText("Recipient mobile number").performTextInput("501234567")
        compose.onNodeWithText("501234567").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(1)
    }

    @Test fun registrationPhoneIsReadOnlyAndUnverifiedIsTruthful() {
        compose.setContent {
            MaterialTheme { Column { RegisteredPhone(Profile("owner", "Name", "+971501234567", false)) } }
        }
        compose.onNodeWithText("+971501234567").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Registered · ownership not verified").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        compose.onNodeWithText("Save phone").assertDoesNotExist()
    }
}
