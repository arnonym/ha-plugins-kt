package io.github.arnonym.sip

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Port of tests/test_account.py. */
class AccountTest {
    @Test
    fun is_number_in_list() {
        Account.isNumberInList(null, listOf("12345")) shouldBe false
        Account.isNumberInList(null, emptyList()) shouldBe false
        Account.isNumberInList("12345", emptyList()) shouldBe false
        Account.isNumberInList("12345", listOf("12345")) shouldBe true
        Account.isNumberInList("1234", listOf("12345")) shouldBe false
        Account.isNumberInList("123456", listOf("1234{*}")) shouldBe true
        Account.isNumberInList("123456", listOf("1234{?}")) shouldBe false
        Account.isNumberInList("12345", listOf("1234{?}")) shouldBe true
        Account.isNumberInList("12345", listOf("1{*}5")) shouldBe true
        Account.isNumberInList("12345", listOf("12{?}45")) shouldBe true
        Account.isNumberInList("12345", listOf("{*}45")) shouldBe true
        Account.isNumberInList("12345", listOf("{?}345")) shouldBe false
        Account.isNumberInList("12345", listOf("{?}2345")) shouldBe true
        Account.isNumberInList("**620", listOf("**620")) shouldBe true
        Account.isNumberInList("**620", listOf("**{*}")) shouldBe true
    }
}
