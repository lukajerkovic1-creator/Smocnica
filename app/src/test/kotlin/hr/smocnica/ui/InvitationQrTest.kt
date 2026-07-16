package hr.smocnica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvitationQrTest {
    @Test
    fun extractsValidInvitationCode() {
        assertEquals("ABC123", inviteCodeFromQr("smocnica://join?code=abc123"))
    }

    @Test
    fun rejectsUnexpectedOrMalformedQrValues() {
        assertNull(inviteCodeFromQr("https://example.com/join?code=ABC123"))
        assertNull(inviteCodeFromQr("smocnica://other?code=ABC123"))
        assertNull(inviteCodeFromQr("smocnica://join?code=ABC"))
        assertNull(inviteCodeFromQr("not-a-qr-invitation"))
    }
}
