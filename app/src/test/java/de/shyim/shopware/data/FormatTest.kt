package de.shyim.shopware.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    // Cents are shown (issue #2) — most shop prices end in .99 and rounding them
    // away made specific orders impossible to find.
    @Test
    fun `fmtMoney keeps cents`() {
        assertEquals("€19.99", fmtMoney(19.99, "EUR"))
        assertEquals("€1,234.00", fmtMoney(1234.0, "EUR"))
    }

    @Test
    fun `fmtMoney uses the currency scale not the locale scale`() {
        // JPY has 0 fraction digits even though the UK locale default is 2
        assertEquals("JP¥500", fmtMoney(500.0, "JPY"))
    }

    @Test
    fun `fmtMoney respects the shop locale`() {
        assertEquals("19,99\u00A0€", fmtMoney(19.99, "EUR", "de-DE"))
    }

    @Test
    fun `fmtMoney falls back for unknown iso codes`() {
        assertEquals("ZZZ19.99", fmtMoney(19.99, "ZZZ"))
    }
}
