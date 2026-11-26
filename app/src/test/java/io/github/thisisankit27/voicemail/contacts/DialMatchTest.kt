package io.github.thisisankit27.voicemail.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class DialMatchTest {
    private fun c(id: Long, name: String, number: String) = Contact(id, null, name, null, false, listOf(ContactPhone(id, null, name, number, "Mobile", null, false)))

    @Test fun `t9 maps letters to keys`() = assertEquals("527 7878", ContactsRepository.t9("Jas Rust"))

    @Test fun `first-name matches rank above other words`() {
        val list = listOf(c(1, "Alex Kim", "111"), c(2, "Kim Lee", "222"))
        assertEquals(listOf(2L, 1L), ContactsRepository.matchDigits(list, "546").map { it.contactId })
    }

    @Test fun `text search matches word prefixes`() {
        val list = listOf(c(1, "Alex Kim", "111"), c(2, "Sam Lee", "222"))
        assertEquals(listOf(1L), ContactsRepository.matchText(list, "ki").map { it.id })
    }
}
