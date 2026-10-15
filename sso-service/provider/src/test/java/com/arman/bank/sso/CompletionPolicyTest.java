package com.arman.bank.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CompletionPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void emailRequiresAnAuthReceiptBoolean() throws Exception {
        for (String response : new String[]{"{}", "{\"email_verified\":\"true\"}", "{\"email_verified\":1}", "{\"email_verified\":false}"})
            assertFalse(VerificationAction.permitsCompletion("REGISTRATION_EMAIL", JSON.readTree(response)));
        assertTrue(VerificationAction.permitsCompletion("REGISTRATION_EMAIL", JSON.readTree("{\"email_verified\":true}")));
    }
    @Test void finalPhoneStepWaitsForBothReceiptsAndProjectionActivation() throws Exception {
        for (String state : new String[]{"PENDING_CONTACTS", "VERIFIED", "PROJECTION_PENDING", "BLOCKED_MIGRATION", "UNKNOWN"})
            assertFalse(VerificationAction.permitsCompletion("REGISTRATION_PHONE", JSON.readTree(
                    "{\"email_verified\":true,\"phone_verified\":true,\"state\":\"" + state + "\"}")));
        for (String receipt : new String[]{"false", "\"true\"", "null", "1"}) {
            assertFalse(VerificationAction.permitsCompletion("REGISTRATION_PHONE", JSON.readTree(
                    "{\"email_verified\":" + receipt + ",\"phone_verified\":true,\"state\":\"ACTIVE\"}")));
            assertFalse(VerificationAction.permitsCompletion("REGISTRATION_PHONE", JSON.readTree(
                    "{\"email_verified\":true,\"phone_verified\":" + receipt + ",\"state\":\"ACTIVE\"}")));
        }
        assertTrue(VerificationAction.permitsCompletion("REGISTRATION_PHONE", JSON.readTree(
                "{\"email_verified\":true,\"phone_verified\":true,\"state\":\"ACTIVE\"}")));
    }
    @Test void unknownPurposeCannotComplete() throws Exception {
        assertFalse(VerificationAction.permitsCompletion("OTHER", JSON.readTree(
                "{\"email_verified\":true,\"phone_verified\":true,\"state\":\"ACTIVE\"}")));
    }
}
