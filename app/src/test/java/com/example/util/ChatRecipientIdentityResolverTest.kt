package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecipientIdentityResolverTest {
    @Test
    fun resolvesPeerFromPrefixedLocalMatchId() {
        assertEquals(
            "605918cdddc5a40c",
            ChatRecipientIdentityResolver.recipientProfileIdFromMatchId(
                "match_605918cdddc5a40c_7ebef8956864b083",
                "7ebef8956864b083"
            )
        )
    }

    @Test
    fun resolvesPeerFromCanonicalMatchIdWithoutPrefix() {
        assertEquals(
            "605918cdddc5a40c",
            ChatRecipientIdentityResolver.recipientProfileIdFromMatchId(
                "605918cdddc5a40c_7ebef8956864b083",
                "7ebef8956864b083"
            )
        )
    }

    @Test
    fun doesNotResolvePairForNonParticipant() {
        assertNull(
            ChatRecipientIdentityResolver.recipientProfileIdFromMatchId(
                "match_605918cdddc5a40c_7ebef8956864b083",
                "aaaaaaaaaaaaaaaa"
            )
        )
    }

    @Test
    fun distinguishesPhoneNumbersFromProfileIDs() {
        assertTrue(ChatRecipientIdentityResolver.isPhoneNumberCandidate("919743040335"))
        assertTrue(ChatRecipientIdentityResolver.isPhoneNumberCandidate("+91 97430 40335"))
        assertFalse(ChatRecipientIdentityResolver.isPhoneNumberCandidate("605918cdddc5a40c"))
    }
    @Test
    fun phoneIdentityTakesPriorityOverStaleProfileId() {
        assertEquals(
            "919972396133",
            ChatRecipientIdentityResolver.phoneIdentityCandidate(
                "7ebef8956864b083",
                "919972396133"
            )
        )
    }

    @Test
    fun storedPhoneIdentityTakesPriorityOverStaleMatchPair() {
        assertEquals(
            "+91 99723 96133",
            ChatRecipientIdentityResolver.phoneIdentityCandidate(
                "+91 99723 96133",
                "7ebef8956864b083"
            )
        )
    }

}
